package com.whale.ime.preference.model

import com.whale.ime.preference.ManagedPreference


enum class KeyboardOneHandedMod {
    LEFT,
    RIGHT;
    companion object : ManagedPreference.StringLikeCodec<KeyboardOneHandedMod> {
        override fun decode(raw: String) = KeyboardOneHandedMod.valueOf(raw)
    }
}