package com.whale.ime.compose

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.whale.ime.ui.R
import com.whale.ime.compose.keyboard.CandidateBar
import com.whale.ime.compose.keyboard.ClipboardPanel
import com.whale.ime.compose.keyboard.HandwritingSession
import com.whale.ime.compose.keyboard.ImeDragHandle
import com.whale.ime.compose.keyboard.ImeIconButton
import com.whale.ime.compose.keyboard.KeyGrid
import com.whale.ime.compose.keyboard.LocalImePalette
import com.whale.ime.compose.keyboard.PhraseEditorBar
import com.whale.ime.compose.keyboard.SettingsMenuPanel
import com.whale.ime.compose.keyboard.SymbolPanel
import com.whale.ime.compose.keyboard.T9PrefixColumn
import com.whale.ime.compose.keyboard.CandidatesPanel
import com.whale.ime.compose.settings.rememberPreferenceValue
import com.whale.ime.theme.Theme
import com.whale.ime.theme.ThemeManager
import com.whale.ime.keyboard.model.SoftKey
import com.whale.ime.keyboard.model.SoftKeyboard
import com.whale.ime.keyboard.state.KeyboardManager
import com.whale.ime.keyboard.layout.InputModeSwitcher
import com.whale.ime.preference.AppPrefs
import com.whale.ime.preference.model.FullDisplayCenterMode
import com.whale.ime.preference.model.FullDisplayKeyMode
import com.whale.ime.preference.model.PopupMenuMode
import com.whale.ime.dispatch.DictDecoder
import com.whale.ime.keyboard.metrics.Environment
import com.whale.ime.keyboard.builder.KeyboardBuilder
import com.whale.ime.engine.HWEngine
import kotlin.math.abs
import kotlin.math.roundToInt
import com.whale.ime.preference.InputFeedbacks

/**
 * Compose 键盘根内容。
 *
 * 单手模式把整块内容改成 Row：一侧是键盘，另一侧是占位区与切换按钮。
 */
