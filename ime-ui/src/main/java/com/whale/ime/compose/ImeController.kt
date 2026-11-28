package com.whale.ime.compose

import com.whale.ime.ui.R
import com.whale.ime.boot.Launcher
import com.whale.ime.textstyle.TextStyleMode
import com.whale.ime.textstyle.textStyle
import com.whale.ime.theme.ThemeManager
import com.whale.ime.storage.WhaleDatabase
import com.whale.ime.storage.entity.Phrase
import com.whale.ime.keyboard.model.SoftKey
import com.whale.ime.keyboard.state.KeyboardManager
import com.whale.ime.vendor.pinyin4j.PinyinHelper
import com.whale.ime.keyboard.layout.InputModeSwitcher
import com.whale.ime.phrase.PhraseQuickCodeManager
import com.whale.ime.preference.AppPrefs
import com.whale.ime.preference.model.KeyboardMenuMode
import com.whale.ime.preference.model.SymbolMode
import com.whale.ime.ui.service.ImeService
import com.whale.ime.dispatch.InputDispatcher
import com.whale.ime.keyboard.metrics.Environment
import com.whale.ime.ui.util.AppUtil
import com.whale.ime.keyboard.builder.KeyboardBuilder
import com.whale.ime.engine.schema.T9PinYinUtils
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.whale.ime.config.SchemaIds
import com.whale.ime.clipboard.ClipboardExpiry

/** SETTINGS 容器内的三种页面 */
enum class SettingsPage {
    /** 默认：功能菜单宫格 */
    Menu,

    /** 输入方式选择（T9 / 26 键 / 手写 / 双拼 / 17 键 / 笔画） */
    SelectInputMode,

    /** 菜单项自定义（拖拽排序 + 是否常驻候选栏） */
    Customize,
}

/**
 * Compose 键盘的全部 UI 状态。
 *
 * 这里只放「渲染需要、但不属于引擎」的东西：候选列表本身在
 * `DictDecoder.candidateState`，当前键盘类型在 `KeyboardManager.keyboardType`。
 */
data class ImeUiState(
    val settingsPage: SettingsPage = SettingsPage.Menu,
    val symbolMode: SymbolMode = SymbolMode.Symbol,
    val symbolPage: Int = 0,
    val clipboardMode: KeyboardMenuMode = KeyboardMenuMode.ClipBoard,
    /** 0 = 无激活项；n>0 表示激活第 n 个候选（1-based） */
    val activeCandidate: Int = 0,
    val addPhrases: Boolean = false,
    val phraseContent: String = "",
    val phraseQuickCode: String = "",
    /** 编辑点（caret）在正文中的位置；点按正文或换行后按它重绘光标 */
    val phraseContentCursor: Int = 0,
    /** 编辑点在快捷码中的位置 */
    val phraseQuickCodeCursor: Int = 0,
    val phraseEditingQuickCode: Boolean = false,
    val phraseExtra: Phrase? = null,
    /** 编辑键盘浮层（调键盘高度 / 与屏幕底部的距离）是否展开 */
    val keyboardEditorVisible: Boolean = false,
    /** 编辑键盘拖动中的目标尺寸，null = 没有在拖动（详见 [KeyboardEditorDraft]） */
    val keyboardEditorDraft: KeyboardEditorDraft? = null,
    /** 单手模式左侧还是右侧（跟随偏好，但切换需要立即生效） */
    val oneHandedLeft: Boolean = false,
    /** 上一轮的编辑内容是否已提交（防止 e.g. 空内容） */
    val phraseErrorMessage: Int? = null,
    /** 剪贴板内容变化计数：面板据此重新分页加载 */
    val clipboardRevision: Int = 0,
    /** 剪贴板/常用语面板已锁定：选词上屏后不切回输入键盘（工具栏锁头按钮） */
    val clipboardLocked: Boolean = false,
    /** 「清空剪贴板历史」的二次确认层是否展开 */
    val clearClipboardConfirm: Boolean = false,
)

/**
 * 编辑键盘的拖动预览：非空表示正在拖动，字段是「松手后」的目标值。
 *
 * 拖动期间只更新这个预览（画一条目标轮廓），松手才真正重建键盘并重设 IME 窗口高度：
 * 每帧改窗口高度会让宿主应用跟着重排，肉眼可见地抖。
 */
