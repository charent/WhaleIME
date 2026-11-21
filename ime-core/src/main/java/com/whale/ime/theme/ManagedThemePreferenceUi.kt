package com.whale.ime.theme

import androidx.annotation.StringRes
import com.whale.ime.preference.ManagedPreferenceUi

/**
 * 主题选择项的 UI 元数据（浅色主题 / 深色主题两行）。
 *
 * 由 Compose 设置渲染器弹主题列表选择。
 */
class ManagedThemePreferenceUi(
    @StringRes
    val title: Int,
    key: String,
    val defaultValue: Theme,
    @StringRes
    val summary: Int? = null,
    enableUiOn: (() -> Boolean)? = null
) : ManagedPreferenceUi(key, enableUiOn)
