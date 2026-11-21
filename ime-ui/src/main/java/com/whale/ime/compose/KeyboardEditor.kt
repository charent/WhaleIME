package com.whale.ime.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.whale.ime.ui.R
import com.whale.ime.compose.keyboard.ImeIconButton
import com.whale.ime.compose.keyboard.LocalImePalette
import com.whale.ime.preference.AppPrefs
import com.whale.ime.keyboard.metrics.Environment
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import com.whale.ime.util.android.Density

/*
 * 编辑键盘（键盘菜单 →「编辑键盘」）。
 *
 * 三条同时可用的调节通道，都不遮挡键盘：
 * 1. **直接拖键盘**：按住键区顶边（QWER 那一行上方的边框条）上下拖 = 调键盘高度；
 *    按住键盘中间上下拖 = 调键盘与屏幕底部的距离；
 * 2. 候选带里的两个胶囊（高度 / 距底）：拖动 = 同样的调节，兼作实时读数；
 * 3. 只读的轮廓与预览：键区外圈虚线框 + 拖动时「松手后键盘停在这里」的实线框。
 *
 * 拖动期间只更新 [KeyboardEditorDraft]（预览），松手才重建键盘并重设窗口高度：
 * 每帧改 IME 窗口高度会让宿主应用跟着重新布局，肉眼可见地抖。
 */

/** 候选带里的拖动胶囊高度：候选带很矮（约 54dp），胶囊必须紧凑 */
private val CHIP_HEIGHT = 30.dp

/** 轮廓上「高度 / 距底」提示牌的高度 */
private val HINT_PILL_HEIGHT = 22.dp

/**
 * 键区顶部「高度」拖动热区的高度。
 *
 * 取 30dp：既覆盖第一行键帽上方的边框条，又足够好按；再往下就属于「键盘中间」，
 * 归「距底」拖动管。
 */
internal val EDITOR_TOP_ZONE = 30.dp

/** 拖动时正在调哪一项 */
private enum class EditorTarget { Height, Gap }

/**
 * 编辑键盘的操作条，放在候选带里（原候选栏的位置）。
 *
 * 高度胶囊拖动 = 改 [Environment.skbHeight]；
 * 距底胶囊拖动 = 改键盘与屏幕底部的留白（悬浮模式下由拖动条决定位置，不显示该胶囊）。
 */
