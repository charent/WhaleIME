package com.whale.ime.preference.model

import com.whale.ime.preference.ManagedPreference


enum class ClipboardLayoutMode {
    ListView,
    GridView,
    FlexboxView;

    companion object : ManagedPreference.StringLikeCodec<ClipboardLayoutMode> {
        override fun decode(raw: String): ClipboardLayoutMode =
            ClipboardLayoutMode.valueOf(raw)
    }
}