package com.whale.ime.compose.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.whale.ime.ui.R
import com.whale.ime.compose.ImeController
import com.whale.ime.compose.ImeUiState
import com.whale.ime.compose.SettingsPage
import com.whale.ime.compose.settings.rememberPreferenceValue
import com.whale.ime.textstyle.TextStyleMode
import com.whale.ime.textstyle.textStyleFlow
import com.whale.ime.keyboard.menu.keyboardMenuPreset
import com.whale.ime.storage.WhaleDatabase
import com.whale.ime.storage.entity.SkbFun
import com.whale.ime.keyboard.menu.KeyboardMenuItem
import com.whale.ime.preference.AppPrefs
import com.whale.ime.preference.model.DoublePinyinSchemaMode
import com.whale.ime.preference.model.KeyboardMenuMode
import com.whale.ime.engine.HWEngine
import com.whale.ime.preference.InputFeedbacks

/**
 * SETTINGS 容器：功能菜单宫格 / 输入方式选择 / 菜单项定制。
 *
 * 三个页面统一成「卡片格子 / 卡片行」：
 * - 功能宫格：每项是一张圆角卡片（图标 + 标签），带错峰入场与按压缩放；
 * - 输入方式：同款卡片，图标更大、标题居中；
 * - 定制页：行卡片 + 星标（常驻）+ 长按拖拽排序。
 *
 * 排序用「长按拖拽」：按住任意一行拖到目标位置松手即可，中途其余行实时让位；
 * 列表顺序即常驻工具栏顺序（星标项按它们在列表中的先后出现在候选栏上）。
 */
@Composable
fun SettingsMenuPanel(uiState: ImeUiState, controller: ImeController) {
    when (uiState.settingsPage) {
        SettingsPage.Menu -> MenuGridPage(controller)
        SettingsPage.SelectInputMode -> SelectInputModePage(controller)
        SettingsPage.Customize -> CustomizePage(controller)
    }
}

@Composable
private fun MenuGridPage(controller: ImeController) {
    val items = remember { loadMenuItems() }
    val palette = LocalImePalette.current
    // 开关类功能的当前状态：开启时图标右上角标「开」
    val traditional = rememberPreferenceValue(AppPrefs.getInstance().input.chineseFanTi)
    // 火星文开关由 core 的 StateFlow 暴露，这里订阅才能跟着切换实时刷新角标
    val textStyleOn = textStyleFlow.collectAsState().value !=
        TextStyleMode.Disabled

    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
        contentPadding = PaddingValues(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        itemsIndexed(items) { _, item ->
            ImeTile(
                iconRes = item.funImgResource,
                label = item.funName,
                palette = palette,
                modifier = Modifier.height(60.dp),
                active = when (item.keyboardMenuMode) {
                    KeyboardMenuMode.JianFan -> traditional
                    KeyboardMenuMode.FlowerTypeface -> textStyleOn
                    else -> false
                },
                onClick = {
                    InputFeedbacks.tryPlayKeyDown()
                    InputFeedbacks.tryVibrate()
                    controller.onMenuAction(item.keyboardMenuMode)
                },
            )
        }
    }
}

private fun loadMenuItems(): List<KeyboardMenuItem> = runCatching {
    WhaleDatabase.instance.skbFunDao().getAllMenu().mapNotNull { item ->
        keyboardMenuPreset[KeyboardMenuMode.decode(item.name)]
    }
}.getOrDefault(emptyList())

