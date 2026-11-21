package com.whale.ime.ui.service

import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.SystemClock
import android.text.InputType
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.whale.ime.compose.HardwareCandidateView
import com.whale.ime.compose.ImeComposeRoot
import com.whale.ime.emoji.WhaleEmojiCompat
import com.whale.ime.theme.ThemeManager.OnThemeChangeListener
import com.whale.ime.theme.ThemeManager.addOnChangedListener
import com.whale.ime.theme.ThemeManager.onSystemDarkModeChange
import com.whale.ime.theme.ThemeManager.removeOnChangedListener
import com.whale.ime.keyboard.state.KeyboardManager
import com.whale.ime.editor.EnterKeyPolicy
import com.whale.ime.keyboard.layout.InputModeSwitcher
import com.whale.ime.preference.AppPrefs.Companion.getInstance
import com.whale.ime.preference.ManagedPreference
import com.whale.ime.keyboard.metrics.Environment
import com.whale.ime.keyboard.builder.KeyboardBuilder
import com.whale.ime.editor.SentenceCase
import com.whale.ime.textstyle.toTextStyle
import com.whale.ime.editor.SmartSpacing
import com.whale.ime.util.StringUtils
import com.whale.ime.util.android.isDarkMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Main class of the Pinyin input method. 输入法服务。
 *
 * UI 只有一条路径：Compose 根（[ImeComposeRoot]）+ Compose 候选栏
 * （[HardwareCandidateView]）。
 */
class ImeService : InputMethodService() {

    private var isHardwareKeyboard = false
    private var showVirtualKeyboardOnPhysicalKeyboard = false

    /** 当前编辑框信息：判断单行/多行用（上/下方向键是否还有意义） */
    private var currentEditorInfo: EditorInfo? = null

    /** 编辑框里当前 preedit（组合串）的长度：回读「上一个已上屏字符」时要跳过它 */
    private var composingLength = 0

    /** 光标/选区（`onUpdateSelection` 维护）：用 locals 判断有没有选区，不必跨进程问宿主 */
    private var selectionStart = 0
    private var selectionEnd = 0

    /** 软键盘根视图：服务存活期间复用，旋屏/换输入框只重排不重建组合 */
    private var composeRoot: ImeComposeRoot? = null

    /** 物理键盘模式下的候选栏（系统独立窗口） */
    private var hardwareCandidateView: HardwareCandidateView? = null

    private val onThemeChangeListener = OnThemeChangeListener {
        // 主题变了：键帽/背景都要重新读取，直接让键盘缓存失效
        composeRoot?.controller?.bumpKeyboardRevision()
    }

    private val clipboardUpdateContentListener = ManagedPreference.OnChangeListener<String> { _, value ->
        if (getInstance().clipboard.clipboardSuggestion.getValue() && value.isNotBlank()) {
            composeRoot?.controller?.let { controller ->
                controller.bumpClipboardRevision()
                controller.dispatcher.showClipboardSuggestion(value)
            }
        }
    }

    private val showVirtualKeyboardOnPhysicalKeyboardListener =
        ManagedPreference.OnChangeListener<Boolean> { _, value ->
            showVirtualKeyboardOnPhysicalKeyboard = value
            handleHardwareKeyboard()
            updateInputViewShown()
            // 开启「物理键盘显示软键盘」后必须立即收起浮动候选栏，否则两条重叠
            updateCandidatesViewShown(isHardwareKeyboard)
        }

    override fun onCreate() {
        super.onCreate()
        addOnChangedListener(onThemeChangeListener)
        // 进程重建时需要先把已保存的开关值读回来，否则会一直按"有实体键盘"处理，软键盘渲染不出来
        showVirtualKeyboardOnPhysicalKeyboard =
            getInstance().keyboardSetting.showVirtualKeyboardOnPhysicalKeyboard.getValue()
        getInstance().keyboardSetting.showVirtualKeyboardOnPhysicalKeyboard
            .registerOnChangeListener(showVirtualKeyboardOnPhysicalKeyboardListener)
        getInstance().internal.clipboardUpdateContent
            .registerOnChangeListener(clipboardUpdateContentListener)
        handleHardwareKeyboard()
        // 提前建根并推进到 CREATED，让首帧组合可以在键盘真正显示前预热
        ensureComposeRoot()
    }

