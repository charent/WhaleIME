package com.whale.ime.compose.keyboard

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import com.whale.ime.ui.R
import com.whale.ime.compose.GestureTuning
import com.whale.ime.compose.ImeController
import com.whale.ime.compose.ImeUiState
import com.whale.ime.emoji.EmojiFont
import com.whale.ime.emoji.EmojiconData
import com.whale.ime.emoji.KaomojiData
import com.whale.ime.storage.WhaleDatabase
import com.whale.ime.storage.entity.UsedSymbol
import com.whale.ime.keyboard.model.SoftKey
import com.whale.ime.preference.model.SymbolMode
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.roundToInt
import com.whale.ime.preference.InputFeedbacks

/**
 * 符号 / 表情 / 颜文字面板。
 *
 * 用 `HorizontalPager + LazyVerticalGrid` 分页，分页图标用 `EmojiconData` 的 key（drawable res）。
 *
 * 交互：
 * - 符号格按下有缩放 + 圆角高亮；
 * - 底部分类图标用「选中胶囊」，背景色随翻页做颜色过渡；
 * - 表情长按的肤色选择不再用 `PopupWindow`，改为面板内的内联圆角卡片——
 *   IME 窗口里再起 Popup 容易抢焦点，内联一张卡片更稳。卡片按被长按格子的位置就近弹出
 *   （优先在格子上方，空间不足翻到下方，左右越界贴边）；再次点击该表情、点其它表情、
 *   滑动网格或切换分类都会收起。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SymbolPanel(uiState: ImeUiState, controller: ImeController) {
    val palette = LocalImePalette.current
    // 表情统一用随包 emoji 字体渲染：与系统 emoji 字体版本解耦，新表情不会缺字形，
    // 也不会出现「一半系统风格、一半 Noto 风格」的混排。
    // FontFamily 是 Compose 类型，只能在 UI 层构造（core 只提供字体资源常量与 Typeface）。
    val context = LocalContext.current
    val emojiFamily = remember {
        runCatching { FontFamily(Font(EmojiFont.ASSET_PATH, context.assets)) }.getOrNull()
    }

    // 分页数据：第一页「最近使用」按模式从数据库装载，没用过就是空页（显示占位提示）。
    // 提交符号/表情后整体重载：分类数不变、pager 停留当前页，仅最近使用页内容刷新。
    var pages by remember(uiState.symbolMode) { mutableStateOf(loadPages(uiState.symbolMode)) }
    if (pages.isEmpty()) return
    val refreshPages = { pages = loadPages(uiState.symbolMode) }

    val pagerState = rememberPagerState(
        initialPage = uiState.symbolPage.coerceIn(0, pages.lastIndex),
        pageCount = { pages.size },
    )
    // 滑动/程序翻页落定 → 记录当前页
    LaunchedEffect(pagerState.currentPage) { controller.setSymbolPage(pagerState.currentPage) }
    // 点底部分类图标 → 直接驱动 pager 滚动。若经 setSymbolPage → uiState →
    // LaunchedEffect 回读翻页，就会与上一行的「落定回写」形成反馈环：快速切分类时
    // 回写会取消进行中的 animateScrollToPage，pager 停在两页之间（半页手势半页动物）。
    // 这里改为点击时一次性发起滚动，不再经过状态环。
    val pageScope = rememberCoroutineScope()
    // 切换模式（表情/颜文字/符号）时回到第一页
    LaunchedEffect(uiState.symbolMode) {
        if (pagerState.currentPage != 0) pagerState.scrollToPage(0)
    }

    var locked by remember(uiState.symbolMode) { mutableStateOf(uiState.symbolMode != SymbolMode.Symbol) }
    // 肤色条状态：记录基准表情、可选项以及被长按格子在面板内的位置（px），用来就近弹出
    var skinTone by remember(uiState.symbolMode) { mutableStateOf<SkinTonePick?>(null) }
    // 网格所在面板的坐标：把格子坐标换算到面板坐标系（不参与重组，仅取值）
    val panelCoords = remember { CoordsRef() }
    // 翻页 / 切分类 → 关闭肤色条（否则卡片会留在上一个分类的位置上）
    LaunchedEffect(pagerState.currentPage, uiState.symbolMode) { skinTone = null }
    // 手指一动面板就开始收起：`isScrollInProgress` 在越过触摸阈值（约 8dp）时就为 true，
    // 不用等翻过一半（currentPage 变化）——既防误触残留，也不会被轻微抖动误关。
    LaunchedEffect(pagerState.isScrollInProgress) {
        if (pagerState.isScrollInProgress) skinTone = null
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .onGloballyPositioned { panelCoords.value = it },
        ) {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                val values = pages[page].second
                if (values.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "暂无最近使用",
                            color = palette.muted,
                            style = TextStyle(fontSize = 15.sp),
                        )
                    }
                } else {
                    // 尺寸按模式统一给出：列宽决定每行个数，行高 + 字号 + 行距一起收。
                    // - emoji：本身要醒目，一格一个方块；
                    // - 颜文字：按 3 列给宽格（约 104dp）。颜文字平均 5.6em、90% 不超过 9.5em，
                    //   窄格（旧版 55dp）会把绝大多数条目压到 10sp 以下，是「大小不一、很乱」的根因；
                    //   行距给 0 是配合下面的发丝网格线：行距留白会让上下格子各画一条线，叠成双线。
                    val metrics = when (uiState.symbolMode) {
                        SymbolMode.Emojicon -> SymbolGridMetrics(
                            minCellSize = 41.dp,
                            cellHeight = 44.dp,
                            fontSize = 27.sp,
                            rowSpacing = 2.dp,
                        )
                        SymbolMode.Emoticon -> SymbolGridMetrics(
                            minCellSize = 104.dp,
                            cellHeight = 44.dp,
                            fontSize = 15.sp,
                            rowSpacing = 0.dp,
                        )
                        else -> SymbolGridMetrics(
                            minCellSize = 40.dp,
                            cellHeight = 42.dp,
                            fontSize = 20.sp,
                            rowSpacing = 0.dp,
                        )
                    }
                    // 符号页与颜文字页用 Excel 式细线网格：无底色（与面板背景一致），
                    // 1px 方格线把 - _ / · . 这类相近符号、长短不一的颜文字逐格分开；
                    // emoji 页不加框（方块本身有边界，加线反而脏）。
                    val boxed = uiState.symbolMode == SymbolMode.Symbol ||
                            uiState.symbolMode == SymbolMode.Emoticon
                    val gridState = rememberLazyGridState()
                    // 滚动后格子位置变了，肤色条会「悬空」，直接收起
                    LaunchedEffect(gridState.isScrollInProgress) {
                        if (gridState.isScrollInProgress) skinTone = null
                    }
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = metrics.minCellSize),
                        modifier = Modifier.fillMaxSize(),
                        state = gridState,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(metrics.rowSpacing),
                    ) {
                        items(values) { raw ->
                            // 只有符号表需要去尾随空格/回车（历史遗留）；颜文字里的半角、全角空格
                            // 是外观的一部分（`( ´∀`)ﾉ` 去掉空格就变味），emoji 也不动
                            val symbol = if (uiState.symbolMode == SymbolMode.Symbol) {
                                raw.replace(SYMBOL_BLANK, "")
                            } else {
                                raw
                            }
                            // 支持肤色的表情长按弹肤色条；格子上要能看出来，所以提前查一次变体
                            val variants = if (uiState.symbolMode == SymbolMode.Emojicon) {
                                EmojiconData.skinToneVariantsOf(symbol)
                            } else null
                            val cellCoords = remember { CoordsRef() }
                            SymbolCell(
                                symbol = symbol,
                                fontSize = metrics.fontSize,
                                cellHeight = metrics.cellHeight,
                                showGrid = boxed,
                                skinToneCapable = variants != null,
                                fontFamily = if (uiState.symbolMode == SymbolMode.Emojicon) emojiFamily else null,
                                onPositioned = { cellCoords.value = it },
                                onClick = {
                                    // 再次点击正在选肤色的那个表情：只收起肤色条，不重复上屏
                                    val sameAsPopup = skinTone?.base == symbol
                                    skinTone = null
                                    if (!sameAsPopup) {
                                        InputFeedbacks.tryPlayKeyDown()
                                        InputFeedbacks.tryVibrate()
                                        commitSymbol(controller, uiState.symbolMode, symbol)
                                        refreshPages()
                                        if (!locked) controller.switchToInputKeyboard()
                                    }
                                },
                                onLongClick = {
                                    if (variants == null) {
                                        skinTone = null
                                        commitSymbol(controller, uiState.symbolMode, symbol)
                                        refreshPages()
                                        if (!locked) controller.switchToInputKeyboard()
                                    } else {
                                        InputFeedbacks.tryPlayKeyDown()
                                        InputFeedbacks.tryVibrate()
                                        skinTone = SkinTonePick(
                                            base = symbol,
                                            variants = variants,
                                            anchor = anchorRectOf(panelCoords, cellCoords),
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
            }
            // 肤色选择条：贴着被长按的表情就近浮出，靠近面板边缘时自动翻面/贴边
            skinTone?.let { pick ->
                SkinTonePopup(
                    options = listOf(pick.base) + pick.variants,
                    anchor = pick.anchor,
                    fontFamily = emojiFamily,
                    onPick = { option ->
                        skinTone = null
                        commitEmoji(controller, option)
                        refreshPages()
                    },
                )
            }
        }

        // 底部分类栏：图标更大、间距更宽，分类多时可左右滑动
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LazyRow(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 2.dp),
            ) {
                items(pages.size) { index ->
                    val page = pages[index]
                    val selected = pagerState.currentPage == index
                    val bg by animateColorAsState(
                        targetValue = if (selected) palette.accent.copy(alpha = 0.16f) else Color.Transparent,
                        label = "symbolPageBg",
                    )
                    val tint by animateColorAsState(
                        targetValue = if (selected) palette.accent else palette.muted,
                        label = "symbolPageTint",
                    )
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(bg)
                            .imeClickable {
                                pageScope.launch { pagerState.animateScrollToPage(index) }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(
                            painter = painterResource(page.first),
                            contentDescription = null,
                            colorFilter = ColorFilter.tint(tint),
                            modifier = Modifier.size(26.dp),
                        )
                    }
                }
            }
            // 「返回」按钮：旧版用的是切换键盘的循环箭头图标，看起来像「循环/切换」而不是返回；
            // 这里的返回只是「回到键盘」，没有系统导航语义，写汉字最直白。
            Box(
                modifier = Modifier
                    .padding(start = 4.dp)
                    .size(width = 52.dp, height = 40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(palette.functionKey)
                    .imeClickable { controller.switchToInputKeyboard() },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = controller.string(R.string.keyboard_back),
                    color = palette.onSurface,
                    style = TextStyle(fontSize = 13.sp),
                )
            }
            DeleteButton(
                locked = locked,
                onToggleLock = { locked = it },
                onDelete = {
                    InputFeedbacks.tryPlayKeyDown(android.view.KeyEvent.KEYCODE_DEL)
                    InputFeedbacks.tryVibrate()
                    controller.dispatcher.responseKeyEvent(SoftKey(android.view.KeyEvent.KEYCODE_DEL))
                },
            )
        }
    }
}

/**
 * 一页格子的尺寸：`minCellSize` 是列的最小宽度（决定每行个数），
 * `cellHeight` / `fontSize` / `rowSpacing` 一起控制纵向疏密。
 */
