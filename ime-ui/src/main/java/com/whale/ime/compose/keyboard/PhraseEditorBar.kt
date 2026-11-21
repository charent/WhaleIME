package com.whale.ime.compose.keyboard

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.whale.ime.ui.R
import com.whale.ime.compose.ImeController
import com.whale.ime.compose.ImeUiState

/**
 * 「增加常用语」编辑栏。
 *
 * 「当前编辑哪个输入框 + 文本内容 + 编辑点」收进 [ImeUiState]，
 * 按键由 `ImeController.editPhrase` 直接改写字符串，
 * 省掉了「IME 窗口里再挂一个可聚焦 EditText」带来的焦点竞争。
 *
 * 布局分上下两栏：上栏是常用语正文（整句换行显示，超长时可纵向滚动），
 * 下栏是快捷码（正文前 4 个字的拼音首字母）。
 * 整个卡片是候选栏**上方**的独立弹层（见 `ImeApp.PhraseEditorPopup` /
 * [com.whale.ime.keyboard.metrics.Environment.phraseEditorPopupHeight]），
 * 候选栏与键区的高度、位置都不参与变化，输入拼音时的气泡与候选照常显示。
 * 点按正文或快捷码里的任意位置即可把编辑点挪过去，
 * 光标按 [TextLayoutResult.getCursorRect] 画出来，长句换行后自动滚进可视区。
 *
 * 视觉上是一块输入卡片 + 两个圆形按钮（取消 / 完成），完成键用强调色实心，
 * 和键盘回车键的语义保持一致。
 */