    /**
     * 取（必要时创建）Compose 根视图。
     *
     * 缓存单例：旋屏、切换输入框、窗口重建都复用同一个 View 与同一份组合，
     * 避免每次唤起都重建 `Recomposer`（首帧开销远大于常驻）。
     */
    private fun ensureComposeRoot(): ImeComposeRoot {
        composeRoot?.let { return it }
        return ImeComposeRoot(baseContext, this).also {
            it.imeLifecycleOwner.onCreate()
            composeRoot = it
        }
    }

    override fun onCreateInputView(): View = ensureComposeRoot()

    override fun onCreateCandidatesView(): View {
        hardwareCandidateView?.let { return it }
        val root = ensureComposeRoot()
        // owner 统一取自 ImeComposeRoot：SavedStateRegistryOwnerImpl 只能在生命周期
        // 处于 INITIALIZED 时构造，而 onCreate 预热根视图时已把它推到 CREATED。
        return HardwareCandidateView(
            baseContext,
            root.controller,
            root.imeLifecycleOwner,
            root.viewModelStoreOwner,
            root.savedStateRegistryOwner,
        ).also {
            hardwareCandidateView = it
            currentInputConnection?.requestCursorUpdates(InputConnection.CURSOR_UPDATE_MONITOR)
        }
    }

    override fun onEvaluateInputViewShown(): Boolean {
        super.onEvaluateInputViewShown()
        return if (showVirtualKeyboardOnPhysicalKeyboard) true else !isHardwareKeyboard
    }

    override fun onStartInput(editorInfo: EditorInfo?, restarting: Boolean) {
        super.onStartInput(editorInfo, restarting)
        currentEditorInfo = editorInfo
        // 回车键「聊天发送」策略：每个输入会话都按新编辑框重算（null 时清空，避免沿用上一个框）
        EnterKeyPolicy.update(editorInfo)
        if (editorInfo != null) {
            WhaleEmojiCompat.setEditorInfo(editorInfo)
            InputModeSwitcher.requestInputWithSkb(editorInfo)
        }
        // 浮动候选栏与软键盘互斥：软键盘自带候选栏，只有「有实体键盘且不显示软键盘」
        // 时才需要浮动候选栏。每次输入开始都重新求值，覆盖偏好/配置变更后的状态。
        updateCandidatesViewShown(isHardwareKeyboard)
    }

    override fun onStartInputView(editorInfo: EditorInfo, restarting: Boolean) {
        composeRoot?.let { root ->
            // 编辑器类型可能变了（数字框/搜索框），按当前 input 模式重算键盘类型
            KeyboardManager.instance.switchKeyboard()
            root.controller.dispatcher.onStartInputView(restarting)
            root.imeLifecycleOwner.show()
        }
        // 新的输入会话：preedit 长度与「上一个已上屏字符」缓存都要重新来过。
        // 非重启时趁编辑框还没有 preedit 回读一次做校准，中英混输的第一个交界与句首判定都准。
        composingLength = 0
        // 新输入框的选区还不知道（宿主随后会回调 onUpdateSelection），先按"没有选区"起步
        selectionStart = 0
        selectionEnd = 0
        SmartSpacing.invalidate()
        if (!restarting) prevSnapshot()
        super.onStartInputView(editorInfo, restarting)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        // 键盘收起只降到 CREATED，组合保留
        composeRoot?.imeLifecycleOwner?.hide()
        SmartSpacing.invalidate()
        super.onFinishInputView(finishingInput)
    }

    override fun onDestroy() {
        hardwareCandidateView?.destroy()
        hardwareCandidateView = null
        composeRoot?.destroyComposition()
        composeRoot = null
        super.onDestroy()
        getInstance().keyboardSetting.showVirtualKeyboardOnPhysicalKeyboard
            .unregisterOnChangeListener(showVirtualKeyboardOnPhysicalKeyboardListener)
        getInstance().internal.clipboardUpdateContent
            .unregisterOnChangeListener(clipboardUpdateContentListener)
        removeOnChangedListener(onThemeChangeListener)
    }

