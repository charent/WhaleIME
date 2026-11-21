package com.whale.ime.keyboard.menu

import com.whale.ime.preference.model.KeyboardMenuMode

class KeyboardMenuItem(val funName: String, @JvmField val funImgResource: Int, val keyboardMenuMode: KeyboardMenuMode){
    override fun equals(other: Any?): Boolean {
        return when(other) {
            !is KeyboardMenuItem -> false
            else -> this === other || this.keyboardMenuMode == other.keyboardMenuMode
        }
    }
}