@Composable
internal fun KeyboardEditorBar(controller: ImeController, draft: KeyboardEditorDraft?) {
    val env = remember { Environment.instance }
    val palette = LocalImePalette.current
    val prefs = AppPrefs.getInstance()
    // 胶囊上显示的必须是「拖动中的目标值」，否则拖动时没有读数反馈
    val ratio = draft?.heightRatio ?: env.keyBoardHeightRatio
    val gapDp = draft?.bottomGapDp ?: prefs.keyboardSetting.keyboardBottomGap.getValue()

    // 拖动起点 + 累计位移：每帧按总位移换算，避免反复取整带来的漂移
    var startRatio by remember { mutableFloatStateOf(0f) }
    var startGapPx by remember { mutableIntStateOf(0) }
    var dragDy by remember { mutableFloatStateOf(0f) }

    Row(
        // 整条候选带垂直居中即可：候选带很矮（约 54dp），操作条只占中间一层，
        // 不再另外内缩——否则「候选栏高度」调小以后操作条会被压扁到放不下文字
        modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EditorDragChip(
            label = controller.string(R.string.keyboard_editor_height),
            value = "${(ratio * 100).roundToInt()}%",
            description = controller.string(R.string.keyboard_editor_height_hint),
            accent = palette.accent,
            background = palette.card,
            labelColor = palette.muted,
            onDragStart = {
                startRatio = env.keyBoardHeightRatio
                dragDy = 0f
                controller.setKeyboardEditorDraft(KeyboardEditorDraft(heightRatio = startRatio))
            },
            onDrag = { dy ->
                dragDy += dy
                controller.setKeyboardEditorDraft(
                    KeyboardEditorDraft(heightRatio = heightRatioAfterDrag(startRatio, dragDy, env))
                )
            },
            onDragEnd = { controller.commitKeyboardEditorDraft() },
        )

        if (!env.keyboardModeFloat) {
            Spacer(Modifier.width(8.dp))
            EditorDragChip(
                label = controller.string(R.string.keyboard_editor_gap),
                value = "${gapDp}dp",
                description = controller.string(R.string.keyboard_editor_gap_hint),
                accent = palette.accent,
                background = palette.card,
                labelColor = palette.muted,
                onDragStart = {
                    startGapPx = env.keyboardBottomGapPx
                    dragDy = 0f
                    controller.setKeyboardEditorDraft(
                        KeyboardEditorDraft(bottomGapDp = Density.px2dip(startGapPx).roundToInt())
                    )
                },
                onDrag = { dy ->
                    dragDy += dy
                    controller.setKeyboardEditorDraft(
                        KeyboardEditorDraft(bottomGapDp = gapDpAfterDrag(startGapPx, dragDy))
                    )
                },
                onDragEnd = { controller.commitKeyboardEditorDraft() },
            )
        }

        Spacer(Modifier.weight(1f))
        ImeIconButton(
            iconRes = R.drawable.ic_menu_reset,
            contentDescription = controller.string(R.string.setting_ime_keyboard_height_reset),
            tint = palette.muted,
            size = 34.dp,
            iconSize = 19.dp,
            onClick = { controller.resetKeyboardSize() },
        )
        ImeIconButton(
            iconRes = R.drawable.ic_menu_done,
            contentDescription = controller.string(R.string.keyboard_editor_done),
            tint = palette.onAccent,
            background = palette.accent,
            size = 34.dp,
            iconSize = 19.dp,
            onClick = { controller.closeKeyboardEditor() },
        )
    }
}

/**
 * 键盘本体的拖动层：铺满键区，编辑期间顶替按键接收手势。
 *
 * - 落点在键区最上方 [EDITOR_TOP_ZONE] 内（QWER 那一行上方的边框条）→ 调键盘高度；
 * - 落点在键区其余位置（「键盘中间」）→ 调键盘与屏幕底部的距离；
 * - 悬浮模式没有「距底」的概念（位置由拖动条决定），整块都调高度。
 *
 * 这里用底层的 `awaitPointerEvent` 而不是 `detectDragGestures`：编辑期间必须**吃掉**
 * 所有事件，否则按键仍会响应、输入出看不见的候选。
 */
@Composable
internal fun KeyboardEditorDragLayer(controller: ImeController) {
    val env = remember { Environment.instance }
    val floatMode = env.keyboardModeFloat
    val topZonePx = with(LocalDensity.current) { EDITOR_TOP_ZONE.toPx() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(floatMode, topZonePx) {
                val slop = viewConfiguration.touchSlop
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        val target = if (floatMode || down.position.y <= topZonePx) {
                            EditorTarget.Height
                        } else {
                            EditorTarget.Gap
                        }
                        val startRatio = env.keyBoardHeightRatio
                        val startGapPx = env.keyboardBottomGapPx
                        var dy = 0f
                        // 有位移才进入调节：单击（误差在 touchSlop 内）什么都不改
                        var adjusting = false
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            change.consume()
                            if (!change.pressed) break
                            dy += change.position.y - change.previousPosition.y
                            if (!adjusting && abs(dy) > slop) adjusting = true
                            if (!adjusting) continue
                            controller.setKeyboardEditorDraft(
                                when (target) {
                                    EditorTarget.Height -> KeyboardEditorDraft(
                                        heightRatio = heightRatioAfterDrag(startRatio, dy, env)
                                    )

                                    EditorTarget.Gap -> KeyboardEditorDraft(
                                        bottomGapDp = gapDpAfterDrag(startGapPx, dy)
                                    )
                                }
                            )
                        }
                        if (adjusting) controller.commitKeyboardEditorDraft()
                        else controller.setKeyboardEditorDraft(null)
                    }
                }
            },
    )
}

