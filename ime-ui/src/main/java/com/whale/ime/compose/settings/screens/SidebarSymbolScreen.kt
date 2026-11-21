package com.whale.ime.compose.settings.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.whale.ime.ui.R
import com.whale.ime.compose.settings.SettingsDest
import com.whale.ime.compose.settings.string
import com.whale.ime.compose.settings.ui.SettingNavRow
import com.whale.ime.compose.settings.ui.SettingsGroup
import com.whale.ime.compose.settings.ui.SettingsScaffold
import com.whale.ime.storage.WhaleDatabase
import com.whale.ime.storage.entity.SideSymbol
import com.whale.ime.keyboard.state.KeyboardManager
import com.whale.ime.util.thread.ThreadPoolUtils
import kotlinx.coroutines.delay

/**
 * 侧边栏符号。
 *
 * 每个符号只有一个值：侧边栏里显示的就是点上去屏的内容，所以列表只有一列输入框。
 * 顺序 = 列表顺序（上移按钮调整），增删改直接落库并让键盘失效重取。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SidebarSymbolScreen(onBack: () -> Unit) {
    val types = remember { listOf("pinyin", "number") }
    var tab by remember { mutableIntStateOf(0) }
    val lists = remember {
        val dao = WhaleDatabase.instance.sideSymbolDao()
        listOf(
            mutableStateListOf<SideSymbol>().apply { addAll(dao.getAllSideSymbolPinyin()) },
            mutableStateListOf<SideSymbol>().apply { addAll(dao.getAllSideSymbolNumber()) },
        )
    }

    /** 有未落库的改动 */
    var dirty by remember { mutableStateOf(false) }

    /**
     * 顺序即 rowid，重排只能整表重写。
     *
     * 写库不走 composition 的协程作用域：页面退出后仍然要落地（`ThreadPoolUtils` 独立线程池）。
     */
    fun persistIfDirty() {
        if (!dirty) return
        dirty = false
        val snapshot = lists.map { rows -> rows.filter { it.symbol.isNotBlank() } }
        ThreadPoolUtils.executeSingleton {
            val dao = WhaleDatabase.instance.sideSymbolDao()
            types.forEachIndexed { index, type ->
                dao.deleteAll(type)
                dao.insertAll(snapshot[index])
            }
            KeyboardManager.instance.clearKeyboard()
        }
    }

    fun edit() {
        dirty = true
        persistIfDirty()
    }

    // 输入框逐字改：改成防抖落库，避免每按一键就整表重写；离开页面时兜底再存一次
    var editRevision by remember { mutableIntStateOf(0) }
    LaunchedEffect(editRevision) {
        if (editRevision == 0) return@LaunchedEffect
        delay(400)
        persistIfDirty()
    }
    DisposableEffect(Unit) {
        onDispose { persistIfDirty() }
    }

    val back = {
        persistIfDirty()
        onBack()
    }
    val current = lists[tab]

    SettingsScaffold(
        title = string(SettingsDest.Sidebar.title),
        onBack = back,
        actions = {
            TextButton(onClick = {
                current.add(SideSymbol("", type = types[tab]))
                edit()
            }) { Text(string(R.string.skb_prefix_add)) }
        },
    ) {
        item {
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            ) {
                SegmentedButton(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                ) { Text(string(R.string.sidebar_symbol_pinyin), fontSize = 13.sp) }
                SegmentedButton(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                ) { Text(string(R.string.sidebar_symbol_number), fontSize = 13.sp) }
            }
        }

        item {
            Text(
                text = string(R.string.skb_prefix_tips),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
            )
        }

        if (current.isEmpty()) {
            item {
                SettingsGroup {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 20.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = string(R.string.skb_prefix_empty),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        } else {
            itemsIndexed(current) { index, item ->
                SymbolRow(
                    symbol = item.symbol,
                    canMoveUp = index > 0,
                    onSymbolChange = {
                        current[index] = item.copy(symbol = it)
                        dirty = true
                        editRevision++
                    },
                    onMoveUp = {
                        if (index > 0) {
                            val prev = current[index - 1]
                            current[index - 1] = item
                            current[index] = prev
                            edit()
                        }
                    },
                    onDelete = {
                        current.removeAt(index)
                        edit()
                    },
                )
            }
        }

        item {
            SettingsGroup {
                SettingNavRow(
                    title = string(R.string.save),
                    onClick = { persistIfDirty() },
                )
            }
        }
    }
}

@Composable
private fun SymbolRow(
    symbol: String,
    canMoveUp: Boolean,
    onSymbolChange: (String) -> Unit,
    onMoveUp: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = symbol,
            onValueChange = onSymbolChange,
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onMoveUp, enabled = canMoveUp) {
            Icon(
                painter = painterResource(R.drawable.ic_menu_arrow_up),
                contentDescription = null,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                painter = painterResource(R.drawable.ic_menu_delete),
                contentDescription = null,
            )
        }
    }
}
