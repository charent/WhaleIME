package com.whale.ime.compose.settings.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.whale.ime.ui.R
import com.whale.ime.compose.settings.SettingsDest
import com.whale.ime.compose.settings.rememberPreferenceValue
import com.whale.ime.compose.settings.string
import com.whale.ime.compose.settings.ui.SettingSwitchRow
import com.whale.ime.compose.settings.ui.SettingsDims
import com.whale.ime.compose.settings.ui.SettingsGroup
import com.whale.ime.compose.settings.ui.SettingsScaffold
import com.whale.ime.preference.AppPrefs
import com.whale.ime.storage.entity.UserWord
import com.whale.ime.vendor.pinyin4j.PinyinHelper
import com.whale.ime.userword.UserWordManager
import com.whale.ime.userword.UserWordCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 用户词库：搜索 / 筛选 / 翻页 / 增删改。
 *
 * 列表按页取数（[PAGE_SIZE] 条/页），翻页由底部常驻的操作条控制：
 * 词库上万条时不会一次把全部词条读进内存。
 * 清空这类低频操作收进标题栏的溢出菜单，任何滚动位置都能点到。
 *
 * 导入导出不再单独实现文本格式，统一跳转到「备份与恢复」页（[onBackup]），
 * 那里按分类打包 zip，用户词库只是其中一个分类。
 *
 * 数据写操作一律走 [UserWordManager]（它同时维护内存索引，改完立即对输入生效）。
 */
