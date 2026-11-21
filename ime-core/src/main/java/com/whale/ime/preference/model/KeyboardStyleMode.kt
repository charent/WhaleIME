package com.whale.ime.preference.model

import com.whale.ime.preference.ManagedPreference


enum class KeyboardStyleMode {
    Google,
    Whale;
    companion object : ManagedPreference.StringLikeCodec<KeyboardStyleMode> {
        // 已下线的历史取值（三星风格）会残留在已装设备的偏好里，取不到就回落到默认风格
        override fun decode(raw: String) = entries.firstOrNull { it.name == raw } ?: Whale
    }
}
