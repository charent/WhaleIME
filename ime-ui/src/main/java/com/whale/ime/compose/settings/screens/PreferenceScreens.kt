package com.whale.ime.compose.settings.screens

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.whale.ime.ui.R
import com.whale.ime.compose.settings.KeyboardPreviewCard
import com.whale.ime.compose.settings.SettingsDest
import com.whale.ime.compose.settings.SettingsGroups
import com.whale.ime.compose.settings.SettingsResetAction
import com.whale.ime.compose.settings.rememberPreferenceValue
import com.whale.ime.compose.settings.rememberPreferencesRevision
import com.whale.ime.compose.settings.string
import com.whale.ime.compose.settings.ui.SettingInfoRow
import com.whale.ime.compose.settings.ui.SettingNavRow
import com.whale.ime.compose.settings.ui.SettingsDims
import com.whale.ime.compose.settings.ui.SettingsGroup
import com.whale.ime.compose.settings.ui.SettingsScaffold
import com.whale.ime.theme.ThemeManager
import com.whale.ime.preference.AppPrefs
import com.whale.ime.engine.HWEngine

/**
 * 输入设置。
 *
 * 所有取值/副作用都由 [SettingsGroups] 统一处理（见 `SettingsEffects`），
 * 页面本身不再注册偏好监听，也不再手动通知引擎。
 */
@Composable
fun InputSettingsScreen(
    onBack: () -> Unit,
    onHandwriting: () -> Unit,
    onVoice: () -> Unit,
) {
    val input = remember { AppPrefs.getInstance().input }
    val snackbar = remember { SnackbarHostState() }
    val handwritingAvailable = remember { HWEngine.isAvailable }

    SettingsScaffold(
        title = string(SettingsDest.Input.title),
        onBack = onBack,
        snackbarHostState = snackbar,
        actions = { SettingsResetAction(listOf(input), snackbar) },
    ) {
        // 用户词库的开关归「用户词库」页，这里不重复渲染（入口也只在首页出现一次）
        item {
            SettingsGroups(
                providers = listOf(input),
                excludedKeys = setOf(input.userDictEnable.key, input.userDictAutoLearn.key),
            )
        }
        item {
            SettingsGroup(title = string(R.string.input_methods)) {
                if (handwritingAvailable) {
                    SettingNavRow(
                        title = string(R.string.ime_settings_handwriting),
                        onClick = onHandwriting,
                    )
                }
                SettingNavRow(
                    title = string(R.string.ime_settings_voice),
                    onClick = onVoice,
                )
            }
        }
    }
}

/** 手写设置；16KB 设备上给出不可用说明 */
@Composable
fun HandwritingSettingsScreen(onBack: () -> Unit) {
    val handwriting = remember { AppPrefs.getInstance().handwriting }
    val snackbar = remember { SnackbarHostState() }

    SettingsScaffold(
        title = string(SettingsDest.Handwriting.title),
        onBack = onBack,
        snackbarHostState = snackbar,
        actions = { SettingsResetAction(listOf(handwriting), snackbar) },
    ) {
        item { SettingsGroups(providers = listOf(handwriting)) }
        if (!HWEngine.isAvailable) {
            item {
                // 无标题分组之间要自己留组间距，否则卡片圆角会互相咬住
                SettingsGroup(modifier = Modifier.padding(top = SettingsDims.SectionTitleTop)) {
                    SettingInfoRow(
                        title = string(R.string.handwriting_unavailable_title),
                        summary = string(R.string.handwriting_unavailable_summary),
                    )
                }
            }
        }
    }
}

/**
 * 键盘与布局：顶部实时预览，字号/间距类改动即时反映到预览。
 *
 * 「全面屏键盘」是键盘形态（底部三个功能位），和高度/布局一起调，所以归这一页而不是主题页。
 */
@Composable
fun KeyboardSettingsScreen(onBack: () -> Unit, onFullDisplay: () -> Unit) {
    val keyboardSetting = remember { AppPrefs.getInstance().keyboardSetting }
    val snackbar = remember { SnackbarHostState() }
    val activeTheme = remember { ThemeManager.activeTheme }
    val fullDisplay = rememberPreferenceValue(
        AppPrefs.getInstance().internal.fullDisplayKeyboardEnable
    )
    // 环境尺寸是快照，改动后要靠 revision 强制重建预览
    val revision = rememberPreferencesRevision(listOf(keyboardSetting))

    SettingsScaffold(
        title = string(SettingsDest.Keyboard.title),
        onBack = onBack,
        snackbarHostState = snackbar,
        actions = { SettingsResetAction(listOf(keyboardSetting), snackbar) },
        // 预览固定在列表上方，改字号/间距/高度时不用上滑就能看到完整效果
        pinnedHeader = {
            KeyboardPreviewCard(
                theme = activeTheme,
                revision = revision,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
        },
    ) {
        item {
            SettingsGroup {
                SettingNavRow(
                    title = string(R.string.full_display_keyboard),
                    // 摘要直接回显当前模式，不用点进去才知道选的是哪套键盘
                    summary = string(
                        if (fullDisplay) R.string.keyboard_full_display_advanced_tip
                        else R.string.keyboard_full_display_normal_tip
                    ),
                    onClick = onFullDisplay,
                )
            }
        }
        // 键盘设置以高级项为主（字体/交互细节），直接展开，省一次点击
        item {
            SettingsGroups(
                providers = listOf(keyboardSetting),
                advancedExpandedByDefault = true,
                modifier = Modifier.padding(top = SettingsDims.SectionTitleTop),
            )
        }
    }
}

/** 剪贴板设置 */
@Composable
fun ClipboardSettingsScreen(onBack: () -> Unit) {
    val clipboard = remember { AppPrefs.getInstance().clipboard }
    val snackbar = remember { SnackbarHostState() }

    SettingsScaffold(
        title = string(SettingsDest.Clipboard.title),
        onBack = onBack,
        snackbarHostState = snackbar,
        actions = { SettingsResetAction(listOf(clipboard), snackbar) },
    ) {
        item { SettingsGroups(providers = listOf(clipboard)) }
    }
}

/** 语音设置（功能未上线，仅占位说明） */
@Composable
fun VoiceSettingsScreen(onBack: () -> Unit) {
    SettingsScaffold(
        title = string(SettingsDest.Voice.title),
        onBack = onBack,
    ) {
        item {
            SettingsGroup {
                SettingInfoRow(
                    title = string(R.string.ime_settings_voice),
                    summary = string(R.string.voice_unavailable_summary),
                )
            }
        }
    }
}
