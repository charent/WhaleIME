package com.whale.ime.keyboard.model

import com.whale.ime.preference.ManagedPreference

enum class KeyType {
    AccentKey,
    Function,
    Normal;

    companion object : ManagedPreference.StringLikeCodec<KeyType> {
        override fun decode(raw: String): KeyType =
            KeyType.valueOf(raw)
    }
}