@Composable
fun ImeApp() {
    ImeTheme {
        val controller = LocalImeController.current
        val type by controller.keyboardType.collectAsStateWithLifecycle()
        val uiState by controller.state.collectAsStateWithLifecycle()
        val revision by controller.keyboardRevision.collectAsStateWithLifecycle()
        // KeyboardManager.clearKeyboard() 的失效计数也并进来：设置页/主题/尺寸改动都走它
        val managerRevision by KeyboardManager.instance.revision.collectAsStateWithLifecycle()
        val kbRevision = revision + managerRevision
        val candidateState by DictDecoder.candidateState.collectAsStateWithLifecycle()
        val theme = LocalImeTheme.current
        val env = remember { Environment.instance }
        val density = LocalDensity.current
        // 常用语编辑态：候选栏上方浮出的一张弹层卡片。
        // 非悬浮模式窗口临时加高同值，并由 ImeService.onComputeInsets 把 contentTopInsets
        // 加回同值 —— 宿主布局完全不变，弹层像对话框一样盖在宿主内容上；
        // 候选带的高度/位置一律不动（输入拼音时的气泡与候选照常可见）。
        val phraseEditorPopupPx = if (uiState.addPhrases) env.phraseEditorPopupHeight else 0
        val phraseEditorPopupDp = with(density) { phraseEditorPopupPx.toDp() }

        val keyboardSetting = AppPrefs.getInstance().keyboardSetting
        val oneHanded = keyboardSetting.oneHandedModSwitch.getValue()
        val oneHandedLeft = keyboardSetting.oneHandedMod.getValue() ==
                com.whale.ime.preference.model.KeyboardOneHandedMod.LEFT
        val floatMode = env.keyboardModeFloat

        // 主题背景按目标区域**精确渲染**（等同于 BitmapDrawable 按视图边界拉伸铺满）：
        // - 非悬浮：铺满整个 IME 窗口（候选区+键区+间隔+全面屏条+导航栏占位）。
        //   若按 inputAreaHeight 渲染再 Fit 居中，背景会整体上移——候选栏压到键盘
        //   首行上方、窗口底部露出一条未覆盖的底色（地球/剪贴板图标异色）。
        // - 悬浮：只铺键盘卡片自身（skbWidth × 键区 + 移动条），窗口四周保持透明。
        // 尺寸传 0 表示当前模式用不到，渲染函数会直接返回 null，避免两张大图都常驻。
        // 键盘色面不含顶部那条拼音气泡带：背景位图按「窗口高度 - 气泡带」渲染，
        // 再整体下移一条气泡带绘制。气泡带透明，宿主内容会从这里透出来。
        val keyBorder = AppPrefs.getInstance().keyboardSetting.keyBorder.getValue()
        val windowBackground = rememberKeyboardBackground(
            theme = theme,
            width = if (floatMode) 0 else env.mScreenWidth,
            // 弹层条本身透明（浮在宿主内容上），键盘色面从它下面才开始
            height = if (floatMode) 0 else env.skbAreaHeight - env.composingOverhang,
            keyBorder = keyBorder,
        )
        val floatCardBackground = rememberKeyboardBackground(
            theme = theme,
            width = if (floatMode) env.skbWidth else 0,
            height = if (floatMode) {
                env.inputAreaHeight + phraseEditorPopupPx + env.heightForKeyboardMove - env.composingOverhang
            } else 0,
            keyBorder = keyBorder,
        )
        val overhangDp = with(density) { env.composingOverhang.toDp() }

        // 编辑键盘：只在输入键盘上生效（切到功能宫格/符号/剪贴板等面板时自动收起）
        val editing = uiState.keyboardEditorVisible && type.isInputKeyboardType()
        // 尺寸可能是在设置页改的（不经过 controller.applyKeyboardSize）：
        // 这里按环境尺寸兜底重设窗口高度，否则只有键区内容变高、窗口还挂在旧高度
        LaunchedEffect(env.inputAreaHeight, env.skbAreaHeight) {
            controller.applyRootPreferredHeight()
        }
        // 进出常用语编辑态时窗口要跟着加高/回落，否则多出来的编辑栏会被窗口裁掉
        LaunchedEffect(uiState.addPhrases) {
            controller.applyRootPreferredHeight()
        }
        // 键区实测矩形（根坐标系）：编辑键盘的轮廓与拖动预览靠它定位，
        // 这样横屏居中、单手模式、悬浮卡片的位置差异都不用另算一套几何
        var keyAreaBounds by remember { mutableStateOf(Rect.Zero) }

        val content: @Composable () -> Unit = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(with(density) { (env.inputAreaHeight + phraseEditorPopupPx).toDp() }),
            ) {
                // 常用语弹层：非悬浮模式下浮在候选栏上方（窗口加高的那一条，
                // 宿主布局由 onComputeInsets 补偿，候选带位置完全不变）
                if (!floatMode && uiState.addPhrases) {
                    PhraseEditorPopup(controller, uiState, phraseEditorPopupDp)
                }
                // 候选栏独占整条候选带（拼音气泡条 + 候选行 + 与键盘的间距）。
                // 间距不再是独立的 Spacer：栏自己留底部内边距，点击命中与工具栏居中
                // 都由栏内部决定，不再需要「算出占位高度再补偿居中」。
                // 常用语编辑**不占用**这条带：输入拼音时的气泡与候选必须照常可见。
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(with(density) { env.candidatesBarHeight.toDp() }),
                ) {
                    if (editing) {
                        // 编辑键盘的操作条放在候选带里：这条带本来就空着，
                        // 放在这里既不会盖住键盘，也不用额外撑高窗口
                        KeyboardEditorBar(controller, uiState.keyboardEditorDraft)
                    } else {
                        CandidateBar(
                            keyboardType = type,
                            uiState = uiState,
                            controller = controller,
                            candidateState = candidateState,
                        )
                    }
                }
                // 悬浮模式的窗口本来就是全屏：弹层放在候选带下面，随键盘卡片一起向上长。
                // 不能放在候选带上方 —— 那里是悬浮卡片的气泡透明带，位置不能挪。
                if (floatMode && uiState.addPhrases) {
                    PhraseEditorPopup(controller, uiState, phraseEditorPopupDp)
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .onGloballyPositioned { coords ->
                            val origin = coords.positionInRoot()
                            val rect = Rect(
                                origin.x, origin.y,
                                origin.x + coords.size.width,
                                origin.y + coords.size.height,
                            )
                            if (rect != keyAreaBounds) keyAreaBounds = rect
                        },
                ) {
                    KeyboardArea(
                        type = type,
                        uiState = uiState,
                        controller = controller,
                        revision = kbRevision,
                    )
                    if (editing) {
                        // 编辑期间键区改由拖动层接管：顶边拖 = 调高度，中间拖 = 调距底；
                        // 同时吃掉按键事件（候选栏被操作条占用，误触输入会看不到也选不了候选）
                        KeyboardEditorDragLayer(controller)
                    }
                }
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            if (floatMode) {
                // 悬浮模式：窗口透明，主题背景只铺在键盘卡片后面；内容贴右下角，
                // 位置由拖动条改写 internal 的 padding 偏好
                val floatBottom = floatBottomPadding(env.isLandscape)
                val floatRight = floatRightPadding(env.isLandscape)
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(
                            // 拖动中的实时偏移直接叠在偏好值上，卡片跟手移动
                            bottom = with(density) {
                                (floatBottom - controller.floatDragY).coerceAtLeast(0).toDp()
                            },
                            end = with(density) {
                                (floatRight - controller.floatDragX).coerceAtLeast(0).toDp()
                            },
                        ),
                ) {
                    Box(
                        modifier = Modifier
                            .floatDragGesture(controller)
                            .onGloballyPositioned { info ->
                            // 上报卡片实测窗口坐标：悬浮态的 touchableRegion 直接用它，
                            // 保证键盘任何一行（含底行、拖动条）都在可点击范围内
                            val topLeft = info.localToWindow(androidx.compose.ui.geometry.Offset.Zero)
                            controller.floatCardBounds = android.graphics.Rect(
                                topLeft.x.roundToInt(), topLeft.y.roundToInt(),
                                (topLeft.x + info.size.width).roundToInt(),
                                (topLeft.y + info.size.height).roundToInt(),
                            )
                        },
                    ) {
                        ThemeBackground(
                            theme = theme,
                            bitmap = floatCardBackground,
                            modifier = Modifier.matchParentSize().padding(top = overhangDp),
                        )
                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .width(with(density) { env.skbWidth.toDp() }),
                        ) {
                            content()
                            FloatMoveBar(controller)
                        }
                    }
                }
            } else {
                ThemeBackground(
                    theme = theme,
                    bitmap = windowBackground,
                    // 弹层条 + 气泡带都留空：宿主内容从这两条透明带里透出来
                    modifier = Modifier.fillMaxSize().padding(top = overhangDp + phraseEditorPopupDp),
                )
                if (oneHanded && !oneHandedLeft) {
                    Row(Modifier.fillMaxSize()) {
                        OneHandPlaceholder(controller, leftSide = true)
                        Box(Modifier.weight(1f)) { content() }
                    }
                } else if (oneHanded) {
                    Row(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f)) { content() }
                        OneHandPlaceholder(controller, leftSide = false)
                    }
                } else {
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f)) { content() }
                        KeyboardBottomBar(controller)
                    }
                }
            }
            // 编辑键盘的轮廓与拖动预览画在最上层：只画虚线框和「松手后停在这里」的目标框，
            // 没有手势，所以不会挡住下面任何可点区域
            if (editing) {
                KeyboardEditorOverlay(bounds = keyAreaBounds, draft = uiState.keyboardEditorDraft)
            }
        }

        // 编辑态只属于输入键盘：切到功能宫格/符号/剪贴板时收起，回到键盘时再手动打开
        LaunchedEffect(type) {
            if (uiState.keyboardEditorVisible && type != null && !type.isInputKeyboardType()) {
                controller.closeKeyboardEditor()
            }
        }
    }
}