private class SymbolGridMetrics(
    val minCellSize: Dp,
    val cellHeight: Dp,
    val fontSize: TextUnit,
    val rowSpacing: Dp,
)

/** 肤色条状态：被长按的表情 + 可选肤色 + 该格子在面板内的矩形（px，可能为空） */
private class SkinTonePick(
    val base: String,
    val variants: List<String>,
    val anchor: Rect?,
)

/** 布局坐标的轻量持有者：只在回调里读写，不参与重组 */
private class CoordsRef {
    var value: LayoutCoordinates? = null
}

/** 把格子坐标换算成面板坐标系里的矩形；任一坐标尚未就绪时返回 null */
private fun anchorRectOf(panel: CoordsRef, cell: CoordsRef): Rect? {
    val panelCoords = panel.value ?: return null
    val cellCoords = cell.value ?: return null
    if (!panelCoords.isAttached || !cellCoords.isAttached) return null
    val origin = cellCoords.positionInRoot() - panelCoords.positionInRoot()
    return Rect(origin, cellCoords.size.toSize())
}

private val SKIN_TONE_GAP = 6.dp
private val SKIN_TONE_EDGE = 6.dp

/**
 * 肤色选择条：按 [anchor]（被长按格子在本面板内的矩形）就近摆放——
 * 优先浮在该格子上方，上方空间不够就翻到下方；左右越界时贴边，
 * 保证整条卡片始终落在面板（即屏幕）范围内。
 */
