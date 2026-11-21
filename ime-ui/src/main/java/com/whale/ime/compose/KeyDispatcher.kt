package com.whale.ime.compose

import android.view.KeyEvent
import com.whale.ime.ui.R
import com.whale.ime.callback.IResponseKeyEvent
import com.whale.ime.emoji.EmojiconData.SymbolPreset
import com.whale.ime.util.StringQueue
import com.whale.ime.keyboard.model.SoftKey
import com.whale.ime.keyboard.state.KeyboardManager
import com.whale.ime.keyboard.layout.KeyPreset
import com.whale.ime.keyboard.layout.InputModeSwitcher
import com.whale.ime.preference.AppPrefs.Companion.getInstance
import com.whale.ime.preference.model.PopupMenuMode
import com.whale.ime.preference.model.KeyboardMenuMode
import com.whale.ime.dispatch.DictDecoder
import com.whale.ime.ui.service.ImeService
import com.whale.ime.dispatch.InputDispatcher
import com.whale.ime.engine.CustomEngine
import com.whale.ime.engine.RimeEngine
import com.whale.ime.rime.CandidateListItem
import android.os.Handler
import android.os.Looper
import com.whale.ime.config.SchemaIds

/**
 * 键盘事件派发器：UI ↔ 引擎的**唯一**咽喉点。
 *
 * 职责边界：
 * - 不持有 `Context` / `View`，只依赖 [ImeService] 的公开方法；
 * - 不直接操作任何容器，而是写 [ImeController] 的状态
 *   （候选列表由 `DictDecoder.candidateState` 驱动，UI 自己响应）；
 * - 短语编辑状态是 [ImeUiState] 里的纯字符串。
 *
 * 所有按键都经过同一份「按键 → 引擎」的判定，键值序列可回归验证。
 */
class KeyDispatcher(private val controller: ImeController) : IResponseKeyEvent {

    private val service: ImeService get() = controller.service
    private val appPrefs = getInstance()
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 记录删除内容，供「上滑清空 → 上滑恢复」使用 */
    private val textBeforeCursors = StringQueue(50)

    var hasSelection = false
    var hasSelectionAll = false

    private var chinesePrediction = true
    private var selStart = 0
    private var selEnd = 0
    private var oldCandidatesEnd = 0

    fun onStartInputView(restarting: Boolean) {
        chinesePrediction = appPrefs.input.chinesePrediction.getValue()
        if (restarting) return
        resetToIdleState()
        val clipboard = appPrefs.clipboard
        if (clipboard.clipboardSuggestion.getValue()) {
            val internal = appPrefs.internal
            val timeoutMs = appPrefs.clipboard.clipboardItemTimeout.getValue() * 1000L
            val lastTime = internal.clipboardUpdateTime.getValue()
            if (System.currentTimeMillis() - lastTime <= timeoutMs) {
                val content = internal.clipboardUpdateContent.getValue()
                if (content.isNotBlank()) {
                    showClipboardSuggestion(content)
                    internal.clipboardUpdateTime.setValue(0L)
                }
            }
        }
    }

    // ---------------------------------------------------------------------
    // IResponseKeyEvent
    // ---------------------------------------------------------------------

    override fun responseKeyEvent(sKey: SoftKey) {
        val keyCode = sKey.code
        if (sKey.isUserDefKey) {
            processUserDefKey(keyCode, sKey.keyLabel)
        } else if (sKey.isUniStrKey) {
            if (!DictDecoder.isAssociate && !DictDecoder.isCandidatesEmpty) chooseAndUpdate()
            sKey.label.takeIf(String::isNotEmpty)?.let {
                if (SymbolPreset.containsKey(it)) commitPairSymbol(it) else commitText(it)
            }
        } else {
            val metaState = when (InputDispatcher.getCurrentRimeSchema()) {
                SchemaIds.SCHEMA_ZH_T9,
                SchemaIds.SCHEMA_ZH_T14,
                SchemaIds.SCHEMA_ZH_STROKE -> KeyEvent.META_CAPS_LOCK_ON
                else -> InputModeSwitcher.mToggleStates.modifiers
            }
            processKeyUp(
                KeyEvent(
                    0, 0, KeyEvent.ACTION_UP, keyCode, 0, metaState, 0, 0,
                    KeyEvent.FLAG_SOFT_KEYBOARD
                )
            )
        }
    }

