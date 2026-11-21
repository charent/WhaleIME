package com.whale.ime.compose.settings.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.whale.ime.ui.R
import com.whale.ime.compose.settings.KeyboardPreviewCard
import com.whale.ime.compose.settings.SettingsDest
import com.whale.ime.compose.settings.SettingsEffects
import com.whale.ime.compose.settings.SettingsGroups
import com.whale.ime.compose.settings.SettingsResetAction
import com.whale.ime.compose.settings.string
import com.whale.ime.compose.settings.ui.SettingNavRow
import com.whale.ime.compose.settings.ui.SettingRow
import com.whale.ime.compose.settings.ui.SettingsGroup
import com.whale.ime.compose.settings.ui.SettingsPickerDialog
import com.whale.ime.compose.settings.ui.SettingsScaffold
import com.whale.ime.compose.settings.ui.ThemeSwatch
import com.whale.ime.theme.Theme
import com.whale.ime.theme.ThemeManager
import com.whale.ime.ui.theme.CustomThemeActivity
import java.util.UUID

/**
 * 主题与外观：主题选择 + 自定义主题列表。
 *
 * 顶部一张键盘预览卡片（与「键盘与布局」页同一组件），切换主题/亮暗主题时预览即时更新，
 * 不用回键盘确认效果；它只在「更多」列表里被钉在列表上方（`pinnedHeader`），
 * 「主题」页上是普通首项，跟随列表滚动。
 * 主题切换后键盘尺寸/缓存需要失效，统一走 [SettingsEffects.rebuildKeyboard]。
 *
 * 「全面屏键盘」（底部功能条，本质是键盘形态）不在这里，见 `KeyboardSettingsScreen`。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeScreen(onBack: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    var active by remember { mutableStateOf(ThemeManager.activeTheme) }
    var themes by remember { mutableStateOf(ThemeManager.getAllThemes()) }
    var pendingSelect by remember { mutableStateOf<Theme?>(null) }
    var showAddMenu by remember { mutableStateOf(false) }
    var showDuplicate by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val prefs = remember { ThemeManager.prefs }

    val editLauncher = rememberLauncherForActivityResult(CustomThemeActivity.Contract()) { result ->
        when (result) {
            is CustomThemeActivity.BackgroundResult.Created -> {
                ThemeManager.saveTheme(result.theme)
                if (!ThemeManager.prefs.followSystemDayNightTheme.getValue()) {
                    ThemeManager.setNormalModeTheme(result.theme)
                }
            }

            is CustomThemeActivity.BackgroundResult.Updated -> ThemeManager.saveTheme(result.theme)
            is CustomThemeActivity.BackgroundResult.Deleted -> ThemeManager.deleteTheme(result.name)
            null -> Unit
        }
        themes = ThemeManager.getAllThemes()
    }

    DisposableEffect(Unit) {
        ThemeManager.refreshThemes()
        active = ThemeManager.activeTheme
        themes = ThemeManager.getAllThemes()
        val listener = ThemeManager.OnThemeChangeListener { theme ->
            active = theme
            SettingsEffects.rebuildKeyboard()
        }
        ThemeManager.addOnChangedListener(listener)
        onDispose { ThemeManager.removeOnChangedListener(listener) }
    }

    val previewCard: @Composable () -> Unit = {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 8.dp),
        ) {
            KeyboardPreviewCard(theme = active)
        }
    }

    SettingsScaffold(
        title = string(SettingsDest.Theme.title),
        onBack = onBack,
        snackbarHostState = snackbar,
        actions = { SettingsResetAction(listOf(prefs), snackbar) },
        // 只有「更多」列表才把预览卡片钉在列表上方：挑主题时不用上滑就能看到完整效果；
        // 「主题」页仍是普通首项，跟随列表滚动
        pinnedHeader = if (tab == 1) previewCard else null,
    ) {
        if (tab == 0) {
            item { previewCard() }
        }

        item {
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp, bottom = 4.dp),
            ) {
                SegmentedButton(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                ) { Text(string(R.string.theme), fontSize = 13.sp) }
                SegmentedButton(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                ) { Text(string(R.string.more), fontSize = 13.sp) }
            }
        }

        if (tab == 0) {
            item { SettingsGroups(providers = listOf(prefs)) }
        } else {
            item {
                SettingsGroup {
                    SettingNavRow(
                        title = string(R.string.new_theme),
                        summary = string(R.string.choose_image),
                        onClick = { showAddMenu = true },
                    )
                    themes.forEach { theme ->
                        val isActive = theme.name == active.name
                        val isLight = ThemeManager.prefs.lightModeTheme.getValue().name == theme.name
                        val isDark = ThemeManager.prefs.darkModeTheme.getValue().name == theme.name
                        SettingRow(
                            title = theme.name,
                            summary = when {
                                theme is Theme.Custom -> string(R.string.custom_theme)
                                isLight -> string(R.string.light_mode_theme)
                                isDark -> string(R.string.dark_mode_theme)
                                else -> null
                            },
                            leading = { ThemeSwatch(theme = theme) },
                            onClick = {
                                if (ThemeManager.prefs.followSystemDayNightTheme.getValue()) {
                                    pendingSelect = theme
                                } else {
                                    ThemeManager.setNormalModeTheme(theme)
                                }
                            },
                            trailing = {
                                if (isActive) {
                                    Text(
                                        text = "✓",
                                        color = MaterialTheme.colorScheme.primary,
                                        fontSize = 15.sp,
                                    )
                                }
                                if (theme is Theme.Custom) {
                                    TextButton(onClick = { editLauncher.launch(theme) }) {
                                        Text(string(R.string.edit), fontSize = 13.sp)
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
    }

    if (showAddMenu) {
        AlertDialog(
            onDismissRequest = { showAddMenu = false },
            title = { Text(string(R.string.new_theme)) },
            text = {
                Column {
                    TextButton(onClick = {
                        showAddMenu = false
                        editLauncher.launch(null)
                    }) { Text(string(R.string.choose_image)) }
                    TextButton(onClick = {
                        showAddMenu = false
                        showDuplicate = true
                    }) { Text(string(R.string.duplicate_builtin_theme)) }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAddMenu = false }) {
                    Text(string(R.string.cancel))
                }
            },
        )
    }

    if (showDuplicate) {
        val builtin = ThemeManager.BuiltinThemes
        SettingsPickerDialog(
            title = string(R.string.duplicate_builtin_theme),
            options = builtin.map { it.name },
            selectedIndex = -1,
            onDismiss = { showDuplicate = false },
            onSelect = { index ->
                builtin.getOrNull(index)?.let { theme ->
                    val newTheme = theme.deriveCustomNoBackground(UUID.randomUUID().toString())
                    ThemeManager.saveTheme(newTheme)
                    themes = ThemeManager.getAllThemes()
                }
            },
        )
    }

    pendingSelect?.let { theme ->
        AlertDialog(
            onDismissRequest = { pendingSelect = null },
            title = { Text(string(R.string.configure)) },
            text = { Text(string(R.string.theme_message_follow_system_day_night_mode_enabled)) },
            confirmButton = {
                TextButton(onClick = {
                    ThemeManager.prefs.followSystemDayNightTheme.setValue(false)
                    ThemeManager.setNormalModeTheme(theme)
                    pendingSelect = null
                }) { Text(string(R.string.disable_it)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingSelect = null }) {
                    Text(string(R.string.cancel))
                }
            },
        )
    }
}
