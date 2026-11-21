package com.whale.ime.preference.model

import com.whale.ime.preference.ManagedPreference


enum class FullDisplayKeyMode {
    SwitchIme,
    SwitchLanguage,
    Clipboard,
    Phrases,
    None;

    companion object : ManagedPreference.StringLikeCodec<FullDisplayKeyMode> {
        override fun decode(raw: String): FullDisplayKeyMode =
            FullDisplayKeyMode.valueOf(raw)
    }
}