data class KeyboardEditorDraft(
    /** 目标键盘高度比例，null = 不改 */
    val heightRatio: Float? = null,
    /** 目标底部留白（dp），null = 不改 */
    val bottomGapDp: Int? = null,
)

/**
 * Compose 键盘的「控制器」：状态 + 菜单动作 + 短语编辑器。
 *
 * 生命周期跟随 [ImeComposeRoot]，一个输入法进程内只有一个实例。
 * 之所以不放进 ViewModel：它强依赖 [ImeService]（提交文本/取光标上下文），
 * 而服务本身的生命周期已经长于组合，再套一层 ViewModel 只会让依赖关系更绕。
 */
class ImeController(val service: ImeService) {

    private val appPrefs = AppPrefs.getInstance()

    private val _state = MutableStateFlow(
        ImeUiState(oneHandedLeft = appPrefs.keyboardSetting.oneHandedMod.getValue() ==
                com.whale.ime.preference.model.KeyboardOneHandedMod.LEFT)
    )
    val state: StateFlow<ImeUiState> = _state.asStateFlow()

    /**
     * 键帽「可切换状态」的版本号：shift 大小写、enter 动作图标、选区高亮变化时 +1。
     * `KeyGrid` 以此作为绘制的 key，保证 `SoftKeyToggle.stateId` 变更后缓存失效。
     */
    private val _keyboardRevision = MutableStateFlow(0)
    val keyboardRevision: StateFlow<Int> = _keyboardRevision.asStateFlow()

    /**
     * 导航栏高度（insets 实时值）。与 [Environment.systemNavbarWindowsBottom]
     * 同步更新，但这里是 Compose 状态：底部栏/占位区的组合读到它才会随 insets 变化重组。
     */
    var navbarInset: Int by mutableIntStateOf(0)

    /**
     * 悬浮模式键盘卡片的实测窗口坐标（onGloballyPositioned 上报）。
     * 悬浮态的 touchableRegion 直接用它的真实位置，而不是按偏好值反推——
     * 偏好值和实际渲染一旦有出入，键盘边缘（底行、拖动条）就会点不到。
     */
    var floatCardBounds: android.graphics.Rect? = null

    /**
     * 悬浮键盘拖动中的实时偏移（px，右/下为正方向的反向值）。
     * 拖动条拖动时即时改写，让卡片跟手；松手才写回偏好并清零。
     */
    var floatDragX: Int by mutableIntStateOf(0)
    var floatDragY: Int by mutableIntStateOf(0)

    /** IME 窗口实测高度（px）。悬浮拖拽的边界钳位以真实窗口为准，不能假设全屏 */
    var floatWindowHeight: Int = 0

    val dispatcher = KeyDispatcher(this)

    val keyboardType: StateFlow<KeyboardManager.KeyboardType?> = KeyboardManager.instance.keyboardType

    fun update(block: (ImeUiState) -> ImeUiState) {
        _state.value = block(_state.value)
    }

    fun bumpKeyboardRevision() {
        _keyboardRevision.value = _keyboardRevision.value + 1
    }

    /** 剪贴板/短语库内容变化：让面板重新分页加载 */
    fun bumpClipboardRevision() = update { it.copy(clipboardRevision = it.clipboardRevision + 1) }

    // ------------------------------------------------------------------
    // 候选
    // ------------------------------------------------------------------

    fun resetActiveCandidate() = update { it.copy(activeCandidate = 0) }

    fun setActiveCandidate(index: Int) = update { it.copy(activeCandidate = index.coerceAtLeast(0)) }

    fun activeCandidateId(): Int = _state.value.activeCandidate.let { if (it > 0) it - 1 else 0 }

    // ------------------------------------------------------------------
    // 键盘类型
    // ------------------------------------------------------------------

    val currentKeyboardType: KeyboardManager.KeyboardType?
        get() = KeyboardManager.instance.keyboardType.value