@Composable
private fun SkinTonePopup(
    options: List<String>,
    anchor: Rect?,
    fontFamily: FontFamily?,
    onPick: (String) -> Unit,
) {
    val palette = LocalImePalette.current
    Layout(
        modifier = Modifier.fillMaxSize(),
        content = {
            Row(
                modifier = Modifier
                    .shadow(10.dp, RoundedCornerShape(20.dp))
                    .clip(RoundedCornerShape(20.dp))
                    .background(palette.card)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                options.forEach { option ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .imeClickable {
                                InputFeedbacks.tryPlayKeyDown()
                                InputFeedbacks.tryVibrate()
                                onPick(option)
                            }
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = option,
                            color = palette.onSurface,
                            fontFamily = fontFamily,
                            style = TextStyle(fontSize = 22.sp),
                        )
                    }
                }
            }
        },
    ) { measurables, constraints ->
        val gap = SKIN_TONE_GAP.roundToPx()
        val edge = SKIN_TONE_EDGE.roundToPx()
        val panelW = constraints.maxWidth
        val panelH = constraints.maxHeight
        // 卡片宽度不能超过面板，否则贴边也压不住
        val placeable = measurables.first().measure(
            Constraints(maxWidth = (panelW - edge * 2).coerceAtLeast(0)),
        )
        // 没有坐标时退回面板底部居中（极少发生：长按必然已布局完成）
        val rect = anchor ?: Rect(Offset(panelW / 2f, panelH.toFloat()), Size.Zero)
        val maxX = (panelW - placeable.width - edge).coerceAtLeast(edge)
        val x = (rect.center.x - placeable.width / 2f).roundToInt().coerceIn(edge, maxX)
        val above = rect.top - gap - placeable.height
        val y = if (above >= edge) {
            above.roundToInt()
        } else {
            (rect.bottom + gap).coerceIn(
                edge.toFloat(),
                (panelH - placeable.height - edge).coerceAtLeast(edge).toFloat(),
            ).roundToInt()
        }
        layout(panelW, panelH) { placeable.place(x, y) }
    }
}

