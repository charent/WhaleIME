package com.whale.ime.compose.keyboard

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.whale.ime.ui.R
import com.whale.ime.compose.ImeController
import com.whale.ime.compose.ImeUiState
import com.whale.ime.compose.LocalImeTheme
import com.whale.ime.compose.SettingsPage
import com.whale.ime.keyboard.menu.keyboardMenuPreset
import com.whale.ime.storage.WhaleDatabase
import com.whale.ime.keyboard.menu.KeyboardMenuItem
import com.whale.ime.keyboard.state.KeyboardManager
import com.whale.ime.keyboard.state.isInputKeyboardType
import com.whale.ime.keyboard.state.isPanelKeyboardType
import com.whale.ime.preference.AppPrefs
import com.whale.ime.preference.model.KeyboardMenuMode
import com.whale.ime.dispatch.DictDecoder
import com.whale.ime.keyboard.metrics.Environment
import com.whale.ime.preference.InputFeedbacks

/**
 * 候选栏。
 *
 * 三态互斥：
 * 1. **候选态**：候选词占满整行（左侧不留菜单入口，可横滑、滚到尾部自动预取下一页）；
 * 2. **面板态**：符号/表情页、剪贴板页各自暴露自己的菜单项；
 * 3. **空闲态**：显示用户在「定制工具栏」里配置的常驻菜单。
 *
 * 布局所有权：整条候选带 = 拼音气泡条 + 候选行 + 与键盘的间距，
 * 全部由本组件拥有——间距是候选行自己的底部内边距，不再是一个独立、可点击、
 * 还要参与居中补偿的占位节点。因此：
 * - 工具栏态（以及面板态）没有防误触需求，整条色带都归它，图标在「候选行 + 间距」
 *   内自然垂直居中，不需要任何补偿；
 * - 候选态把间距作为内边距留在自己下方，点间距即上屏第一个候选。
 *
 * 拼音气泡画在栏上方的透明条里（[Environment.composingOverhang]）：
 * 那条带不属于键盘色面（主题背景从条下开始绘制），宿主内容会延伸进来，
 * 气泡因此看起来像「凸出到键盘之外」，同时省掉了栏内一整行拼音。
 */