    /** 当前是否处于输入键盘，不依赖 View 容器 */
    val isInputKeyboard: Boolean
        get() = when (currentKeyboardType) {
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

    private fun switchKeyboard(type: KeyboardManager.KeyboardType) {
        KeyboardManager.instance.switchKeyboard(type)
    }

    /**
     * 回到「输入键盘」（按当前输入模式重新计算类型）。
     *
     * 编辑（光标）键盘必须单独还原输入模式：它不像符号/剪贴板那样只是换 UI，
     * 而是把 mInputMode 改成了 TEXTEDIT，只调 switchKeyboard() 会原地打转。
     */
    fun switchToInputKeyboard() {
        if (!InputModeSwitcher.leaveTextEditMode()) {
            KeyboardManager.instance.switchKeyboard()
        }
        bumpKeyboardRevision()
    }

    /** 关闭当前面板（符号/剪贴板/功能宫格/编辑键盘）回到输入键盘：候选栏右侧「×」 */
    fun closePanel() {
        // 关面板等于放弃这次清空：确认层不能留到下次打开面板时又冒出来
        if (_state.value.clearClipboardConfirm) {
            update { it.copy(clearClipboardConfirm = false) }
        }
        switchToInputKeyboard()
    }

    /**
     * 打开 SETTINGS 容器的某一页；已经停在同一页时再次触发 = 收回输入键盘。
     *
     * 工具栏上的功能键是「开关」而不是「单向入口」：用户点开后再点同一个键应当回到
     * 输入界面，否则只能靠左上的返回箭头退出。符号面板 / 剪贴板面板的
     * [openSymbolPanel] / [openClipboardPanel] 用的是同一套规则。
     */
    fun openSettingsPage(page: SettingsPage) {
        val samePage = currentKeyboardType == KeyboardManager.KeyboardType.SETTINGS &&
                _state.value.settingsPage == page
        if (samePage) {
            switchToInputKeyboard()
        } else {
            update { it.copy(settingsPage = page) }
            KeyboardManager.instance.switchKeyboard(KeyboardManager.KeyboardType.SETTINGS)
        }
    }

    // ------------------------------------------------------------------
    // 符号面板
    // ------------------------------------------------------------------

    fun openSymbolPanel(mode: SymbolMode) {
        val samePanel = currentKeyboardType == KeyboardManager.KeyboardType.SYMBOL && _state.value.symbolMode == mode
        if (samePanel) {
            switchToInputKeyboard()
        } else {
            update { it.copy(symbolMode = mode, symbolPage = 0) }
            switchKeyboard(KeyboardManager.KeyboardType.SYMBOL)
        }
    }

    fun setSymbolPage(page: Int) = update { it.copy(symbolPage = page) }

    // ------------------------------------------------------------------
    // 剪贴板 / 短语
    // ------------------------------------------------------------------

    fun openClipboardPanel(mode: KeyboardMenuMode) {
        val samePanel = currentKeyboardType == KeyboardManager.KeyboardType.ClipBoard && _state.value.clipboardMode == mode
        if (samePanel) {
            switchToInputKeyboard()
        } else {
            // 换面板时两个临时状态都作废：锁定只对「这一次打开的面板」有效，
            // 未确认的清空请求也不该跟着带到下一次
            update { it.copy(clipboardMode = mode, clipboardLocked = false, clearClipboardConfirm = false) }
            switchKeyboard(KeyboardManager.KeyboardType.ClipBoard)
        }
    }

    /** 工具栏锁头：锁定后选词上屏不切回输入键盘，方便连续上屏多条 */
    fun toggleClipboardLock() = update { it.copy(clipboardLocked = !it.clipboardLocked) }

    /**
     * 点击当时的锁定状态。
     *
     * 上屏的点击回调可能持有旧一帧的 [ImeUiState]，所以这里现读；界面上的高亮请读 `uiState.clipboardLocked`。
     */
    val isClipboardLocked: Boolean get() = _state.value.clipboardLocked

    /** 工具栏垃圾桶：先弹二次确认，确认后才真正清空 */
    fun requestClearClipboard() = update { it.copy(clearClipboardConfirm = true) }

    fun dismissClearClipboardConfirm() = update { it.copy(clearClipboardConfirm = false) }

    /** 确认清空：删掉全部剪贴板历史（含置顶），并让面板重新分页加载 */
    fun clearClipboardHistory() {
        runCatching { WhaleDatabase.instance.clipboardDao().deleteAll() }
        // 历史已经空表：到期边界一个都不剩，让定时器按当前设置重排（内部会取消旧 Job）
        runCatching { ClipboardExpiry.refresh() }
        update {
            it.copy(
                clearClipboardConfirm = false,
                clipboardRevision = it.clipboardRevision + 1,
            )
        }
    }

    // ------------------------------------------------------------------
    // 菜单动作
    // ------------------------------------------------------------------

    fun onMenuAction(mode: KeyboardMenuMode) {
        when (mode) {
            KeyboardMenuMode.Emojicon, KeyboardMenuMode.Emoticon -> {
                openSymbolPanel(if (mode == KeyboardMenuMode.Emoticon) SymbolMode.Emoticon else SymbolMode.Emojicon)
            }

            KeyboardMenuMode.SwitchKeyboard -> openSettingsPage(SettingsPage.SelectInputMode)

            // 编辑键盘同样是开关：已展开时再点收回
            KeyboardMenuMode.KeyboardHeight -> toggleKeyboardEditor()

            KeyboardMenuMode.DarkTheme -> {
                val theme = (if (ThemeManager.activeTheme.isDark) ThemeManager.prefs.lightModeTheme
                else ThemeManager.prefs.darkModeTheme).getValue()
                ThemeManager.setNormalModeTheme(theme)
                KeyboardManager.instance.clearKeyboard()
                switchToInputKeyboard()
            }

            KeyboardMenuMode.JianFan -> {
                val chineseFanTi = appPrefs.input.chineseFanTi.getValue()
                appPrefs.input.chineseFanTi.setValue(!chineseFanTi)
                InputDispatcher.nativeUpdateImeOption()
                switchToInputKeyboard()
            }

            KeyboardMenuMode.LockEnglish -> {
                val locked = appPrefs.keyboardSetting.keyboardLockEnglish.getValue()
                appPrefs.keyboardSetting.keyboardLockEnglish.setValue(!locked)
                switchToInputKeyboard()
            }

            KeyboardMenuMode.SymbolShow -> {
                val symbol = appPrefs.keyboardSetting.keyboardSymbol.getValue()
                appPrefs.keyboardSetting.keyboardSymbol.setValue(!symbol)
                KeyboardManager.instance.clearKeyboard()
                switchToInputKeyboard()
            }

            KeyboardMenuMode.EmojiInput -> {
                val emojiInput = appPrefs.input.emojiInput.getValue()
                appPrefs.input.emojiInput.setValue(!emojiInput)
                InputDispatcher.nativeUpdateImeOption()
                switchToInputKeyboard()
            }

            KeyboardMenuMode.Handwriting -> AppUtil.launchSettingsToHandwriting(Launcher.instance.context)

            KeyboardMenuMode.Settings -> AppUtil.launchSettings(Launcher.instance.context)

            KeyboardMenuMode.OneHanded -> {
                val switch = appPrefs.keyboardSetting.oneHandedModSwitch
                switch.setValue(!switch.getValue())
                refreshEnvironment()
                update {
                    it.copy(
                        oneHandedLeft = appPrefs.keyboardSetting.oneHandedMod.getValue() ==
                                com.whale.ime.preference.model.KeyboardOneHandedMod.LEFT
                    )
                }
            }

            KeyboardMenuMode.FlowerTypeface -> {
                textStyle =
                    if (textStyle == TextStyleMode.Disabled) TextStyleMode.Mars
                    else TextStyleMode.Disabled
                switchToInputKeyboard()
            }

            KeyboardMenuMode.FloatKeyboard -> {
                Environment.instance.keyboardModeFloat = !Environment.instance.keyboardModeFloat
                refreshEnvironment()
                // 窗口高度随悬浮/非悬浮完全不同（全屏 vs skbAreaHeight），必须重设
                applyRootPreferredHeight()
            }

            KeyboardMenuMode.ClipBoard, KeyboardMenuMode.Phrases -> openClipboardPanel(mode)

            // 只在剪贴板列表态出现：清空的是剪贴板历史，不是常用语
            KeyboardMenuMode.ClearClipBoard -> requestClearClipboard()

            KeyboardMenuMode.Custom -> openSettingsPage(SettingsPage.Customize)

            KeyboardMenuMode.CloseSKB -> service.requestHideSelf(0)

            KeyboardMenuMode.SettingsMenu -> {
                if (isInputKeyboard) {
                    update { it.copy(settingsPage = SettingsPage.Menu) }
                    switchKeyboard(KeyboardManager.KeyboardType.SETTINGS)
                } else {
                    switchToInputKeyboard()
                }
            }

            // 展开全屏候选同样是开关：已经展开时再点收回输入键盘。
            // （候选栏右侧箭头在候选态有自己的分支，不会走到这里）
            KeyboardMenuMode.CandidatesMore -> if (currentKeyboardType == KeyboardManager.KeyboardType.CANDIDATES) {
                switchToInputKeyboard()
            } else {
                switchKeyboard(KeyboardManager.KeyboardType.CANDIDATES)
            }

            KeyboardMenuMode.LockClipBoard -> toggleClipboardLock()

            KeyboardMenuMode.TextEdit -> InputModeSwitcher.switchModeForUserKey(
                if (InputModeSwitcher.isTextEditSkb) InputModeSwitcher.USER_KEYCODE_RETURN
                else InputModeSwitcher.USER_KEYCODE_TEXTEDIT
            )

            KeyboardMenuMode.AddPhrases -> startAddPhrases(extra = null)

            else -> {}
        }
    }

    /** 进入添加短语流程 */
    fun startAddPhrases(extra: Phrase?) {
        update {
            val content = extra?.content ?: ""
            val code = extra?.qwerty ?: ""
            it.copy(
                addPhrases = true,
                phraseContent = content,
                phraseQuickCode = code,
                // 打开编辑栏时光标默认在末尾（继续追加），点按正文/快捷码可以再挪
                phraseContentCursor = content.length,
                phraseQuickCodeCursor = code.length,
                phraseEditingQuickCode = false,
                phraseExtra = extra,
                phraseErrorMessage = null,
            )
        }
        if (extra != null) {
            WhaleDatabase.instance.phraseDao().deleteByContent(extra.content)
            // 编辑中的旧行已经删掉，候选索引不能还留着它
            PhraseQuickCodeManager.reload()
        }
        KeyboardManager.instance.switchKeyboard(InputModeSwitcher.skbImeLayout)
    }

    fun clearPhraseContent() = update {
        it.copy(
            phraseContent = "",
            phraseQuickCode = "",
            phraseContentCursor = 0,
            phraseQuickCodeCursor = 0,
        )
    }

    /**
     * 切换正在编辑的字段；光标挪到该字段末尾。
     *
     * 点按具体的字由 [setPhraseCursor] 处理（会把编辑点落在点到的位置）。
     */
    fun setPhraseEditingQuickCode(editing: Boolean) = update {
        if (editing) it.copy(
            phraseEditingQuickCode = true,
            phraseQuickCodeCursor = it.phraseQuickCode.length,
        ) else it.copy(
            phraseEditingQuickCode = false,
            phraseContentCursor = it.phraseContent.length,
        )
    }

    /** 点按常用语正文 / 快捷码：切换编辑字段并把编辑点移到 [index] */
    fun setPhraseCursor(index: Int, quickCode: Boolean) = update {
        if (quickCode) {
            it.copy(
                phraseEditingQuickCode = true,
                phraseQuickCodeCursor = index.coerceIn(0, it.phraseQuickCode.length),
            )
        } else {
            it.copy(
                phraseEditingQuickCode = false,
                phraseContentCursor = index.coerceIn(0, it.phraseContent.length),
            )
        }
    }

    /** 短语编辑框收到一个字符/按键 */
    fun editPhrase(keyCode: Int) {
        when (keyCode) {
            android.view.KeyEvent.KEYCODE_DEL -> {
                update {
                    if (it.phraseEditingQuickCode) {
                        val pos = it.phraseQuickCodeCursor.coerceIn(0, it.phraseQuickCode.length)
                        if (pos == 0) it
                        else it.copy(
                            phraseQuickCode = it.phraseQuickCode.removeRange(pos - 1, pos),
                            phraseQuickCodeCursor = pos - 1,
                        )
                    } else {
                        val pos = it.phraseContentCursor.coerceIn(0, it.phraseContent.length)
                        if (pos == 0) it
                        else it.copy(
                            phraseContent = it.phraseContent.removeRange(pos - 1, pos),
                            phraseContentCursor = pos - 1,
                        )
                    }
                }
            }
            android.view.KeyEvent.KEYCODE_ENTER -> finishAddPhrases()
            else -> {
                val ch = android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, keyCode).unicodeChar.toChar()
                if (ch != Char.MIN_VALUE) appendPhraseText(ch.toString())
            }
        }
    }

