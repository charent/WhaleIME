package com.whale.ime.compose.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.whale.ime.ui.R
import com.whale.ime.compose.settings.ui.SettingDropdownRow
import com.whale.ime.compose.settings.ui.SettingInfoRow
import com.whale.ime.compose.settings.ui.SettingNavRow
import com.whale.ime.compose.settings.ui.SettingSegmentedRow
import com.whale.ime.compose.settings.ui.SettingSliderFloatRow
import com.whale.ime.compose.settings.ui.SettingSliderRow
import com.whale.ime.compose.settings.ui.SettingSwitchRow
import com.whale.ime.compose.settings.ui.SettingTwinSliderRow
import com.whale.ime.compose.settings.ui.SettingsGroup
import com.whale.ime.compose.settings.ui.SettingsGroupHeader
import com.whale.ime.compose.settings.ui.ThemePickerDialog
import com.whale.ime.compose.settings.ui.ThemeSwatch
import com.whale.ime.theme.ManagedThemePreference
import com.whale.ime.theme.ManagedThemePreferenceUi
import com.whale.ime.theme.ThemeManager
import com.whale.ime.preference.ManagedPreference
import com.whale.ime.preference.ManagedPreferenceProvider
import com.whale.ime.preference.ManagedPreferenceUi

/**
 * 元数据驱动的设置列表。
 *
 * 输入是 [ManagedPreferenceProvider]（数据 + UI 元数据），输出是卡片式分组：
 *
 * | 元数据 | 控件 |
 * |---|---|
 * | [ManagedPreferenceUi.Category] | 分组标题（卡片上方） |
 * | [ManagedPreferenceUi.Switch] | 行 + 开关（整行可点） |
 * | [ManagedPreferenceUi.StringList]（≤4 项） | 分段控件 |
 * | [ManagedPreferenceUi.StringList]（>4 项） | 下拉菜单 |
 * | [ManagedPreferenceUi.SeekBarInt] | 行内展开滑杆 |
 * | [ManagedPreferenceUi.SeekBarFloat] | 行内展开滑杆（按 step 换算成整数档位） |
 * | [ManagedPreferenceUi.TwinSeekBarInt] | 行内展开双滑杆 |
 * | [ManagedPreferenceUi.EditTextInt] | 数字输入对话框 |
 * | [ManagedThemePreferenceUi] | 主题选择对话框 |
 *
 * `advanced = true` 的项统一收进页面底部的「高级选项」折叠区，
 * 传 [advancedExpandedByDefault] 可让该区默认展开（键盘设置这类以高级项为主的页面）。
 *
 * [excludedKeys] 用于「这一组挪到别的页面渲染」的场景：传要跳过的偏好 key，
 * 这些项不渲染；某个分组如果被排除空了，分组标题也不会出现
 * （例如用户词库的两个开关归到「用户词库」页，输入页不再重复显示）。
 */
@Composable
fun SettingsGroups(
    providers: List<ManagedPreferenceProvider>,
    modifier: Modifier = Modifier,
    advancedExpandedByDefault: Boolean = false,
    excludedKeys: Set<String> = emptySet(),
    onChanged: (String) -> Unit = { SettingsEffects.onChanged(it) },
) {
    val revision = rememberPreferencesRevision(providers)
    val enabledMap = remember(revision) {
        providers.flatMap { it.managedPreferencesUi }
            .associate { it.key to it.isEnabled() }
    }
    val groups = remember(providers, excludedKeys) {
        buildGroups(providers, excludedKeys)
    }
    val advancedItems = remember(providers, excludedKeys) {
        collectAdvanced(providers, excludedKeys)
    }
    var advancedExpanded by rememberSaveable { mutableStateOf(advancedExpandedByDefault) }

    Column(modifier = modifier.fillMaxWidth()) {
        groups.forEach { group ->
            SettingsGroup(title = group.title?.let { string(it) }) {
                group.items.forEach { item ->
                    SettingsItem(
                        item = item,
                        enabled = enabledMap[item.key] ?: true,
                        providers = providers,
                        onChanged = onChanged,
                    )
                }
            }
        }
        if (advancedItems.isNotEmpty()) {
            SettingsGroupHeader(
                title = string(R.string.settings_advanced_options),
                modifier = Modifier.clickable { advancedExpanded = !advancedExpanded },
                trailing = {
                    Icon(
                        painter = painterResource(R.drawable.ic_expand_more_24),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(18.dp)
                            .rotate(if (advancedExpanded) 180f else 0f),
                    )
                },
            )
            if (advancedExpanded) {
                SettingsGroup {
                    advancedItems.forEach { item ->
                        SettingsItem(
                            item = item,
                            enabled = enabledMap[item.key] ?: true,
                            providers = providers,
                            onChanged = onChanged,
                        )
                    }
                }
            }
        }
    }
}

