package com.whale.ime.compose.settings.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.whale.ime.ui.R
import com.whale.ime.ui.backup.AutoBackup
import com.whale.ime.ui.backup.AutoBackupScheduler
import com.whale.ime.compose.settings.SettingsDest
import com.whale.ime.compose.settings.SettingsEffects
import com.whale.ime.compose.settings.rememberPreferenceValue
import com.whale.ime.compose.settings.string
import com.whale.ime.compose.settings.ui.SettingCheckRow
import com.whale.ime.compose.settings.ui.SettingDropdownRow
import com.whale.ime.compose.settings.ui.SettingNavRow
import com.whale.ime.compose.settings.ui.SettingRow
import com.whale.ime.compose.settings.ui.SettingSegmentedRow
import com.whale.ime.compose.settings.ui.SettingSwitchRow
import com.whale.ime.compose.settings.ui.SettingsGroup
import com.whale.ime.compose.settings.ui.SettingsScaffold
import com.whale.ime.backup.BackupCategory
import com.whale.ime.backup.UserDataBackup
import com.whale.ime.preference.AppPrefs
import com.whale.ime.preference.PreferenceGroup
import com.whale.ime.ui.util.AppUtil
import com.whale.ime.util.TimeUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 数据备份与恢复。
 *
 * - 导出的内容按分类勾选：默认只有「用户词典 + 自动学习词 + 剪贴板历史」，
 *   一键全选会连设置项（逐组）、常用语、符号、主题一起带上；
 * - 导入先读包内 manifest，只列出这个包里真的有的分类，默认覆盖（可切合并）；
 * - Rime 自学习词库在高级区：它不可按词条合并、恢复后必须重启。
 */
