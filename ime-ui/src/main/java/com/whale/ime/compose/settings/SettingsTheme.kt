package com.whale.ime.compose.settings

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.whale.ime.theme.ThemeManager
import com.whale.ime.preference.model.SettingsAppearanceMode

/**
 * 设置页（以及引导页、主题编辑页、裁剪页）的统一外观。
 *
 * 与键盘主题刻意解耦：键盘主题那套颜色是为“画在键盘上”调的，
 * 直接用在文字界面会不可控。这里只决定文字界面是亮还是暗、强调色取什么。
 *
 * - 外观模式：跟随系统 / 浅色 / 深色（设置 → 外观 → 外观模式）
 * - 动态取色：API 31+ 用壁纸色生成配色；关闭后回落到品牌蓝青配色
 */
@Composable
fun SettingsTheme(content: @Composable () -> Unit) {
    val prefs = ThemeManager.prefs
    val mode = rememberPreferenceValue(prefs.appearanceMode)
    val dynamicColor = rememberPreferenceValue(prefs.dynamicColor)
    val systemDark = isSystemInDarkTheme()
    val dark = when (mode) {
        SettingsAppearanceMode.FollowSystem -> systemDark
        SettingsAppearanceMode.Light -> false
        SettingsAppearanceMode.Dark -> true
    }
    val context = LocalContext.current
    val colorScheme = remember(dark, dynamicColor, context) {
        when {
            dynamicColor && dark -> dynamicDarkColorScheme(context)
            dynamicColor -> dynamicLightColorScheme(context)
            dark -> SettingsDarkColorScheme
            else -> SettingsLightColorScheme
        }
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
