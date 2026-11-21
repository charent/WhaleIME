package com.whale.ime.preference.model

import com.whale.ime.preference.ManagedPreference

/**
 * 键盘文字粗细档位，[weight] 直接对应 Compose `FontWeight` 的数值。
 *
 * 只给三档而不是连续滑块：字体文件里的字重是离散的，滑到中间值系统会就近取整，
 * 用户会以为「拖了没反应」。
 */
enum class KeyboardFontWeight(val weight: Int) {
    NORMAL(400),
    MEDIUM(500),
    BOLD(700);

    companion object : ManagedPreference.StringLikeCodec<KeyboardFontWeight> {
        override fun decode(raw: String): KeyboardFontWeight =
            runCatching { valueOf(raw) }.getOrDefault(NORMAL)
    }
}
