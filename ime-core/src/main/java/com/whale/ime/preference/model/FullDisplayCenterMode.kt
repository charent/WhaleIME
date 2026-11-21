package com.whale.ime.preference.model

import com.whale.ime.preference.ManagedPreference


enum class FullDisplayCenterMode {
    MoveCursor,
//    MovePinyin,
    None;

    companion object : ManagedPreference.StringLikeCodec<FullDisplayCenterMode> {
        override fun decode(raw: String): FullDisplayCenterMode =
            FullDisplayCenterMode.valueOf(raw)
    }
}