/**
 * 常用语编辑弹层：浮在候选栏上方的一张卡片。
 *
 * 只占 IME 窗口里新增的那一条透明区域，候选栏与键区都不参与布局变化；
 * 非悬浮模式下窗口临时加高的就是这一条（见 [Environment.phraseEditorPopupHeight]）。
 */
@Composable
private fun PhraseEditorPopup(
    controller: ImeController,
    uiState: ImeUiState,
    height: Dp,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        PhraseEditorBar(controller, uiState)
    }
}

/** 键盘区：按当前 [KeyboardManager.KeyboardType] 决定画哪一屏 */
@Composable
private fun KeyboardArea(
    type: KeyboardManager.KeyboardType?,
    uiState: ImeUiState,
    controller: ImeController,
    revision: Int,
) {
    if (type == null) return
    // 不做面板切换的滑入/淡入：键盘 ↔ 功能宫格 ↔ 符号之间高频切换，
    // 整块面板动起来会让视觉锚点（按键位置）漂移，实测比瞬时切换更晕
    Box(Modifier.fillMaxSize()) {
        when (type) {
            KeyboardManager.KeyboardType.SYMBOL -> SymbolPanel(uiState, controller)
            KeyboardManager.KeyboardType.SETTINGS -> SettingsMenuPanel(uiState, controller)
            KeyboardManager.KeyboardType.ClipBoard -> ClipboardPanel(uiState, controller)
            KeyboardManager.KeyboardType.CANDIDATES -> CandidatesPanel(controller)
            else -> InputKeyboardArea(type, uiState, controller, revision)
        }
    }
}