    override fun responseLongKeyEvent(result: Pair<PopupMenuMode, String>) {
        val (mode, value) = result
        // Clear 例外：它要么只清编码、要么清上屏内容，绝不能先把当前候选上屏
        //（否则「上滑清空」会先选掉第一个候选，再连它一起删掉）。
        if (mode != PopupMenuMode.None && mode != PopupMenuMode.Clear &&
            !DictDecoder.isAssociate && !DictDecoder.isCandidatesEmpty
        ) {
            if (InputModeSwitcher.isChinese || InputModeSwitcher.isEnglish) chooseAndUpdate()
        }

        when (mode) {
            PopupMenuMode.Text -> if (SymbolPreset.containsKey(value)) commitPairSymbol(value) else commitText(value)
            PopupMenuMode.SwitchIME -> com.whale.ime.ui.util.InputMethodUtil.showPicker()
            PopupMenuMode.EMOJI -> controller.onMenuAction(KeyboardMenuMode.Emojicon)
            PopupMenuMode.EnglishCell -> {
                val pref = appPrefs.input.abcSearchEnglishCell
                pref.setValue(!pref.getValue())
                KeyboardManager.instance.switchKeyboard()
                controller.bumpKeyboardRevision()
            }
            PopupMenuMode.Clear -> {
                if (controller.state.value.addPhrases) {
                    controller.clearPhraseContent()
                } else if (!hasPinyinComposition()) {
                    // 没有未上屏的拼音时才动上屏内容；有编码时这一段只清编码，
                    // 由末尾的 resetToIdleState() 收尾（见下方注释）。
                    service.getTextBeforeCursor(1000).takeIf { it.isNotEmpty() }?.let {
                        textBeforeCursors.push(it)
                        service.deleteSurroundingText(1000)
                    }
                } else if (InputModeSwitcher.isEnglish) {
                    // 英文模式的编码是编辑框里的 preedit（拼音只在键盘候选栏里），
                    // 引擎 reset 不会收回编辑框的组合区，这里单独清掉。
                    service.setComposingText("")
                }
            }
            PopupMenuMode.Revertl -> textBeforeCursors.popInReverseOrder()?.takeIf { it.isNotEmpty() }
                ?.let { commitText(it) }
            PopupMenuMode.Enter -> commitText("\n")
            else -> {}
        }
        // 不论清的是编码还是上屏内容，最后都要把输入态归零：
        // 有编码时这一步就是「只清拼音」，上屏内容在上面那段里根本没被碰过。
        if (mode == PopupMenuMode.Clear) resetToIdleState()
    }

    override fun responseHandwritingResultEvent(words: Array<CandidateListItem>) {
        DictDecoder.cacheCandidates(words)
    }

    // ---------------------------------------------------------------------
    // 硬件键
    // ---------------------------------------------------------------------