    /**
     * 横竖屏切换
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        CoroutineScope(Dispatchers.Main).launch {
            delay(200) //延时，解决获取屏幕尺寸不准确。
            handleHardwareKeyboard()
            updateCandidatesViewShown(isHardwareKeyboard)
            Environment.instance.initData(baseContext)
            KeyboardBuilder.instance.clearKeyboardMap()
            KeyboardManager.instance.clearKeyboard()
            KeyboardManager.instance.switchKeyboard()
            // Compose 根的高度是显式指定的，旋屏/改比例后需要重算，否则沿用上一次方向的高度
            composeRoot?.applyPreferredHeight()
        }
        onSystemDarkModeChange(newConfig.isDarkMode())
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return if (0 != event.repeatCount || event.isShiftPressed || event.isMetaPressed)
            super.onKeyDown(keyCode, event)
        else if (event.isCtrlPressed && keyCode != KeyEvent.KEYCODE_SPACE)
            super.onKeyDown(keyCode, event)
        else {
            val handled = composeRoot?.controller?.dispatcher
                ?.processHardwareKeyDown(keyCode, event) ?: false
            handled || super.onKeyDown(keyCode, event)
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        return if (0 != event.repeatCount || event.isShiftPressed || event.isMetaPressed)
            super.onKeyUp(keyCode, event)
        else if (event.isCtrlPressed && keyCode != KeyEvent.KEYCODE_SPACE)
            super.onKeyUp(keyCode, event)
        else {
            val handled = composeRoot?.controller?.dispatcher
                ?.processHardwareKeyUp(event) ?: false
            handled || super.onKeyUp(keyCode, event)
        }
    }

    override fun setInputView(view: View) {
        // 输入法窗口重建（切换输入框、分屏/悬浮、旋屏、被系统回收后重新拉起）时，
        // 系统可能把还挂在之前窗口上的同一个视图再传进来。这时 InputMethodService.setInputView
        // 内部的 addView 会抛 "The specified child already has a parent"，
        // 表现就是「点输入框没反应，再点一次才弹出键盘」（第一次把输入法进程打崩了）。
        // 交回系统之前先摘掉原有父容器，让这次 addView 一定能成功。
        (view.parent as? ViewGroup)?.removeView(view)
        super.setInputView(view)
        val layoutParams = view.layoutParams ?: return
        // Compose 根没有可测量的子 View，必须显式给高度；否则 MATCH_PARENT 会撑满整个 IME 窗口，
        // 并让 onComputeInsets 报出覆盖全屏的键盘区域。
        val targetHeight = if (view is ImeComposeRoot) {
            view.preferredKeyboardHeight().takeIf { it > 0 } ?: ViewGroup.LayoutParams.MATCH_PARENT
        } else {
            ViewGroup.LayoutParams.MATCH_PARENT
        }
        if (layoutParams.height != targetHeight) {
            layoutParams.height = targetHeight
            view.layoutParams = layoutParams
        }
    }

    /**
     * 与 [setInputView] 同一原因：旋屏/深色切换等配置变更会走
     * `resetStateForNewConfiguration()` → `initViews()` 重建整个窗口根视图，
     * 而 [HardwareCandidateView] 是跨窗口复用的单例，仍挂在已废弃的
     * candidatesArea 上，`setCandidatesView` 里的 addView 会抛
     * "The specified child already has a parent"。
     */
    override fun setCandidatesView(view: View) {
        (view.parent as? ViewGroup)?.removeView(view)
        super.setCandidatesView(view)
    }