@Composable
private fun InputKeyboardArea(
    type: KeyboardManager.KeyboardType,
    uiState: ImeUiState,
    controller: ImeController,
    revision: Int,
) {
    val env = remember { Environment.instance }
    val density = LocalDensity.current
    val skbValue = type.toSkbValue()
    val keyboard = remember(skbValue, env.skbWidth, env.skbHeight, revision) {
        runCatching { KeyboardBuilder.instance.getSoftKeyboard(skbValue) }.getOrNull()
    } ?: return

    val dispatcher = controller.dispatcher

    val handwriting = remember(type) {
        if (type == KeyboardManager.KeyboardType.HANDWRITING) {
            HandwritingSession(
                onRecognize = { points ->
                    if (HWEngine.isAvailable) {
                        HWEngine.recognitionData(points) { items ->
                            dispatcher.responseHandwritingResultEvent(items)
                        }
                    }
                },
                onCommitCurrentCandidate = {
                    // 停笔超时后再落笔 = 确认当前高亮候选
                    dispatcher.responseKeyEvent(SoftKey(InputModeSwitcher.USER_KEYCODE_SELECTED))
                },
            )
        } else null
    }

    Box(Modifier.fillMaxSize()) {
        KeyGrid(
            keyboard = keyboard,
            modifier = Modifier.fillMaxSize(),
            revision = revision,
            addPhrases = uiState.addPhrases,
            handwriting = handwriting,
            onKeyTap = { key ->
                dispatcher.responseKeyEvent(key)
            },
            onLongResult = { result -> dispatcher.responseLongKeyEvent(result) },
            onKeyFeedback = { key ->
                // 按下即登记删除键的本次按压语义：有拼音/候选时按住只清编码，
                // 抬手后重新按下才会删上屏内容（见 KeyDispatcher.onKeyPressed）
                dispatcher.onKeyPressed(key.code)
                InputFeedbacks.tryPlayKeyDown(key.code)
                InputFeedbacks.tryVibrate()
            },
            onKeyReleased = { dispatcher.onKeyReleased() },
            onRepeat = { key, dx, dy, longPressed -> dispatchRepeat(dispatcher, key, dx, dy, longPressed) },
        )

        // T9 家族左侧符号/拼音栏、手写右侧符号栏
        val isT9Family = type == KeyboardManager.KeyboardType.T9 ||
                type == KeyboardManager.KeyboardType.NUMBER
        val isHandwriting = type == KeyboardManager.KeyboardType.HANDWRITING
        if (isHandwriting) {
            // 调试标签：当前手写推理后端（Vulkan/CPU），极小字号贴边，不占书写区
            Text(
                text = HWEngine.backendName ?: "CPU",
                style = TextStyle(fontSize = 8.sp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = 2.dp, y = 1.dp),
            )
        }
        if (isT9Family || isHandwriting) {
            val holder = keyboard.getKeyByCode(InputModeSwitcher.USER_KEYCODE_LEFT_SYMBOL)
            if (holder != null) {
                // 位置 = 占位键单元格四边内缩键边距，与相邻键帽的间距保持一致
                // 内缩要取渲染用的值（宽键布局横竖都会有调整），否则和 T9 主键区错位
                val holderXMargin = keyboard.xMarginForRender(holder)
                val holderYMargin = keyboard.yMarginForRender
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset(
                            x = with(density) { (holder.mLeft + holderXMargin).toDp() },
                            y = with(density) { (holder.mTop + holderYMargin).toDp() },
                        )
                        .width(with(density) { (holder.width() - 2 * holderXMargin).toDp() })
                        .height(
                            with(density) {
                                (holder.mBottom - holder.mTop - 2 * holderYMargin).toDp()
                            },
                        ),
                ) {
                    T9PrefixColumn(
                        numberMode = type == KeyboardManager.KeyboardType.NUMBER,
                        onSelectPrefix = { position -> dispatcher.selectPrefix(position) },
                        onSymbol = { symbol -> dispatcher.responseKeyEvent(SoftKey(label = symbol)) },
                        onAddSymbol = {
                            val args = android.os.Bundle()
                            args.putInt("type", if (type == KeyboardManager.KeyboardType.NUMBER) 1 else 0)
                            com.whale.ime.ui.util.AppUtil.launchSettingsToPrefix(
                                com.whale.ime.boot.Launcher.instance.context, args
                            )
                        },
                    )
                }
            }
        }
    }
}

