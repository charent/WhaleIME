package com.whale.ime.textstyle

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private val textStyleState = MutableStateFlow(TextStyleMode.Disabled)

/**
 * 花体字（原「火星文」）状态。core 不用 Compose：这里只保存值并以 StateFlow 暴露，
 * 菜单宫格「开」角标等界面通过 [textStyleFlow] 订阅。
 */
var textStyle: TextStyleMode
    get() = textStyleState.value
    set(value) {
        textStyleState.value = value
    }

/** 供 UI 订阅花体字开关变化（`collectAsState()`） */
val textStyleFlow: StateFlow<TextStyleMode> = textStyleState.asStateFlow()

/**
 * 字符串转花体字
 */
fun toTextStyle(src: String): String {
    return when (textStyle) {
        TextStyleMode.Disabled -> {
            src
        }

        TextStyleMode.Mars -> {  //焱暒妏
            src.map { simplifiedToMarsPreset[it] ?: it }.joinToString("")
        }

        TextStyleMode.FlowerVine -> {   // ζั͡花ั͡藤ั͡字ั͡✾
            "ζั͡" + src.map { it }.joinToString("ั͡").plus("ั͡✾")
        }

        TextStyleMode.Messy -> {  //"҉҉҉凌҉҉҉乱҉҉҉字҉҉҉"
            "҉҉҉" + src.map { it }.joinToString("҉҉҉").plus("҉҉҉")
        }

        TextStyleMode.Germinate -> {  //ོ发ོ芽ོ字ོ
            "ོ" + src.map { it }.joinToString("ོ").plus("ོ")
        }

        TextStyleMode.Fog -> {  //"҈҈҈҈雾҈҈҈҈霾҈҈҈҈字҈҈҈҈"
            "҈҈҈҈" + src.map { it }.joinToString("҈҈҈҈").plus("҈҈҈҈")
        }

        TextStyleMode.ProhibitAccess -> {  //禁⃠止⃠查⃠看
            src.map { it }.joinToString("⃠").plus("⃠")
        }

        TextStyleMode.Grass -> {  // 长҉҉҈草҉҉҈字҉҉҈
            "҈҈҈" + src.map { it }.joinToString("҈҈҈").plus("҈҈҈")
        }

        TextStyleMode.Wind -> {  // =͟͟起=͟风͟͞͞=͟了͟͞͞͞͞
            "=͟͟͞͞" + src.map { it }.joinToString("=͟͟͞͞")
        }
    }
}