    /** 在编辑点处插入文本（不是简单追加：点了正文中间就插在中间） */
    fun appendPhraseText(text: String) {
        update {
            if (it.phraseEditingQuickCode) {
                if (it.phraseQuickCode.length >= MAX_PHRASE_QUICK_CODE_LENGTH) it
                else {
                    val pos = it.phraseQuickCodeCursor.coerceIn(0, it.phraseQuickCode.length)
                    it.copy(
                        phraseQuickCode = it.phraseQuickCode.substring(0, pos) + text +
                                it.phraseQuickCode.substring(pos),
                        phraseQuickCodeCursor = pos + text.length,
                    )
                }
            } else {
                val pos = it.phraseContentCursor.coerceIn(0, it.phraseContent.length)
                it.copy(
                    phraseContent = it.phraseContent.substring(0, pos) + text +
                            it.phraseContent.substring(pos),
                    phraseContentCursor = pos + text.length,
                )
            }
        }
        updateQuickCodeFromContent()
    }

    /** 内容变化时按前 4 个字重算拼音首字母 */
    private fun updateQuickCodeFromContent() {
        val state = _state.value
        if (state.phraseEditingQuickCode) return
        val head = runCatching {
            PinyinHelper.getPinYinHeadChar(state.phraseContent.take(4))
        }.getOrDefault("")
        if (head.isNotBlank()) {
            // 自动改写后光标跟到末尾，否则下一次手动编辑快捷码会落在旧位置
            update { it.copy(phraseQuickCode = head, phraseQuickCodeCursor = head.length) }
        }
    }