/** 长按连发：间隔与首次延迟 */
private fun dispatchRepeat(
    dispatcher: KeyDispatcher,
    key: SoftKey,
    dx: Float,
    dy: Float,
    longPressed: Boolean,
) {
    if (key.code == android.view.KeyEvent.KEYCODE_DEL && longPressed) {
        if (abs(dx) >= abs(dy)) {
            if (dx >= -2) dispatcher.responseKeyEvent(SoftKey(android.view.KeyEvent.KEYCODE_DEL))
            else dispatcher.responseLongKeyEvent(Pair(PopupMenuMode.Revertl, ""))
        } else {
            if (dy > 0) dispatcher.responseKeyEvent(SoftKey(android.view.KeyEvent.KEYCODE_DEL))
            else if (dy < 0) dispatcher.responseLongKeyEvent(Pair(PopupMenuMode.Revertl, ""))
        }
        return
    }
    if (key.code == InputModeSwitcher.USER_KEYCODE_CURSOR_DIRECTION) {
        val code = if (abs(dx) >= abs(dy)) {
            if (dx > 0) android.view.KeyEvent.KEYCODE_DPAD_LEFT else android.view.KeyEvent.KEYCODE_DPAD_RIGHT
        } else {
            if (dy < 0) android.view.KeyEvent.KEYCODE_DPAD_DOWN else android.view.KeyEvent.KEYCODE_DPAD_UP
        }
        dispatcher.responseKeyEvent(SoftKey(code))
        return
    }
    dispatcher.responseKeyEvent(key)
}

/** 底部：全面屏键盘优化条 + 导航栏占位 */
@Composable
private fun KeyboardBottomBar(controller: ImeController) {
    val env = remember { Environment.instance }
    val density = LocalDensity.current
    // 必须订阅而不是直接 getValue()：本 Composable 的参数（controller）不变，
    // 组合器可以直接跳过它，偏好值改了也不会重组——表现就是设置里选了「普通键盘」，
    // 底部的切换输入法/剪贴板图标依旧留着。
    val fullDisplay = rememberPreferenceValue(
        AppPrefs.getInstance().internal.fullDisplayKeyboardEnable
    )
    // 条本身参与窗口高度（skbAreaHeight）：开关一变就重设窗口，否则条没了、
    // 窗口底部仍留着一整条空白
    LaunchedEffect(fullDisplay) { controller.applyRootPreferredHeight() }
    // 键盘与屏幕底部的留白：编辑键盘浮层里拖的就是这一项
    val bottomPadding = env.keyboardBottomGapPx
    // 读 controller.navbarInset（Compose 状态）而不是 env 同名字段：
    // insets 变化时需要触发重组，普通字段变化不会
    val navbar = controller.navbarInset
    val barHeight = if (fullDisplay && !env.isLandscape) env.heightForFullDisplayBar else 0

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(with(density) { (navbar + bottomPadding + barHeight).toDp() }),
    ) {
        // 顺序很关键：「距底」的留白放在按键区与功能条之间，功能条始终贴着底部。
        // 反过来（留白在功能条下面）时，调大「距底」会把功能条一起顶起来，
        // 功能条下面再露出空白，看起来像是键盘被莫名抬高了一截。
        if (bottomPadding > 0) {
            Spacer(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(with(density) { bottomPadding.toDp() })
            )
        }
        if (barHeight > 0) {
            FullDisplayBar(controller)
        }
        // 剩余高度 = 系统手势区/导航栏，按键与功能条都不能压进去
        Spacer(modifier = Modifier.fillMaxWidth().weight(1f))
    }
}

