package com.whale.ime.preference.model

import com.whale.ime.preference.ManagedPreference


enum class DoublePinyinSchemaMode {
    natural,
    flypy,
    abc,
    mspy,
    sogou,
    ziguang;

    companion object : ManagedPreference.StringLikeCodec<DoublePinyinSchemaMode> {
        override fun decode(raw: String) = DoublePinyinSchemaMode.valueOf(raw)
    }
}