    /** 提交短语 */
    fun finishAddPhrases() {
        val s = _state.value
        val content = s.phraseContent
        if (content.isNotBlank()) {
            val head = s.phraseQuickCode
            val t9 = head.map { T9PinYinUtils.pinyin2T9Key(it) }.joinToString("")
            WhaleDatabase.instance.phraseDao().insert(Phrase(content = content, t9 = t9, qwerty = head))
        }
        // 新增/改写的快捷码立即对候选生效
        PhraseQuickCodeManager.reload()
        update {
            it.copy(
                addPhrases = false,
                phraseExtra = null,
                phraseContent = "",
                phraseQuickCode = "",
                phraseContentCursor = 0,
                phraseQuickCodeCursor = 0,
            )
        }
        KeyboardManager.instance.switchKeyboard(InputModeSwitcher.skbImeLayout)
    }

    fun cancelAddPhrases() {
        update { it.copy(addPhrases = false, phraseExtra = null) }
        KeyboardManager.instance.switchKeyboard(InputModeSwitcher.skbImeLayout)
    }

    // ------------------------------------------------------------------
    // 输入方式选择
    // ------------------------------------------------------------------

    fun selectInputMode(mode: KeyboardMenuMode) {
        val value = when (mode) {
            KeyboardMenuMode.Pinyin26Jian -> InputModeSwitcher.MASK_SKB_LAYOUT_QWERTY_PINYIN to
                    SchemaIds.SCHEMA_ZH_QWERTY

            KeyboardMenuMode.PinyinHandWriting -> {
                // 16KB 页设备上手写库不可用，入口已过滤，这里再兜一层
                if (!com.whale.ime.engine.HWEngine.isAvailable) return
                InputModeSwitcher.MASK_SKB_LAYOUT_HANDWRITING to SchemaIds.SCHEMA_ZH_HANDWRITING
            }

            KeyboardMenuMode.PinyinStroke -> InputModeSwitcher.MASK_SKB_LAYOUT_STROKE to
                    SchemaIds.SCHEMA_ZH_STROKE

            KeyboardMenuMode.Pinyin14Jian -> InputModeSwitcher.MASK_SKB_LAYOUT_T14_PINYIN to
                    SchemaIds.SCHEMA_ZH_T14

            KeyboardMenuMode.Pinyin26Double -> InputModeSwitcher.MASK_SKB_LAYOUT_QWERTY_PINYIN to
                    (SchemaIds.SCHEMA_ZH_DOUBLE_FLYPY + appPrefs.input.doublePYSchemaMode.getValue())

            else -> InputModeSwitcher.MASK_SKB_LAYOUT_T9_PINYIN to SchemaIds.SCHEMA_ZH_T9
        }
        InputModeSwitcher.switchModeForSetting(value)
        dispatcher.resetToIdleState()
        bumpKeyboardRevision()
    }