/** 单个符号格：按下时浮出圆角底色 + ripple，不做缩放（缩放会让整块面板跟着「沉」） */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SymbolCell(
    symbol: String,
    fontSize: TextUnit,
    cellHeight: Dp,
    showGrid: Boolean,
    /** 支持长按换肤色的表情，右上角标一个小圆点 */
    skinToneCapable: Boolean,
    fontFamily: FontFamily?,
    /** 记录格子坐标，供肤色条定位 */
    onPositioned: (LayoutCoordinates) -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val palette = LocalImePalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val background by animateColorAsState(
        targetValue = if (pressed) palette.keyPressed.copy(alpha = 0.7f) else Color.Transparent,
        label = "symbolCellBg",
    )
    Box(
        modifier = Modifier
            .height(cellHeight)
            .onGloballyPositioned(onPositioned)
            .then(if (showGrid) Modifier else Modifier.clip(RoundedCornerShape(10.dp)))
            .background(background)
            .then(
                if (showGrid) {
                    // Excel 式格子：只画右侧 + 底部 1px 发丝线。
                    // 不能四边都画：行距是 0，相邻格各画一遍会叠成 2~3px 的深色块——
                    // 旧实现（0.5dp 四边框 + outline 覆盖 alpha 而非相乘）正是「框太深、线太粗」的来源。
                    Modifier.drawBehind {
                        val stroke = 1f  // 物理像素：不随屏幕密度变粗
                        drawLine(
                            color = palette.gridLine,
                            start = Offset(size.width - stroke / 2f, 0f),
                            end = Offset(size.width - stroke / 2f, size.height),
                            strokeWidth = stroke,
                        )
                        drawLine(
                            color = palette.gridLine,
                            start = Offset(0f, size.height - stroke / 2f),
                            end = Offset(size.width, size.height - stroke / 2f),
                            strokeWidth = stroke,
                        )
                    }
                } else {
                    Modifier
                }
            )
            .combinedClickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        ImeAutoSizeText(
            text = symbol,
            color = palette.onSurface,
            baseFontSize = fontSize,
            fontFamily = fontFamily,
        )
        if (skinToneCapable) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 6.dp, end = 6.dp)
                    .size(4.dp)
                    .clip(CircleShape)
                    .background(palette.accent.copy(alpha = 0.55f)),
            )
        }
    }
}