private class UiGroup(val title: Int?, val items: MutableList<ManagedPreferenceUi> = mutableListOf())

private fun buildGroups(
    providers: List<ManagedPreferenceProvider>,
    excludedKeys: Set<String>,
): List<UiGroup> {
    val groups = mutableListOf<UiGroup>()
    var current = UiGroup(null)
    providers.forEach { provider ->
        provider.managedPreferencesUi.forEach { ui ->
            if (ui is ManagedPreferenceUi.Category) {
                if (current.items.isNotEmpty()) groups += current
                current = UiGroup(ui.title)
            } else if (!ui.advanced && ui.key !in excludedKeys) {
                current.items += ui
            }
        }
    }
    if (current.items.isNotEmpty()) groups += current
    return groups
}

private fun collectAdvanced(
    providers: List<ManagedPreferenceProvider>,
    excludedKeys: Set<String>,
): List<ManagedPreferenceUi> =
    providers.flatMap { it.managedPreferencesUi }
        .filter { it.advanced && it !is ManagedPreferenceUi.Category && it.key !in excludedKeys }

@Composable
private fun SettingsItem(
    item: ManagedPreferenceUi,
    enabled: Boolean,
    providers: List<ManagedPreferenceProvider>,
    onChanged: (String) -> Unit,
) {
    when (item) {
        is ManagedPreferenceUi.Switch -> {
            val pref = providers.pBool(item.key)
            if (pref != null) {
                SettingSwitchRow(
                    title = string(item.title),
                    summary = item.summary?.let { string(it) },
                    checked = rememberPreferenceValue(pref),
                    enabled = enabled,
                    onCheckedChange = {
                        pref.setValue(it)
                        onChanged(item.key)
                    },
                )
            }
        }

        is ManagedPreferenceUi.StringList<*> -> {
            val pref = providers.pStringLike(item.key)
            if (pref != null) {
                val labels = item.entryLabels.map { string(it) }
                val raw = rememberPreferenceValue(pref).toString()
                val selected = item.indexOfRaw(raw)
                val onSelect: (Int) -> Unit = { index ->
                    item.entryValues.getOrNull(index)?.let { value ->
                        @Suppress("UNCHECKED_CAST")
                        (pref as? ManagedPreference<Any>)?.setValue(value)
                    }
                    onChanged(item.key)
                }
                if (labels.size <= 4) {
                    SettingSegmentedRow(
                        title = string(item.title),
                        options = labels,
                        selectedIndex = selected,
                        onSelect = onSelect,
                        enabled = enabled,
                    )
                } else {
                    SettingDropdownRow(
                        title = string(item.title),
                        options = labels,
                        selectedIndex = selected,
                        onSelect = onSelect,
                        enabled = enabled,
                    )
                }
            }
        }

        is ManagedPreferenceUi.EditTextInt -> {
            val pref = providers.pInt(item.key)
            if (pref != null) {
                val value = rememberPreferenceValue(pref)
                var showDialog by remember { mutableStateOf(false) }
                SettingNavRow(
                    title = string(item.title),
                    value = "$value ${item.unit}".trim(),
                    enabled = enabled,
                    onClick = { showDialog = true },
                )
                if (showDialog) {
                    NumberInputDialog(
                        title = string(item.title),
                        initial = value,
                        min = item.min,
                        max = item.max,
                        unit = item.unit,
                        onDismiss = { showDialog = false },
                        onConfirm = {
                            pref.setValue(it)
                            onChanged(item.key)
                        },
                    )
                }
            }
        }

        is ManagedPreferenceUi.SeekBarInt -> {
            val pref = providers.pInt(item.key)
            if (pref != null) {
                SettingSliderRow(
                    title = string(item.title),
                    value = rememberPreferenceValue(pref),
                    min = item.min,
                    max = item.max,
                    step = item.step,
                    unit = item.unit,
                    defaultValue = item.defaultValue,
                    defaultLabel = item.defaultLabel?.let { string(it) },
                    enabled = enabled,
                    onValueChange = {
                        pref.setValue(it)
                        onChanged(item.key)
                    },
                )
            }
        }

        is ManagedPreferenceUi.SeekBarFloat -> {
            val pref = providers.pFloat(item.key)
            if (pref != null) {
                SettingSliderFloatRow(
                    title = string(item.title),
                    value = rememberPreferenceValue(pref),
                    min = item.min,
                    max = item.max,
                    step = item.step,
                    unit = item.unit,
                    defaultValue = item.defaultValue,
                    defaultLabel = item.defaultLabel?.let { string(it) },
                    enabled = enabled,
                    onValueChange = {
                        pref.setValue(it)
                        onChanged(item.key)
                    },
                )
            }
        }

        is ManagedPreferenceUi.TwinSeekBarInt -> {
            val primary = providers.pInt(item.key)
            val secondary = providers.pInt(item.secondaryKey)
            if (primary != null && secondary != null) {
                SettingTwinSliderRow(
                    title = string(item.title),
                    primaryLabel = string(item.label),
                    primary = rememberPreferenceValue(primary),
                    secondaryLabel = string(item.secondaryLabel),
                    secondary = rememberPreferenceValue(secondary),
                    min = item.min,
                    max = item.max,
                    step = item.step,
                    unit = item.unit,
                    defaultLabel = item.defaultLabel?.let { string(it) },
                    primaryDefault = item.defaultValue,
                    secondaryDefault = item.secondaryDefaultValue,
                    enabled = enabled,
                    onValueChange = { p, s ->
                        primary.setValue(p)
                        secondary.setValue(s)
                        onChanged(item.key)
                    },
                )
            }
        }

        is ManagedThemePreferenceUi -> {
            val pref = providers.theme(item.key)
            if (pref != null) {
                val current = rememberPreferenceValue(pref)
                var showDialog by remember { mutableStateOf(false) }
                SettingNavRow(
                    title = string(item.title),
                    summary = item.summary?.let { string(it) },
                    value = current.name,
                    enabled = enabled,
                    leading = { ThemeSwatch(theme = current) },
                    onClick = { showDialog = true },
                )
                if (showDialog) {
                    val themes = ThemeManager.getAllThemes()
                    ThemePickerDialog(
                        title = string(item.title),
                        themes = themes,
                        selectedName = current.name,
                        onSelect = { theme ->
                            pref.setValue(theme)
                            onChanged(item.key)
                        },
                        onDismiss = { showDialog = false },
                    )
                }
            }
        }

        is ManagedPreferenceUi.Category -> Unit
    }
}