/**
 * 键区轮廓 + 拖动预览 + 两条拖动热区的提示，盖在整个窗口最上层（无手势，不拦截点击）。
 *
 * [bounds] 是键区在根坐标系里的实测矩形：横屏居中 / 单手模式 / 悬浮模式的位置差异
 * 都由测量得到，几何换算只负责「变化量」——改高度时上边缘移动、改留白时整体上移。
 */
@Composable
internal fun KeyboardEditorOverlay(bounds: Rect, draft: KeyboardEditorDraft?) {
    val env = remember { Environment.instance }
    val palette = LocalImePalette.current
    val density = LocalDensity.current

    val gapPx = env.keyboardBottomGapPx
    val newGapPx = draft?.bottomGapDp?.let { Density.dip2px(it) } ?: gapPx
    val curHeight = env.skbHeight
    val newHeight = draft?.heightRatio?.let { (env.screenHeightVertical * it).roundToInt() } ?: curHeight
    val dGap = (newGapPx - gapPx).toFloat()
    val dHeight = (newHeight - curHeight).toFloat()
    val ready = bounds.width > 0f && bounds.height > 0f

    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            if (!ready) return@Canvas
            val inset = 2.dp.toPx()
            val radius = CornerRadius(12.dp.toPx())
            val current = Rect(
                bounds.left + inset, bounds.top + inset,
                bounds.right - inset, bounds.bottom - inset,
            )

            // 顶边热区：淡淡一条底色 + 下沿细线，说明「这一条可以抓着调高度」
            val zone = min(EDITOR_TOP_ZONE.toPx(), current.height / 3f)
            drawRect(
                color = palette.accent.copy(alpha = 0.12f),
                topLeft = Offset(current.left, current.top),
                size = Size(current.width, zone),
            )
            drawLine(
                color = palette.accent.copy(alpha = 0.55f),
                start = Offset(current.left, current.top + zone),
                end = Offset(current.right, current.top + zone),
                strokeWidth = 1.dp.toPx(),
            )

            // 当前键盘：虚线框（「正在编辑的就是这块」）
            drawRoundRect(
                color = palette.accent.copy(alpha = 0.55f),
                topLeft = current.topLeft,
                size = current.size,
                cornerRadius = radius,
                style = Stroke(
                    width = 1.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(7.dp.toPx(), 5.dp.toPx())),
                ),
            )
            if (draft == null || (dGap == 0f && dHeight == 0f)) return@Canvas

            // 目标位置：实线框 + 极淡填充（松手后键盘就落在这里）
            val target = Rect(
                current.left,
                current.top - dGap - dHeight,
                current.right,
                current.bottom - dGap,
            )
            drawRoundRect(
                color = palette.accent.copy(alpha = 0.10f),
                topLeft = target.topLeft,
                size = target.size,
                cornerRadius = radius,
            )
            drawRoundRect(
                color = palette.accent,
                topLeft = target.topLeft,
                size = target.size,
                cornerRadius = radius,
                style = Stroke(2.dp.toPx()),
            )
            val dot = 3.dp.toPx()
            drawCircle(palette.accent, dot, Offset(target.center.x, target.top))
            drawCircle(palette.accent, dot, Offset(target.center.x, target.bottom))
        }

        if (ready) {
            // 提示牌跟着键区走：顶部「高度」、底部「距底」，标出两条拖动热区的方向
            Box(
                modifier = Modifier
                    .offset { IntOffset(bounds.left.roundToInt(), bounds.top.roundToInt()) }
                    .size(
                        with(density) { bounds.width.toDp() },
                        with(density) { bounds.height.toDp() },
                    ),
            ) {
                EditorHintPill(
                    text = stringResource(R.string.keyboard_editor_height),
                    accent = palette.accent,
                    background = palette.card,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 4.dp),
                )
                EditorHintPill(
                    text = stringResource(R.string.keyboard_editor_gap),
                    accent = palette.accent,
                    background = palette.card,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp),
                )
            }
        }
    }
}

