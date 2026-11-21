package com.whale.ime.compose.keyboard

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.whale.ime.ui.R
import com.whale.ime.compose.LocalImeTheme
import com.whale.ime.theme.ThemeManager
import com.whale.ime.storage.WhaleDatabase
import com.whale.ime.keyboard.model.SoftKey
import com.whale.ime.keyboard.state.KeyboardManager
import com.whale.ime.preference.AppPrefs
import com.whale.ime.dispatch.DictDecoder
import com.whale.ime.keyboard.metrics.Environment
import com.whale.ime.preference.InputFeedbacks

/**
 * T9 / 17 键 / 数字键左侧、手写右侧的竖排列表。
 *
 * 内容规则：
 * - 有拼音前缀时显示前缀（点选 = 选择拼音）；
 * - 没有前缀时显示用户自定义的侧边符号（点选 = 上屏该符号），底部带「去设置」入口。
 */
@Composable
fun T9PrefixColumn(
    numberMode: Boolean,
    onSelectPrefix: (Int) -> Unit,
    onSymbol: (String) -> Unit,
    onAddSymbol: () -> Unit,
) {
    val theme = LocalImeTheme.current
    val palette = LocalImePalette.current
    val env = remember { Environment.instance }
    val candidateState by DictDecoder.candidateState.collectAsStateWithLifecycle()
    val textColor = palette.onSurface
    val keyBorder = AppPrefs.getInstance().keyboardSetting.keyBorder.getValue()

    val prefixes = remember(candidateState.prefixRevision) {
        runCatching { DictDecoder.prefixs.toList() }.getOrDefault(emptyList())
    }
    // 设置页改完侧边栏符号会 clearKeyboard() 自增 revision，这里跟着重查，
    // 否则 remember 缓存会一直用离键盘前的旧符号
    val revision by KeyboardManager.instance.revision.collectAsStateWithLifecycle()
    val symbols = remember(numberMode, revision) {
        runCatching {
            if (numberMode) WhaleDatabase.instance.sideSymbolDao().getAllSideSymbolNumber()
            else WhaleDatabase.instance.sideSymbolDao().getAllSideSymbolPinyin()
        }.getOrDefault(emptyList())
    }
    val isPrefixMode = prefixes.isNotEmpty()
    val listState = rememberLazyListState()
    val radius = AppPrefs.getInstance().keyboardSetting.keyRadius.getValue().toFloat()
    val density = LocalDensity.current
    // keyTextSmallSize 是像素值（与 KeyGrid.pxToSp 同一换算规则）。直接 `.sp` 会被
    // density 再放大一次，符号大出整个密度倍数
    val symbolTextSize = remember(env.keyTextSmallSize, density) {
        with(density) { (env.keyTextSmallSize / fontScale).toSp() }
    }

    // 整条侧栏是一块键帽底色（即占位键背景），条目只画文本：
    // 各自再画底色会和键盘键帽叠出双背景，把侧栏啃出毛边
    // 注意：这里不要再加「consume 所有触摸」的 pointerInput —— 侧栏在 Compose
    // 命中测试里本来就在键盘上层，事件不会落到下面的按键；而父级 consume 会把
    // LazyColumn 的滚动 slop 判定掐死，导致符号多时列表滚不动（只显示前几个）。
    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (keyBorder) Modifier.background(
                    palette.card.copy(alpha = 0.92f),
                    RoundedCornerShape(radius.coerceAtLeast(12f)),
                )
                else Modifier
            ),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
        ) {
            if (isPrefixMode) {
                itemsIndexed(prefixes) { index, prefix ->
                    SideCell(text = prefix, textColor = textColor, textSize = symbolTextSize) {
                        InputFeedbacks.tryPlayKeyDown()
                        InputFeedbacks.tryVibrate()
                        onSelectPrefix(index)
                    }
                }
            } else {
                itemsIndexed(symbols) { _, symbol ->
                    SideCell(text = symbol.symbol, textColor = textColor, textSize = symbolTextSize) {
                        InputFeedbacks.tryPlayKeyDown()
                        InputFeedbacks.tryVibrate()
                        onSymbol(symbol.symbol)
                    }
                }
                // 「去设置」入口是列表末尾的 footer：
                // 拉到列表末尾才看到，而不是固定在底部被滚动的符号盖住
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        ImeIconButton(
                            iconRes = R.drawable.ic_ui_setting,
                            contentDescription = null,
                            tint = palette.muted,
                            size = 34.dp,
                            iconSize = 20.dp,
                            onClick = onAddSymbol,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SideCell(
    text: String,
    textColor: Color,
    textSize: TextUnit,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 3.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(10.dp))
            .imeClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = textColor,
            style = TextStyle(fontSize = textSize, fontWeight = FontWeight.Medium),
            modifier = Modifier.padding(vertical = 11.dp),
        )
    }
}