@Composable
private fun SelectInputModePage(controller: ImeController) {
    val palette = LocalImePalette.current
    val appPrefs = AppPrefs.getInstance()

    val doublePYSchemaName = when (appPrefs.input.doublePYSchemaMode.getValue()) {
        DoublePinyinSchemaMode.flypy -> R.string.double_pinyin_flypy_plus
        DoublePinyinSchemaMode.natural -> R.string.double_pinyin_natural
        DoublePinyinSchemaMode.abc -> R.string.double_pinyin_abc
        DoublePinyinSchemaMode.mspy -> R.string.double_pinyin_mspy
        DoublePinyinSchemaMode.sogou -> R.string.double_pinyin_sougou
        DoublePinyinSchemaMode.ziguang -> R.string.double_pinyin_ziguang
    }

    // 顺序即入口顺序：中文26键 / 中文14键 / 中文9键 / 手写 / 笔画 / 双拼
    val items = remember {
        buildList {
            add(Triple(controller.string(R.string.keyboard_name_cn26), R.drawable.selece_input_mode_py26, KeyboardMenuMode.Pinyin26Jian))
            add(Triple(controller.string(R.string.keyboard_name_t14), R.drawable.selece_input_mode_py14, KeyboardMenuMode.Pinyin14Jian))
            add(Triple(controller.string(R.string.keyboard_name_t9), R.drawable.selece_input_mode_py9, KeyboardMenuMode.PinyinT9))
            // 16KB 页设备上手写 .so 无法加载，直接不展示该入口
            if (HWEngine.isAvailable) {
                add(Triple(controller.string(R.string.keyboard_name_hand), R.drawable.selece_input_mode_handwriting, KeyboardMenuMode.PinyinHandWriting))
            }
            add(Triple(controller.string(R.string.keyboard_name_stroke), R.drawable.selece_input_mode_stroke, KeyboardMenuMode.PinyinStroke))
            add(Triple(controller.string(doublePYSchemaName), R.drawable.selece_input_mode_dpy26, KeyboardMenuMode.Pinyin26Double))
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp),
        contentPadding = PaddingValues(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(items) { _, (name, icon, mode) ->
            ImeTile(
                iconRes = icon,
                label = name,
                palette = palette,
                modifier = Modifier.height(84.dp),
                iconSize = 32.dp,
                labelSize = 12,
                onClick = {
                    InputFeedbacks.tryPlayKeyDown()
                    InputFeedbacks.tryVibrate()
                    controller.selectInputMode(mode)
                },
            )
        }
    }
}

@Composable
private fun CustomizePage(controller: ImeController) {
    val palette = LocalImePalette.current
    val density = LocalDensity.current
    val listState = rememberLazyListState()
    var items by remember { mutableStateOf(loadMenuItems()) }
    var keepNames by remember { mutableStateOf(loadKeepNames()) }

    // 长按右侧把手拖拽排序。dragIndex 是被拖行当前下标；dragDelta 是它相对自身
    // 槽位的位移（px），由「手指在视口中的位置 − 行槽位中心」实时得出，因此列表
    // 自动滚动时被拖行仍贴着手指。拖到视口上下边缘时由下面的循环持续滚动。
    var dragIndex by remember { mutableStateOf(-1) }
    var dragDelta by remember { mutableStateOf(0f) }
    var dragFingerY by remember { mutableStateOf(0f) }
    var dragGrabOffset by remember { mutableStateOf(0f) }

    val edgePx = with(density) { DRAG_SCROLL_EDGE.toPx() }
    val autoScrollPx = with(density) { DRAG_SCROLL_SPEED.toPx() }

    fun draggedCenter(): Float? =
        listState.layoutInfo.visibleItemsInfo
            .firstOrNull { it.index == dragIndex }
            ?.let { it.offset + it.size / 2f }

    fun applyDrag() {
        if (dragIndex < 0) return
        val center = draggedCenter() ?: return
        dragDelta = dragFingerY - dragGrabOffset - center
        val target = listState.layoutInfo.visibleItemsInfo
            .firstOrNull {
                it.index != dragIndex && it.index in items.indices &&
                    dragFingerY >= it.offset && dragFingerY < it.offset + it.size
            }
            ?.index
        if (target != null) {
            items = items.toMutableList().also { it.add(target, it.removeAt(dragIndex)) }
            dragIndex = target
            draggedCenter()?.let { dragDelta = dragFingerY - dragGrabOffset - it }
        }
    }

    LaunchedEffect(dragIndex != -1) {
        if (dragIndex == -1) return@LaunchedEffect
        while (true) {
            applyDrag()
            val info = listState.layoutInfo
            val scroll = when {
                dragFingerY < info.viewportStartOffset + edgePx -> -autoScrollPx
                dragFingerY > info.viewportEndOffset - edgePx -> autoScrollPx
                else -> 0f
            }
            if (scroll != 0f) listState.dispatchRawDelta(scroll)
            delay(16)
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp),
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(CUSTOMIZE_ROW_SPACING),
    ) {
        itemsIndexed(items, key = { _, item -> item.keyboardMenuMode.name }) { _, item ->
            val kept = keepNames.contains(item.keyboardMenuMode.name)
            val dragging = dragIndex == items.indexOfFirst { it.keyboardMenuMode == item.keyboardMenuMode }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(CUSTOMIZE_ROW_HEIGHT)
                    .zIndex(if (dragging) 1f else 0f)
                    .animateItem()
                    .graphicsLayer {
                        translationY = if (dragging) dragDelta else 0f
                        scaleX = if (dragging) 1.02f else 1f
                        scaleY = if (dragging) 1.02f else 1f
                        shadowElevation = if (dragging) 12f else 0f
                    }
                    .clip(RoundedCornerShape(14.dp))
                    .background(palette.card.copy(alpha = 0.9f))
                    .padding(start = 14.dp, end = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = item.funName,
                    color = palette.onSurface,
                    style = TextStyle(fontSize = 13.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                // 常驻候选栏（星标）
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .imeClickable() {
                            val name = item.keyboardMenuMode.name
                            keepNames = if (kept) {
                                WhaleDatabase.instance.skbFunDao().delete(SkbFun(name = name, isKeep = 1))
                                BarMenuStore.reload()
                                keepNames - name
                            } else {
                                // position 取该行在全列表中的下标，保证工具栏按列表顺序显示
                                val position = items.indexOfFirst { it.keyboardMenuMode == item.keyboardMenuMode }
                                WhaleDatabase.instance.skbFunDao().insert(SkbFun(name = name, isKeep = 1, position = position))
                                BarMenuStore.reload()
                                keepNames + name
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (kept) "★" else "☆",
                        color = if (kept) palette.accent else palette.muted,
                        style = TextStyle(fontSize = 16.sp),
                        textAlign = TextAlign.Center,
                    )
                }
                // 拖拽把手：只有按住它才能拖动，避免与点击、列表滑动冲突
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .pointerInput(item.keyboardMenuMode) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { start ->
                                    InputFeedbacks.tryVibrate()
                                    dragIndex = items.indexOfFirst { it.keyboardMenuMode == item.keyboardMenuMode }
                                    // 把手在行内垂直居中：手指相对行中心的偏移 = start.y − 把手半径
                                    dragGrabOffset = start.y - CUSTOMIZE_HANDLE_SIZE.toPx() / 2f
                                    draggedCenter()?.let { dragFingerY = it + dragGrabOffset }
                                    dragDelta = 0f
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    if (dragIndex < 0) return@detectDragGesturesAfterLongPress
                                    dragFingerY += amount.y
                                    applyDrag()
                                },
                                onDragEnd = {
                                    if (dragIndex >= 0) {
                                        persistOrder(items, keepNames)
                                        BarMenuStore.reload()
                                    }
                                    dragIndex = -1
                                    dragDelta = 0f
                                },
                                onDragCancel = {
                                    dragIndex = -1
                                    dragDelta = 0f
                                },
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    DragHandle(color = palette.muted)
                }
            }
        }
        item {
            // 完成：一颗强调色胶囊，和下面的卡片列表拉开层级
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                Box(
                    modifier = Modifier
                        .height(38.dp)
                        .clip(RoundedCornerShape(19.dp))
                        .background(palette.accent)
                        .imeClickable() { controller.switchToInputKeyboard() }
                        .padding(horizontal = 28.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = controller.string(R.string.setting_ime_keyboard_height_sure),
                        color = palette.onAccent,
                        style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
                    )
                }
            }
        }
    }
}

