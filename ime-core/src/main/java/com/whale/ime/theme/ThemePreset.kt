
package com.whale.ime.theme

/**
 * 内置主题。
 *
 * `name` 同时是主题的身份标识，也是列表中展示的标题，因此直接用简体中文名。
 */
object ThemePreset {

    val MaterialLight = Theme.Builtin(
        name = "简约浅色",
        isDark = false,
        keyboardResources = 0x0,
        // 键盘底色改成与主流输入法一致的浅灰，白色键帽 + 底部投影才有「浮起」的层次
        keyboardColor = 0xffd6d7dc,
        barColor = 0xffd6d7dc,
        keyBackgroundColor = 0xfffbfbfc,
        keyTextColor = 0xff37474f,
        accentKeyBackgroundColor = 0xff0f73ff,
        accentKeyTextColor = 0xffffffff,
        keyPressHighlightColor = 0x1f000000,
        popupBackgroundColor = 0xffd9dbdd,
        // 功能键（符号 / 中英 / 123）比底色再深一档，否则在浅灰底上几乎看不见边界
        functionKeyBackgroundColor = 0xffafb3bc,
        functionKeyPressHighlightColor = 0x1f000000,
    )

    val MaterialDark = Theme.Builtin(
        name = "简约深色",
        isDark = true,
        keyboardResources = 0x0,
        barColor = 0xff21272b,
        keyboardColor = 0xff263238,
        keyBackgroundColor = 0xff404a50,
        keyTextColor = 0xffd9dbdc,
        accentKeyBackgroundColor = 0xff0f73ff,
        accentKeyTextColor = 0xffffffff,
        keyPressHighlightColor = 0x33ffffff,
        popupBackgroundColor = 0xff3c474c,
        functionKeyBackgroundColor = 0xff3b464c,
        functionKeyPressHighlightColor = 0x1fffffff,
    )

    val PixelLight = Theme.Builtin(
        name = "原生浅色",
        isDark = false,
        keyboardResources = 0x0,
        barColor = 0xffeeeeee,
        keyboardColor = 0xffE3E4EA,
        keyBackgroundColor = 0xffFCFCFC,
        keyTextColor = 0xff212121,
        accentKeyBackgroundColor = 0xff4285f4,
        accentKeyTextColor = 0xffffffff,
        keyPressHighlightColor = 0x1f000000,
        popupBackgroundColor = 0xffeeeeee,
        functionKeyBackgroundColor = 0xffB7BDC4,
        functionKeyPressHighlightColor = 0x1f000000,
    )

    val PixelDark = Theme.Builtin(
        name = "原生深色",
        isDark = true,
        keyboardResources = 0x0,
        barColor = 0xff373737,
        keyboardColor = 0xff2d2d2d,
        keyBackgroundColor = 0xff464646,
        keyTextColor = 0xfffafafa,
        accentKeyBackgroundColor = 0xff5e97f6,
        accentKeyTextColor = 0xffffffff,
        keyPressHighlightColor = 0x33ffffff,
        popupBackgroundColor = 0xff373737,
        functionKeyBackgroundColor = 0xff4a4a4a,
        functionKeyPressHighlightColor = 0x1fffffff,
    )

    /**
     * transparent background with semi-transparent white keys
     *
     * 不在 [ThemeManager.BuiltinThemes] 里：深色透明底在多数壁纸上看不清键位，已从可选列表下线。
     * 保留为「自定义背景主题」的浅色键帽模板，见 `CustomThemeActivity`。
     */
    val TransparentDark = Theme.Builtin(
        name = "透明深色",
        isDark = true,
        keyboardResources = 0x0,
        barColor = 0x4c000000,
        keyboardColor = 0x00000000,
        keyBackgroundColor = 0x4bffffff,
        keyTextColor = 0xffffffff,
        accentKeyBackgroundColor = 0xff5e97f6,
        accentKeyTextColor = 0xffffffff,
        keyPressHighlightColor = 0x1f000000,
        popupBackgroundColor = 0xff373737,
        functionKeyBackgroundColor = 0x4bffffff,
        functionKeyPressHighlightColor = 0x1fffffff,
    )