@Composable
private fun FullDisplayBar(controller: ImeController) {
    val env = remember { Environment.instance }
    val theme = LocalImeTheme.current
    val density = LocalDensity.current
    val internal = AppPrefs.getInstance().internal
    val leftMode = FullDisplayKeyMode.decode(
        rememberPreferenceValue(internal.fullDisplayKeyModeLeft)
    )
    val rightMode = FullDisplayKeyMode.decode(
        rememberPreferenceValue(internal.fullDisplayKeyModeRight)
    )
    val centerMove = FullDisplayCenterMode.decode(
        rememberPreferenceValue(internal.fullDisplayCenterMode)
    ) == FullDisplayCenterMode.MoveCursor
    var lastX by remember { mutableFloatStateOf(-1f) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(with(density) { env.heightForFullDisplayBar.toDp() })
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        FullDisplayIcon(leftMode, tint = Color(theme.keyTextColor)) { controller.onFullDisplayAction(leftMode) }
        // 中间区域：左右滑动移动光标
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .pointerInput(centerMove) {
                    detectDragGestures(
                        onDragStart = { },
                        onDragEnd = { lastX = -1f },
                        onDrag = { change, _ ->
                            if (!centerMove) return@detectDragGestures
                            val x = change.position.x
                            if (lastX >= 0) {
                                val diff = x - lastX
                                if (abs(diff) > GestureTuning.spaceSwipeSpeed) {
                                    controller.dispatcher.responseKeyEvent(
                                        SoftKey(if (diff < 0) android.view.KeyEvent.KEYCODE_DPAD_LEFT
                                        else android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
                                    )
                                    lastX = x
                                }
                            } else {
                                lastX = x
                            }
                        },
                    )
                },
        )
        FullDisplayIcon(rightMode, tint = Color(theme.keyTextColor)) { controller.onFullDisplayAction(rightMode) }
    }
}

@Composable
private fun FullDisplayIcon(mode: FullDisplayKeyMode, tint: Color, onClick: () -> Unit) {
    val res = when (mode) {
        FullDisplayKeyMode.SwitchIme -> R.drawable.ic_ui_language
        FullDisplayKeyMode.SwitchLanguage -> R.drawable.ic_menu_keyboard_switcher
        FullDisplayKeyMode.Clipboard -> R.drawable.ic_ui_clipboard
        FullDisplayKeyMode.Phrases -> R.drawable.ic_ui_phrases
        else -> 0
    }
    if (res == 0) {
        Spacer(Modifier.width(0.dp))
        return
    }
    ImeIconButton(
        iconRes = res,
        contentDescription = null,
        tint = tint,
        size = 34.dp,
        iconSize = 24.dp,
        onClick = onClick,
    )
}

/** 单手模式的占位区 + 两个切换按钮 */
@Composable
private fun OneHandPlaceholder(controller: ImeController, leftSide: Boolean) {
    val env = remember { Environment.instance }
    val density = LocalDensity.current
    val theme = LocalImeTheme.current

    Column(
        modifier = Modifier
            .width(with(density) { env.holderWidth.toDp() })
            .fillMaxHeight(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            ImeIconButton(
                iconRes = R.drawable.ic_ui_one_hand,
                contentDescription = null,
                tint = Color(theme.keyTextColor),
                size = 38.dp,
                iconSize = 26.dp,
                onClick = { toggleOneHanded() },
            )
            ImeIconButton(
                iconRes = if (controller.state.value.oneHandedLeft) R.drawable.ic_ui_one_hand_right
                else R.drawable.ic_ui_one_hand,
                contentDescription = null,
                tint = Color(theme.keyTextColor),
                size = 38.dp,
                iconSize = 26.dp,
                onClick = { switchOneHandedSide(controller) },
            )
        }
        Spacer(Modifier.height(with(density) { controller.navbarInset.toDp() }))
    }
}

private fun toggleOneHanded() {
    val pref = AppPrefs.getInstance().keyboardSetting.oneHandedModSwitch
    pref.setValue(!pref.getValue())
    Environment.instance.initData()
    KeyboardBuilder.instance.clearKeyboardMap()
    KeyboardManager.instance.clearKeyboard()
    KeyboardManager.instance.switchKeyboard()
}