@Composable
fun CandidateBar(
    keyboardType: KeyboardManager.KeyboardType?,
    uiState: ImeUiState,
    controller: ImeController,
    candidateState: DictDecoder.CandidateState,
    bottomGapPx: Int = Environment.instance.candidatesBottomGap,
) {
    val palette = LocalImePalette.current
    val env = remember { Environment.instance }
    val density = LocalDensity.current

    // 分段组句时把「已确认的字」拼在组合串前面展示：布|si
    val composingDisplay = candidateState.pickedSegmentText + candidateState.composingText
    // 刚刚复制的内容是一整条候选、没有编码可看，气泡改为标注来源，否则用户不知道它从哪来
    val bubbleText = if (candidateState.isClipboardSuggestion) {
        controller.string(R.string.clipboard_suggestion_hint)
    } else {
        composingDisplay
    }
    val hasContent = candidateState.candidates.isNotEmpty() || composingDisplay.isNotEmpty()
    val branch = when {
        keyboardType == KeyboardManager.KeyboardType.SYMBOL -> BarBranch.SymbolMenu
        keyboardType == KeyboardManager.KeyboardType.ClipBoard -> BarBranch.ClipboardMenu
        hasContent -> BarBranch.Candidate
        else -> BarBranch.Toolbar
    }

    val overhang = with(density) { env.composingOverhang.toDp() }
    val gap = with(density) { bottomGapPx.toDp() }
    // 面板态（符号/表情、剪贴板/常用语、功能宫格、编辑键盘）：工具栏右侧是「×」关面板；
    // 只有真正的输入键盘上才是「收起输入法」
    val panelOpen = keyboardType.isPanelKeyboardType()

    Box(modifier = Modifier.fillMaxSize()) {
        // 拼音气泡：只落在透明条内，绝不画到候选行上
        if (branch == BarBranch.Candidate && bubbleText.isNotEmpty()) {
            ComposingBubble(
                text = bubbleText,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .height(overhang),
            )
        }

        // 内容区从气泡条之下开始
        Column(modifier = Modifier.fillMaxSize().padding(top = overhang)) {
            if (branch == BarBranch.Candidate) {
                CandidateRow(
                    candidateState = candidateState,
                    activeCandidate = uiState.activeCandidate,
                    keyboardType = keyboardType,
                    controller = controller,
                    bottomGap = gap,
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 左侧入口：有候选时整行让给候选词，这里只在面板态/空闲态出现
                    ImeIconButton(
                        iconRes = if (keyboardType.isInputKeyboardType()) R.drawable.ic_menu_menu
                        else R.drawable.ic_menu_arrow_up,
                        contentDescription = controller.string(R.string.skb_item_settings),
                        tint = palette.onSurface,
                        modifier = Modifier.padding(start = 4.dp),
                        size = 44.dp,
                        iconSize = 26.dp,
                        onClick = { controller.onMenuAction(KeyboardMenuMode.SettingsMenu) },
                    )

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    ) {
                        when (branch) {
                            BarBranch.SymbolMenu -> MenuRow(
                                items = listOfNotNull(
                                    keyboardMenuPreset[KeyboardMenuMode.Emoticon],
                                    keyboardMenuPreset[KeyboardMenuMode.Emojicon],
                                ),
                                controller = controller,
                                panelOpen = panelOpen,
                            )

                            BarBranch.ClipboardMenu -> {
                                val items = if (uiState.clipboardMode == KeyboardMenuMode.ClipBoard) {
                                    listOfNotNull(
                                        keyboardMenuPreset[KeyboardMenuMode.ClearClipBoard],
                                        keyboardMenuPreset[KeyboardMenuMode.ClipBoard],
                                        keyboardMenuPreset[KeyboardMenuMode.Phrases],
                                        keyboardMenuPreset[KeyboardMenuMode.LockClipBoard],
                                    )
                                } else {
                                    listOfNotNull(
                                        keyboardMenuPreset[KeyboardMenuMode.AddPhrases],
                                        keyboardMenuPreset[KeyboardMenuMode.ClipBoard],
                                        keyboardMenuPreset[KeyboardMenuMode.Phrases],
                                        keyboardMenuPreset[KeyboardMenuMode.LockClipBoard],
                                    )
                                }
                                MenuRow(items = items, controller = controller, panelOpen = panelOpen)
                            }

                            else -> {
                                // 工具栏态：仅在「定制工具栏」页面打开时允许长按拖拽排序。
                                // 列表来自 BarMenuStore，与定制页共享，星标增删后实时重排。
                                MenuRow(
                                    items = BarMenuStore.items,
                                    controller = controller,
                                    panelOpen = panelOpen,
                                    reorderable = uiState.settingsPage == SettingsPage.Customize,
                                    onReorder = { next -> BarMenuStore.update(next) },
                                    onReorderFinished = { persistKeepOrder(BarMenuStore.items) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 候选栏当前显示哪一态（三态互斥 + 面板态细分，避免多处 `when` 条件各写一遍） */
private enum class BarBranch { Toolbar, SymbolMenu, ClipboardMenu, Candidate }

private fun loadBarMenus(): List<KeyboardMenuItem> =
    runCatching {
        WhaleDatabase.instance.skbFunDao().getALlBarMenu().mapNotNull { item ->
            val mode = KeyboardMenuMode.decode(item.name)
            keyboardMenuPreset[mode]
        }
    }.getOrDefault(emptyList())

/**
 * 候选栏常驻工具栏的共享状态：候选栏（拖拽排序）与定制工具栏页（星标增删、
 * 列表拖拽）都读写同一份，任何一方变更后另一方立即重排。
 */
object BarMenuStore {
    var items: List<KeyboardMenuItem> by mutableStateOf(loadBarMenus())
        private set

    /** 拖拽过程中的实时交换（未落库），仅更新显示 */
    fun update(next: List<KeyboardMenuItem>) {
        items = next
    }

    /** 从数据库重新加载（星标增删、列表排序落库后调用） */
    fun reload() {
        items = loadBarMenus()
    }
}

/** 工具栏拖拽落位后持久化：只重排常驻行（isKeep=1），position 即工具栏顺序 */
private fun persistKeepOrder(items: List<KeyboardMenuItem>) {
    runCatching {
        items.forEachIndexed { index, item ->
            WhaleDatabase.instance.skbFunDao().update(
                com.whale.ime.storage.entity.SkbFun(name = item.keyboardMenuMode.name, isKeep = 1, position = index)
            )
        }
    }
}

/**
 * 候选态：候选列表（占满整行）+ 右侧箭头。
 *
 * 拼音不再占一行——它被画在栏上方的透明气泡条里（见 [ComposingBubble]），
 * 所以这里只剩一条候选行；与键盘之间的间距以 [bottomGap] 的形式留在本行下方。
 */
@Composable
private fun CandidateRow(
    candidateState: DictDecoder.CandidateState,
    activeCandidate: Int,
    keyboardType: KeyboardManager.KeyboardType?,
    controller: ImeController,
    bottomGap: Dp,
) {
    val palette = LocalImePalette.current
    val env = remember { Environment.instance }
    // 候选字粗细（设置 → 键盘 → 字体）：字号由 env.candidateTextSize 提供，两者分开调
    val candidateWeight =
        FontWeight(AppPrefs.getInstance().keyboardSetting.candidateFontWeight.getValue().weight)
    val listState = rememberLazyListState()

    // 候选换代（新一次输入）时滚回开头
    LaunchedEffect(candidateState.generation) {
        if (candidateState.candidates.isNotEmpty() && listState.firstVisibleItemIndex != 0) {
            listState.scrollToItem(0)
        }
    }
    // 滑到尾部时预取下一页。
    // 首屏只取一屏量（RimeEngine.FIRST_BATCH_SIZE），必须等**用户真的滚动过**
    // （firstVisibleItemIndex > 0）再补：否则每次按键候选变少都会命中「接近尾部」，
    // 在按键路径上白白多跑一批候选生成。
    LaunchedEffect(listState.firstVisibleItemIndex, candidateState.candidates.size) {
        if (keyboardType == KeyboardManager.KeyboardType.CANDIDATES) return@LaunchedEffect
        if (candidateState.candidates.isEmpty() || listState.firstVisibleItemIndex == 0) {
            return@LaunchedEffect
        }
        val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
            ?: return@LaunchedEffect
        if (last >= candidateState.candidates.size - LOAD_MORE_LOOKAHEAD) {
            DictDecoder.nextPageCandidates
        }
    }

    // 空白处点击 = 上屏第一个候选。
    // 处理器挂在行根节点、并且在 padding 之前：胶囊与右侧箭头先拿到事件并消费，
    // 只有真正没人接的空白（含与键盘之间的间距那一条）才落到这里。
    // 用 rememberUpdatedState 包一层，pointerInput 就不必随候选变化重建（重建会打断手势）。
    val onBlankTap = rememberUpdatedState {
        if (candidateState.candidates.isNotEmpty()) {
            InputFeedbacks.tryVibrate()
            controller.dispatcher.chooseAndUpdate(0)
        }
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { onBlankTap.value() } }
            .padding(bottom = bottomGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LazyRow(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxHeight(),
            verticalAlignment = Alignment.CenterVertically,
            // 左侧不再有菜单按钮，胶囊自己留出与栏边的呼吸位
            contentPadding = PaddingValues(horizontal = 8.dp),
        ) {
            itemsIndexed(candidateState.candidates) { index, item ->
                // 胶囊的 Composition 必须绑定到候选文本上。
                // LazyRow 里 itemsIndexed 是按 index 复用子组合的：上屏后候选整体前移，
                // 同一 index 上的节点会被新候选接管，而 `clickable` 的 ripple 是长在节点上、
                // 抬手后还要「驻留 + 淡出」的动画——于是刚点的那一下波纹会接力到挤上来的
                // 新候选上（点了「我」上屏，波纹画在「们」上）。换 key 后旧节点随旧文本一起
                // 丢掉，波纹同时被回收；按下期间（未抬手）的扩散反馈仍然保留。
                key(item.text) {
                    val text: AnnotatedString = remember(item.text) { AnnotatedString(item.text) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // 相邻候选之间的浅竖线（第一个候选左侧不画）
                        if (index > 0) {
                            CandidateDivider(fontSizeSp = env.candidateTextSize)
                        }
                        CandidateChip(
                            text = text,
                            active = activeCandidate - 1 == index,
                            fontSizeSp = env.candidateTextSize,
                            fontWeight = candidateWeight,
                            isCorrection = item.comment.endsWith("?"),
                            onClick = {
                                InputFeedbacks.tryPlayKeyDown()
                                InputFeedbacks.tryVibrate()
                                controller.dispatcher.chooseAndUpdate(index)
                            },
                        )
                    }
                }
            }
        }
        // 右侧箭头：联想 → 关闭；已展开全屏候选 → 收起；否则 → 展开全屏候选
        val levelIcon = when {
            candidateState.isAssociate -> R.drawable.ic_menu_close
            keyboardType == KeyboardManager.KeyboardType.CANDIDATES -> R.drawable.ic_menu_arrow_up
            else -> R.drawable.ic_menu_arrow_down
        }
        ImeIconButton(
            iconRes = levelIcon,
            contentDescription = null,
            tint = palette.onSurface,
            modifier = Modifier.padding(end = 2.dp),
            size = 34.dp,
            iconSize = 20.dp,
            onClick = {
                when {
                    candidateState.isAssociate -> {
                        controller.dispatcher.resetToIdleState()
                        controller.switchToInputKeyboard()
                    }
                    keyboardType == KeyboardManager.KeyboardType.CANDIDATES ->
                        controller.switchToInputKeyboard()

                    else -> controller.onMenuAction(KeyboardMenuMode.CandidatesMore)
                }
            },
        )
    }
}

/**
 * 拼音气泡：画在候选栏上方的透明条里，因此必须自带底衬——条内会透出宿主内容，
 * 纯文字压上去不可读。底色取功能键色 92% 不透明，与候选胶囊同一套色阶。
 */
@Composable
private fun ComposingBubble(text: String, modifier: Modifier = Modifier) {
    val palette = LocalImePalette.current
    val env = remember { Environment.instance }
    Box(modifier, contentAlignment = Alignment.BottomStart) {
        Box(
            modifier = Modifier
                .padding(start = 6.dp, bottom = 2.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(palette.functionKey.copy(alpha = 0.92f))
                .padding(horizontal = 8.dp, vertical = 1.dp),
        ) {
            Text(
                text = text,
                color = palette.muted,
                style = TextStyle(
                    fontSize = env.composingTextSize.sp,
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 候选之间的浅竖线。
 *
 * 高度取候选字号的 0.9 倍（sp 换算成 dp，跟随系统字体缩放），让线只占文字那一小段，
 * 不顶满行高；颜色用 onSurface 12% 透明度，深浅主题与自定义配色下都自动适配。
 */
@Composable
private fun CandidateDivider(fontSizeSp: Float) {
    val palette = LocalImePalette.current
    val dividerHeight = with(LocalDensity.current) { (fontSizeSp * 0.9f).sp.toDp() }
    Box(
        modifier = Modifier
            .padding(horizontal = 1.dp)
            .width(1.dp)
            .height(dividerHeight)
            .background(palette.onSurface.copy(alpha = 0.12f)),
    )
}

/** 单个候选词：胶囊底 + 颜色过渡的选中态 */
@Composable
private fun CandidateChip(
    text: AnnotatedString,
    active: Boolean,
    fontSizeSp: Float,
    fontWeight: FontWeight,
    isCorrection: Boolean,
    onClick: () -> Unit,
) {
    val palette = LocalImePalette.current
    val background by animateColorAsState(
        targetValue = if (active) palette.accent else Color.Transparent,
        label = "candidateChipBg",
    )
    val foreground by animateColorAsState(
        targetValue = if (active) palette.onAccent else palette.onSurface,
        label = "candidateChipFg",
    )
    Box(
        modifier = Modifier
            .padding(horizontal = 2.dp)
            .clip(ImeChipShape)
            .background(background)
            .imeClickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = foreground,
            style = TextStyle(
                fontSize = fontSizeSp.sp,
                fontWeight = fontWeight,
                platformStyle = PlatformTextStyle(includeFontPadding = false),
            ),
            maxLines = 1,
            overflow = TextOverflow.Visible,
            // 纠错候选右侧要留出问号角标的位置，避免字与问号叠在一起
            modifier = if (isCorrection) Modifier.padding(end = 9.dp) else Modifier,
        )
        // 引擎纠错候选（comment 以 "?" 结尾）：右上角小问号角标，提示拼写被修正
        if (isCorrection) {
            CorrectionBadge(
                fontSizeSp = fontSizeSp,
                color = foreground.copy(alpha = 0.6f),
                modifier = Modifier.align(Alignment.TopEnd),
            )
        }
    }
}

/**
 * 菜单态：自定义工具整体水平居中（含左侧菜单按钮的宽度一起计算），放不下时可横滑。
 *
 * 图标不随候选区高度等比缩放，改用固定 dp：
 * 按钮统一 44dp 触控目标、26dp 图标。垂直方向由所在容器（候选行 + 与键盘的间距）
 * 直接居中——间距已经是容器的内边距，不再需要「把留白折算成 top padding」的补偿。
 * 居中区域由左右两个固定按钮之间的空间构成，工具再多可横滑；
 * 数量上限 [MAX_TOOLBAR_ITEMS]，超出的自定义项按配置顺序截断。
 *
 * @param panelOpen 当前是不是面板态（见 `isPanelKeyboardType`）。是则右侧按钮是「×」，
 *   只关闭面板回到输入键盘；否则是「收起输入法」的下箭头。
 */
@Composable
private fun MenuRow(
    items: List<KeyboardMenuItem>,
    controller: ImeController,
    panelOpen: Boolean,
    reorderable: Boolean = false,
    onReorder: ((List<KeyboardMenuItem>) -> Unit)? = null,
    onReorderFinished: (() -> Unit)? = null,
) {
    val palette = LocalImePalette.current
    val scrollState = rememberScrollState()

    // 拖拽排序（仅定制工具栏页启用）：没有内部副本，每次交换直接通过 [onReorder]
    // 把新顺序上抛给持有状态的一方，界面立即重排；松手时 [onReorderFinished] 落库。
    // 手势闭包通过 rememberUpdatedState 读最新列表，避免捕获到过期参数。
    val currentItems by rememberUpdatedState(items)
    var dragIndex by remember { mutableStateOf(-1) }
    var dragOffset by remember { mutableStateOf(0f) }
    val stepPx = with(LocalDensity.current) { (MENU_ITEM_SIZE + MENU_ITEM_SPACING).toPx() }

    fun swap(a: Int, b: Int) {
        val list = currentItems.toMutableList()
        val tmp = list[a]
        list[a] = list[b]
        list[b] = tmp
        onReorder?.invoke(list)
    }

    Row(
        modifier = Modifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 居中只交给 contentAlignment：内容放得下时 scroll 行宽 = 内容宽，Box 直接把它
        // 居中；放不下时行宽被 horizontalScroll 钳到容器宽，从左侧起排、可横滑——两种
        // 情况都不需要提前知道「是否溢出」。
        // 曾经这里用 `if (scrollState.maxValue == 0) Center else CenterStart`，但
        // `maxValue` 是**测量之后**才写入的状态（初值 Int.MAX_VALUE）：每次切换面板
        // 都会新建 ScrollState，于是新一帧先按 CenterStart 靠左渲染、测完再跳到居中，
        // 表现为「工具按钮先在菜单按钮旁边出现，然后瞬间消失」。
        Box(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .horizontalScroll(scrollState),
                horizontalArrangement = Arrangement.spacedBy(MENU_ITEM_SPACING),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items.take(MAX_TOOLBAR_ITEMS).forEach { item ->
                    key(item.keyboardMenuMode.name) {
                        val idx = currentItems.indexOfFirst { it.keyboardMenuMode == item.keyboardMenuMode }
                        val isLocked = item.keyboardMenuMode == KeyboardMenuMode.LockClipBoard && controller.isClipboardLocked
                        val tint = if (isLocked) palette.accent else palette.onSurface
                        val dragging = dragIndex == idx
                        Box(
                            modifier = Modifier
                                .zIndex(if (dragging) 1f else 0f)
                                .graphicsLayer { translationX = if (dragging) dragOffset else 0f }
                                .size(MENU_ITEM_SIZE)
                                .then(
                                    if (reorderable) {
                                        Modifier.pointerInput(item.keyboardMenuMode) {
                                            detectDragGesturesAfterLongPress(
                                                onDragStart = {
                                                    InputFeedbacks.tryVibrate()
                                                    dragIndex = currentItems.indexOfFirst { it.keyboardMenuMode == item.keyboardMenuMode }
                                                    dragOffset = 0f
                                                },
                                                onDragEnd = {
                                                    if (dragIndex >= 0) onReorderFinished?.invoke()
                                                    dragIndex = -1
                                                    dragOffset = 0f
                                                },
                                                onDragCancel = {
                                                    dragIndex = -1
                                                    dragOffset = 0f
                                                },
                                                onDrag = { change, amount ->
                                                    change.consume()
                                                    if (dragIndex < 0) return@detectDragGesturesAfterLongPress
                                                    dragOffset += amount.x
                                                    while (dragOffset > stepPx / 2 && dragIndex < currentItems.lastIndex) {
                                                        swap(dragIndex, dragIndex + 1)
                                                        dragIndex++
                                                        dragOffset -= stepPx
                                                    }
                                                    while (dragOffset < -stepPx / 2 && dragIndex > 0) {
                                                        swap(dragIndex, dragIndex - 1)
                                                        dragIndex--
                                                        dragOffset += stepPx
                                                    }
                                                },
                                            )
                                        }
                                    } else {
                                        Modifier
                                    }
                                )
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isLocked) palette.accent.copy(alpha = 0.14f) else Color.Transparent)
                                .imeClickable {
                                    InputFeedbacks.tryPlayKeyDown()
                                    InputFeedbacks.tryVibrate()
                                    controller.onMenuAction(item.keyboardMenuMode)
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Image(
                                painter = painterResource(item.funImgResource),
                                contentDescription = item.funName,
                                colorFilter = ColorFilter.tint(tint),
                                modifier = Modifier.size(26.dp),
                            )
                        }
                    }
                }
            }
        }
        ImeIconButton(
            iconRes = if (panelOpen) R.drawable.ic_menu_close else R.drawable.ic_menu_arrow_down,
            contentDescription = controller.string(
                if (panelOpen) R.string.keyboard_iv_menu_close_panel else R.string.keyboard_iv_menu_close
            ),
            tint = palette.onSurface,
            modifier = Modifier.padding(end = 4.dp),
            size = 44.dp,
            iconSize = 26.dp,
            onClick = {
                // 面板态只关面板，回到输入键盘后才收起输入法
                if (panelOpen) controller.closePanel()
                else controller.onMenuAction(KeyboardMenuMode.CloseSKB)
            },
        )
    }
}

private val MENU_ITEM_SIZE = 44.dp
private val MENU_ITEM_SPACING = 10.dp

/** 候选栏自定义工具的数量上限：再多就拥挤、容易误触 */
private const val MAX_TOOLBAR_ITEMS = 5

private const val LOAD_MORE_LOOKAHEAD = 6
