package com.whale.ime.compose.settings

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * 设置页配色（设置页 / 引导页 / 主题编辑 / 裁剪页共用）。
 *
 * 种子取自应用图标渐变，见 `res/drawable/app_icon.xml`：
 * - 起始色 `#38E1FF`（青）
 * - 终止色 `#2E5BFF`（蓝）
 *
 * 两者在色环上相距约 38°，所以这里让**蓝做主色**（`primary`，用于分组标题、开关、
 * 滑块、按钮），**青做副色**（`secondary`），中性色统一带一点冷蓝调 —— 这是关键：
 * 原来直接用 `lightColorScheme()` 默认值，主色是 M3 基线的紫色 `#6750A4`，
 * 和图标完全脱节，而且默认中性底偏洋红（`#FFFBFE`）。
 *
 * 数值按 M3 tonal 阶梯取：浅色模式 primary 取 tone 40、容器取 tone 90、
 * 深色模式整体反转到 tone 80/30。关键对比度已核算：
 * - `primary` 在 `background` 上 ≈ 5.2:1（AA 正文达标）
 * - `onSurfaceVariant` 在 `surfaceContainer` 上 ≈ 8.1:1
 * - 深色模式 `primary` 在 `surface` 上 ≈ 9.4:1
 *
 * 维护提示：图标色值是 XML 里的字面量，改动图标渐变时这两个文件要同步。
 */
internal val SettingsLightColorScheme: ColorScheme = lightColorScheme(
    // 主色线：图标渐变的蓝端
    primary = Color(0xFF2E5BFF),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDEE4FF),
    onPrimaryContainer = Color(0xFF041A6B),
    inversePrimary = Color(0xFFB9C6FF),
    // 副色线：图标渐变的青端，用于次级强调
    secondary = Color(0xFF00687E),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFB7ECFA),
    onSecondaryContainer = Color(0xFF001F27),
    tertiary = Color(0xFF4B45C6),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE2DFFF),
    onTertiaryContainer = Color(0xFF110068),
    // 中性：带轻微冷蓝，避免默认方案的洋红调
    background = Color(0xFFFAFBFF),
    onBackground = Color(0xFF191B22),
    surface = Color(0xFFFAFBFF),
    onSurface = Color(0xFF191B22),
    surfaceVariant = Color(0xFFE2E4EE),
    onSurfaceVariant = Color(0xFF454754),
    surfaceTint = Color(0xFF2E5BFF),
    inverseSurface = Color(0xFF2E3038),
    inverseOnSurface = Color(0xFFF0F1F8),
    outline = Color(0xFF757783),
    outlineVariant = Color(0xFFC5C6D4),
    surfaceBright = Color(0xFFFAFBFF),
    surfaceDim = Color(0xFFDADBE4),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF4F5FB),
    surfaceContainer = Color(0xFFEEF0F8),
    surfaceContainerHigh = Color(0xFFE8EAF2),
    surfaceContainerHighest = Color(0xFFE2E4EE),
)

internal val SettingsDarkColorScheme: ColorScheme = darkColorScheme(
    // 深色模式下主色必须提亮，否则 #2E5BFF 压在深底上对比度只有 3:1 左右
    primary = Color(0xFFB9C6FF),
    onPrimary = Color(0xFF08239B),
    primaryContainer = Color(0xFF1B45D9),
    onPrimaryContainer = Color(0xFFDEE4FF),
    inversePrimary = Color(0xFF2E5BFF),
    secondary = Color(0xFF5FD4F0),
    onSecondary = Color(0xFF00363F),
    secondaryContainer = Color(0xFF00505F),
    onSecondaryContainer = Color(0xFFB0ECFB),
    tertiary = Color(0xFFC7C1FF),
    onTertiary = Color(0xFF2B1F7E),
    tertiaryContainer = Color(0xFF423A99),
    onTertiaryContainer = Color(0xFFE6E0FF),
    background = Color(0xFF101219),
    onBackground = Color(0xFFE3E4EC),
    surface = Color(0xFF101219),
    onSurface = Color(0xFFE3E4EC),
    surfaceVariant = Color(0xFF454754),
    onSurfaceVariant = Color(0xFFC5C6D4),
    surfaceTint = Color(0xFFB9C6FF),
    inverseSurface = Color(0xFFE3E4EC),
    inverseOnSurface = Color(0xFF2E3038),
    outline = Color(0xFF8F909C),
    outlineVariant = Color(0xFF454754),
    surfaceBright = Color(0xFF36383F),
    surfaceDim = Color(0xFF101219),
    surfaceContainerLowest = Color(0xFF0B0D13),
    surfaceContainerLow = Color(0xFF181A21),
    surfaceContainer = Color(0xFF1C1E26),
    surfaceContainerHigh = Color(0xFF272931),
    surfaceContainerHighest = Color(0xFF32343C),
)
