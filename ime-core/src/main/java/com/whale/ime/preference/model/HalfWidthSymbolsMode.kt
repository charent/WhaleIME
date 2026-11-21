package com.whale.ime.preference.model

import com.whale.ime.preference.ManagedPreference


enum class HalfWidthSymbolsMode {
    All,
    OnlyUsed,
    None;

    companion object : ManagedPreference.StringLikeCodec<HalfWidthSymbolsMode> {
        override fun decode(raw: String): HalfWidthSymbolsMode =
            HalfWidthSymbolsMode.valueOf(raw)
    }
}