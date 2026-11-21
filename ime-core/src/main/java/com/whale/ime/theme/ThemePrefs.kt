package com.whale.ime.theme

import android.content.SharedPreferences
import androidx.annotation.StringRes
import com.whale.ime.core.R
import com.whale.ime.preference.ManagedPreferenceCategory
import com.whale.ime.preference.model.SettingsAppearanceMode
import com.whale.ime.preference.model.KeyboardStyleMode

class ThemePrefs(sharedPreferences: SharedPreferences) :
    ManagedPreferenceCategory(R.string.theme, sharedPreferences) {

    private fun themePreference(
        @StringRes
        title: Int,
        key: String,
        defaultValue: Theme,
        @StringRes
        summary: Int? = null,
        enableUiOn: (() -> Boolean)? = null
    ): ManagedThemePreference {
        val pref = ManagedThemePreference(sharedPreferences, key, defaultValue)
        val ui = ManagedThemePreferenceUi(title, key, defaultValue, summary, enableUiOn)
        pref.register()
        ui.registerUi()
        return pref
    }

    // 主题设置title
    val themeSetting = category(R.string.theme_select)
    /**
     * When [followSystemDayNightTheme] is disabled, this theme is used.
     * This is effectively an internal preference which does not need UI.
     */
    val normalModeTheme = ManagedThemePreference(
        sharedPreferences, "normal_mode_theme", ThemeManager.DefaultTheme
    ).also {
        it.register()
    }

    val keyboardStyleMode = list(
        R.string.keyboard_style_mod,
        "keyboard_style_mod",
        KeyboardStyleMode.Whale,
        KeyboardStyleMode,
        listOf(
            KeyboardStyleMode.Whale,
            KeyboardStyleMode.Google
        ),
        listOf(
            R.string.keyboard_style_mod_whale,
            R.string.keyboard_style_mod_google,
        )
    )

    val followSystemDayNightTheme = switch(
        R.string.follow_system_day_night_theme,
        "follow_system_dark_mode",
        true,
        summary = R.string.follow_system_day_night_theme_summary
    )

    val lightModeTheme = themePreference(
        R.string.light_mode_theme,
        "light_mode_theme",
        ThemePreset.MaterialLight,
        enableUiOn = {
            followSystemDayNightTheme.getValue()
        })

    val darkModeTheme = themePreference(
        R.string.dark_mode_theme,
        "dark_mode_theme",
        ThemePreset.MaterialDark,
        enableUiOn = {
            followSystemDayNightTheme.getValue()
        })

    // ---- 设置界面自身的外观（与键盘主题无关） ----
    val appearanceSection = category(R.string.settings_appearance)

    val appearanceMode = list(
        R.string.settings_appearance_mode,
        "settings_appearance_mode",
        SettingsAppearanceMode.FollowSystem,
        SettingsAppearanceMode,
        listOf(
            SettingsAppearanceMode.FollowSystem,
            SettingsAppearanceMode.Light,
            SettingsAppearanceMode.Dark,
        ),
        listOf(
            R.string.settings_appearance_follow_system,
            R.string.settings_appearance_light,
            R.string.settings_appearance_dark,
        )
    )

    /** 动态取色（Material You）：API 31+ 用壁纸色生成配色，关闭后回落到品牌蓝青配色 */
    val dynamicColor = switch(
        R.string.settings_dynamic_color,
        "settings_dynamic_color",
        true,
        summary = R.string.settings_dynamic_color_summary,
    )

    val dayNightModePrefNames = setOf(
        followSystemDayNightTheme.key,
        lightModeTheme.key,
        darkModeTheme.key
    )

    // 键盘外观（符号 / 按键间距 / 圆角）已迁到「键盘与布局」页，见 AppPrefs.KeyboardSetting
}
