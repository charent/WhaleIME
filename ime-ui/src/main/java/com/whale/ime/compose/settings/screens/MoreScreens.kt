package com.whale.ime.compose.settings.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.whale.ime.ui.R
import com.whale.ime.boot.Launcher
import com.whale.ime.compose.settings.SettingsDest
import com.whale.ime.compose.settings.SettingsEffects
import com.whale.ime.compose.settings.SettingsGroups
import com.whale.ime.compose.settings.SettingsResetAction
import com.whale.ime.compose.settings.rememberPreferenceValue
import com.whale.ime.compose.settings.string
import com.whale.ime.compose.settings.ui.SettingDropdownRow
import com.whale.ime.compose.settings.ui.SettingNavRow
import com.whale.ime.compose.settings.ui.SettingSegmentedRow
import com.whale.ime.compose.settings.ui.SettingSliderRow
import com.whale.ime.compose.settings.ui.SettingSwitchRow
import com.whale.ime.compose.settings.ui.SettingsGroup
import com.whale.ime.compose.settings.ui.SettingsScaffold
import com.whale.ime.preference.AppPrefs
import com.whale.ime.preference.FEEDBACK_LEVEL_MAX
import com.whale.ime.preference.FEEDBACK_LEVEL_MIN
import com.whale.ime.preference.model.FeedbackMode
import com.whale.ime.preference.model.FullDisplayCenterMode
import com.whale.ime.preference.model.FullDisplayKeyMode
import com.whale.ime.util.TimeUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import com.whale.ime.preference.InputFeedbacks

/** 全面屏键盘优化：两张大图选择 + 三个功能位 */
@Composable
fun FullDisplayKeyboardScreen(onBack: () -> Unit) {
    val internal = remember { AppPrefs.getInstance().internal }
    val enabled = rememberPreferenceValue(internal.fullDisplayKeyboardEnable)
    val keyModes = remember { FullDisplayKeyMode.entries }
    val centerModes = remember { FullDisplayCenterMode.entries }
    val keyLabels = stringArray(R.array.FullDisplayKeyMode)
    val centerLabels = stringArray(R.array.FullDisplayCenterKeyMode)

    val leftMode = rememberPreferenceValue(internal.fullDisplayKeyModeLeft)
    val rightMode = rememberPreferenceValue(internal.fullDisplayKeyModeRight)
    val centerMode = rememberPreferenceValue(internal.fullDisplayCenterMode)
    val leftIndex = keyModes.indexOf(
        runCatching { FullDisplayKeyMode.decode(leftMode) }.getOrDefault(FullDisplayKeyMode.SwitchIme)
    )
    val rightIndex = keyModes.indexOf(
        runCatching { FullDisplayKeyMode.decode(rightMode) }.getOrDefault(FullDisplayKeyMode.Clipboard)
    )
    val centerIndex = centerModes.indexOf(
        runCatching { FullDisplayCenterMode.decode(centerMode) }
            .getOrDefault(FullDisplayCenterMode.MoveCursor)
    )

    SettingsScaffold(
        title = string(SettingsDest.FullDisplay.title),
        onBack = onBack,
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ModeCard(
                    modifier = Modifier.weight(1f),
                    iconRes = R.drawable.keyboard_t9_full_display,
                    label = string(R.string.keyboard_full_display_advanced_tip),
                    selected = enabled,
                    onClick = {
                        internal.fullDisplayKeyboardEnable.setValue(true)
                        SettingsEffects.rebuildKeyboard()
                    },
                )
                ModeCard(
                    modifier = Modifier.weight(1f),
                    iconRes = R.drawable.keyboard_t9_normal,
                    label = string(R.string.keyboard_full_display_normal_tip),
                    selected = !enabled,
                    onClick = {
                        internal.fullDisplayKeyboardEnable.setValue(false)
                        SettingsEffects.rebuildKeyboard()
                    },
                )
            }
        }
        if (enabled) {
            item {
                SettingsGroup(title = string(R.string.full_display_keyboard)) {
                    SettingDropdownRow(
                        title = string(R.string.keyboard_full_display_key_left),
                        options = keyLabels,
                        selectedIndex = leftIndex,
                        onSelect = { index ->
                            internal.fullDisplayKeyModeLeft.setValue(keyModes[index].name)
                            SettingsEffects.onChanged(internal.fullDisplayKeyModeLeft.key)
                        },
                    )
                    SettingDropdownRow(
                        title = string(R.string.keyboard_full_display_key_right),
                        options = keyLabels,
                        selectedIndex = rightIndex,
                        onSelect = { index ->
                            internal.fullDisplayKeyModeRight.setValue(keyModes[index].name)
                            SettingsEffects.onChanged(internal.fullDisplayKeyModeRight.key)
                        },
                    )
                    SettingSegmentedRow(
                        title = string(R.string.keyboard_full_display_key_center),
                        options = centerLabels,
                        selectedIndex = centerIndex,
                        onSelect = { index ->
                            internal.fullDisplayCenterMode.setValue(centerModes[index].name)
                            SettingsEffects.onChanged(internal.fullDisplayCenterMode.key)
                        },
                    )
                }
            }
        }
    }
}

