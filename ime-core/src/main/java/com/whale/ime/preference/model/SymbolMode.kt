package com.whale.ime.preference.model

import com.whale.ime.preference.ManagedPreference


enum class SymbolMode {
    Symbol,
    Emojicon,
    Emoticon;
    companion object : ManagedPreference.StringLikeCodec<SymbolMode> {
        override fun decode(raw: String) = SymbolMode.valueOf(raw)
    }
}