@Composable
fun PhraseEditorBar(controller: ImeController, uiState: ImeUiState) {
    val palette = LocalImePalette.current
    val density = LocalDensity.current

    val content = uiState.phraseContent
    val code = uiState.phraseQuickCode
    val editingCode = uiState.phraseEditingQuickCode
    val contentCursor = uiState.phraseContentCursor.coerceIn(0, content.length)
    val codeCursor = uiState.phraseQuickCodeCursor.coerceIn(0, code.length)

    var contentLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var codeLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val contentScroll = rememberScrollState()

    val caretColor = palette.accent
    val caretWidth = with(density) { 1.5.dp.toPx() }
    val caretInset = with(density) { 2.dp.toPx() }
    val caretMinHeight = with(density) { 12.sp.toPx() }
    val scrollPad = with(density) { 6.dp.toPx() }

    // 编辑点跟随：点到中间 / 长句换行 / 继续输入后，把光标所在行滚进可视区
    LaunchedEffect(contentLayout, contentCursor, content, editingCode, contentScroll.maxValue) {
        if (editingCode) return@LaunchedEffect
        val layout = contentLayout ?: return@LaunchedEffect
        val rect = layout.getCursorRect(contentCursor)
        val viewport = contentScroll.viewportSize
        if (viewport <= 0) return@LaunchedEffect
        val top = contentScroll.value
        when {
            rect.top < top -> contentScroll.scrollTo((rect.top - scrollPad).toInt().coerceAtLeast(0))
            rect.bottom > top + viewport -> contentScroll.scrollTo(
                (rect.bottom - viewport + scrollPad).toInt().coerceIn(0, contentScroll.maxValue)
            )
        }
    }

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
    ) {
        val buttonSize = if (maxHeight < 38.dp) maxHeight else 38.dp
        val iconSize = buttonSize * 0.58f

        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    // 弹层浮在宿主内容上：卡片必须不透明，再加一层投影和背景分开
                    .shadow(8.dp, RoundedCornerShape(14.dp))
                    .clip(RoundedCornerShape(14.dp))
                    .background(palette.card)
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            ) {
                // 上栏：常用语正文。整句换行显示，点字定位编辑点；
                // 点在文字下方的空白处（本层兜底）则把编辑点放回末尾并切到正文
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .pointerInput(content.isEmpty()) {
                            detectTapGestures {
                                controller.setPhraseEditingQuickCode(false)
                            }
                        },
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().verticalScroll(contentScroll),
                    ) {
                        Text(
                            text = if (content.isEmpty()) controller.string(R.string.add_phrases_input_hint)
                            else content,
                            color = if (content.isEmpty()) palette.muted else palette.onSurface,
                            style = TextStyle(
                                fontSize = 15.sp,
                                lineHeight = 19.sp,
                                fontWeight = if (editingCode) FontWeight.Normal else FontWeight.Medium,
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .pointerInput(content.isEmpty()) {
                                    detectTapGestures { position ->
                                        val layout = contentLayout
                                        if (layout == null || content.isEmpty()) {
                                            // 空内容 / 还没排版：点击即把编辑点交给正文
                                            controller.setPhraseEditingQuickCode(false)
                                        } else {
                                            controller.setPhraseCursor(
                                                layout.getOffsetForPosition(position),
                                                quickCode = false,
                                            )
                                        }
                                    }
                                }
                                .drawWithContent {
                                    drawContent()
                                    if (!editingCode) {
                                        val layout = contentLayout ?: return@drawWithContent
                                        val rect = layout.getCursorRect(
                                            contentCursor.coerceIn(0, layout.layoutInput.text.length)
                                        )
                                        drawRect(
                                            color = caretColor,
                                            topLeft = Offset(rect.left, rect.top + caretInset),
                                            size = Size(
                                                caretWidth,
                                                (rect.height - caretInset * 2).coerceAtLeast(caretMinHeight),
                                            ),
                                        )
                                    }
                                },
                            onTextLayout = { contentLayout = it },
                        )
                    }
                }
                // 下栏：快捷码（正文前 4 个字的拼音首字母），点字同样定位编辑点
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = controller.string(R.string.add_phrases_quick_code_label),
                        color = palette.muted,
                        style = TextStyle(fontSize = 10.sp),
                        maxLines = 1,
                        modifier = Modifier.imeClickable {
                            controller.setPhraseEditingQuickCode(true)
                        },
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = code,
                        color = if (editingCode) palette.onSurface else palette.muted,
                        style = TextStyle(
                            fontSize = 13.sp,
                            fontWeight = if (editingCode) FontWeight.Medium else FontWeight.Normal,
                        ),
                        maxLines = 1,
                        modifier = Modifier
                            .weight(1f)
                            .pointerInput(code.isEmpty()) {
                                detectTapGestures { position ->
                                    val layout = codeLayout
                                    if (layout == null) {
                                        controller.setPhraseEditingQuickCode(true)
                                    } else {
                                        controller.setPhraseCursor(
                                            layout.getOffsetForPosition(position),
                                            quickCode = true,
                                        )
                                    }
                                }
                            }
                            .drawWithContent {
                                drawContent()
                                if (editingCode) {
                                    val layout = codeLayout ?: return@drawWithContent
                                    val rect = layout.getCursorRect(
                                        codeCursor.coerceIn(0, layout.layoutInput.text.length)
                                    )
                                    drawRect(
                                        color = caretColor,
                                        topLeft = Offset(rect.left, rect.top + caretInset),
                                        size = Size(
                                            caretWidth,
                                            (rect.height - caretInset * 2).coerceAtLeast(caretMinHeight),
                                        ),
                                    )
                                }
                            },
                        onTextLayout = { codeLayout = it },
                    )
                }
            }
            // 取消
            ImeIconButton(
                iconRes = R.drawable.ic_menu_close,
                contentDescription = null,
                tint = palette.onSurface,
                size = buttonSize,
                iconSize = iconSize,
                onClick = { controller.cancelAddPhrases() },
            )
            // 完成：强调色实心圆
            Box(
                modifier = Modifier
                    .size(buttonSize)
                    .clip(CircleShape)
                    .background(palette.accent)
                    .imeClickable() { controller.finishAddPhrases() },
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_menu_done),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(palette.onAccent),
                    modifier = Modifier.size(iconSize),
                )
            }
        }
    }
}