    /** 当前编辑框是否多行：单行框上下没有可移动的行，方向键应被吞掉而不是交给焦点导航 */
    val isMultilineEditor: Boolean
        get() {
            val info = currentEditorInfo ?: return false
            return (info.inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT &&
                    (info.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0
        }

    override fun onEvaluateFullscreenMode(): Boolean = false //修复横屏之后输入框遮挡问题

    override fun onComputeInsets(outInsets: Insets) {
        val (x, y, width, height) = currentKeyboardRect()
        outInsets.apply {
            if (Environment.instance.keyboardModeFloat) {
                contentTopInsets = Environment.instance.mScreenHeight
                visibleTopInsets = Environment.instance.mScreenHeight
                touchableInsets = Insets.TOUCHABLE_INSETS_REGION
                touchableRegion.set(x, y, x + width, y + height)
            } else {
                // 窗口顶部那条透明带（拼音气泡条）不属于键盘：宿主内容会一直画到它的下沿。
                // 若仍按窗口顶边报 contentTopInsets，应用会被多顶掉一条（弹出键盘瞬间整屏跳一次）。
                // 窗口向上长了多少，这里就补偿多少 —— 宿主布局像素不变，
                // 气泡只是「浮在宿主最后一条内容之上」。
                val overhang = Environment.instance.composingOverhang
                    .coerceIn(0, (height - 1).coerceAtLeast(0))
                // 常用语编辑弹层同理：窗口向上多留了一条，宿主布局不跟着缩，
                // 弹层像对话框一样浮在宿主内容上（候选栏/键区位置完全不变）
                val popup = if (composeRoot?.controller?.state?.value?.addPhrases == true) {
                    Environment.instance.phraseEditorPopupHeight
                } else 0
                contentTopInsets = y + overhang + popup
                visibleTopInsets = y + overhang + popup
                if (popup > 0) {
                    // 弹层条在 contentTopInsets **之上**：TOUCHABLE_INSETS_CONTENT 会把它
                    // 从可点区域里切掉，卡片上的关闭/完成按钮就点不到。弹层打开时改用整个
                    // 窗口框作为可点区域（弹层盖在宿主内容上，本来也不需要穿透）。
                    touchableInsets = Insets.TOUCHABLE_INSETS_FRAME
                    touchableRegion.setEmpty()
                } else {
                    touchableInsets = Insets.TOUCHABLE_INSETS_CONTENT
                    touchableRegion.setEmpty()
                }
            }
        }
    }

    /** 当前键盘区域：Compose 根自己就是键盘区，直接取它的窗口坐标 */
    private fun currentKeyboardRect(): IntArray {
        composeRoot?.takeIf { it.isAttachedToWindow }?.let { return it.getKeyboardRect() }
        return intArrayOf(0, 0, 0, 0)
    }

    /** 键盘高度比例变化后重设输入视图高度，否则窗口仍挂着原高度、键帽只会变大不会变高 */
    fun applyRootPreferredHeight() {
        composeRoot?.applyPreferredHeight()
    }

    /** 悬浮键盘位置变化后强制走一次布局，让 onComputeInsets 重算 touchableRegion */
    fun refreshInsets() {
        composeRoot?.requestLayout()
        // 第一轮布局时 onComputeInsets 可能早于卡片坐标回调（读到上一次的位置），
        // 布局落地后再补一次，保证 touchableRegion 一定用上新坐标
        composeRoot?.post { composeRoot?.requestLayout() }
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        // 光标变化不是自己上屏造成的（用户点选、宿主改写文本）：混输的空格判定不再可信
        if (!SmartSpacing.consumeOwnCommit(oldSelStart, newSelStart)) {
            SmartSpacing.invalidate()
        }
        // 「有没有选区」就地记下来：上屏前判断时不必再跨进程问一次宿主
        selectionStart = newSelStart
        selectionEnd = newSelEnd
        super.onUpdateSelection(
            oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd
        )
        composeRoot?.controller?.dispatcher?.onUpdateSelection(
            oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesEnd
        )
    }

    private val cursorAnchorPosition = FloatArray(2)

    override fun onUpdateCursorAnchorInfo(cursorAnchorInfo: CursorAnchorInfo?) {
        super.onUpdateCursorAnchorInfo(cursorAnchorInfo)
        if (!isHardwareKeyboard || cursorAnchorInfo == null) return
        cursorAnchorPosition[0] = cursorAnchorInfo.insertionMarkerHorizontal
        cursorAnchorPosition[1] = cursorAnchorInfo.insertionMarkerBottom
        val matrix = cursorAnchorInfo.getMatrix()
        if (matrix != null) {
            matrix.mapPoints(cursorAnchorPosition)
        }
        hardwareCandidateView?.updatePosition(cursorAnchorPosition)
    }

    override fun onWindowShown() {
        // 每次显示都按当前设置重算输入区尺寸并刷新组合，设置页改动后回到键盘立即生效
        composeRoot?.let { root ->
            Environment.instance.initData(baseContext)
            // 悬浮/非悬浮的窗口高度不同，隐藏期间切换过模式的话这里必须重设
            root.applyPreferredHeight()
            root.controller.bumpKeyboardRevision()
            root.imeLifecycleOwner.show()
        }
        super.onWindowShown()
        hideSystemImeSwitchButton()
    }

    override fun onWindowHidden() {
        composeRoot?.let { root ->
            root.controller.dispatcher.resetToIdleState()
            // 编辑键盘浮层是一次性的调整态：收起键盘就退出
            root.controller.closeKeyboardEditor()
            // 符号/剪贴板/功能宫格/编辑键盘都是点开的面板：收起键盘即退回输入键盘，
            // 下次弹出必须是正常输入界面。编辑键盘还要还原输入模式，否则它会一直复活。
            root.controller.switchToInputKeyboard()
            root.imeLifecycleOwner.hide()
        }
        super.onWindowHidden()
    }

    /**
     * 模拟Enter按键点击
     */
    fun sendEnterKeyEvent() {
        val inputConnection = getCurrentInputConnection()
        // 聊天发送模式：宿主上报的多半是 flagNoEnterAction，走下面那条分支会变成换行。
        // 所以必须在原来的判定之前分流，直接按发送动作交给宿主处理。
        if (EnterKeyPolicy.sendInChat) {
            inputConnection?.performEditorAction(EditorInfo.IME_ACTION_SEND)
            return
        }
        WhaleEmojiCompat.mEditorInfo?.run {
            if (inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_NULL ||
                imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0
            ) {
                sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
            } else if (!actionLabel.isNullOrEmpty() && actionId != EditorInfo.IME_ACTION_UNSPECIFIED) {
                inputConnection.performEditorAction(actionId)
            } else when (val action = imeOptions and EditorInfo.IME_MASK_ACTION) {
                EditorInfo.IME_ACTION_UNSPECIFIED, EditorInfo.IME_ACTION_NONE ->
                    sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
                else -> inputConnection.performEditorAction(action)
            }
        }
    }

    fun sendCombinationKeyEvents(
        keyEventCode: Int,
        alt: Boolean = false,
        ctrl: Boolean = false,
        shift: Boolean = false,
    ) {
        // 数字键是按物理键语义上屏的（不经过 commitText），混输的空格只能在这里补：
        // 数字键盘的 0~9 走的就是这条路，漏了它「中文 → 数字」就不会补空格
        val digitCommit =
            if (!alt && !ctrl && !shift) prepareDigitKeyCommit(keyEventCode) else null
        var metaState = 0
        if (alt) metaState = KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        if (ctrl) metaState = metaState or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        if (shift) metaState = metaState or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        val eventTime = SystemClock.uptimeMillis()
        if (alt) sendDownKeyEvent(eventTime, KeyEvent.KEYCODE_ALT_LEFT)
        if (ctrl) sendDownKeyEvent(eventTime, KeyEvent.KEYCODE_CTRL_LEFT)
        if (shift) sendDownKeyEvent(eventTime, KeyEvent.KEYCODE_SHIFT_LEFT)
        sendDownKeyEvent(eventTime, keyEventCode, metaState)
        sendUpKeyEvent(eventTime, keyEventCode, metaState)
        if (shift) sendUpKeyEvent(eventTime, KeyEvent.KEYCODE_SHIFT_LEFT)
        if (ctrl) sendUpKeyEvent(eventTime, KeyEvent.KEYCODE_CTRL_LEFT)
        if (alt) sendUpKeyEvent(eventTime, KeyEvent.KEYCODE_ALT_LEFT)
        // 按键本身由宿主插入，判定缓存直接按「数字已上屏」推进，紧随其后的中文才知道要补空格
        if (digitCommit != null) SmartSpacing.onCommit(digitCommit, digitCommit, 0)
    }

    /**
     * 数字键上屏前按混输规则补一个空格。
     *
     * @return 这次按键实际写进编辑框的文本（可能带补出来的空格）；非数字键返回 null
     */
    private fun prepareDigitKeyCommit(keyCode: Int): String? {
        if (keyCode !in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) return null
        val digit = ('0'.code + (keyCode - KeyEvent.KEYCODE_0)).toChar()
        if (!needsSpaceBefore(prevSnapshot(), digit.code)) return digit.toString()
        currentInputConnection.commitText(" ", 1)
        return " $digit"
    }

    fun sendDownKeyEvent(eventTime: Long, keyEventCode: Int, metaState: Int = 0) {
        if (keyEventCode == KeyEvent.KEYCODE_DEL || keyEventCode == KeyEvent.KEYCODE_FORWARD_DEL) {
            // 删除键交给宿主处理，删掉的是哪个字我们不知道，混输的空格判定退回回读编辑框
            SmartSpacing.invalidate()
        }
        currentInputConnection?.sendKeyEvent(
            KeyEvent(
                eventTime, eventTime, KeyEvent.ACTION_DOWN, keyEventCode, 0, metaState,
                KeyCharacterMap.VIRTUAL_KEYBOARD, keyEventCode,
                KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE
            )
        )
    }

    fun sendUpKeyEvent(eventTime: Long, keyEventCode: Int, metaState: Int = 0) {
        currentInputConnection.sendKeyEvent(
            KeyEvent(
                eventTime, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, keyEventCode, 0, metaState,
                KeyCharacterMap.VIRTUAL_KEYBOARD, keyEventCode,
                KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE
            )
        )
    }

    /** 向输入框提交预选词 */
    fun setComposingText(text: CharSequence) {
        composingLength = text.length
        currentInputConnection.setComposingText(text, 1)
    }

    /** 结束提交预选词 */
    fun finishComposingText() {
        composingLength = 0
        currentInputConnection.finishComposingText()
    }

    /**
     * 发送字符串给编辑框。
     *
     * 这里是上屏的唯一出口，中英/中数混输的空格与英文句首大写都在这里改：补出的空格与正文
     * **在同一次 commitText 里提交**，拆成两次会把空格插进 preedit 中间。
     */
    fun commitText(text: String) {
        val body = decorateForCommit(text)
        val converted = toTextStyle(body)
        val replaced = composingLength
        currentInputConnection.commitText(converted, 1)
        // commitText 已把 preedit 换成正文，宿主侧不再有组合区
        composingLength = 0
        SmartSpacing.onCommit(raw = body, committed = converted, replaced = replaced)
    }

    /**
     * 上屏前的改写：中英/中数混输补空格 + 英文句首大写 + 英文标点后补空格。
     *
     * 三条判定都基于「已上屏的最后一个字符」。英文那两条要求**精确**：英文模式下的句号、
     * 逗号是 `sendKeyEvent` 交给宿主写的，宿主插一个字符与"我们上屏一个字符"在光标位移上
     * 完全一样（见 [SmartSpacing.consumeOwnCommit]），缓存会稳定落后一个字符 —— 所以英文
     * 模式下这两条只要开着就回读编辑框；中文混输那条容错高，继续走缓存。
     *
     * 只处理「已上屏的最后一个字符」与「本次上屏的第一个字符」之间的交界，
     * 文本内部不拆词。整段内容（剪贴板 / 常用语，含空白或换行）整体放过。
     */
    private fun decorateForCommit(text: String): String {
        if (text.isEmpty()) return text
        val first = SmartSpacing.firstCodePoint(text)
        if (first < 0 || Character.isWhitespace(first)) return text
        if (text.any { it == ' ' || it == '\n' || it == '\t' }) return text
        val prev = if (needsExactPrev()) readPrevSnapshot() else prevSnapshot()
        val cased = if (shouldCapitalizeFirst(first, prev)) {
            SentenceCase.capitalizeFirstLetter(text)
        } else {
            text
        }
        return if (needsSpaceBefore(prev, first)) " $cased" else cased
    }

    /** 英文模式下的改写是否要求精确的前一个字符（见 [decorateForCommit]） */
    private fun needsExactPrev(): Boolean {
        if (!InputModeSwitcher.isEnglish) return false
        val input = getInstance().input
        return input.englishSentenceCase.getValue() || input.englishPunctSpace.getValue()
    }

    /** 本次上屏（或按键）的第一个字符 [next] 前面是否需要补一个空格 */
    private fun needsSpaceBefore(prev: PrevSnapshot, next: Int): Boolean {
        if (!prev.known || isPasswordEditor()) return false
        val previous = prev.lastCodePoint ?: return false
        val input = getInstance().input
        val enabled = when (SmartSpacing.boundary(previous, next)) {
            SmartSpacing.Boundary.Digit -> input.chineseDigitSpace.getValue()
            SmartSpacing.Boundary.Letter -> input.chineseEnglishSpace.getValue()
            // 英文标点后的空格只在英文模式、且不是网址/邮箱这类编辑框里补
            SmartSpacing.Boundary.EnglishPunct ->
                input.englishPunctSpace.getValue() &&
                    InputModeSwitcher.isEnglish &&
                    !isEnglishRewriteExcludedEditor()
            null -> false
        }
        if (!enabled) return false
        // 有选区 = 正替换一段文字，「前一个字符」没有意义
        return !hasSelection()
    }

    /**
     * 本次上屏的首字符 [first] 是否要按「英文句首」改成大写。
     *
     * 只改写 ASCII 小写字母：用户按 Shift / CapsLock 时首字符本来就是大写，数字、符号、
     * 汉字、emoji 也都不参与 —— 这条判据天然尊重用户的显式大小写，不必再读 Shift 状态
     * （`processKeyUp` 在派发按键时就已经把 shift 熄掉了，读了也不准）。
     */
    private fun shouldCapitalizeFirst(first: Int, prev: PrevSnapshot): Boolean {
        if (first !in 'a'.code..'z'.code) return false
        if (!getInstance().input.englishSentenceCase.getValue()) return false
        // 只在英文模式生效：中文模式下选中的英文候选、中文句子里夹的英文都不动
        if (!InputModeSwitcher.isEnglish) return false
        if (!prev.known || isEnglishRewriteExcludedEditor()) return false
        // 有选区 = 正替换一段文字，「前一个字符」没有意义
        if (hasSelection()) return false
        return SentenceCase.shouldCapitalizeAfter(prev.decisiveCodePoint)
    }

    /**
     * 光标前已上屏内容的快照。
     *
     * [known] = false 表示编辑框读不到（连接失效 / 拿不到文本）：补空格与大写一律放弃，
     * 宁可不改，不可改错。[lastCodePoint] 是紧邻光标的字符（补空格用），[decisiveCodePoint]
     * 是跳过空白与引号括号后的最后一个字符（句首判定用）；两者都是 null = 已确认光标前没有字符。
     */
    private class PrevSnapshot(
        val known: Boolean,
        val lastCodePoint: Int?,
        val decisiveCodePoint: Int?,
    ) {
        companion object {
            val Unknown = PrevSnapshot(false, null, null)
        }
    }

    /**
     * 取「光标前一个字符」快照：缓存可信时直接用，否则回读编辑框。
     *
     * 只给补空格用 —— 那条判定的容错很高（缓存落后一个标点，结论通常不变）。
     */
    private fun prevSnapshot(): PrevSnapshot {
        if (SmartSpacing.isCacheTrusted()) {
            return PrevSnapshot(
                known = true,
                lastCodePoint = SmartSpacing.lastCodePointOrNull(),
                decisiveCodePoint = SmartSpacing.lastDecisiveCodePointOrNull(),
            )
        }
        return readPrevSnapshot()
    }

    /**
     * 回读编辑框，取「光标前一个字符」快照并校准缓存。
     *
     * 跳过当前 preedit（英文模式下编辑框里挂着编码）。读不到就返回 [PrevSnapshot.Unknown]：
     * 调用方一律放弃改写 —— 宁可不改，不可改错。
     */
    private fun readPrevSnapshot(): PrevSnapshot {
        val connection = currentInputConnection ?: return PrevSnapshot.Unknown
        val skip = composingLength.coerceAtLeast(0)
        val before = runCatching { connection.getTextBeforeCursor(skip + PREV_READ_LENGTH, 0) }
            .getOrNull() ?: return PrevSnapshot.Unknown
        // 连 preedit 都没读全：读到的内容不可用
        if (before.length < skip) return PrevSnapshot.Unknown
        val committed = before.subSequence(0, before.length - skip)
        val snapshot = PrevSnapshot(
            known = true,
            lastCodePoint = lastCodePointOf(committed),
            decisiveCodePoint = SentenceCase.lastDecisiveCodePoint(committed),
        )
        SmartSpacing.restore(snapshot.lastCodePoint, snapshot.decisiveCodePoint)
        return snapshot
    }

    /** 文本末字符的码点（代理对按一个字符算）；空串返回 null */
    private fun lastCodePointOf(text: CharSequence): Int? {
        val end = text.length
        if (end == 0) return null
        val last = text[end - 1]
        return if (Character.isLowSurrogate(last) && end >= 2) {
            Character.toCodePoint(text[end - 2], last)
        } else {
            last.code
        }
    }

    /**
     * 只有密码框不补空格。
     *
     * 不按 URI / EMAIL / TYPE_NULL 排除：浏览器的地址栏和搜索栏就是 URI 变体，
     * 很多 App 的自定义输入框上报的是 TYPE_NULL，一刀切会让这些常用场景整个失效；
     * 读不到光标前文本时判定本来就会自动放弃，不需要靠类型兜底。
     */
    private fun isPasswordEditor(): Boolean {
        val info = currentEditorInfo ?: return false
        val type = info.inputType
        if (type and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) return false
        return when (type and InputType.TYPE_MASK_VARIATION) {
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            -> true
            else -> false
        }
    }

    /**
     * 英文的两条智能改写（句首大写、标点后补空格）在哪些编辑框里失效：密码 + 网址 + 邮箱。
     *
     * 与中文混输补空格不同，这两条都是**破坏性**改写（地址栏里 `https` 被顶成 `Https`、
     * `example.com` 中间被塞空格，内容直接就错了），所以 URI / 邮箱也得排除；
     * 中文混输补空格不能这么排 —— 浏览器的地址栏和搜索栏都是 URI 变体，
     * 一刀切会让这些常用场景整个失效。
     */
    private fun isEnglishRewriteExcludedEditor(): Boolean {
        val info = currentEditorInfo ?: return false
        val type = info.inputType
        if (type and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) return false
        return when (type and InputType.TYPE_MASK_VARIATION) {
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_URI,
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
            -> true
            else -> false
        }
    }

    private fun hasSelection(): Boolean = selectionStart != selectionEnd

    /** 发送字符串给编辑框 */
    fun commitText(text: String, newCursorPosition: Int) {
        currentInputConnection.commitText(
            toTextStyle(text), newCursorPosition
        )
    }

    fun getTextBeforeCursor(length: Int): String {
        return currentInputConnection.getTextBeforeCursor(length, 0).toString()
    }

    fun getTextAfterCursor(length: Int): String {
        return currentInputConnection.getTextAfterCursor(length, 0).toString()
    }

    fun commitTextEditMenu(id: Int) {
        currentInputConnection.performContextMenuAction(id)
    }

    fun performEditorAction(editorAction: Int) {
        currentInputConnection.performEditorAction(editorAction)
    }

    fun deleteSurroundingText(length: Int) {
        // 删除后「上一个已上屏字符」可能是别的字了，缓存的尾部不再可信
        SmartSpacing.invalidate()
        currentInputConnection.deleteSurroundingText(length, 0)
    }

    fun setSelection(start: Int, end: Int) {
        currentInputConnection.setSelection(start, end)
    }

    fun handleHardwareKeyboard(newConfig: Configuration? = null): Boolean {
        val hardwareKeyboard = if (showVirtualKeyboardOnPhysicalKeyboard) false
        else if (newConfig != null) (newConfig.keyboard != Configuration.KEYBOARD_NOKEYS)
        else resources.configuration.keyboard != Configuration.KEYBOARD_NOKEYS
        isHardwareKeyboard = hardwareKeyboard
        return hardwareKeyboard
    }

    fun updateCandidatesViewShown(shown: Boolean) {
        setCandidatesViewShown(shown)
    }

    /**
     * Android 13 起，系统会在输入法窗口底部自绘一条导航栏（NavigationBarFrame），
     * 左端是系统的输入法切换（地球）按钮，右端是收起键盘按钮。
     * 小鲸鱼的输入法切换统一由设置里的「全面屏键盘优化」提供，这里把系统那个切换按钮隐藏掉，
     * 避免出现两个切换入口（收起键盘按钮保留，不影响返回）。
     */
    private fun hideSystemImeSwitchButton() {
        val decor = window?.window?.decorView as? ViewGroup ?: return
        val hide: () -> Unit = {
            val switcherId = resources.getIdentifier("ime_switch_button", "id", "android")
            val switcher = if (switcherId != 0) decor.findViewById<View>(switcherId) else null
            if (switcher != null) {
                switcher.visibility = View.INVISIBLE
            } else {
                // 个别机型按钮 id 不同，退化成整条系统导航栏隐藏
                findSystemNavigationBarFrame(decor)?.visibility = View.INVISIBLE
            }
        }
        decor.post { hide() }
        decor.postDelayed({ hide() }, 120) // 个别机型要等首帧布局后才插入系统导航栏
    }

    private fun findSystemNavigationBarFrame(view: View): View? {
        if (view.javaClass.simpleName == NAVIGATION_BAR_FRAME_CLASS_NAME) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findSystemNavigationBarFrame(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    companion object {
        // 系统在输入法窗口里自绘的导航栏容器类名（android.inputmethodservice.navigationbar.NavigationBarFrame）
        private const val NAVIGATION_BAR_FRAME_CLASS_NAME = "NavigationBarFrame"

        /**
         * 回读编辑框时多读的字符数。
         *
         * 句首判定要跳过尾部的空白、引号、括号才能找到决定性字符，只读 1~2 个字符不够
         * （`Hi.  "` 这种就是典型）；读不到更前面的内容时扫到哪算哪，不影响保守判定。
         */
        private const val PREV_READ_LENGTH = 8
    }
}