private fun loadKeepNames(): Set<String> = runCatching {
    WhaleDatabase.instance.skbFunDao().getALlBarMenu().map { it.name }.toSet()
}.getOrDefault(emptySet())

private fun persistOrder(items: List<KeyboardMenuItem>, keepNames: Set<String>) {
    runCatching {
        items.forEachIndexed { index, item ->
            val name = item.keyboardMenuMode.name
            // isKeep 必须带原值：主键是 (name, isKeep)，写错会更新不到行
            WhaleDatabase.instance.skbFunDao().update(
                SkbFun(name = name, isKeep = if (name in keepNames) 1 else 0, position = index)
            )
        }
    }
}

private val CUSTOMIZE_ROW_HEIGHT = 46.dp
private val CUSTOMIZE_ROW_SPACING = 6.dp
private val CUSTOMIZE_HANDLE_SIZE = 34.dp

/** 拖到距列表边缘该距离内开始自动滚动 */
private val DRAG_SCROLL_EDGE = 32.dp

/** 自动滚动速度（每帧） */
private val DRAG_SCROLL_SPEED = 9.dp

/** 三横杆拖拽把手 */
@Composable
private fun DragHandle(color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        repeat(3) {
            Box(
                modifier = Modifier
                    .padding(vertical = 2.dp)
                    .width(16.dp)
                    .height(2.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(color),
            )
        }
    }
}
