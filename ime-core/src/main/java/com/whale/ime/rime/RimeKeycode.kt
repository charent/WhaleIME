// 本包与 libwhaleime.so 一一对应，改包名 = 必须重编引擎（详见 Rime.kt 顶部说明）。
package com.whale.ime.rime

/**
 * librime 的键码（`process_key` 的 keycode）。
 *
 * librime 用的是 X11 keysym：可见字符直接用其码位（如空格 `0x20`、字母 `'a'.code`），
 * 功能键集中在 `0xFF00` 起始的区间。
 *
 * 工程实际只用得上退格：翻页走全量候选列表（`Rime.getCandidates`），不合成 `Page_Down`。
 * 需要新键时按 X11 keysym 在这里补常量，不要再往 native 层加方法。
 */
object RimeKeycode {
    /** 退格 */
    const val BACK_SPACE = 0xFF08
}