    /**
     * transparent background with semi-transparent black keys
     */
    val TransparentLight = Theme.Builtin(
        name = "透明浅色",
        isDark = false,
        keyboardResources = 0x0,
        barColor = 0x26000000,
        keyboardColor = 0x00000000,
        keyBackgroundColor = 0x4bffffff,
        keyTextColor = 0xff000000,
        accentKeyBackgroundColor = 0xff5e97f6,
        accentKeyTextColor = 0xffffffff,
        keyPressHighlightColor = 0x1f000000,
        popupBackgroundColor = 0xffeeeeee,
        functionKeyBackgroundColor = 0x5affffff,
        functionKeyPressHighlightColor = 0x1f000000,
    )

    // ---- 纯色系（浅色底 + 实色键帽 / 彩色底 + 半透明键帽），按色卡顺序排列 ----

    val Frost = Theme.Builtin(
        name = "霜白",
        isDark = false,
        keyboardResources = 0x0,
        barColor = 0xffe8ece3,
        keyboardColor = 0xfff4f6f1,
        keyBackgroundColor = 0xfffbfcf9,
        keyTextColor = 0xff37474f,
        accentKeyBackgroundColor = 0xff3ebfa8,
        accentKeyTextColor = 0xffffffff,
        keyPressHighlightColor = 0x1f000000,
        popupBackgroundColor = 0xffe8ece3,
        functionKeyBackgroundColor = 0xffc8cfc2,
        functionKeyPressHighlightColor = 0x1f000000,
    )

    val Charcoal = Theme.Builtin(
        name = "炭灰",
        isDark = true,
        keyboardResources = 0x0,
        barColor = 0xff232628,
        keyboardColor = 0xff2b2f31,
        keyBackgroundColor = 0x33ffffff,
        keyTextColor = 0xffe8eceb,
        accentKeyBackgroundColor = 0xff86d9cd,
        accentKeyTextColor = 0xff1f2426,
        keyPressHighlightColor = 0x33ffffff,
        popupBackgroundColor = 0xff232628,
        functionKeyBackgroundColor = 0xff3c4144,
        functionKeyPressHighlightColor = 0x1fffffff,
    )

    val Cloud = Theme.Builtin(
        name = "云白",
        isDark = false,
        keyboardResources = 0x0,
        barColor = 0xfff0f1f3,
        keyboardColor = 0xffffffff,
        keyBackgroundColor = 0xffe8eaed,
        keyTextColor = 0xff202124,
        accentKeyBackgroundColor = 0xff1a73e8,
        accentKeyTextColor = 0xffffffff,
        keyPressHighlightColor = 0x1f000000,
        popupBackgroundColor = 0xffe8eaed,
        functionKeyBackgroundColor = 0xffdde1e6,
        functionKeyPressHighlightColor = 0x1f000000,
    )

    val Graphite = Theme.Builtin(
        name = "石墨",
        isDark = true,
        keyboardResources = 0x0,
        barColor = 0xff2a2d30,
        keyboardColor = 0xff33363a,
        keyBackgroundColor = 0x33ffffff,
        keyTextColor = 0xffe6e8ea,
        accentKeyBackgroundColor = 0xff4a9eff,
        accentKeyTextColor = 0xffffffff,
        keyPressHighlightColor = 0x33ffffff,
        popupBackgroundColor = 0xff2a2d30,
        functionKeyBackgroundColor = 0xff45494e,
        functionKeyPressHighlightColor = 0x1fffffff,
    )

    val Ruby = Theme.Builtin(
        name = "朱红",
        isDark = true,
        keyboardResources = 0x0,
        barColor = 0xffa82822,
        keyboardColor = 0xffc8342e,
        keyBackgroundColor = 0x33ffffff,
        keyTextColor = 0xffffffff,
        accentKeyBackgroundColor = 0xfff4511e,
        accentKeyTextColor = 0xffffffff,
        keyPressHighlightColor = 0x33ffffff,
        popupBackgroundColor = 0xffa82822,
        functionKeyBackgroundColor = 0xffd94a44,
        functionKeyPressHighlightColor = 0x1fffffff,
    )

