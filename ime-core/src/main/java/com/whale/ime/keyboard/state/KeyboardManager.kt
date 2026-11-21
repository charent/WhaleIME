package com.whale.ime.keyboard.state

import com.whale.ime.keyboard.layout.InputModeSwitcher
import com.whale.ime.engine.HWEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 键盘类型与「键盘缓存失效」的唯一状态源。
 *
 * 不持有任何 View / Container，只保留两路状态供 Compose 订阅：
 *
 * - [keyboardType]：要显示哪套键盘（输入 / 符号 / 设置 / 候选 / 剪贴板 …）；
 * - [revision]：`clearKeyboard()` 的失效计数，Any 处改了尺寸、主题、键位、侧边栏数据后
 *   只要调用 `clearKeyboard()`，键盘就会重新从 `KeyboardBuilder` 取软键盘数据。
 */
class KeyboardManager {

    enum class KeyboardType {
        T9, T14, QWERTY, QWERTYABC, NUMBER, SYMBOL, SETTINGS, HANDWRITING, CANDIDATES, ClipBoard,
        TEXTEDIT, STROKE
    }

    private val _keyboardType = MutableStateFlow<KeyboardType?>(null)
    val keyboardType: StateFlow<KeyboardType?> = _keyboardType.asStateFlow()

    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    /** 丢弃键盘缓存（键帽数据、尺寸、主题等变化后调用） */
    fun clearKeyboard() {
        _revision.value = _revision.value + 1
    }

    fun switchKeyboard(layout: Int = InputModeSwitcher.skbLayout) {
        val keyboardName = when (layout) {
            0x1000 -> KeyboardType.QWERTY
            0x4000 -> KeyboardType.QWERTYABC
            // 手写引擎不可用时（16KB 页设备）不允许停留在手写模式：
            // 上次退出时若正处在手写模式，恢复时会走到这里
            0x3000 -> if (HWEngine.isAvailable) KeyboardType.HANDWRITING else KeyboardType.QWERTY
            0x5000 -> KeyboardType.NUMBER
            0x7000 -> KeyboardType.STROKE
            0x8000 -> KeyboardType.TEXTEDIT
            0x9000 -> KeyboardType.T14
            else -> KeyboardType.T9
        }
        switchKeyboard(keyboardName)
    }

    fun switchKeyboard(keyboardName: KeyboardType) {
        _keyboardType.value = keyboardName
    }

    val currentType: KeyboardType?
        get() = _keyboardType.value

    /** 当前是否停留在「可以输入字符」的键盘上（符号/设置/候选/剪贴板都不算） */
    val isInputKeyboard: Boolean
        get() = currentType.isInputKeyboardType()

    companion object {
        private var mInstance: KeyboardManager? = null
        @JvmStatic
        val instance: KeyboardManager
            get() {
                if (null == mInstance) {
                    mInstance = KeyboardManager()
                }
                return mInstance!!
            }
    }
}

/**
 * 与 [KeyboardManager.isInputKeyboard] 同口径，接收可空类型，
 * 便于直接消费 Compose 侧的 `StateFlow<KeyboardType?>` 参数（保证可重组）。
 */
fun KeyboardManager.KeyboardType?.isInputKeyboardType(): Boolean = when (this) {
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

/**
 * 「面板态」：由快捷栏图标点开、占住键盘区域的一次性界面。
 *
 * 与 [isInputKeyboardType] 的唯一区别是编辑（光标）键盘：它虽然也能上屏按键，但和
 * 符号/剪贴板/功能宫格一样是从快捷栏点开的临时面板——候选栏右侧按钮是「×」，
 * 关掉它回到输入键盘，只有真正的输入键盘上才是「收起输入法」。
 */
fun KeyboardManager.KeyboardType?.isPanelKeyboardType(): Boolean = when (this) {
    KeyboardManager.KeyboardType.SYMBOL,
    KeyboardManager.KeyboardType.SETTINGS,
    KeyboardManager.KeyboardType.ClipBoard,
    KeyboardManager.KeyboardType.CANDIDATES,
    KeyboardManager.KeyboardType.TEXTEDIT -> true
    else -> false
}