    /**
     * 物理键盘模式下的按下判定。
     *
     * 与软键盘路径的区别：英文模式一律放行给宿主应用，数字键用于选候选。
     */
    fun processHardwareKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (InputModeSwitcher.isEnglish) return false
        if (keyCode >= KeyEvent.KEYCODE_A && keyCode <= KeyEvent.KEYCODE_Z) return true
        if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) return true
        if (keyCode == KeyEvent.KEYCODE_SPACE) return true
        if (keyCode == KeyEvent.KEYCODE_APOSTROPHE || keyCode == KeyEvent.KEYCODE_SEMICOLON) return true
        if (keyCode == KeyEvent.KEYCODE_DEL) return true
        if (keyCode == KeyEvent.KEYCODE_ENTER) return true
        if (keyCode == KeyEvent.KEYCODE_TAB) return true
        if (keyCode in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_RIGHT) return true
        return false
    }

    fun processHardwareKeyUp(event: KeyEvent): Boolean {
        InputModeSwitcher.resetCharCase()
        return if (processHardwareFunctionKeys(event)) true
        else if (InputModeSwitcher.isChinese) processHardwareInput(event)
        else false
    }

    private fun processHardwareFunctionKeys(event: KeyEvent): Boolean {
        when (val keyCode = event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_SPACE -> {
                when {
                    DictDecoder.isAssociate && controller.state.value.activeCandidate == 0 -> {
                        sendKeyEvent(keyCode)
                        resetToIdleState()
                    }
                    !DictDecoder.isCandidatesEmpty -> chooseAndUpdate()
                    // 有编码但没候选（i / u / v 这类）：按编码上屏
                    DictDecoder.isComposing -> {
                        commitDecInfoText(DictDecoder.composingStrForCommit)
                        resetToIdleState()
                    }
                    else -> {
                        sendKeyEvent(keyCode)
                        resetToIdleState()
                    }
                }
                if (keyCode == KeyEvent.KEYCODE_SPACE && event.isCtrlPressed) {
                    InputModeSwitcher.switchModeForUserKey(InputModeSwitcher.USER_KEYCODE_LANG)
                    resetToIdleState()
                    android.widget.Toast.makeText(
                        service,
                        if (InputModeSwitcher.isEnglish) "小鲸鱼输入法-英文" else "小鲸鱼输入法-拼音",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
                return true
            }
            KeyEvent.KEYCODE_CLEAR -> {
                resetToIdleState()
                return true
            }
            KeyEvent.KEYCODE_ENTER -> {
                if ((DictDecoder.isCandidatesEmpty && !DictDecoder.isComposing) || DictDecoder.isAssociate) {
                    sendKeyEvent(keyCode)
                } else {
                    commitDecInfoText(DictDecoder.composingStrForCommit)
                }
                resetToIdleState()
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (!DictDecoder.isCandidatesEmpty) updateActiveCandidateNo(keyCode) else sendKeyEvent(keyCode)
                return true
            }
            else -> return false
        }
    }

    private fun processHardwareInput(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val keyChar = event.unicodeChar
        val label = keyChar.toChar().toString()
        return when {
            keyCode == KeyEvent.KEYCODE_DEL -> {
                if ((DictDecoder.isCandidatesEmpty && !DictDecoder.isComposing) || DictDecoder.isAssociate) {
                    sendKeyEvent(keyCode)
                } else {
                    if (!InputDispatcher.isFinish) InputDispatcher.deleteAction() else InputDispatcher.reset()
                    updateCandidate()
                }
                true
            }
            label.isNotEmpty() && label.all(Char::isDigit) -> {
                // 数字键选候选
                chooseAndUpdate(label.toInt() - 1)
                true
            }
            Character.isLetter(keyChar) || keyCode == KeyEvent.KEYCODE_APOSTROPHE ||
                    keyCode == KeyEvent.KEYCODE_SEMICOLON -> {
                InputDispatcher.inputKeyCode(event)
                updateCandidate()
                true
            }
            else -> {
                if (!DictDecoder.isCandidatesEmpty && !DictDecoder.isAssociate) chooseAndUpdate()
                false
            }
        }
    }

    fun processKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode >= KeyEvent.KEYCODE_A && keyCode <= KeyEvent.KEYCODE_Z) return true
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_APOSTROPHE, KeyEvent.KEYCODE_SPACE,
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DEL -> return true
            KeyEvent.KEYCODE_BACK -> return service.isInputViewShown
        }
        return false
    }

    fun processKeyUp(event: KeyEvent): Boolean {
        if (event.isSystem) return processSystemKeys(event)
        if (isFunctionKey(event.keyCode)) {
            processFunctionKey(event)
            return true
        }
        InputModeSwitcher.resetCharCase()
        val englishCellDisable = InputModeSwitcher.isEnglish && !appPrefs.input.abcSearchEnglishCell.getValue()
        return when {
            englishCellDisable -> processEnglishKey(event)
            InputModeSwitcher.isEnglish || InputModeSwitcher.isChinese -> processInput(event)
            else -> processEnglishKey(event)
        }
    }

    // ---------------------------------------------------------------------
    // 具体分支
    // ---------------------------------------------------------------------

    private fun processEnglishKey(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val keyChar = event.unicodeChar
        val label = keyChar.toChar().toString()
        var result = true
        when {
            keyCode == KeyEvent.KEYCODE_DEL -> {
                service.getTextBeforeCursor(1).takeIf { it.isNotEmpty() }?.let { textBeforeCursors.push(it) }
                sendKeyEvent(keyCode)
            }
            keyCode in (KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) -> {
                textBeforeCursors.clear()
                commitText(label)
            }
            keyCode != 0 -> sendKeyEvent(keyCode)
            label.isNotEmpty() -> if (SymbolPreset.containsKey(label)) commitPairSymbol(label) else commitText(label)
            else -> result = false
        }
        return result
    }

    private fun processSystemKeys(event: KeyEvent): Boolean {
        return when (event.keyCode) {
            KeyEvent.KEYCODE_BACK -> if (service.isInputViewShown) {
                service.requestHideSelf(0)
                true
            } else false
            else -> false
        }
    }

    fun isFunctionKey(keyCode: Int): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_CLEAR, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT, KeyEvent.KEYCODE_LANGUAGE_SWITCH,
            KeyEvent.KEYCODE_SYM, KeyEvent.KEYCODE_PICTSYMBOLS, KeyEvent.KEYCODE_NUM -> return true
        }
        return false
    }

    private fun processFunctionKey(event: KeyEvent) {
        when (val keyCode = event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_SPACE -> {
                when {
                    DictDecoder.isAssociate -> {
                        sendKeyEvent(keyCode)
                        resetToIdleState()
                    }
                    !DictDecoder.isCandidatesEmpty -> chooseAndUpdate()
                    // 有编码但没候选（i / u / v 这类）：像回车一样把编码上屏，别让编码被空格吃掉
                    DictDecoder.isComposing -> {
                        commitDecInfoText(DictDecoder.composingStrForCommit)
                        resetToIdleState()
                    }
                    else -> {
                        sendKeyEvent(keyCode)
                        resetToIdleState()
                    }
                }
            }
            KeyEvent.KEYCODE_CLEAR -> resetToIdleState()
            KeyEvent.KEYCODE_ENTER -> {
                if ((DictDecoder.isCandidatesEmpty && !DictDecoder.isComposing) || DictDecoder.isAssociate) {
                    sendKeyEvent(keyCode)
                } else {
                    commitDecInfoText(DictDecoder.composingStrForCommit)
                }
                resetToIdleState()
            }
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> {
                if (InputModeSwitcher.isChinese && !InputDispatcher.isFinish) {
                    processInput(
                        KeyEvent(
                            0, 0, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_APOSTROPHE, 0, 0, 0, 0,
                            KeyEvent.FLAG_SOFT_KEYBOARD
                        )
                    )
                } else {
                    InputModeSwitcher.processShiftKey(keyCode)
                }
            }
        }
        // 键帽状态（shift 大写/分词、enter 动作图标）跟着变了，通知 UI 重绘
        controller.bumpKeyboardRevision()
    }

    private fun processUserDefKey(keyCode: Int, label: String) {
        when {
            keyCode == InputModeSwitcher.USER_KEYCODE_CURSOR_DIRECTION -> {
                resetToIdleState()
                return
            }
            !DictDecoder.isAssociate && !DictDecoder.isCandidatesEmpty -> {
                if (InputModeSwitcher.isChinese || InputModeSwitcher.isEnglish) chooseAndUpdate()
            }
        }

        when (keyCode) {
            InputModeSwitcher.USER_KEYCODE_SYMBOL ->
                controller.openSymbolPanel(com.whale.ime.preference.model.SymbolMode.Symbol)

            InputModeSwitcher.USER_KEYCODE_EMOJI ->
                controller.openSymbolPanel(com.whale.ime.preference.model.SymbolMode.Emojicon)
            in InputModeSwitcher.USER_KEYCODE_RETURN..InputModeSwitcher.USER_KEYCODE_LANG ->
                InputModeSwitcher.switchModeForUserKey(keyCode)
            in InputModeSwitcher.USER_KEYCODE_PASTE..InputModeSwitcher.USER_KEYCODE_CUT ->
                commitTextEditMenu(KeyPreset.textEditMenuPreset[keyCode])
            InputModeSwitcher.USER_KEYCODE_MOVE_START ->
                service.setSelection(0, if (hasSelection) selEnd else 0)
            InputModeSwitcher.USER_KEYCODE_MOVE_END -> {
                if (hasSelection) {
                    val start = selStart
                    commitTextEditMenu(KeyPreset.textEditMenuPreset[InputModeSwitcher.USER_KEYCODE_SELECT_ALL])
                    mainHandler.postDelayed({ service.setSelection(start, selEnd) }, 100)
                } else {
                    commitTextEditMenu(KeyPreset.textEditMenuPreset[InputModeSwitcher.USER_KEYCODE_SELECT_ALL])
                    mainHandler.postDelayed({ service.sendCombinationKeyEvents(KeyEvent.KEYCODE_DPAD_RIGHT) }, 100)
                }
            }
            InputModeSwitcher.USER_KEYCODE_SELECT_MODE -> {
                hasSelection = !hasSelection
                if (!hasSelection) service.sendCombinationKeyEvents(KeyEvent.KEYCODE_DPAD_RIGHT)
                controller.bumpKeyboardRevision()
            }
            InputModeSwitcher.USER_KEYCODE_SELECT_ALL -> {
                hasSelectionAll = !hasSelectionAll
                if (!hasSelectionAll) service.sendCombinationKeyEvents(KeyEvent.KEYCODE_DPAD_RIGHT)
                else commitTextEditMenu(KeyPreset.textEditMenuPreset[keyCode])
            }
            else -> {
                if (label.isNotEmpty()) {
                    if (SymbolPreset.containsKey(label)) commitPairSymbol(label) else commitText(label)
                }
            }
        }
    }

    private fun processInput(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val keyChar = event.unicodeChar
        val label = keyChar.toChar().toString()

        return when {
            keyCode == KeyEvent.KEYCODE_DEL -> {
                when {
                    // 本次按下开始时还有拼音：整段按住期间只清编码，
                    // 编码删空就停手，已上屏内容要抬手重新按下才处理。
                    deleteKeepsCommittedText -> if (hasPinyinComposition()) {
                        InputDispatcher.deleteAction()
                        updateCandidate()
                    }
                    (DictDecoder.isCandidatesEmpty && !DictDecoder.isComposing) || DictDecoder.isAssociate -> {
                        service.getTextBeforeCursor(1).takeIf { it.isNotEmpty() }?.let { textBeforeCursors.push(it) }
                        sendKeyEvent(keyCode)
                    }
                    else -> {
                        InputDispatcher.deleteAction()
                        updateCandidate()
                    }
                }
                true
            }
            (Character.isLetterOrDigit(keyChar) && keyCode != KeyEvent.KEYCODE_0) ||
                    keyCode == KeyEvent.KEYCODE_APOSTROPHE || keyCode == KeyEvent.KEYCODE_SEMICOLON -> {
                textBeforeCursors.clear()
                InputDispatcher.inputKeyCode(event)
                updateCandidate()
                true
            }
            keyCode != 0 -> {
                if (!DictDecoder.isCandidatesEmpty && !DictDecoder.isAssociate) chooseAndUpdate()
                else if (DictDecoder.isComposing && !DictDecoder.isAssociate) {
                    // 有编码没候选（i/u/v）：先把编码上屏，再把按键交给宿主
                    commitDecInfoText(DictDecoder.composingStrForCommit)
                }
                sendKeyEvent(keyCode)
                resetToIdleState()
                true
            }
            label.isNotEmpty() -> {
                if (!DictDecoder.isCandidatesEmpty && !DictDecoder.isAssociate) chooseAndUpdate()
                else if (DictDecoder.isComposing && !DictDecoder.isAssociate) {
                    commitDecInfoText(DictDecoder.composingStrForCommit)
                }
                if (SymbolPreset.containsKey(label)) commitPairSymbol(label) else commitText(label)
                true
            }
            else -> false
        }
    }

    // ---------------------------------------------------------------------
    // 候选与状态
    // ---------------------------------------------------------------------

    fun resetToIdleState() {
        clearSegmentGroup()
        resetCandidateWindow()
        if (hasSelectionAll) hasSelectionAll = false
        controller.resetActiveCandidate()
    }

    /**
     * 是否还有未上屏的拼音编码（含分段组句的已选部分）。
     *
     * 只认引擎组合串：联想词是「已经上屏之后」的续写建议，表情/剪贴板/算式候选
     * 也不是未上屏输入，都不该挡住对已上屏内容的处理。
     */
    private fun hasPinyinComposition(): Boolean =
        pickedSegmentTexts.isNotEmpty() || RimeEngine.compositionText.isNotEmpty()

    /**
     * 本次「按下删除键」开始时是否还有拼音编码。
     *
     * 长按连发必须守住这条边界：有编码时整段按住期间只删编码，删空就停手，不能顺势
     * 把已上屏的文字一路删下去；抬手后重新按下（此时已无编码）才删上屏内容。
     */
    private var deleteKeepsCommittedText = false

    /** 软键盘按键按下回调（[KeyGrid] 的 onKeyFeedback 里调用），登记删除键的本次按压语义 */
    fun onKeyPressed(keyCode: Int) {
        if (keyCode == KeyEvent.KEYCODE_DEL) deleteKeepsCommittedText = hasPinyinComposition()
    }

    /** 抬手/手势取消：一次按压的语义到此结束，下次按下重新判定 */
    fun onKeyReleased() {
        deleteKeepsCommittedText = false
    }

    fun chooseAndUpdate(candId: Int = controller.activeCandidateId()) {
        // 分段组句还没结束（用户可能已经把全屏候选页收起来了）：候选栏/候选页上的这一下
        // 就是「确认当前段」，已确认的字必须跟着一起上屏。否则只提交这一条候选，
        // 「我看」既没上屏、也留不成组合串，只能在候选栏上挂一个点不掉的胶囊。
        if (RimeEngine.isSegmentFocused) {
            val total = segmentTotal.takeIf { it > 0 } ?: RimeEngine.focusedSegmentCount
            pickSegmentCandidate(candId, RimeEngine.focusedSegmentIndex, total)
            return
        }
        val candidate = DictDecoder.getCandidate(candId)
        if (candidate != null && candidate.comment == RimeEngine.EXPR_TAG) {
            // 等式候选自带完整「等式=结果」文本：先删掉光标前的等式原文再整体提交
            if (!controller.state.value.addPhrases) {
                CustomEngine.parseExpressionAtEnd(service.getTextBeforeCursor(500))?.length
                    ?.takeIf { it > 0 }?.let { service.deleteSurroundingText(it) }
            }
            commitDecInfoText(candidate.text)
            resetToIdleState()
            predictAssociationAfterCommit()
        } else if (candidate != null &&
            (candidate.comment == RimeEngine.CUSTOM_TAG || candidate.comment == RimeEngine.GREEK_TAG)
        ) {
            // 自定义候选（用户词库 / 希腊字母）不经过 Rime 选词，提交后清掉组合串，
            // 否则拼音会留在输入框上方
            commitDecInfoText(candidate.text)
            resetToIdleState()
            predictAssociationAfterCommit()
        } else {
            // 记录发起选词时是不是在全屏候选页：在候选页里把整串选完，就该回键盘，
            // 不能因为「上屏后还有联想词」就继续赖在候选页（联想词本身显示在候选栏里）。
            val fromCandidatePanel =
                KeyboardManager.instance.currentType == KeyboardManager.KeyboardType.CANDIDATES
            val choice = DictDecoder.chooseDecodingCandidate(candId)
            // 用引擎的实时组合串判断「这次输入结束了没有」：上屏后联想词会让候选非空，
            // 但组合串一定是空的，只有它才代表输入真的结束了。
            val composing = RimeEngine.compositionText.isNotEmpty()
            if (DictDecoder.isCandidatesEmpty || DictDecoder.isAssociate) {
                KeyboardManager.instance.switchKeyboard()
                commitDecInfoText(choice)
                predictAssociationAfterCommit()
            } else if (fromCandidatePanel && !composing) {
                KeyboardManager.instance.switchKeyboard()
                commitDecInfoText(choice)
                predictAssociationAfterCommit()
            } else {
                if (!DictDecoder.isCandidatesEmpty) {
                    if (InputModeSwitcher.isEnglish) service.setComposingText(DictDecoder.composingStrForCommit)
                } else {
                    resetToIdleState()
                }
            }
        }
        controller.resetActiveCandidate()
    }

    /**
     * 上屏后立刻取联想词（不等宿主回调）。
     *
     * 联想词原本只由 [onUpdateSelection] 那条路径生成，而那是**跨进程往返**：`commitText`
     * 之后、宿主回调回来之前的这一两帧里，候选快照是空的（候选栏三态互斥：没有候选、
     * 也没有组合串就退回常驻工具栏）。工具栏内容与候选胶囊的左内边距并不相同
     * （工具图标 4dp 起排、胶囊 10dp 起排），于是视觉上就是「候选先出现在偏左的位置，
     * 然后突然闪到靠右一点」。
     *
     * 选中候选后光标已经移动，这里直接读光标前文本，在**同一个事件**里把联想词发出去：
     * StateFlow 会合并掉前面那份空快照，候选栏就不会渲染中间态；宿主回调随后再来一次
     * 是幂等的（内容相同、[DictDecoder.CandidateState.generation] 变了但候选文本不变，
     * 胶囊的 key 不变，视图不会重建）。
     */
    private fun predictAssociationAfterCommit() {
        if (!chinesePrediction || !InputModeSwitcher.isChinese) return
        // 常用语编辑栏里 commit 只是往输入框追加文字，没有「已上屏内容」可联想
        if (controller.state.value.addPhrases) return
        // 还有组合串 = 这次只是消费掉一段，候选仍属于剩下的编码，不能拿已上屏文本联想
        if (RimeEngine.compositionText.isNotEmpty()) return
        val textBeforeCursor = service.getTextBeforeCursor(10)
        if (textBeforeCursor.isBlank()) return
        InputDispatcher.predictAssociationWords(textBeforeCursor)
        updateCandidate()
    }

    fun updateCandidate() {
        // 引擎侧的分段态已经结束（继续按键 / 删键后引擎退出分段）：本地攒的字和
        // 「已选 + 当前段」的胶囊也必须一起丢掉，否则它会一直挂在候选栏上。
        if (pickedSegmentTexts.isNotEmpty() && !RimeEngine.isSegmentFocused) clearSegmentGroup()
        DictDecoder.updateDecodingCandidate()

        if (!DictDecoder.isCandidatesEmpty || DictDecoder.isComposing) {
            // 候选/前缀变化已通过 StateFlow 通知 UI
        } else {
            resetToIdleState()
        }
        if (InputModeSwitcher.isEnglish) service.setComposingText(DictDecoder.composingStrForCommit)
    }

    /** 候选页左侧拼音栏：替换组合串首音节（原始/纠错读音）后重新组句 */
    fun replaceFirstSyllable(syllable: String) {
        RimeEngine.replaceFirstSyllable(syllable)
        updateCandidate()
    }

    /**
     * 候选页读音栏：把所选读音写回引擎重新检索（用户敲的键先记账，可还原）。
     *
     * @return false 表示当前形态不支持改写（九键 / 双拼 / 分段态），调用方应退回纯过滤
     */
    fun applyReadingSyllable(syllable: String): Boolean {
        val ok = RimeEngine.applyReadingSyllable(syllable)
        updateCandidate()
        return ok
    }

    /** 候选页读音栏：取消读音试打，把用户敲的键还回去 */
    fun clearReadingSyllable() {
        if (RimeEngine.clearReadingSyllable()) updateCandidate()
    }

    /** 分段组句状态：段下标 → 已确认的字；[segmentTotal] 是本次分段的总段数 */
    private val pickedSegmentTexts = LinkedHashMap<Int, String>()
    private var segmentTotal = 0

    /**
     * 候选页读音栏：分段选择。
     *
     * 点某一段 = 只看这一段的字/词（`b'si` 里的 `b` → 被/吧/把/比/不…）。
     * 选中的字先攒着不上屏，所有段选完再一次性拼接上屏。
     */
    fun focusSegment(segments: List<String>, index: Int) {
        if (pickedSegmentTexts.isEmpty()) segmentTotal = segments.size
        if (RimeEngine.focusSegment(segments, index)) updateCandidate()
    }

    /** 已确认的段（UI 用来把「已消费」的段置灰/隐藏） */
    val pickedSegmentIndexes: Set<Int>
        get() = pickedSegmentTexts.keys

    /** 已确认的字按段序拼起来，用于在候选栏/面板里回显"已选了什么" */
    val pickedSegmentText: String
        get() = (0 until segmentTotal).joinToString("") { pickedSegmentTexts[it] ?: "" }

    /**
     * 分段组句：确认当前段选中的字，切到下一个未选的段；全部选完后拼接上屏并回键盘。
     *
     * 段下标与总段数都由调用方传入（不在引擎里反推）：分段态下组合串只剩当前这一段，
     * 从 preedit 反推会得到错误的分段数，上一版就是这么把「布丝」拼成空串的。
     *
     * @return 下一个要选的段下标，-1 表示已全部选完（此时已上屏）
     */
    fun pickSegmentCandidate(
        candId: Int,
        segmentIndex: Int,
        total: Int,
    ): Int {
        val text = DictDecoder.getCandidate(candId)?.text ?: return segmentIndex
        // 同一轮分段里各入口给的总段数都相同，取大值兜底：候选页收起后从候选栏点选时，
        // 本地可能还一条已确认段都没有。
        if (total > 0) segmentTotal = maxOf(segmentTotal, total)
        pickedSegmentTexts[segmentIndex] = text
        // 整串在这里就地按段序拼「已确认的段 + 本次这条」：不能靠调用方传进来，
        // 候选页收起之后它那份分段状态就没了，而这份 map 一直在。
        // 同时先存进局部变量——下面的 updateCandidate()/resetToIdleState() 会清 map。
        val joinedText = (0 until segmentTotal)
            .joinToString("") { pickedSegmentTexts[it] ?: "" }
        DictDecoder.pickedSegmentText = joinedText
        val next = (0 until segmentTotal).firstOrNull { it !in pickedSegmentTexts }
        // 整串交给引擎：最后一段选完时它要按「原始编码 → 整串」记进用户词库
        RimeEngine.pickSegmentCandidate(candId, next ?: -1, joinedText)
        updateCandidate()
        if (next != null) {
            controller.resetActiveCandidate()
            return next
        }
        // 所有段都选完：用拼好的整串上屏，不做逐段提交
        // （中途的 commit 会触发联想、把后面几段的候选冲掉）。
        pickedSegmentTexts.clear()
        segmentTotal = 0
        commitDecInfoText(joinedText)
        KeyboardManager.instance.switchKeyboard()
        resetToIdleState()
        controller.resetActiveCandidate()
        return -1
    }

    /** 清空分段组句状态（取消输入 / 切键盘时调用） */
    private fun clearSegmentGroup() {
        pickedSegmentTexts.clear()
        segmentTotal = 0
        DictDecoder.pickedSegmentText = ""
    }

    /** 退出分段选择，恢复原始分段输入 */
    fun restoreSegments() {
        clearSegmentGroup()
        RimeEngine.restoreSegments()
        updateCandidate()
    }

    fun updateActiveCandidateNo(keyCode: Int): Int {
        var active = controller.state.value.activeCandidate
        if (!DictDecoder.isCandidatesEmpty) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> if (--active <= 0) active = 0
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (++active > DictDecoder.candidateSize) active = DictDecoder.candidateSize
            }
            controller.setActiveCandidate(active)
        }
        return active
    }

    private fun resetCandidateWindow() {
        // 引擎与候选快照由 InputDispatcher 统一按「先清引擎、再发快照」的顺序重置；
        // 这里不再单独调 DictDecoder.reset()，避免多发一份仍带原组合串的中间快照。
        InputDispatcher.reset()
    }

    // ---------------------------------------------------------------------
    // 提交与宿主交互
    // ---------------------------------------------------------------------

    fun selectPrefix(position: Int) {
        InputDispatcher.selectPrefix(position)
        updateCandidate()
    }

    fun showSymbols(symbols: Array<String>) {
        showCandidates(symbols.map { CandidateListItem("📋", it) }.toTypedArray())
    }

    /**
     * 刚刚复制的内容作为候选展示。
     *
     * 和 [showSymbols] 的区别只在于打上「来自剪贴板」的来源标记：
     * 这类候选没有编码可看，候选栏会在上方气泡里标一句「来自剪贴板：」，
     * 否则用户只会看到一条凭空出现的候选。
     */
    fun showClipboardSuggestion(content: String) {
        DictDecoder.cacheCandidates(
            arrayOf(CandidateListItem("📋", content)),
            associate = true,
            clipboardSuggestion = true,
        )
    }

    fun showCandidates(items: Array<CandidateListItem>) {
        DictDecoder.cacheCandidates(items, true)
    }

    private fun sendKeyEvent(keyCode: Int) {
        if (controller.state.value.addPhrases) {
            controller.editPhrase(keyCode)
            return
        }
        when (keyCode) {
            KeyEvent.KEYCODE_ENTER -> service.sendEnterKeyEvent()
            in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_RIGHT -> {
                // 单行编辑框上下没有可移动的行：吞掉，事件交给宿主会走焦点导航，
                // 焦点移出输入框就会把当前编辑界面切走（表现为「按上下键退出」）
                val vertical = keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                if (!vertical) {
                    service.sendCombinationKeyEvents(keyCode, shift = hasSelection)
                    if (hasSelectionAll) hasSelectionAll = false
                } else if (service.isMultilineEditor && verticalCanMoveInEditor(keyCode)) {
                    service.sendCombinationKeyEvents(keyCode, shift = hasSelection)
                    if (hasSelectionAll) hasSelectionAll = false
                }
            }
            else -> service.sendCombinationKeyEvents(keyCode)
        }
    }

    /**
     * 多行编辑框内垂直方向键是否还在编辑框内移动：
     * 光标之后（按下键）/之前（按上键）还有换行才可能换行移动。
     * 已在最后一行按「下」会把焦点移出输入框，宿主重启输入会话，
     * `onStartInputView` 里的 switchKeyboard 就会把编辑面板切回输入键盘——
     * 所以边界上直接吞掉事件，面板保持不动。
     */
    private fun verticalCanMoveInEditor(keyCode: Int): Boolean {
        return if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            service.getTextAfterCursor(10000).contains('\n')
        } else {
            service.getTextBeforeCursor(10000).contains('\n')
        }
    }

    fun setComposingText(text: CharSequence) {
        if (!controller.state.value.addPhrases) service.setComposingText(text)
    }

    fun commitText(text: String) {
        if (controller.state.value.addPhrases) {
            controller.appendPhraseText(text)
        } else {
            // 花体字转换与混输空格都在 ImeService.commitText 里做（上屏唯一出口），这里保持原文
            service.commitText(text)
        }
    }

    fun commitPairSymbol(text: String) {
        if (controller.state.value.addPhrases) {
            controller.appendPhraseText(text)
        } else {
            if (appPrefs.input.symbolPairInput.getValue()) {
                service.commitText(text + SymbolPreset[text]!!)
                mainHandler.postDelayed({ service.sendCombinationKeyEvents(KeyEvent.KEYCODE_DPAD_LEFT) }, 300)
            } else {
                service.commitText(text)
            }
        }
    }

    fun commitTextEditMenu(id: Int?) {
        id?.let { service.commitTextEditMenu(it) }
    }

    fun performEditorAction(editorAction: Int) = service.performEditorAction(editorAction)

    fun commitDecInfoText(resultText: String?) {
        resultText ?: return
        if (controller.state.value.addPhrases) {
            controller.appendPhraseText(resultText)
        } else {
            RimeEngine.clearPendingLearning()
            service.commitText(resultText)
            if (InputModeSwitcher.isEnglish) {
                service.finishComposingText()
                if (appPrefs.input.abcSpaceAuto.getValue()) service.commitText(" ")
                resetToIdleState()
            }
        }
    }

    // ---------------------------------------------------------------------
    // 光标与联想
    // ---------------------------------------------------------------------

    fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesEnd: Int,
    ) {
        selStart = newSelStart
        selEnd = newSelEnd
        if (InputModeSwitcher.isEnglish) {
            if (oldCandidatesEnd == candidatesEnd) {
                service.finishComposingText()
                resetToIdleState()
            }
            oldCandidatesEnd = candidatesEnd
            updateQwertyExpressionCandidates()
            return
        }
        if (oldSelStart == newSelStart) return
        when {
            InputModeSwitcher.isNumberSkb -> {
                val textBeforeCursor = service.getTextBeforeCursor(500)
                if (textBeforeCursor.isBlank()) resetCandidateWindow()
                else CustomEngine.parseExpressionAtEnd(textBeforeCursor).let {
                    showCandidates(CustomEngine.expressionCalculator(textBeforeCursor, it))
                }
            }
            InputModeSwitcher.isQwert && showQwertyExpressionCandidates() -> Unit
            chinesePrediction && InputModeSwitcher.isChinese -> {
                val textBeforeCursor = service.getTextBeforeCursor(10)
                when {
                    // 还有组合串（分段选择剩下的段）：绝不能拿已上屏的文本去联想，
                    // 那会把剩余段的候选整个覆盖掉（选完「布」后看不到 si 的字）。
                    RimeEngine.compositionText.isNotEmpty() -> updateCandidate()

                    textBeforeCursor.isBlank() -> resetCandidateWindow()

                    else -> {
                        InputDispatcher.predictAssociationWords(textBeforeCursor)
                        updateCandidate()
                        // 联想词只属于键盘候选栏：输入已经结束还停在候选页就直接收回。
                        if (KeyboardManager.instance.currentType ==
                            KeyboardManager.KeyboardType.CANDIDATES
                        ) {
                            KeyboardManager.instance.switchKeyboard()
                        }
                    }
                }
            }
            else -> resetCandidateWindow()
        }
    }

    /** 供候选栏「点击间隔区上屏正上方候选」使用 */
    fun candidateAt(index: Int): CandidateListItem? = DictDecoder.getCandidate(index)

    /**
     * 26 键（QWERTY）下的等式计算：复用数字盘的 `parseExpressionAtEnd` /
     * `expressionCalculator`，对光标前已上屏的等式（如 "1+3"）给出计算候选。
     */
    private var qwertyExpressionShown = false

    private fun showQwertyExpressionCandidates(): Boolean {
        val textBeforeCursor = service.getTextBeforeCursor(500)
        val expression = CustomEngine.parseExpressionAtEnd(textBeforeCursor)
        if (!CustomEngine.isComputableExpression(expression)) return false
        showCandidates(CustomEngine.expressionCalculator(textBeforeCursor, expression))
        qwertyExpressionShown = true
        return true
    }

    /** 英文模式：等式消失后清掉残留的计算候选，其余情况不打扰原有候选 */
    private fun updateQwertyExpressionCandidates() {
        if (!InputModeSwitcher.isQwert) return
        if (!showQwertyExpressionCandidates() && qwertyExpressionShown) {
            qwertyExpressionShown = false
            resetCandidateWindow()
        }
    }

    /** 手动折行：给「全面屏键盘优化」的换行键复用 */
    fun commitNewLine() = commitText("\n")

    /** 字符串资源便捷入口（避免 UI 层到处 import R） */
    fun string(resId: Int): String = service.getString(resId)

    companion object {
        /** 与 `R.string.handwriting_unavailable_title` 对应的提示，手写不可用时使用 */
        const val HANDWRITING_UNAVAILABLE_HINT = R.string.handwriting_unavailable_title
    }
}