    val Forest = Theme.Builtin(
        name = "森绿",
        isDark = true,
        keyboardResources = 0x0,
        barColor = 0xff35853a,
        keyboardColor = 0xff43a047,
        keyBackgroundColor = 0x33ffffff,
        keyTextColor = 0xffffffff,
        accentKeyBackgroundColor = 0xff1b5e20,
        accentKeyTextColor = 0xffffffff,
        keyPressHighlightColor = 0x33ffffff,
        popupBackgroundColor = 0xff35853a,
        functionKeyBackgroundColor = 0xff4caf50,
        functionKeyPressHighlightColor = 0x1fffffff,
    )

    val Emerald = Theme.Builtin(
        name = "墨绿",
        isDark = true,
        keyboardResources = 0x0,
        barColor = 0xff00554a,
        keyboardColor = 0xff00695c,
        keyBackgroundColor = 0x33ffffff,
        keyTextColor = 0xffe0f2ef,
        accentKeyBackgroundColor = 0xff5dd3b3,
        accentKeyTextColor = 0xff00332c,
        keyPressHighlightColor = 0x33ffffff,
        popupBackgroundColor = 0xff00554a,
        functionKeyBackgroundColor = 0xff0b7d6f,
        functionKeyPressHighlightColor = 0x1fffffff,
    )

    val Ocean = Theme.Builtin(
        name = "海蓝",
        isDark = true,
        keyboardResources = 0x0,
        barColor = 0xff11529c,
        keyboardColor = 0xff1565c0,
        keyBackgroundColor = 0x33ffffff,
        keyTextColor = 0xffffffff,
        accentKeyBackgroundColor = 0xff64b5f6,
        accentKeyTextColor = 0xff0d3c75,
        keyPressHighlightColor = 0x33ffffff,
        popupBackgroundColor = 0xff11529c,
        functionKeyBackgroundColor = 0xff1e74d0,
        functionKeyPressHighlightColor = 0x1fffffff,
    )

    val Aqua = Theme.Builtin(
        name = "青碧",
        isDark = false,
        keyboardResources = 0x0,
        barColor = 0xff35bccf,
        keyboardColor = 0xff4dd0e1,
        keyBackgroundColor = 0xffe0f7fa,
        keyTextColor = 0xff00444c,
        accentKeyBackgroundColor = 0xff00838f,
        accentKeyTextColor = 0xffffffff,
        keyPressHighlightColor = 0x1f000000,
        popupBackgroundColor = 0xffc9eff4,
        functionKeyBackgroundColor = 0xffb2e5ec,
        functionKeyPressHighlightColor = 0x1f000000,
    )

    val Violet = Theme.Builtin(
        name = "黛紫",
        isDark = true,
        keyboardResources = 0x0,
        barColor = 0xff391f85,
        keyboardColor = 0xff4527a0,
        keyBackgroundColor = 0x33ffffff,
        keyTextColor = 0xffede7f6,
        accentKeyBackgroundColor = 0xffb39ddb,
        accentKeyTextColor = 0xff2a1380,
        keyPressHighlightColor = 0x33ffffff,
        popupBackgroundColor = 0xff391f85,
        functionKeyBackgroundColor = 0xff5334b0,
        functionKeyPressHighlightColor = 0x1fffffff,
    )

    val Magenta = Theme.Builtin(
        name = "洋红",
        isDark = true,
        keyboardResources = 0x0,
        barColor = 0xff8e1047,
        keyboardColor = 0xffad1457,
        keyBackgroundColor = 0x33ffffff,
        keyTextColor = 0xffffffff,
        accentKeyBackgroundColor = 0xffff4081,
        accentKeyTextColor = 0xffffffff,
        keyPressHighlightColor = 0x33ffffff,
        popupBackgroundColor = 0xff8e1047,
        functionKeyBackgroundColor = 0xffc21a66,
        functionKeyPressHighlightColor = 0x1fffffff,
    )

    val Blossom = Theme.Builtin(
        name = "藕粉",
        isDark = false,
        keyboardResources = 0x0,
        barColor = 0xfff2b3c1,
        keyboardColor = 0xfff8c6d0,
        keyBackgroundColor = 0xffffffff,
        keyTextColor = 0xff4a2b33,
        accentKeyBackgroundColor = 0xffe91e63,
        accentKeyTextColor = 0xffffffff,
        keyPressHighlightColor = 0x1f000000,
        popupBackgroundColor = 0xfff7dae1,
        functionKeyBackgroundColor = 0xffe2a3b3,
        functionKeyPressHighlightColor = 0x1f000000,
    )