@Composable
fun UserDictScreen(onBack: () -> Unit, onBackup: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var keyword by remember { mutableStateOf("") }
    var source by remember { mutableIntStateOf(FILTER_ALL) }
    var items by remember { mutableStateOf<List<UserWord>>(emptyList()) }
    var total by remember { mutableIntStateOf(0) }
    var pageIndex by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var generation by remember { mutableIntStateOf(0) }
    var menuOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<UserWord?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var pendingClear by remember { mutableStateOf<Boolean?>(null) } // true=仅学习词

    val pageCount = ((total + PAGE_SIZE - 1) / PAGE_SIZE).coerceAtLeast(1)

    /**
     * 载入第 [target] 页。总数一起取回来，用于算页数并在删到空页时把页码收回来。
     * 每次载入递增 [generation]，先发起后返回的旧请求会被丢弃。
     */
    fun load(target: Int, scrollToTop: Boolean) {
        generation++
        val gen = generation
        loading = true
        scope.launch {
            val count = withContext(Dispatchers.IO) { UserWordManager.count(keyword, source) }
            val lastPage = if (count <= 0) 0 else (count - 1) / PAGE_SIZE
            val safePage = target.coerceIn(0, lastPage)
            val page = withContext(Dispatchers.IO) {
                UserWordManager.page(keyword, source, PAGE_SIZE, safePage * PAGE_SIZE)
            }
            if (gen != generation) return@launch
            val pageChanged = safePage != pageIndex
            total = count
            pageIndex = safePage
            items = page
            loading = false
            if (scrollToTop || pageChanged) listState.scrollToItem(0)
        }
    }

    // 搜索/筛选变化：回到第一页重新取数（防抖，避免每敲一个字都查库）
    LaunchedEffect(keyword, source) {
        delay(SEARCH_DEBOUNCE_MS)
        load(0, scrollToTop = true)
    }

    SettingsScaffold(
        title = string(SettingsDest.UserDict.title),
        onBack = onBack,
        listState = listState,
        actions = {
            TextButton(onClick = {
                editing = null
                showEditor = true
            }) { Text(string(R.string.user_dict_add)) }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_menu_more_horiz),
                        contentDescription = string(R.string.user_dict_more_actions),
                    )
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(string(R.string.user_dict_import_export)) },
                        onClick = {
                            menuOpen = false
                            onBackup()
                        },
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text(string(R.string.user_dict_clear_learned)) },
                        onClick = {
                            menuOpen = false
                            pendingClear = true
                        },
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = string(R.string.user_dict_clear_all),
                                color = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = {
                            menuOpen = false
                            pendingClear = false
                        },
                    )
                }
            }
        },
        bottomBar = {
            UserDictPager(
                pageIndex = pageIndex,
                pageCount = pageCount,
                total = total,
                enabled = !loading,
                onPrev = { load(pageIndex - 1, scrollToTop = true) },
                onNext = { load(pageIndex + 1, scrollToTop = true) },
            )
        },
    ) {
        // 词库的开关就放在词库页：输入页不再重复一份入口和一套开关
        item {
            val inputPrefs = remember { AppPrefs.getInstance().input }
            val enable = rememberPreferenceValue(inputPrefs.userDictEnable)
            val autoLearn = rememberPreferenceValue(inputPrefs.userDictAutoLearn)
            SettingsGroup {
                SettingSwitchRow(
                    title = string(R.string.user_dict_enable),
                    summary = string(R.string.user_dict_enable_summary),
                    checked = enable,
                    onCheckedChange = { inputPrefs.userDictEnable.setValue(it) },
                )
                SettingSwitchRow(
                    title = string(R.string.user_dict_auto_learn),
                    summary = string(R.string.user_dict_auto_learn_summary),
                    checked = autoLearn,
                    onCheckedChange = { inputPrefs.userDictAutoLearn.setValue(it) },
                )
            }
        }

        item {
            OutlinedTextField(
                value = keyword,
                onValueChange = { keyword = it.trim() },
                singleLine = true,
                placeholder = { Text(string(R.string.user_dict_search_hint)) },
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            )
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(
                    R.string.user_dict_filter_all to FILTER_ALL,
                    R.string.user_dict_filter_manual to UserWord.SOURCE_MANUAL,
                    R.string.user_dict_filter_learned to UserWord.SOURCE_LEARNED,
                ).forEach { (label, value) ->
                    val selected = source == value
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (selected) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceContainerHigh
                            )
                            .clickable { source = value }
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                    ) {
                        Text(
                            text = string(label),
                            fontSize = 13.sp,
                            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        if (items.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (loading) {
                        CircularProgressIndicator()
                    } else {
                        Text(
                            text = string(
                                if (keyword.isEmpty() && source == FILTER_ALL) {
                                    R.string.user_dict_empty
                                } else {
                                    R.string.user_dict_no_match
                                }
                            ),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        } else {
            items(items, key = { "${it.word}|${it.code}" }) { word ->
                UserWordRow(word = word, onClick = {
                    editing = word
                    showEditor = true
                })
            }
        }
    }

    if (showEditor) {
        UserWordEditorDialog(
            origin = editing,
            onDismiss = { showEditor = false },
            onSaved = {
                showEditor = false
                load(pageIndex, scrollToTop = false)
            },
        )
    }

    pendingClear?.let { learnedOnly ->
        AlertDialog(
            onDismissRequest = { pendingClear = null },
            title = { Text(string(R.string.user_dict)) },
            text = {
                Text(
                    string(
                        if (learnedOnly) R.string.user_dict_clear_learned_confirm
                        else R.string.user_dict_clear_all_confirm
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingClear = null
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            if (learnedOnly) UserWordManager.clearLearned()
                            else UserWordManager.clearAll()
                        }
                        load(0, scrollToTop = true)
                    }
                }) { Text(string(android.R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingClear = null }) { Text(string(R.string.cancel)) }
            },
        )
    }
}

/**
 * 底部常驻分页条：上一页 / 页码 / 下一页。
 *
 * 放在 Scaffold 的 bottomBar 里而不是列表末尾 —— 列表可能很长，
 * 但翻页控件必须一直可见；底条自己吃掉导航栏 inset。
 */
@Composable
private fun UserDictPager(
    pageIndex: Int,
    pageCount: Int,
    total: Int,
    enabled: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 3.dp,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                modifier = Modifier
                    .widthIn(max = SettingsDims.ContentMaxWidth)
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = onPrev,
                    enabled = enabled && pageIndex > 0,
                ) { Text(string(R.string.user_dict_prev_page)) }

                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = string(R.string.user_dict_page_of).format(pageIndex + 1, pageCount),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = string(R.string.user_dict_count).format(total),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }

                TextButton(
                    onClick = onNext,
                    enabled = enabled && pageIndex < pageCount - 1,
                ) { Text(string(R.string.user_dict_next_page)) }
            }
        }
    }
}

