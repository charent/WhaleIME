
package com.whale.ime.theme

import android.content.res.Configuration
import androidx.annotation.Keep
import com.whale.ime.preference.AppPrefs
import com.whale.ime.util.WeakHashSet
import com.whale.ime.util.android.isDarkMode
import com.whale.ime.preference.ManagedPreference

object ThemeManager {

    fun interface OnThemeChangeListener {
        fun onThemeChange(theme: Theme)
    }

    /**
     * 内置主题，顺序即设置页的展示顺序；纯色系按色卡排列（浅 → 深，末尾三档为近黑）
     */
    val BuiltinThemes = listOf(
        ThemePreset.MaterialLight,
        ThemePreset.MaterialDark,
        ThemePreset.PixelLight,
        ThemePreset.PixelDark,
        ThemePreset.TransparentLight,
        ThemePreset.Frost,
        ThemePreset.Charcoal,
        ThemePreset.Cloud,
        ThemePreset.Graphite,
        ThemePreset.Ruby,
        ThemePreset.Forest,
        ThemePreset.Emerald,
        ThemePreset.Ocean,
        ThemePreset.Aqua,
        ThemePreset.Violet,
        ThemePreset.Magenta,
        ThemePreset.Blossom,
        ThemePreset.Sand,
        ThemePreset.Cocoa,
        ThemePreset.Slate,
        ThemePreset.Noir,
        ThemePreset.Midnight,
        ThemePreset.Ink,
    )

    val DefaultTheme = ThemePreset.MaterialLight

    private val customThemes: MutableList<Theme.Custom> = ThemeFilesManager.listThemes()

    fun getTheme(name: String) =
        customThemes.find { it.name == name } ?: BuiltinThemes.find { it.name == name }

    fun getAllThemes() = customThemes + BuiltinThemes

    fun refreshThemes() {
        customThemes.clear()
        customThemes.addAll(ThemeFilesManager.listThemes())
        activeTheme = evaluateActiveTheme()
    }

    /**
     * [backing property](https://kotlinlang.org/docs/properties.html#backing-properties)
     * of [activeTheme]; holds the [Theme] object currently in use
     */
    private lateinit var _activeTheme: Theme

    var activeTheme: Theme
        get() = _activeTheme
        private set(value) {
            if (_activeTheme == value) return
            _activeTheme = value
            fireChange()
        }

    private var isDarkMode = false

    private val onChangeListeners = WeakHashSet<OnThemeChangeListener>()

    fun addOnChangedListener(listener: OnThemeChangeListener) {
        onChangeListeners.add(listener)
    }

    fun removeOnChangedListener(listener: OnThemeChangeListener) {
        onChangeListeners.remove(listener)
    }

    private fun fireChange() {
        onChangeListeners.forEach { it.onThemeChange(_activeTheme) }
    }

    val prefs = AppPrefs.getInstance().registerProvider(::ThemePrefs)

    fun saveTheme(theme: Theme.Custom) {
        ThemeFilesManager.saveThemeFiles(theme)
        customThemes.indexOfFirst { it.name == theme.name }.also {
            if (it >= 0) customThemes[it] = theme else customThemes.add(0, theme)
        }
        if (activeTheme.name == theme.name) {
            activeTheme = theme
        }
    }

    fun deleteTheme(name: String) {
        customThemes.find { it.name == name }?.also {
            ThemeFilesManager.deleteThemeFiles(it)
            customThemes.remove(it)
        }
        if (activeTheme.name == name) {
            activeTheme = evaluateActiveTheme()
        }
    }

    fun setNormalModeTheme(theme: Theme) {
        // `normalModeTheme.setValue(theme)` would trigger `onThemePrefsChange` listener,
        // which calls `fireChange()`.
        // `activateTheme`'s setter would also trigger `fireChange()` when theme actually changes.
        // write to backing property directly to avoid unnecessary `fireChange()`
        _activeTheme = theme
        prefs.normalModeTheme.setValue(theme)
    }

    private fun evaluateActiveTheme(): Theme {
        return if (prefs.followSystemDayNightTheme.getValue()) {
            if (isDarkMode) prefs.darkModeTheme else prefs.lightModeTheme
        } else {
            prefs.normalModeTheme
        }.getValue()
    }

    @Keep
    private val onThemePrefsChange = ManagedPreference.OnChangeListener<Any> { key, _ ->
        if (prefs.dayNightModePrefNames.contains(key)) {
            activeTheme = evaluateActiveTheme()
        } else {
            fireChange()
        }
    }

    fun init(configuration: Configuration) {
        isDarkMode = configuration.isDarkMode()
        // fire all `OnThemeChangedListener`s on theme preferences change
        prefs.managedPreferences.values.forEach {
            it.registerOnChangeListener(onThemePrefsChange)
        }
        _activeTheme = evaluateActiveTheme()
    }

    fun onSystemDarkModeChange(isDark: Boolean) {
        isDarkMode = isDark
        activeTheme = evaluateActiveTheme()
    }

}