/**
 * 反馈：音效与触感。
 *
 * 每组是「开关 + 方式（跟随系统 / 自定义）+ 档位滑杆」：
 * 关闭只写开关，档位保留；跟随系统时档位不可用（由系统的开关与强度决定）。
 * 切换方式、松手滑杆都会立即试听/试振，方便当场定档。
 */
@Composable
fun FeedbackSettingsScreen(onBack: () -> Unit) {
    val feedback = remember { AppPrefs.getInstance().feedback }

    val soundEnabled = rememberPreferenceValue(feedback.soundEnabled)
    val soundMode = rememberPreferenceValue(feedback.soundMode)
    val soundLevel = rememberPreferenceValue(feedback.soundLevel)

    val vibrationEnabled = rememberPreferenceValue(feedback.vibrationEnabled)
    val vibrationMode = rememberPreferenceValue(feedback.vibrationMode)
    val vibrationLevel = rememberPreferenceValue(feedback.vibrationLevel)

    val modes = FeedbackMode.entries
    val modeLabels = listOf(
        string(R.string.feedback_mode_follow_system),
        string(R.string.feedback_mode_custom),
    )
    val followSystemSummary = string(R.string.feedback_mode_follow_system_summary)

    SettingsScaffold(
        title = string(SettingsDest.Feedback.title),
        onBack = onBack,
    ) {
        item {
            SettingsGroup {
                SettingSwitchRow(
                    title = string(R.string.button_sound_volume),
                    checked = soundEnabled,
                    onCheckedChange = {
                        feedback.soundEnabled.setValue(it)
                        if (it) InputFeedbacks.tryPlayKeyDown()
                    },
                )
                SettingSegmentedRow(
                    title = string(R.string.feedback_mode),
                    options = modeLabels,
                    selectedIndex = modes.indexOf(soundMode).coerceAtLeast(0),
                    enabled = soundEnabled,
                    summary = followSystemSummary.takeIf { soundMode == FeedbackMode.FollowSystem },
                    onSelect = {
                        feedback.soundMode.setValue(modes[it])
                        InputFeedbacks.tryPlayKeyDown()
                    },
                )
                SettingSliderRow(
                    title = string(R.string.settings_sound_level),
                    value = soundLevel,
                    min = FEEDBACK_LEVEL_MIN,
                    max = FEEDBACK_LEVEL_MAX,
                    step = 1,
                    unit = string(R.string.settings_level_unit),
                    enabled = soundEnabled && soundMode == FeedbackMode.Custom,
                    onValueChange = {
                        feedback.soundLevel.setValue(it)
                        InputFeedbacks.tryPlayKeyDown()
                    },
                )
            }
        }
        item {
            SettingsGroup {
                SettingSwitchRow(
                    title = string(R.string.button_vibration_amplitude),
                    checked = vibrationEnabled,
                    onCheckedChange = {
                        feedback.vibrationEnabled.setValue(it)
                        if (it) InputFeedbacks.tryVibrate()
                    },
                )
                SettingSegmentedRow(
                    title = string(R.string.feedback_mode),
                    options = modeLabels,
                    selectedIndex = modes.indexOf(vibrationMode).coerceAtLeast(0),
                    enabled = vibrationEnabled,
                    summary = followSystemSummary.takeIf { vibrationMode == FeedbackMode.FollowSystem },
                    onSelect = {
                        feedback.vibrationMode.setValue(modes[it])
                        InputFeedbacks.tryVibrate()
                    },
                )
                SettingSliderRow(
                    title = string(R.string.settings_vibration_level),
                    value = vibrationLevel,
                    min = FEEDBACK_LEVEL_MIN,
                    max = FEEDBACK_LEVEL_MAX,
                    step = 1,
                    unit = string(R.string.settings_level_unit),
                    enabled = vibrationEnabled && vibrationMode == FeedbackMode.Custom,
                    onValueChange = {
                        feedback.vibrationLevel.setValue(it)
                        InputFeedbacks.tryVibrate()
                    },
                )
            }
        }
    }
}

