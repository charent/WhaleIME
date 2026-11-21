package com.whale.ime.preference

import android.content.SharedPreferences
import com.whale.ime.core.R

/**
 * 语音输入：目前没有可配置项，只保留分组占位（设置页分组标题由
 * `ManagedPreferenceUi.Category` 决定，空分组不会显示）。
 *
 * **类名 `Voice` 即备份分组 id**，不要改名。
 */
class Voice(sharedPreferences: SharedPreferences) :
    ManagedPreferenceCategory(R.string.ime_settings_voice, sharedPreferences) {
//        val titleChinese = category(R.string.chinese_input_setting)
}

/**
 * 其它杂项。
 *
 * **类名 `Other` 即备份分组 id**，不要改名。
 */
class Other(sharedPreferences: SharedPreferences) :
    ManagedPreferenceCategory(R.string.setting_ime_other, sharedPreferences) {
    val imeHideIcon = switch(R.string.ime_hide_icon, "ime_hide_icon_enable", false, R.string.ime_hide_icon_tips)
}
