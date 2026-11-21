package com.whale.ime.compose.settings.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.whale.ime.ui.R
import com.whale.ime.compose.settings.SettingsDest
import com.whale.ime.compose.settings.SettingsEffects
import com.whale.ime.compose.settings.SettingsHomeMap
import com.whale.ime.compose.settings.rememberImeSetupDone
import com.whale.ime.compose.settings.rememberPreferenceValue
import com.whale.ime.compose.settings.string
import com.whale.ime.compose.settings.ui.SettingNavRow
import com.whale.ime.compose.settings.ui.SettingsChip
import com.whale.ime.compose.settings.ui.SettingsGroup
import com.whale.ime.compose.settings.ui.SettingsScaffold
import com.whale.ime.compose.settings.ui.SettingsSearchEntry
import com.whale.ime.storage.WhaleDatabase
import com.whale.ime.theme.ThemeManager
import com.whale.ime.preference.AppPrefs
import com.whale.ime.ui.setup.SetupActivity
import com.whale.ime.userword.UserWordManager
import com.whale.ime.util.android.appVersionName
import com.whale.ime.util.android.startActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 设置首页。
 *
 * 结构：搜索入口 → 当前状态 → 快捷开关 →（未启用时）开始使用 → 四组入口 → 版本页脚。
 *
 * 入口清单只有一份（[SettingsHomeMap]）：标题取 `SettingsDest.title`（与页面顶栏同源），
 * 摘要只描述「这页里有什么」。
 *
 * 「当前状态」只放需要「一眼看数量/名字」的三项：主题、用户词库条数、剪贴板条数。
 * 数量在每次回到前台时重新求值（键盘端可能刚剪贴过内容、刚学到新词）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    onNavigate: (SettingsDest) -> Unit,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    val versionName = remember(context) { context.appVersionName() }
    val appPrefs = remember { AppPrefs.getInstance() }
    val input = appPrefs.input
    val feedback = appPrefs.feedback

    val jianTi = rememberPreferenceValue(input.chineseFanTi)
    val soundEnabled = rememberPreferenceValue(feedback.soundEnabled)
    val vibrationEnabled = rememberPreferenceValue(feedback.vibrationEnabled)
    // 从系统设置回来时重估，不能直接调 SetupActivity.shouldShowUp()（不是 Compose state）
    val imeSetupDone = rememberImeSetupDone()

    // ---- 当前状态：主题名 + 两个条数 ----
    var activeThemeName by remember { mutableStateOf(ThemeManager.activeTheme.name) }
    DisposableEffect(Unit) {
        val listener = ThemeManager.OnThemeChangeListener { theme ->
            activeThemeName = theme.name
        }
        ThemeManager.addOnChangedListener(listener)
        onDispose { ThemeManager.removeOnChangedListener(listener) }
    }

    var userWordCount by remember { mutableIntStateOf(UserWordManager.count()) }
    var clipboardCount by remember { mutableIntStateOf(0) }
    // 页面在别的导航页之上时不会重组，回前台用 resume 事件把数量重新取一遍
    var statusTick by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) statusTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(statusTick) {
        userWordCount = runCatching { UserWordManager.count() }.getOrDefault(0)
        // 剪贴板数量要查库，放到 IO 线程
        clipboardCount = withContext(Dispatchers.IO) {
            runCatching { WhaleDatabase.instance.clipboardDao().getCount() }.getOrDefault(0)
        }
    }

    val unit = string(R.string.settings_count_unit)
    val countFormat = { count: Int -> "$count $unit" }

    SettingsScaffold(title = string(SettingsDest.Home.title)) {
        item {
            SettingsSearchEntry(
                placeholder = string(R.string.settings_search_hint),
                onClick = { onNavigate(SettingsDest.Search) },
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        item {
            SettingsGroup(title = string(R.string.settings_overview)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    OverviewTile(
                        label = string(R.string.settings_overview_theme),
                        value = activeThemeName,
                        modifier = Modifier.weight(1f),
                        onClick = { onNavigate(SettingsDest.Theme) },
                    )
                    OverviewTile(
                        label = string(R.string.settings_overview_user_dict),
                        value = countFormat(userWordCount),
                        modifier = Modifier.weight(1f),
                        onClick = { onNavigate(SettingsDest.UserDict) },
                    )
                    OverviewTile(
                        label = string(R.string.settings_overview_clipboard),
                        value = countFormat(clipboardCount),
                        modifier = Modifier.weight(1f),
                        onClick = { onNavigate(SettingsDest.Clipboard) },
                    )
                }
            }
        }

        // ---- 快捷开关：高频且有明确开/关状态的项，免去逐页点进去改 ----
        item {
            SettingsGroup(title = string(R.string.settings_quick_toggle)) {
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SettingsChip(
                        label = string(R.string.setting_jian_fan),
                        selected = jianTi,
                        onClick = {
                            input.chineseFanTi.setValue(!jianTi)
                            SettingsEffects.applyImeOption()
                        },
                    )
                    SettingsChip(
                        label = string(R.string.settings_quick_audio),
                        selected = soundEnabled,
                        onClick = { feedback.soundEnabled.setValue(!soundEnabled) },
                    )
                    SettingsChip(
                        label = string(R.string.settings_quick_vibrate),
                        selected = vibrationEnabled,
                        onClick = { feedback.vibrationEnabled.setValue(!vibrationEnabled) },
                    )
                }
            }
        }

        // ---- 未完成引导时单独成组，不混进「输入」 ----
        if (!imeSetupDone) {
            item {
                SettingsGroup(title = string(R.string.settings_section_setup)) {
                    SettingNavRow(
                        title = string(R.string.enable_ime),
                        summary = string(R.string.test_input_enable_ime_summary),
                        iconRes = R.drawable.ic_ui_language,
                        onClick = { context.startActivity<SetupActivity>() },
                    )
                }
            }
        }

        SettingsHomeMap.sections.forEach { section ->
            item {
                SettingsGroup(title = string(section.titleRes)) {
                    section.entries.forEach { entry ->
                        SettingNavRow(
                            title = string(entry.dest.title),
                            summary = string(entry.summaryRes),
                            iconRes = entry.iconRes,
                            onClick = { onNavigate(entry.dest) },
                        )
                    }
                }
            }
        }

        item {
            Spacer(Modifier.height(24.dp))
            Text(
                text = "${string(R.string.app_name)} · ${versionName}",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** 概览格：上标签下数值，可点击直达对应页面 */
@Composable
private fun OverviewTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = value,
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