    // ------------------------------------------------------------------
    // 环境尺寸
    // ------------------------------------------------------------------

    /** 键盘尺寸相关设置变化后统一刷新 */
    fun refreshEnvironment() {
        Environment.instance.initData()
        KeyboardBuilder.instance.clearKeyboardMap()
        KeyboardManager.instance.clearKeyboard()
        switchToInputKeyboard()
        bumpKeyboardRevision()
    }

    // ------------------------------------------------------------------
    // 编辑键盘
    // ------------------------------------------------------------------

    fun toggleKeyboardEditor() {
        if (_state.value.keyboardEditorVisible) {
            closeKeyboardEditor()
        } else {
            switchToInputKeyboard()
            update { it.copy(keyboardEditorVisible = true, keyboardEditorDraft = null) }
        }
    }

    fun closeKeyboardEditor() =
        update { it.copy(keyboardEditorVisible = false, keyboardEditorDraft = null) }

    /** 拖动中更新预览轮廓（不重建键盘） */
    fun setKeyboardEditorDraft(draft: KeyboardEditorDraft?) =
        update { it.copy(keyboardEditorDraft = draft) }

    /** 松手：把预览值落成偏好并重建键盘 */
    fun commitKeyboardEditorDraft() {
        val draft = _state.value.keyboardEditorDraft ?: return
        setKeyboardEditorDraft(null)
        applyKeyboardSize(heightRatio = draft.heightRatio, bottomGapDp = draft.bottomGapDp)
    }