@Composable
private fun DeleteButton(
    locked: Boolean,
    onToggleLock: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val palette = LocalImePalette.current
    val handler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    val repeatRunnable = remember {
        // 长按连发：定时器走 Handler，避免在手势的 restricted suspend 作用域里 delay
        object : Runnable {
            override fun run() {
                onDelete()
                handler.postDelayed(this, GestureTuning.REPEAT_INTERVAL_MS)
            }
        }
    }
    // 键帽样式的功能按钮：和左侧分类图标（透明底、小图标）拉开视觉层级
    Box(
        modifier = Modifier
            .padding(start = 4.dp)
            .size(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (locked) palette.accent.copy(alpha = 0.2f) else palette.functionKey)
            .pointerInput(locked) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (locked) {
                        handler.postDelayed(repeatRunnable, GestureTuning.REPEAT_START_DELAY_MS)
                        var pressed = true
                        while (pressed) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            pressed = change.pressed
                        }
                        handler.removeCallbacks(repeatRunnable)
                        onDelete()
                    } else {
                        // 未锁定：本次点击只是解锁
                        var pressed = true
                        while (pressed) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            pressed = change.pressed
                        }
                        onToggleLock(true)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(
                if (locked) R.drawable.sdk_skb_key_delete_icon else R.drawable.icon_symbol_lock
            ),
            contentDescription = null,
            colorFilter = ColorFilter.tint(if (locked) palette.accent else palette.onSurface),
            modifier = Modifier.size(24.dp),
        )
    }
}

private fun commitSymbol(controller: ImeController, mode: SymbolMode, symbol: String) {
    recordUsed(symbol, mode.historyType)
    controller.dispatcher.responseKeyEvent(SoftKey(label = symbol))
}

private fun commitEmoji(controller: ImeController, emoji: String) {
    recordUsed(emoji, EMOJI_TYPE)
    controller.dispatcher.responseKeyEvent(SoftKey(label = emoji))
}

/**
 * 写「最近使用」：三类各存一份（`used_symbol.type`），每类最多留 50 条。
 * 颜文字单列一类，避免和 emoji 互相挤占（旧实现两者共用一个 type）。
 */
private fun recordUsed(symbol: String, type: String) {
    runCatching {
        WhaleDatabase.instance.usedSymbolDao().insert(UsedSymbol(symbol = symbol, type = type))
        val num = max(WhaleDatabase.instance.usedSymbolDao().getCount(type) - RECENTS_LIMIT, 0)
        WhaleDatabase.instance.usedSymbolDao().deleteOldest(type, num)
    }
}

private val SymbolMode.historyType: String
    get() = when (this) {
        SymbolMode.Symbol -> "symbol"
        SymbolMode.Emoticon -> KAOMOJI_TYPE
        else -> EMOJI_TYPE
    }

private const val EMOJI_TYPE = "emoji"
private const val KAOMOJI_TYPE = "kaomoji"
private const val RECENTS_LIMIT = 50

/** 组装分页数据；「最近使用」页（recents）实时读库 */
private fun loadPages(mode: SymbolMode): List<Pair<Int, List<String>>> {
    val data = when (mode) {
        SymbolMode.Emoticon -> KaomojiData.kaomojiData
        SymbolMode.Emojicon -> EmojiconData.emojiconData
        else -> EmojiconData.symbolData
    }
    return data.entries.map { (key, values) ->
        if (key == RECENTS_KEY) key to loadRecents(mode)
        else key to values
    }
}

/**
 * 最近使用：符号键盘取常用符号，表情键盘取 emoji 记录，颜文字键盘取颜文字记录。
 * 三类分表存放（`used_symbol.type`），互不串味。
 * 从没用过就是空的——不回退到某个分类的内容冒充「最近使用」。
 */
private fun loadRecents(mode: SymbolMode): List<String> {
    return runCatching {
        when (mode) {
            SymbolMode.Symbol -> WhaleDatabase.instance.usedSymbolDao().getAllUsedSymbol()
            SymbolMode.Emoticon -> WhaleDatabase.instance.usedSymbolDao().getAllSymbolKaomoji()
            else -> WhaleDatabase.instance.usedSymbolDao().getAllSymbolEmoji()
        }.map { it.symbol }
    }.getOrDefault(emptyList())
}

private val RECENTS_KEY = R.drawable.icon_emojibar_recents

/**
 * 符号表里有个别条目带尾随空格/回车（历史遗留），渲染前去掉。
 * 只对符号页生效：颜文字的空格是外观的一部分，emoji 也不含这类脏字符。
 */
private val SYMBOL_BLANK = "[ \\r]".toRegex()