private fun switchOneHandedSide(controller: ImeController) {
    val pref = AppPrefs.getInstance().keyboardSetting.oneHandedMod
    val next = if (pref.getValue() == com.whale.ime.preference.model.KeyboardOneHandedMod.LEFT)
        com.whale.ime.preference.model.KeyboardOneHandedMod.RIGHT
    else com.whale.ime.preference.model.KeyboardOneHandedMod.LEFT
    pref.setValue(next)
    controller.update { it.copy(oneHandedLeft = next == com.whale.ime.preference.model.KeyboardOneHandedMod.LEFT) }
    controller.refreshEnvironment()
}

/**
 * 悬浮键盘拖动手势。
 *
 * 挂在键盘卡片本身和底部拖动条两处：卡片上的非按键区域（候选栏空白、边缘）都能拖，
 * 按键自己的点按/长按/滑动手势会先消费事件，不受影响。
 *
 * 位置约定：startRight/startBottom 是卡片到窗口右/下边的 padding，手指位移 accX/accY
 * 直接从 padding 里减（向右拖 = 卡片右移 = padding 减小）；拖动中的实时偏移用同一套
 * 符号（floatDrag = +acc），保证松手落点和拖动中位置一致。
 * 注意钳位必须用窗口尺寸——之前在小横条的 pointerInput 里取 size，宽度等于键盘宽，
 * maxRight/maxBottom 恒为 0，松手必被钳回右下角，表现为「拖不动」。
 */
private fun Modifier.floatDragGesture(controller: ImeController): Modifier =
    pointerInput(Unit) {
        val env = Environment.instance
        var startRight = 0f
        var startBottom = 0f
        var accX = 0f
        var accY = 0f

        // 钳位以 IME 窗口真实高度为准：窗口可能比屏幕矮（切到悬浮的第一帧、
        // 窗口重排瞬间），按整屏钳位会让卡片顶出窗口被压缩
        fun maxBottomPx(): Float {
            val windowH = controller.floatWindowHeight.takeIf { it > 0 } ?: env.mScreenHeight
            return (windowH - env.inputAreaHeight - env.heightForKeyboardMove).coerceAtLeast(0).toFloat()
        }

        fun maxRightPx(): Float = (env.mScreenWidth - env.skbWidth).coerceAtLeast(0).toFloat()

        // padding = start - acc，必须落在 [0, max] 内，反推出 acc 的合法区间
        fun coerceAccX() = accX.coerceIn(startRight - maxRightPx(), startRight)
        fun coerceAccY() = accY.coerceIn(startBottom - maxBottomPx(), startBottom)

        fun resetDrag() {
            accX = 0f
            accY = 0f
            controller.floatDragX = 0
            controller.floatDragY = 0
        }
        detectDragGestures(
            onDragStart = {
                startRight = floatRightPadding(env.isLandscape).toFloat()
                startBottom = floatBottomPadding(env.isLandscape).toFloat()
                resetDrag()
            },
            onDrag = { _, drag ->
                accX += drag.x
                accY += drag.y
                // 卡片跟手 + 实时钳位：拖动中也不能让卡片超出窗口（否则会被压缩变形）
                controller.floatDragX = coerceAccX().roundToInt()
                controller.floatDragY = coerceAccY().roundToInt()
            },
            onDragEnd = {
                val internal = AppPrefs.getInstance().internal
                // 最终 padding = 起始 padding - 钳制后的位移（注意不能把钳制后的位移当 padding 用）
                val finalRight = (startRight - coerceAccX()).coerceIn(0f, maxRightPx())
                val finalBottom = (startBottom - coerceAccY()).coerceIn(0f, maxBottomPx())
                if (env.isLandscape) {
                    internal.keyboardRightPaddingLandscapeFloat.setValue(finalRight.roundToInt())
                    internal.keyboardBottomPaddingLandscapeFloat.setValue(finalBottom.roundToInt())
                } else {
                    internal.keyboardRightPaddingFloat.setValue(finalRight.roundToInt())
                    internal.keyboardBottomPaddingFloat.setValue(finalBottom.roundToInt())
                }
                controller.bumpKeyboardRevision()
                // 位置变了必须重算 touchableRegion，否则松手后键盘点到的是之前的位置
                controller.refreshInsets()
                resetDrag()
            },
            onDragCancel = { resetDrag() },
        )
    }