    val Sand = Theme.Builtin(
        name = "米杏",
        isDark = false,
        keyboardResources = 0x0,
        barColor = 0xffded3c0,
        keyboardColor = 0xffe9e0d1,
        keyBackgroundColor = 0xffffffff,
        keyTextColor = 0xff4a4034,
        accentKeyBackgroundColor = 0xffff8a65,
        accentKeyTextColor = 0xffffffff,
        keyPressHighlightColor = 0x1f000000,
        popupBackgroundColor = 0xfff2ebdf,
        functionKeyBackgroundColor = 0xffcfc2ab,
        functionKeyPressHighlightColor = 0x1f000000,
    )

    val Cocoa = Theme.Builtin(
        name = "可可",
        isDark = true,
        keyboardResources = 0x0,
        barColor = 0xff402a25,
        keyboardColor = 0xff4e342e,
        keyBackgroundColor = 0x33ffffff,
        keyTextColor = 0xfff1e4e0,
        accentKeyBackgroundColor = 0xffa1887f,
        accentKeyTextColor = 0xff2b1a16,
        keyPressHighlightColor = 0x33ffffff,
        popupBackgroundColor = 0xff402a25,
        functionKeyBackgroundColor = 0xff5f413a,
        functionKeyPressHighlightColor = 0x1fffffff,
    )

    val Slate = Theme.Builtin(
        name = "青灰",
        isDark = true,
        keyboardResources = 0x0,
        barColor = 0xff2c3a41,
        keyboardColor = 0xff37474f,
        keyBackgroundColor = 0x33ffffff,
        keyTextColor = 0xffeceff1,
        accentKeyBackgroundColor = 0xff90a4ae,
        accentKeyTextColor = 0xff22303a,
        keyPressHighlightColor = 0x33ffffff,
        popupBackgroundColor = 0xff2c3a41,
        functionKeyBackgroundColor = 0xff455a64,
        functionKeyPressHighlightColor = 0x1fffffff,
    )

    val Noir = Theme.Builtin(
        name = "墨黑",
        isDark = true,
        keyboardResources = 0x0,
        barColor = 0xff0a0a0a,
        keyboardColor = 0xff000000,
        keyBackgroundColor = 0xff2a2a2a,
        keyTextColor = 0xffffffff,
        accentKeyBackgroundColor = 0xff26a69a,
        accentKeyTextColor = 0xffffffff,
        keyPressHighlightColor = 0x33ffffff,
        popupBackgroundColor = 0xff141414,
        functionKeyBackgroundColor = 0xff2a2a2a,
        functionKeyPressHighlightColor = 0x1fffffff,
    )

    val Midnight = Theme.Builtin(
        name = "午夜",
        isDark = true,
        keyboardResources = 0x0,
        barColor = 0xff0d0d0d,
        keyboardColor = 0xff121212,
        keyBackgroundColor = 0xff2a2a2a,
        keyTextColor = 0xffeeeeee,
        accentKeyBackgroundColor = 0xff1565c0,
        accentKeyTextColor = 0xffffffff,
        keyPressHighlightColor = 0x33ffffff,
        popupBackgroundColor = 0xff0d0d0d,
        functionKeyBackgroundColor = 0xff262626,
        functionKeyPressHighlightColor = 0x1fffffff,
    )

    val Ink = Theme.Builtin(
        name = "砚黑",
        isDark = true,
        keyboardResources = 0x0,
        barColor = 0xff141414,
        keyboardColor = 0xff1a1a1a,
        keyBackgroundColor = 0xff313131,
        keyTextColor = 0xffe0e0e0,
        accentKeyBackgroundColor = 0xff616161,
        accentKeyTextColor = 0xffffffff,
        keyPressHighlightColor = 0x33ffffff,
        popupBackgroundColor = 0xff141414,
        functionKeyBackgroundColor = 0xff2e2e2e,
        functionKeyPressHighlightColor = 0x1fffffff,
    )
}
