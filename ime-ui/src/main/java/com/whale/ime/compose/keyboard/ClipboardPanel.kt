package com.whale.ime.compose.keyboard

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.whale.ime.ui.R
import com.whale.ime.compose.ImeController
import com.whale.ime.compose.ImeUiState
import com.whale.ime.compose.settings.rememberPreferenceValue
import com.whale.ime.storage.WhaleDatabase
import com.whale.ime.storage.entity.Clipboard
import com.whale.ime.phrase.PhraseQuickCodeManager
import com.whale.ime.preference.AppPrefs
import com.whale.ime.preference.model.ClipboardLayoutMode
import com.whale.ime.preference.model.PopupMenuMode
import com.whale.ime.preference.model.KeyboardMenuMode
import kotlinx.coroutines.flow.distinctUntilChanged
import com.whale.ime.preference.InputFeedbacks
import com.whale.ime.clipboard.ClipboardLock

/**
 * 剪贴板 / 常用语列表。
 *
 * 「置顶 / 删除」两个动作放在每条右侧的图标上——侧滑删除在 IME 这种
 * 与宿主滑动嵌套的容器里很容易和「候选栏横滑」抢手势，
 * 用显式图标后行为更可预期，能力不减少。
 *
 * 布局跟随「剪贴板布局」设置（单列 / 双列 / 紧凑），三种都用同一个网格承载：
 * - 单列：整行卡片，文字 + 两个按钮；
 * - 双列：两列卡片，文字上、按钮下；
 * - 紧凑：自适应列宽，列数随屏幕宽度变。
 */
@Composable
fun ClipboardPanel(uiState: ImeUiState, controller: ImeController) {
    val palette = LocalImePalette.current
    val mode = uiState.clipboardMode
    val layout = rememberPreferenceValue(AppPrefs.getInstance().clipboard.clipboardLayoutCompact)
    val gridState = rememberLazyGridState()

    var items by remember(mode, uiState.clipboardRevision) { mutableStateOf(loadFirstPage(mode)) }
    var hasMore by remember(mode, uiState.clipboardRevision) { mutableStateOf(items.size >= PAGE_SIZE) }
    // 常用语的「首字母快捷码」：仅作标识展示，常用语不会出现在候选里
    val phraseCodes = remember(mode, uiState.clipboardRevision) {
        if (mode == KeyboardMenuMode.Phrases) loadPhraseCodes() else emptyMap()
    }

    // 滚到底部附近自动续载：跟着「最后一个可见格子」变化，单列/双列/紧凑通用
    LaunchedEffect(mode, uiState.clipboardRevision, gridState) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .distinctUntilChanged()
            .collect { last ->
                if (last < 0 || !hasMore) return@collect
                if (last >= items.size - PRELOAD_DISTANCE) {
                    val next = loadPage(mode, items.size)
                    if (next.isNotEmpty()) {
                        items = items + next
                        hasMore = next.size >= PAGE_SIZE
                    } else {
                        hasMore = false
                    }
                }
            }
    }

    if (items.isEmpty()) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_ui_clipboard),
                contentDescription = null,
                colorFilter = ColorFilter.tint(palette.muted),
                modifier = Modifier.size(34.dp),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = controller.string(R.string.clipboard_empty_ltip),
                color = palette.muted,
                style = TextStyle(fontSize = 14.sp),
            )
        }
        return
    }

    LazyVerticalGrid(
        columns = when (layout) {
            ClipboardLayoutMode.ListView -> GridCells.Fixed(1)
            ClipboardLayoutMode.GridView -> GridCells.Fixed(2)
            ClipboardLayoutMode.FlexboxView -> GridCells.Adaptive(minSize = 112.dp)
        },
        state = gridState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        itemsIndexed(items, key = { _, item -> item.content }) { index, item ->
            ClipboardCell(
                item = item,
                mode = mode,
                code = phraseCodes[item.content],
                compact = layout != ClipboardLayoutMode.ListView,
                onCommit = {
                    InputFeedbacks.tryPlayKeyDown()
                    InputFeedbacks.tryVibrate()
                    controller.dispatcher.responseLongKeyEvent(Pair(PopupMenuMode.Text, item.content))
                    if (!ClipboardLock.enabled) controller.switchToInputKeyboard()
                },
                // 置顶 / 编辑
                onPin = {
                    if (mode == KeyboardMenuMode.ClipBoard) {
                        runCatching {
                            item.isKeep = 1 - item.isKeep
                            WhaleDatabase.instance.clipboardDao().update(item)
                        }
                        items = loadFirstPage(mode)
                    } else {
                        controller.startAddPhrases(
                            runCatching {
                                WhaleDatabase.instance.phraseDao().queryByContent(item.content)
                            }.getOrNull()
                        )
                    }
                },
                onDelete = {
                    runCatching {
                        if (mode == KeyboardMenuMode.ClipBoard) {
                            WhaleDatabase.instance.clipboardDao().deleteByContent(item.content)
                        } else {
                            WhaleDatabase.instance.phraseDao().deleteByContent(item.content)
                            // 常用语快捷码可能已被注入候选，索引同步移除
                            PhraseQuickCodeManager.reload()
                        }
                    }
                    items = items.toMutableList().also { list ->
                        if (index in list.indices) list.removeAt(index)
                    }
                },
            )
        }
    }
}

