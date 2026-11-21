package com.whale.ime.compose.settings.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.whale.ime.ui.R
import com.whale.ime.compose.settings.SettingsDest
import com.whale.ime.compose.settings.SettingsSearchIndex
import com.whale.ime.compose.settings.string
import com.whale.ime.compose.settings.ui.SettingNavRow
import com.whale.ime.compose.settings.ui.SettingsGroup
import com.whale.ime.compose.settings.ui.SettingsScaffold

/**
 * 设置搜索。
 *
 * 索引直接从各 provider 的 UI 元数据生成（见 [SettingsSearchIndex]），
 * 支持中文包含匹配与拼音首字母（「sz」→ 字号）。
 */
@Composable
fun SettingsSearchScreen(
    onBack: () -> Unit,
    onNavigate: (SettingsDest) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val context = LocalContext.current
    val entries = remember {
        SettingsSearchIndex.build { context.getString(it) }
    }
    val results = remember(query, entries) {
        SettingsSearchIndex.search(entries, query)
    }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }

    SettingsScaffold(
        title = string(SettingsDest.Search.title),
        onBack = onBack,
    ) {
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                placeholder = { Text(string(R.string.settings_search_hint)) },
                leadingIcon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_search_24),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .focusRequester(focusRequester),
            )
        }

        when {
            query.isBlank() -> item {
                Hint(string(R.string.settings_search_prompt))
            }

            results.isEmpty() -> item {
                Hint(string(R.string.settings_search_empty))
            }

            else -> item {
                SettingsGroup(title = string(R.string.settings_search_title)) {
                    results.forEach { entry ->
                        SettingNavRow(
                            title = entry.title,
                            summary = listOf(entry.groupTitle, string(entry.dest.title))
                                .filter { it.isNotBlank() }
                                .joinToString(" · "),
                            onClick = { onNavigate(entry.dest) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 40.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