/** 数字输入对话框（带 min/max 校验，非法输入时禁用确定） */
@Composable
private fun NumberInputDialog(
    title: String,
    initial: Int,
    min: Int,
    max: Int,
    unit: String,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var text by remember { mutableStateOf(initial.toString()) }
    val parsed = text.trim().toIntOrNull()
    val valid = parsed != null && parsed in min..max
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { input -> text = input.filter { it.isDigit() || it == '-' } },
                singleLine = true,
                isError = !valid,
                label = { Text("$min ~ $max $unit".trim()) },
            )
        },
        confirmButton = {
            TextButton(onClick = { parsed?.let(onConfirm); onDismiss() }, enabled = valid) {
                Text(string(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(string(R.string.cancel)) }
        },
    )
}

private fun List<ManagedPreferenceProvider>.findPref(key: String): ManagedPreference<*>? {
    forEach { provider ->
        provider.managedPreferences[key]?.let { return it }
    }
    return null
}

private fun List<ManagedPreferenceProvider>.pBool(key: String): ManagedPreference.PBool? =
    findPref(key) as? ManagedPreference.PBool

private fun List<ManagedPreferenceProvider>.pInt(key: String): ManagedPreference.PInt? =
    findPref(key) as? ManagedPreference.PInt

private fun List<ManagedPreferenceProvider>.pFloat(key: String): ManagedPreference.PFloat? =
    findPref(key) as? ManagedPreference.PFloat

private fun List<ManagedPreferenceProvider>.pStringLike(key: String): ManagedPreference.PStringLike<*>? =
    findPref(key) as? ManagedPreference.PStringLike<*>

private fun List<ManagedPreferenceProvider>.theme(key: String): ManagedThemePreference? =
    findPref(key) as? ManagedThemePreference

/** 兼容旧调用点的说明行 */
@Composable
fun SettingsInfoRow(title: String, summary: String?) {
    SettingInfoRow(title = title, summary = summary)
}