/** 悬浮键盘的拖动条 */
@Composable
private fun FloatMoveBar(controller: ImeController) {
    val env = remember { Environment.instance }
    val density = LocalDensity.current
    val theme = LocalImeTheme.current

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(with(density) { env.heightForKeyboardMove.toDp() })
            .floatDragGesture(controller),
        contentAlignment = Alignment.Center,
    ) {
        ImeDragHandle(color = Color(theme.keyTextColor), width = 42.dp, height = 4.dp)
    }
}

/** 编辑键盘只对输入键盘有意义：功能宫格 / 符号 / 剪贴板等面板没有「尺寸」可调 */
private fun KeyboardManager.KeyboardType?.isInputKeyboardType(): Boolean = when (this) {
    KeyboardManager.KeyboardType.T9,
    KeyboardManager.KeyboardType.T14,
    KeyboardManager.KeyboardType.QWERTY,
    KeyboardManager.KeyboardType.QWERTYABC,
    KeyboardManager.KeyboardType.NUMBER,
    KeyboardManager.KeyboardType.TEXTEDIT,
    KeyboardManager.KeyboardType.HANDWRITING,
    KeyboardManager.KeyboardType.STROKE -> true
    else -> false
}

// ---------------------------------------------------------------------------
// 主题背景
// ---------------------------------------------------------------------------

/**
 * 主题背景层：位图已按目标区域渲染（拉伸语义），
 * 这里只负责 1:1 铺放；主题无背景图时退回纯色。
 */
@Composable
private fun ThemeBackground(theme: Theme, bitmap: ImageBitmap?, modifier: Modifier = Modifier) {
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = modifier,
        )
    } else {
        Box(modifier.background(Color(theme.keyboardColor)))
    }
}

@Composable
private fun rememberKeyboardBackground(
    theme: Theme,
    width: Int,
    height: Int,
    keyBorder: Boolean,
): ImageBitmap? = remember(theme, width, height, keyBorder) {
    if (width <= 0 || height <= 0) return@remember null
    runCatching {
        val drawable = theme.backgroundDrawable(keyBorder)
        drawable.toSizedImageBitmap(width, height)
    }.getOrNull()
}

private fun Drawable.toSizedImageBitmap(width: Int, height: Int): ImageBitmap? = runCatching {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    setBounds(0, 0, width, height)
    draw(canvas)
    bitmap.asImageBitmap()
}.getOrNull()

// ---------------------------------------------------------------------------
// 工具
// ---------------------------------------------------------------------------

private fun floatBottomPadding(landscape: Boolean): Int {
    val internal = AppPrefs.getInstance().internal
    return if (landscape) internal.keyboardBottomPaddingLandscapeFloat.getValue()
    else internal.keyboardBottomPaddingFloat.getValue()
}

private fun floatRightPadding(landscape: Boolean): Int {
    val internal = AppPrefs.getInstance().internal
    return if (landscape) internal.keyboardRightPaddingLandscapeFloat.getValue()
    else internal.keyboardRightPaddingFloat.getValue()
}

private fun KeyboardManager.KeyboardType.toSkbValue(): Int = when (this) {
    KeyboardManager.KeyboardType.T14 -> InputModeSwitcher.MASK_SKB_LAYOUT_T14_PINYIN
    KeyboardManager.KeyboardType.QWERTY -> InputModeSwitcher.MASK_SKB_LAYOUT_QWERTY_PINYIN
    KeyboardManager.KeyboardType.QWERTYABC -> InputModeSwitcher.MASK_SKB_LAYOUT_QWERTY_ABC
    KeyboardManager.KeyboardType.T9 -> InputModeSwitcher.MASK_SKB_LAYOUT_T9_PINYIN
    KeyboardManager.KeyboardType.NUMBER -> InputModeSwitcher.MASK_SKB_LAYOUT_NUMBER
    KeyboardManager.KeyboardType.TEXTEDIT -> InputModeSwitcher.MASK_SKB_LAYOUT_TEXTEDIT
    KeyboardManager.KeyboardType.HANDWRITING -> InputModeSwitcher.MASK_SKB_LAYOUT_HANDWRITING
    KeyboardManager.KeyboardType.STROKE -> InputModeSwitcher.MASK_SKB_LAYOUT_STROKE
    else -> AppPrefs.getInstance().internal.inputDefaultMode.getValue() and InputModeSwitcher.MASK_SKB_LAYOUT
}