/**
 * 高级设置：隐藏桌面图标 + 崩溃日志导出。
 *
 * 备份与恢复已经提到首页（属于「数据」，不是「高级」），这里只剩调试与系统级开关。
 */
@Composable
fun AdvancedSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val other = remember { AppPrefs.getInstance().other }
    val snackbar = remember { SnackbarHostState() }
    val imeHideIcon = rememberPreferenceValue(other.imeHideIcon)
    var logTimestamp by remember { mutableStateOf(System.currentTimeMillis()) }

    // 组件启用状态是系统级副作用，偏好一变就同步
    LaunchedEffect(imeHideIcon) {
        SettingsEffects.applyLauncherIconVisibility(context, imeHideIcon)
    }

    val logLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { exportCrashLogs(it) }
                        ?: error("open failed")
                }.isSuccess
            }
            if (!ok) Toast.makeText(context, R.string.export_crash_log, Toast.LENGTH_SHORT).show()
        }
    }

    SettingsScaffold(
        title = string(SettingsDest.Advanced.title),
        onBack = onBack,
        snackbarHostState = snackbar,
        actions = { SettingsResetAction(listOf(other), snackbar) },
    ) {
        item { SettingsGroups(providers = listOf(other)) }
        item {
            SettingsGroup(title = string(R.string.app_log)) {
                SettingNavRow(
                    title = string(R.string.export_crash_log),
                    summary = string(R.string.export_crash_log_summary),
                    onClick = {
                        logTimestamp = System.currentTimeMillis()
                        logLauncher.launch(
                            "whaleIme_crash_log${TimeUtils.iso8601UTCDateTime(logTimestamp)}.zip"
                        )
                    },
                )
            }
        }
    }
}

// ---------------------------------------------------------------- 局部组件

/** 把 `files/crash_logs` 打包成 zip */
private fun exportCrashLogs(dest: java.io.OutputStream) {
    val outputDir = File(Launcher.instance.context.filesDir, "crash_logs")
    ZipOutputStream(dest.buffered()).use { zip ->
        outputDir.walkTopDown().forEach { f ->
            val related = f.relativeTo(outputDir)
            if (related.path.isNotEmpty()) {
                when {
                    f.isDirectory -> zip.putNextEntry(ZipEntry("${related.path}/"))
                    f.isFile -> {
                        zip.putNextEntry(ZipEntry(related.path))
                        f.inputStream().use { it.copyTo(zip) }
                    }
                }
            }
        }
        zip.closeEntry()
    }
}

@Composable
private fun ModeCard(
    modifier: Modifier = Modifier,
    iconRes: Int,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val border = if (selected) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.outlineVariant
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .border(2.dp, border, RoundedCornerShape(20.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                else MaterialTheme.colorScheme.surfaceContainerLow
            )
            .clickable { onClick() }
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier
                .fillMaxWidth()
                .height(100.dp),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = label,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun stringArray(resId: Int): List<String> {
    val context = LocalContext.current
    return remember(resId) { context.resources.getStringArray(resId).toList() }
}
