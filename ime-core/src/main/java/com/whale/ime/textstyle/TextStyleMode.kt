package com.whale.ime.textstyle

import com.whale.ime.preference.ManagedPreference

enum class TextStyleMode {
    Mars, FlowerVine, Messy, Germinate, Fog, ProhibitAccess, Grass, Wind, Disabled;
   // 火星文, 花藤字, 凌乱字, 发芽字, 雾霾字, 禁止查阅， 长草字，起风了， 关闭;
    companion object : ManagedPreference.StringLikeCodec<TextStyleMode> {
        override fun decode(raw: String) = TextStyleMode.valueOf(raw)
    }
}
