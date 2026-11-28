package com.whale.ime.compose.keyboard

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import com.whale.ime.preference.InputFeedbacks
import com.whale.ime.clipboard.ClipboardExpiry
import java.util.Locale

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
 *
 * 工具栏的「清空剪贴板」不直接删库：先由 [ClearClipboardConfirm] 做二次确认（见 `ImeUiState.clearClipboardConfirm`）。
 */
@Composable
fun ClipboardPanel(uiState: ImeUiState, controller: ImeController) {
    Box(modifier = Modifier.fillMaxSize()) {
        ClipboardList(uiState, controller)
        // 工具栏「清空剪贴板」的二次确认：盖在列表上，遮罩吃掉点击，只能明确选择取消/清空
        if (uiState.clearClipboardConfirm) {
            ClearClipboardConfirm(
                onDismiss = { controller.dismissClearClipboardConfirm() },
                onConfirm = { controller.clearClipboardHistory() },
            )
        }
    }
}

/** 列表本体：空态提示 + 分页网格 */
@Composable
private fun ClipboardList(uiState: ImeUiState, controller: ImeController) {
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

    // 内容自动失效只在剪贴板面板生效（常用语mode 下 Clipboard 只是展示载体）
    val clipboardPrefs = AppPrefs.getInstance().clipboard
    val expireEnabled = mode == KeyboardMenuMode.ClipBoard &&
        rememberPreferenceValue(clipboardPrefs.clipboardListening) &&
        rememberPreferenceValue(clipboardPrefs.clipboardExpireEnable)
    val keepHistory = rememberPreferenceValue(clipboardPrefs.clipboardExpireKeepHistory)
    val durationMs = rememberPreferenceValue(clipboardPrefs.clipboardExpireDuration) * 1000L
    // 保留历史时只给「最新的一条」显示倒计时：旧内容在系统剪贴板里早被覆盖，倒计时没有意义
    val newestTime = remember(items) { items.maxOfOrNull { it.time } }

    // 秒级倒计时：整屏只有一个 ticker，面板销毁即取消；只有倒计时那行小字读 [nowMs]，
    // 卡片、列表、数据库都不会因此重组或重查
    val nowMs = remember { mutableLongStateOf(System.currentTimeMillis()) }
    val now = remember(nowMs) { { nowMs.longValue } }
    LaunchedEffect(expireEnabled, mode, uiState.clipboardRevision) {
        if (!expireEnabled) return@LaunchedEffect
        while (true) {
            delay(1000L - System.currentTimeMillis() % 1000L)   // 对齐整秒，避免 1 秒内多跳
            nowMs.longValue = System.currentTimeMillis()
            // 不保留历史：到点的条目先从列表里摘掉（库里由 ClipboardExpiry 负责删）。
            // 分界点之前的记录不参与删除——关开关不能把已有历史一起清掉
            if (!clipboardPrefs.clipboardExpireKeepHistory.getValue()) {
                val expireBefore = nowMs.longValue - clipboardPrefs.clipboardExpireDuration.getValue() * 1000L
                val since = ClipboardExpiry.keepHistorySince()
                val alive = items.filter { it.time > expireBefore || it.time < since }
                if (alive.size != items.size) items = alive
            }
        }
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
            // 保留历史时旧内容的倒计时直接不给，期满那条也跟着消失；不保留历史则每条各算各的
            val expireAt = when {
                !expireEnabled -> null
                keepHistory && item.time != newestTime -> null
                else -> item.time + durationMs
            }
            ClipboardCell(
                item = item,
                mode = mode,
                code = phraseCodes[item.content],
                compact = layout != ClipboardLayoutMode.ListView,
                expireAt = expireAt,
                now = now,
                onCommit = {
                    InputFeedbacks.tryPlayKeyDown()
                    InputFeedbacks.tryVibrate()
                    controller.dispatcher.responseLongKeyEvent(Pair(PopupMenuMode.Text, item.content))
                    if (!controller.isClipboardLocked) controller.switchToInputKeyboard()
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
                            // 删掉的正是系统剪贴板里那条时，同步让它失效，否则别的 App 长按还能粘贴到
                            ClipboardExpiry.onContentRemoved(item.content)
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
 * 清空剪贴板历史的二次确认层。
 *
 * 不用 `Dialog`：输入法窗口里新建 Dialog 需要独立 window token，部分机型上会直接不显示
 * 或把输入法窗口顶掉，所以就在面板里铺一层遮罩 + 卡片。
 * 遮罩点击 = 取消（卡片自己消费点击，避免点卡片也被当成取消）。
 */
@Composable
private fun ClearClipboardConfirm(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val palette = LocalImePalette.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.shadow.copy(alpha = 0.32f))
            .imeClickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .shadow(8.dp, ImeCardShape)
                .clip(ImeCardShape)
                .background(palette.card)
                // 吃掉卡片上的点击：只有取消/清空两个按钮能决定去留
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(horizontal = 18.dp, vertical = 14.dp),
        ) {
            Text(
                text = stringResource(R.string.clipboard_clear_confirm_title),
                color = palette.onSurface,
                style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.clipboard_clear_confirm_message),
                color = palette.muted,
                style = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ConfirmActionButton(
                    text = stringResource(R.string.cancel),
                    filled = false,
                    onClick = onDismiss,
                )
                Spacer(Modifier.width(8.dp))
                ConfirmActionButton(
                    text = stringResource(R.string.clipboard_clear_confirm_action),
                    filled = true,
                    onClick = onConfirm,
                )
            }
        }
    }
}

