package com.whale.ime.preference.model

import com.whale.ime.preference.ManagedPreference

/**
 * 设置页（以及引导页、主题编辑页）自身的外观模式。
 *
 * 与键盘主题无关：键盘主题描述的是“键盘长什么样”，这里描述的是“文字界面用亮还是暗”。
 */
enum class SettingsAppearanceMode {
    FollowSystem,
    Light,
    Dark;

    companion object : ManagedPreference.StringLikeCodec<SettingsAppearanceMode> {
        override fun decode(raw: String): SettingsAppearanceMode =
            entries.find { it.name == raw } ?: FollowSystem
    }
}
