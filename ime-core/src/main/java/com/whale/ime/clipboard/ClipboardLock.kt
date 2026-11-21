package com.whale.ime.clipboard

/**
 * 剪贴板/常用语面板的界面锁定开关（锁定后选词上屏不切回输入键盘）。
 *
 * 纯界面状态，由 `ImeController` 读写、`ClipboardPanel` 判断；不涉及任何剪贴板数据逻辑。
 */
object ClipboardLock {
    var enabled = false
}