/**
 * 一条记录。
 *
 * [compact] = 双列 / 紧凑布局：卡片更窄，文字单独一行，操作按钮收到底部右侧；
 * 否则是单列整行布局。
 */
@Composable
private fun ClipboardCell(
    item: Clipboard,
    mode: KeyboardMenuMode,
    /** 常用语的首字母快捷码（剪贴板为空） */
    code: String?,
    compact: Boolean,
    onCommit: () -> Unit,
    onPin: () -> Unit,
    onDelete: () -> Unit,
) {
    val palette = LocalImePalette.current
    val pinIcon = if (mode == KeyboardMenuMode.ClipBoard) {
        if (item.isKeep == 1) R.drawable.ic_baseline_untop_circle_32
        else R.drawable.ic_baseline_top_circle_32
    } else R.drawable.ic_menu_edit
    val pinTint = if (item.isKeep == 1) palette.accent else palette.muted
    val cardModifier = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(14.dp))
        .background(palette.card.copy(alpha = 0.9f))

    if (!compact) {
        Row(
            modifier = cardModifier
                .height(46.dp)
                .padding(start = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = item.content.displayPreview(),
                color = palette.onSurface,
                style = TextStyle(fontSize = 14.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .imeClickable(onClick = onCommit),
            )
            if (!code.isNullOrBlank()) {
                Text(
                    text = code,
                    color = palette.muted,
                    style = TextStyle(fontSize = 11.sp),
                    modifier = Modifier.padding(end = 2.dp),
                )
            }
            ImeIconButton(
                iconRes = pinIcon,
                contentDescription = null,
                tint = pinTint,
                size = 34.dp,
                iconSize = 20.dp,
                onClick = onPin,
            )
            ImeIconButton(
                iconRes = R.drawable.ic_menu_delete,
                contentDescription = null,
                tint = palette.muted,
                size = 34.dp,
                iconSize = 20.dp,
                onClick = onDelete,
            )
        }
    } else {
        Column(
            modifier = cardModifier
                .heightIn(min = 60.dp)
                .padding(start = 12.dp, end = 4.dp, top = 6.dp),
        ) {
            Text(
                text = item.content.displayPreview(),
                color = palette.onSurface,
                style = TextStyle(fontSize = 13.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .imeClickable(onClick = onCommit),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!code.isNullOrBlank()) {
                    Text(
                        text = code,
                        color = palette.muted,
                        style = TextStyle(fontSize = 10.sp),
                        modifier = Modifier.padding(end = 4.dp),
                    )
                }
                ImeIconButton(
                    iconRes = pinIcon,
                    contentDescription = null,
                    tint = pinTint,
                    size = 28.dp,
                    iconSize = 16.dp,
                    onClick = onPin,
                )
                ImeIconButton(
                    iconRes = R.drawable.ic_menu_delete,
                    contentDescription = null,
                    tint = palette.muted,
                    size = 28.dp,
                    iconSize = 16.dp,
                    onClick = onDelete,
                )
            }
        }
    }
}

/**
 * 列表预览文本：仅去掉开头的空白（含 \n、\t、全角空格）。
 *
 * 以换行开头的内容（复制来的整段文本很常见）首行是空的，
 * 单行 Text 会把整条渲染成「…」，看不出是什么内容。
 * 这里只影响展示，提交 / 置顶 / 编辑用的仍是 [Clipboard.content] 原文，
 * 原文的空白字符不会被修改。
 */
private fun String.displayPreview(): String = trimStart()

private fun loadFirstPage(mode: KeyboardMenuMode): List<Clipboard> = loadPage(mode, 0)

private fun loadPage(mode: KeyboardMenuMode, offset: Int): List<Clipboard> = runCatching {
    if (mode == KeyboardMenuMode.ClipBoard) {
        WhaleDatabase.instance.clipboardDao().getPage(PAGE_SIZE, offset)
    } else {
        WhaleDatabase.instance.phraseDao().getPage(PAGE_SIZE, offset)
            .map { line -> Clipboard(line.content) }
    }
}.getOrDefault(emptyList())

/** 常用语快捷码：内容 → 首字母码（如 "sd"），列表里作为只读提示展示 */
private fun loadPhraseCodes(): Map<String, String> = runCatching {
    WhaleDatabase.instance.phraseDao().getAll()
        .mapNotNull { phrase -> phrase.qwerty.takeIf { it.isNotBlank() }?.let { phrase.content to it } }
        .toMap()
}.getOrDefault(emptyMap())

private const val PAGE_SIZE = 100
private const val PRELOAD_DISTANCE = 10