/**
 * 可上下拖动的胶囊：上下箭头 + 名称 + 实时读数。
 *
 * 用 `rememberUpdatedState` 包一层回调——`pointerInput` 块只创建一次，
 * 直接捕获 lambda 会一直用第一次组合时的版本（读数停在初始值）。
 */
@Composable
private fun EditorDragChip(
    label: String,
    value: String,
    description: String,
    accent: Color,
    background: Color,
    labelColor: Color,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    val startState = rememberUpdatedState(onDragStart)
    val dragState = rememberUpdatedState(onDrag)
    val endState = rememberUpdatedState(onDragEnd)
    val shape = RoundedCornerShape(CHIP_HEIGHT / 2)

    Box(
        modifier = Modifier
            .height(CHIP_HEIGHT)
            .clip(shape)
            // 实心底色：候选带顶部有一条透明的拼音气泡带，半透明胶囊压上去会露出宿主内容
            .background(background)
            .border(1.dp, accent.copy(alpha = 0.55f), shape)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { startState.value() },
                    onDragEnd = { endState.value() },
                    onDragCancel = { endState.value() },
                    onDrag = { change, amount ->
                        change.consume()
                        dragState.value(amount.y)
                    },
                )
            }
            .semantics { contentDescription = description }
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DragArrowMark(color = accent, modifier = Modifier.width(10.dp).height(14.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                text = label,
                color = labelColor,
                style = TextStyle(fontSize = 11.sp),
            )
            Spacer(Modifier.width(5.dp))
            Text(
                text = value,
                color = accent,
                style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold),
            )
        }
    }
}

/** 轮廓上的提示牌：只有外观，不带手势（真正的手势在键区铺满的那一层） */
@Composable
private fun EditorHintPill(
    text: String,
    accent: Color,
    background: Color,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(HINT_PILL_HEIGHT / 2)
    Row(
        modifier = modifier
            .height(HINT_PILL_HEIGHT)
            .clip(shape)
            .background(background)
            .border(1.dp, accent.copy(alpha = 0.5f), shape)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DragArrowMark(color = accent, modifier = Modifier.width(9.dp).height(12.dp))
        Spacer(Modifier.width(4.dp))
        Text(
            text = text,
            color = accent,
            style = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Medium),
        )
    }
}

/** 上下双向箭头（胶囊左侧的拖动提示） */
@Composable
private fun DragArrowMark(color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val stroke = 1.4.dp.toPx()
        val chevron = 3.2.dp.toPx()
        val top = 1.dp.toPx()
        val bottom = size.height - 1.dp.toPx()
        val cx = size.width / 2f
        val path = Path().apply {
            moveTo(cx, top)
            lineTo(cx, bottom)
            moveTo(cx - chevron, top + chevron)
            lineTo(cx, top)
            lineTo(cx + chevron, top + chevron)
            moveTo(cx - chevron, bottom - chevron)
            lineTo(cx, bottom)
            lineTo(cx + chevron, bottom - chevron)
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

/** 拖动位移 → 键盘高度比例（向上拖 = 变高） */
private fun heightRatioAfterDrag(
    startRatio: Float,
    dy: Float,
    env: Environment,
): Float = snapKeyHeightRatio(startRatio - dy / env.screenHeightVertical.coerceAtLeast(1))

/** 拖动位移 → 键盘底部留白（dp，向上拖 = 抬高键盘） */
private fun gapDpAfterDrag(startGapPx: Int, dy: Float): Int {
    val limit = Density.dip2px(Environment.KEYBOARD_BOTTOM_GAP_MAX_DP).toFloat()
    val px = (startGapPx - dy).coerceIn(0f, limit)
    return Density.px2dip(px.roundToInt()).roundToInt()
}

/** 键盘高度比例吸附到 1%，并停在可调范围内 */
internal fun snapKeyHeightRatio(value: Float): Float = ((value * 100).roundToInt() / 100f)
    .coerceIn(Environment.KEYBOARD_HEIGHT_RATIO_MIN, Environment.KEYBOARD_HEIGHT_RATIO_MAX)