/** 确认层的胶囊按钮：实心 = 主操作（清空），描边/底色 = 取消 */
@Composable
private fun ConfirmActionButton(text: String, filled: Boolean, onClick: () -> Unit) {
    val palette = LocalImePalette.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (filled) palette.accent else palette.surface)
            .imeClickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (filled) palette.onAccent else palette.onSurface,
            style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
        )
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
    /** 需要展示倒计时的到期时刻；null = 不展示 */
    expireAt: Long?,
    /** 只读的时间源：只有倒计时那行小字会读它，避免每秒整行重组 */
    now: () -> Long,
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
                .heightIn(min = 46.dp)
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
            // 倒计时立到操作按钮的上方，紧贴它们
            Column(horizontalAlignment = Alignment.End) {
                if (expireAt != null) {
                    ExpireCountdown(expireAt = expireAt, now = now, modifier = Modifier.padding(end = 8.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
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
            }
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
            if (expireAt != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    ExpireCountdown(expireAt = expireAt, now = now, modifier = Modifier.padding(end = 10.dp))
                }
            }
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
 * 距内容失效还剩多久（很小的字，挂在置顶/删除按钮上方）。
 *
 * 只有这个可组合函数读 [now]，所以每秒的重组范围就止步于这一行 Text；
 * 到期（`remaining <= 0`）后自己消失，条目本身按设置留在历史里还是被删掉由上层决定。
 */
@Composable
private fun ExpireCountdown(expireAt: Long, now: () -> Long, modifier: Modifier = Modifier) {
    val remaining = expireAt - now()
    if (remaining <= 0L) return
    val palette = LocalImePalette.current
    Text(
        text = formatCountdown(remaining),
        // 最后 1 分钟换个颜色提醒，平时保持克制
        color = if (remaining <= COUNTDOWN_WARN_MS) palette.accent else palette.muted,
        style = TextStyle(fontSize = 9.sp),
        maxLines = 1,
        softWrap = false,
        modifier = modifier,
    )
}

/** 999 秒以内按秒显示，超过按「xx.x 分钟」显示 */
@Composable
private fun formatCountdown(remainingMs: Long): String {
    val seconds = (remainingMs + 999L) / 1000L   // 向上取整，不会出现「0 秒」
    return if (seconds <= 999L) {
        stringResource(R.string.clipboard_expire_countdown_seconds, seconds)
    } else {
        stringResource(
            R.string.clipboard_expire_countdown_minutes,
            String.format(Locale.getDefault(), "%.1f", seconds / 60f),
        )
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

/** 倒计时进入最后 1 分钟时换强调色 */
private const val COUNTDOWN_WARN_MS = 60_000L
