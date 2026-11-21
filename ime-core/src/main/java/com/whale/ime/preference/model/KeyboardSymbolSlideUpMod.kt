package com.whale.ime.preference.model

import com.whale.ime.preference.ManagedPreference


enum class KeyboardSymbolSlideUpMod {
    SHORT,
    MEDIUM,
    LONG;
    companion object : ManagedPreference.StringLikeCodec<KeyboardSymbolSlideUpMod> {
        override fun decode(raw: String) = KeyboardSymbolSlideUpMod.valueOf(raw)
    }
}