/** 词条行：词 + 编码 + 来源，点击编辑 */
@Composable
private fun UserWordRow(word: UserWord, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = word.word,
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = word.code,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = if (word.source == UserWord.SOURCE_MANUAL)
                    string(R.string.user_dict_filter_manual)
                else string(R.string.user_dict_filter_learned),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (word.weight != 0) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = "${string(R.string.user_dict_weight)}: ${word.weight}",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 8.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
    )
}

@Composable
private fun UserWordEditorDialog(
    origin: UserWord?,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val context = LocalContext.current
    var word by remember { mutableStateOf(origin?.word ?: "") }
    var code by remember { mutableStateOf(origin?.code ?: "") }
    var weight by remember { mutableStateOf((origin?.weight ?: 0).toString()) }
    var codeTouched by remember { mutableStateOf(origin != null) }

    fun toast(resId: Int) = Toast.makeText(context, resId, Toast.LENGTH_SHORT).show()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(string(if (origin == null) R.string.user_dict_add else R.string.user_dict_edit))
        },
        text = {
            Column {
                OutlinedTextField(
                    value = word,
                    onValueChange = { value ->
                        word = value
                        // 编码未手动改过时，用首字母自动填充
                        if (!codeTouched) {
                            code = PinyinHelper.getPinYinHeadChar(value).lowercase()
                                .take(UserWordCodec.MAX_CODE_LENGTH)
                        }
                    },
                    singleLine = true,
                    label = { Text(string(R.string.user_dict_word)) },
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = {
                        code = it
                        codeTouched = true
                    },
                    singleLine = true,
                    label = { Text(string(R.string.user_dict_code)) },
                    supportingText = { Text(string(R.string.user_dict_code_tips)) },
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = weight,
                    onValueChange = { weight = it.filter { c -> c.isDigit() || c == '-' } },
                    singleLine = true,
                    label = { Text(string(R.string.user_dict_weight)) },
                    supportingText = { Text(string(R.string.user_dict_weight_tips)) },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val w = weight.toIntOrNull() ?: 0
                val result = if (origin == null) {
                    UserWordManager.add(word, code, w)
                } else {
                    UserWordManager.update(origin.word, origin.code, word, code, w)
                }
                when (result) {
                    UserWordManager.SaveResult.OK -> onSaved()
                    UserWordManager.SaveResult.EMPTY_WORD -> toast(R.string.user_dict_word_empty)
                    UserWordManager.SaveResult.INVALID_CODE -> toast(R.string.user_dict_code_invalid)
                    UserWordManager.SaveResult.DUPLICATE -> toast(R.string.user_dict_duplicate)
                }
            }) { Text(string(R.string.user_dict_save)) }
        },
        dismissButton = {
            Row {
                if (origin != null) {
                    TextButton(onClick = {
                        UserWordManager.delete(origin.word, origin.code)
                        onSaved()
                    }) { Text(string(R.string.user_dict_delete)) }
                }
                TextButton(onClick = onDismiss) { Text(string(R.string.cancel)) }
            }
        },
    )
}

private const val FILTER_ALL = -1

/** 每页条数：一屏约能显示十几条，50 条足够翻两屏，又不至于把整库读进内存 */
private const val PAGE_SIZE = 50
private const val SEARCH_DEBOUNCE_MS = 250L