    /**
     * 应用键盘尺寸改动并重建。
     *
     * 除重建键盘缓存外，还必须重设 IME 窗口高度：窗口高度不变时只有键帽字号跟着
     * [Environment.skbHeight] 变，看起来像「调字体」而不是「调键盘大小」。
     */
    fun applyKeyboardSize(heightRatio: Float? = null, bottomGapDp: Int? = null) {
        val env = Environment.instance
        heightRatio?.let { env.keyBoardHeightRatio = it }
        bottomGapDp?.let { appPrefs.keyboardSetting.keyboardBottomGap.setValue(it) }
        env.initData()
        KeyboardBuilder.instance.clearKeyboardMap()
        KeyboardManager.instance.clearKeyboard()
        applyRootPreferredHeight()
        bumpKeyboardRevision()
    }

    /** 恢复键盘高度与底部留白的默认值 */
    fun resetKeyboardSize() {
        val env = Environment.instance
        val ratioDefault = if (env.isLandscape) {
            appPrefs.internal.keyboardHeightRatioLandscape.defaultValue
        } else {
            appPrefs.keyboardSetting.keyboardHeightRatio.defaultValue
        }
        applyKeyboardSize(
            heightRatio = ratioDefault,
            bottomGapDp = appPrefs.keyboardSetting.keyboardBottomGap.defaultValue,
        )
    }

    /** 键盘高度比例变化后同步窗口高度 */
    fun applyRootPreferredHeight() = service.applyRootPreferredHeight()

    /** 悬浮键盘位置变化后强制重算 insets */
    fun refreshInsets() = service.refreshInsets()

    fun keyLabelOf(key: SoftKey): String = key.keyLabel

    /** 全面屏键盘优化栏的动作 */
    fun onFullDisplayAction(mode: com.whale.ime.preference.model.FullDisplayKeyMode) {
        when (mode) {
            com.whale.ime.preference.model.FullDisplayKeyMode.SwitchIme ->
                com.whale.ime.ui.util.InputMethodUtil.showPicker()

            com.whale.ime.preference.model.FullDisplayKeyMode.SwitchLanguage ->
                InputModeSwitcher.switchModeForUserKey(InputModeSwitcher.USER_KEYCODE_LANG)

            com.whale.ime.preference.model.FullDisplayKeyMode.Clipboard -> openClipboardPanel(KeyboardMenuMode.ClipBoard)
            com.whale.ime.preference.model.FullDisplayKeyMode.Phrases -> openClipboardPanel(KeyboardMenuMode.Phrases)
            else -> {}
        }
    }

    val string: (Int) -> String = { service.getString(it) }

    companion object {
        const val EMPTY_TEXT_HINT = R.string.add_phrases_input_hint

        /** 快捷码输入上限：编辑栏只有一行，再长就显示不下了（正常只有 4 个首字母） */
        const val MAX_PHRASE_QUICK_CODE_LENGTH = 16
    }
}