@Composable
fun BackupSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val internal = remember { AppPrefs.getInstance().internal }
    val prefGroups = remember { AppPrefs.getInstance().backupPreferenceGroups() }
    val allPrefGroupIds = remember(prefGroups) { prefGroups.map { it.id }.toSet() }
    val allCategoryIds = remember { BackupCategory.entries.map { it.id }.toSet() }

    var selected by rememberSaveable(stateSaver = SelectionSaver) {
        mutableStateOf(BackupCategory.DefaultSelection)
    }
    var selectedGroups by rememberSaveable(stateSaver = SelectionSaver) {
        mutableStateOf(allPrefGroupIds)
    }
    var stats by remember { mutableStateOf(UserDataBackup.Stats()) }
    var settingsExpanded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var pendingImport by remember { mutableStateOf<ImportPreview?>(null) }
    var restartNeeded by remember { mutableStateOf(false) }

    val autoEnabled = rememberPreferenceValue(internal.autoBackupEnabled)
    val autoDays = rememberPreferenceValue(internal.autoBackupIntervalDays)
    val autoUri = rememberPreferenceValue(internal.autoBackupTreeUri)
    val autoLastTime = rememberPreferenceValue(internal.autoBackupLastTime)

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { stats = UserDataBackup.stats() }
    }

    fun showResult(message: String) {
        scope.launch { snackbar.showSnackbar(message) }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val categories = selected
        val groups = selectedGroups
        scope.launch {
            busy = true
            val ok = withContext(NonCancellable + Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)!!.use { out ->
                        UserDataBackup.export(out, categories, groups).getOrThrow()
                    }
                }.isSuccess
            }
            busy = false
            showResult(context.getString(if (ok) R.string.backup_export_done else R.string.backup_export_failed))
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            val manifest = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)!!.use {
                        UserDataBackup.inspect(it).getOrThrow()
                    }
                }.getOrNull()
            }
            busy = false
            if (manifest == null) {
                showResult(context.getString(R.string.backup_import_unreadable))
            } else {
                pendingImport = ImportPreview(uri, manifest)
            }
        }
    }

    val treeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        internal.autoBackupTreeUri.setValue(uri.toString())
        internal.autoBackupEnabled.setValue(true)
        AutoBackupScheduler.reschedule(context)
    }

    SettingsScaffold(
        title = string(SettingsDest.Backup.title),
        onBack = onBack,
        snackbarHostState = snackbar,
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val allSelected = selected == allCategoryIds && selectedGroups == allPrefGroupIds
                    OutlinedButton(
                        onClick = {
                            if (allSelected) {
                                selected = emptySet()
                                selectedGroups = emptySet()
                            } else {
                                selected = allCategoryIds
                                selectedGroups = allPrefGroupIds
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(string(if (allSelected) R.string.backup_deselect_all else R.string.backup_select_all))
                    }
                    Button(
                        onClick = {
                            exportLauncher.launch(
                                "whaleIme_${TimeUtils.iso8601UTCDateTime()}.zip"
                            )
                        },
                        enabled = !busy && (selected.isNotEmpty() || selectedGroups.isNotEmpty()),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(string(R.string.backup_export))
                    }
                    OutlinedButton(
                        onClick = { importLauncher.launch("application/zip") },
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(string(R.string.backup_import))
                    }
                }
            }
        },
    ) {
        item {
            Text(
                text = string(R.string.backup_export_hint),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        item {
            SettingsGroup(title = string(R.string.backup_section_content)) {
                SettingCheckRow(
                    title = string(BackupCategory.USER_DICT.title),
                    summary = context.getString(R.string.backup_count_items, stats.manualWords),
                    checked = BackupCategory.USER_DICT.id in selected,
                    onCheckedChange = { selected = selected.toggle(BackupCategory.USER_DICT.id, it) },
                )
                SettingCheckRow(
                    title = string(BackupCategory.LEARNED_WORDS.title),
                    summary = context.getString(R.string.backup_count_items, stats.learnedWords),
                    checked = BackupCategory.LEARNED_WORDS.id in selected,
                    onCheckedChange = { selected = selected.toggle(BackupCategory.LEARNED_WORDS.id, it) },
                )
                SettingCheckRow(
                    title = string(BackupCategory.CLIPBOARD.title),
                    summary = context.getString(R.string.backup_count_items, stats.clipboard),
                    checked = BackupCategory.CLIPBOARD.id in selected,
                    onCheckedChange = { selected = selected.toggle(BackupCategory.CLIPBOARD.id, it) },
                )
                SettingCheckRow(
                    title = string(BackupCategory.PHRASE.title),
                    summary = context.getString(R.string.backup_count_items, stats.phrases),
                    checked = BackupCategory.PHRASE.id in selected,
                    onCheckedChange = { selected = selected.toggle(BackupCategory.PHRASE.id, it) },
                )
                SettingCheckRow(
                    title = string(BackupCategory.SYMBOLS.title),
                    summary = context.getString(
                        R.string.backup_symbols_summary,
                        stats.sideSymbols,
                        stats.usedSymbols,
                    ),
                    checked = BackupCategory.SYMBOLS.id in selected,
                    onCheckedChange = { selected = selected.toggle(BackupCategory.SYMBOLS.id, it) },
                )
                SettingCheckRow(
                    title = string(BackupCategory.THEME.title),
                    summary = context.getString(R.string.backup_count_items, stats.themes),
                    checked = BackupCategory.THEME.id in selected,
                    onCheckedChange = { selected = selected.toggle(BackupCategory.THEME.id, it) },
                )
                ExpandableCheckRow(
                    title = string(BackupCategory.SETTINGS.title),
                    summary = context.getString(R.string.backup_settings_summary, selectedGroups.size),
                    checked = BackupCategory.SETTINGS.id in selected,
                    expanded = settingsExpanded,
                    onCheckedChange = { checked ->
                        selected = selected.toggle(BackupCategory.SETTINGS.id, checked)
                        selectedGroups = if (checked) allPrefGroupIds else emptySet()
                    },
                    onToggleExpand = { settingsExpanded = !settingsExpanded },
                )
                if (settingsExpanded) {
                    if (prefGroups.isEmpty()) {
                        SettingRow(title = string(R.string.backup_settings_empty), enabled = false)
                    } else {
                        prefGroups.forEach { group ->
                            SettingCheckRow(
                                title = string(group.title),
                                checked = group.id in selectedGroups,
                                indent = true,
                                onCheckedChange = { checked ->
                                    selectedGroups = selectedGroups.toggle(group.id, checked)
                                    // 子组全不选时父项也置灰，避免导出一个空设置项
                                    selected = selected.toggle(
                                        BackupCategory.SETTINGS.id,
                                        selectedGroups.isNotEmpty(),
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
        item {
            SettingsGroup(title = string(R.string.backup_advanced_section)) {
                SettingCheckRow(
                    title = string(BackupCategory.RIME_USER.title),
                    summary = string(R.string.backup_category_rime_user_summary),
                    checked = BackupCategory.RIME_USER.id in selected,
                    onCheckedChange = { selected = selected.toggle(BackupCategory.RIME_USER.id, it) },
                )
            }
        }
        item {
            SettingsGroup(title = string(R.string.backup_auto_section)) {
                SettingSwitchRow(
                    title = string(R.string.backup_auto_enable),
                    summary = string(R.string.backup_auto_enable_summary),
                    checked = autoEnabled,
                    onCheckedChange = { checked ->
                        if (checked && autoUri.isEmpty()) {
                            // 先拿到目录再开启，否则任务会立刻失败
                            treeLauncher.launch(null)
                        } else {
                            internal.autoBackupEnabled.setValue(checked)
                            AutoBackupScheduler.reschedule(context)
                        }
                    },
                )
                SettingDropdownRow(
                    title = string(R.string.backup_auto_interval),
                    options = AUTO_INTERVAL_LABELS.map { string(it) },
                    selectedIndex = AUTO_INTERVAL_DAYS.indexOf(autoDays).coerceAtLeast(0),
                    enabled = autoEnabled,
                    onSelect = { index ->
                        internal.autoBackupIntervalDays.setValue(AUTO_INTERVAL_DAYS[index])
                        AutoBackupScheduler.reschedule(context)
                    },
                )
                SettingNavRow(
                    title = string(R.string.backup_auto_dir),
                    value = autoUri.takeIf { it.isNotEmpty() }?.let { dirLabel(it) }
                        ?: string(R.string.backup_auto_dir_unset),
                    onClick = { treeLauncher.launch(null) },
                )
                SettingRow(
                    title = string(R.string.backup_auto_last_time),
                    summary = if (autoLastTime <= 0L) string(R.string.backup_auto_never)
                    else TimeUtils.iso8601UTCDateTime(autoLastTime),
                )
                SettingNavRow(
                    title = string(R.string.backup_auto_now),
                    enabled = AutoBackup.isConfigured() && !busy,
                    onClick = {
                        scope.launch {
                            busy = true
                            val ok = withContext(Dispatchers.IO) {
                                runCatching { AutoBackup.runNow(context).getOrThrow() }.isSuccess
                            }
                            busy = false
                            Toast.makeText(
                                context,
                                if (ok) R.string.backup_export_done else R.string.backup_export_failed,
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    },
                )
            }
        }
    }

    pendingImport?.let { preview ->
        ImportDialog(
            preview = preview,
            prefGroups = prefGroups,
            onDismiss = { pendingImport = null },
            onConfirm = { categories, groups, mode ->
                pendingImport = null
                scope.launch {
                    busy = true
                    val result = withContext(NonCancellable + Dispatchers.IO) {
                        runCatching {
                            context.contentResolver.openInputStream(preview.uri)!!.use { input ->
                                UserDataBackup.import(input, categories, groups, mode).getOrThrow()
                            }
                        }
                    }
                    busy = false
                    val imported = result.getOrNull()
                    if (imported != null) {
                        SettingsEffects.rebuildKeyboard()
                        stats = withContext(Dispatchers.IO) { UserDataBackup.stats() }
                        snackbar.showSnackbar(
                            context.getString(R.string.backup_import_done, imported.counts.values.sum())
                        )
                        restartNeeded = imported.needsRestart
                    } else {
                        snackbar.showSnackbar(
                            context.getString(
                                R.string.backup_import_failed,
                                result.exceptionOrNull()?.message ?: "",
                            )
                        )
                    }
                }
            },
        )
    }

    if (busy) {
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text(string(R.string.backup_working)) },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(string(R.string.backup_working_hint))
                }
            },
        )
    }

    if (restartNeeded) {
        AlertDialog(
            onDismissRequest = { restartNeeded = false },
            title = { Text(string(R.string.user_data_imported)) },
            text = { Text(string(R.string.backup_restart_hint)) },
            confirmButton = {
                TextButton(onClick = {
                    restartNeeded = false
                    AppUtil.showRestartNotification(context)
                    AppUtil.exit()
                }) { Text(string(R.string.backup_restart_now)) }
            },
            dismissButton = {
                TextButton(onClick = { restartNeeded = false }) {
                    Text(string(R.string.backup_restart_later))
                }
            },
        )
    }
}

private class ImportPreview(val uri: Uri, val manifest: UserDataBackup.Manifest)

private fun Set<String>.toggle(id: String, checked: Boolean): Set<String> =
    if (checked) this + id else this - id

private val SelectionSaver = listSaver<Set<String>, String>(
    save = { it.toList() },
    restore = { it.toSet() },
)

private val AUTO_INTERVAL_DAYS = listOf(1, 3, 7, 30)
private val AUTO_INTERVAL_LABELS = listOf(
    R.string.backup_interval_daily,
    R.string.backup_interval_3days,
    R.string.backup_interval_weekly,
    R.string.backup_interval_monthly,
)

private fun dirLabel(uriString: String): String = runCatching {
    Uri.parse(uriString).lastPathSegment?.substringAfterLast(':')?.takeIf { it.isNotEmpty() }
        ?: uriString
}.getOrDefault(uriString)

/** 可展开的复选行：「设置项」父项 + 子组 */
@Composable
private fun ExpandableCheckRow(
    title: String,
    summary: String?,
    checked: Boolean,
    expanded: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onToggleExpand: () -> Unit,
) {
    SettingRow(
        title = title,
        summary = summary,
        onClick = { onCheckedChange(!checked) },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onToggleExpand, modifier = Modifier.size(36.dp)) {
                    Icon(
                        painter = painterResource(R.drawable.ic_chevron_right_24),
                        contentDescription = null,
                        modifier = Modifier
                            .size(18.dp)
                            .rotate(if (expanded) 90f else 0f),
                    )
                }
                Checkbox(checked = checked, onCheckedChange = onCheckedChange)
            }
        },
    )
}

/** 导入确认：只列这个包里真的有的分类，默认勾选用户词典 + 剪贴板，默认覆盖 */
@Composable
private fun ImportDialog(
    preview: ImportPreview,
    prefGroups: List<PreferenceGroup>,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>, Set<String>, UserDataBackup.ImportMode) -> Unit,
) {
    val availableCategories = remember(preview) {
        BackupCategory.entries.filter { it.id in preview.manifest.categories }.map { it.id }
    }
    val availableGroups = remember(preview, prefGroups) {
        prefGroups.filter { (UserDataBackup.PREF_GROUP_PREFIX + it.id) in preview.manifest.categories }
    }
    var checked by remember(preview) {
        mutableStateOf(BackupCategory.DefaultSelection.intersect(preview.manifest.categories.keys))
    }
    var mode by remember(preview) { mutableStateOf(UserDataBackup.ImportMode.OVERWRITE) }

    val categoryCount = availableCategories.size + availableGroups.size
    val groupsSelected = checked.filter { it.startsWith(UserDataBackup.PREF_GROUP_PREFIX) }
        .map { it.removePrefix(UserDataBackup.PREF_GROUP_PREFIX) }
        .toSet()
    val categoriesSelected = checked.filterNot { it.startsWith(UserDataBackup.PREF_GROUP_PREFIX) }.toSet()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(string(R.string.backup_import_title)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = string(R.string.backup_import_source_hint),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (categoryCount == 0) {
                    Text(
                        text = string(R.string.backup_import_empty),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
                availableCategories.forEach { id ->
                    val category = BackupCategory.of(id) ?: return@forEach
                    SettingCheckRow(
                        title = string(category.title),
                        summary = preview.manifest.categories[id]?.let {
                            string(R.string.backup_count_items).format(it)
                        },
                        checked = id in checked,
                        onCheckedChange = { checked = checked.toggle(id, it) },
                    )
                }
                if (availableGroups.isNotEmpty()) {
                    Text(
                        text = string(BackupCategory.SETTINGS.title),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    availableGroups.forEach { group ->
                        val id = UserDataBackup.PREF_GROUP_PREFIX + group.id
                        SettingCheckRow(
                            title = string(group.title),
                            summary = preview.manifest.categories[id]?.let {
                                string(R.string.backup_count_items).format(it)
                            },
                            checked = id in checked,
                            indent = true,
                            onCheckedChange = { checked = checked.toggle(id, it) },
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                SettingSegmentedRow(
                    title = string(R.string.backup_import_mode),
                    options = listOf(
                        string(R.string.backup_import_mode_overwrite),
                        string(R.string.backup_import_mode_merge),
                    ),
                    selectedIndex = if (mode == UserDataBackup.ImportMode.OVERWRITE) 0 else 1,
                    onSelect = {
                        mode = if (it == 0) UserDataBackup.ImportMode.OVERWRITE
                        else UserDataBackup.ImportMode.MERGE
                    },
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = checked.isNotEmpty() || groupsSelected.isNotEmpty(),
                onClick = {
                    // 只勾了若干设置子组时也要带上「设置项」总开关，恢复入口按它分发
                    val categories = if (groupsSelected.isEmpty()) categoriesSelected
                    else categoriesSelected + BackupCategory.SETTINGS.id
                    onConfirm(categories, groupsSelected, mode)
                },
            ) { Text(string(R.string.backup_import_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(string(R.string.cancel)) }
        },
    )
}
