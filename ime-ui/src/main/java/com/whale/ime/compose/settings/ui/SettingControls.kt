package com.whale.ime.compose.settings.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.whale.ime.ui.R
import kotlin.math.roundToInt

/**
 * 枚举型设置：**≤4 项用分段控件**直接铺在行下方（一次点击到位，不再弹单选对话框），
 * 更多项走 [SettingDropdownRow] 下拉。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingSegmentedRow(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    enabled: Boolean = true,
) {
    val alpha = if (enabled) 1f else 0.38f
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = SettingsDims.RowHorizontal, vertical = SettingsDims.RowVertical)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            fontSize = SettingsDims.TitleSize,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
        )
        if (!summary.isNullOrEmpty()) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = summary,
                fontSize = SettingsDims.SummarySize,
                lineHeight = SettingsDims.SummaryLineHeight,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
            )
        }
        Spacer(Modifier.height(10.dp))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, label ->
                SegmentedButton(
                    selected = index == selectedIndex,
                    onClick = { onSelect(index) },
                    enabled = enabled,
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                    label = {
                        Text(
                            text = label,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontSize = 13.sp,
                        )
                    },
                )
            }
        }
    }
}

/** 枚举型设置（>4 项）：行点击展开下拉菜单 */
@Composable
fun SettingDropdownRow(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    enabled: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier.fillMaxWidth()) {
        SettingRow(
            title = title,
            summary = summary,
            enabled = enabled,
            onClick = { expanded = true },
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = options.getOrNull(selectedIndex).orEmpty(),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(2.dp))
                    Icon(
                        painter = painterResource(R.drawable.ic_expand_more_24),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            },
            // 与入口行一致：当前值最多占约 1/3 行宽，长选项不会把标题挤成两行
            trailingWeight = 0.5f,
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            options.forEachIndexed { index, label ->
                DropdownMenuItem(
                    text = { Text(label, fontSize = 14.sp) },
                    onClick = {
                        expanded = false
                        onSelect(index)
                    },
                    trailingIcon = if (index == selectedIndex) {
                        {
                            Text(
                                text = "✓",
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 14.sp,
                            )
                        }
                    } else null,
                )
            }
        }
    }
}

/**
 * 数值型设置：行点击展开行内滑杆。
 *
 * 拖动期间只更新本地状态（避免每帧重写 SharedPreferences 并触发键盘重排），
 * 松手时吸附到 step 并回调 [onValueChange]。
 */
@Composable
fun SettingSliderRow(
    title: String,
    value: Int,
    min: Int,
    max: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    step: Int = 1,
    unit: String = "",
    summary: String? = null,
    defaultValue: Int? = null,
    defaultLabel: String? = null,
    enabled: Boolean = true,
) {
    var expanded by rememberSaveable(title) { mutableStateOf(false) }
    var local by remember(value) { mutableFloatStateOf(value.coerceIn(min, max).toFloat()) }
    val shown = snap(local, min, max, step)
    val alpha = if (enabled) 1f else 0.38f
    val isDefault = defaultValue != null && shown == defaultValue

    Column(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = SettingsDims.RowMinHeight)
                .clickable(enabled = enabled) { expanded = !expanded }
                .padding(horizontal = SettingsDims.RowHorizontal, vertical = SettingsDims.RowVertical),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontSize = SettingsDims.TitleSize,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
                )
                if (!summary.isNullOrEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = summary,
                        fontSize = SettingsDims.SummarySize,
                        lineHeight = SettingsDims.SummaryLineHeight,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                    )
                }
            }
            Text(
                text = if (isDefault && defaultLabel != null) defaultLabel
                else "$shown $unit".trim(),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                painter = painterResource(R.drawable.ic_expand_more_24),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                modifier = Modifier
                    .size(18.dp)
                    .rotate(if (expanded) 180f else 0f),
            )
        }
        if (expanded) {
            Column(modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 4.dp)) {
                Slider(
                    value = local,
                    onValueChange = { local = it },
                    onValueChangeFinished = { onValueChange(snap(local, min, max, step)) },
                    valueRange = min.toFloat()..max.toFloat(),
                    steps = if (step > 1) ((max - min) / step - 1).coerceAtLeast(0) else 0,
                    enabled = enabled,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp, end = 4.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (defaultValue != null && defaultLabel != null) {
                        TextButton(
                            onClick = {
                                local = defaultValue.toFloat()
                                onValueChange(defaultValue)
                            },
                            enabled = !isDefault && enabled,
                        ) { Text(defaultLabel, fontSize = 12.sp) }
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = if (unit.isEmpty()) "$min ~ $max" else "$min ~ $max $unit",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * 浮点滑杆：按 [step] 换算成整数档位后复用 [SettingSliderRow]。
 *
 * 换算后显示的数值即「档位数」（step = 0.01、unit = "%" 时正好是百分数），
 * 因此 [step] 与 [unit] 必须配套，否则读数的单位会对不上。
 */
@Composable
fun SettingSliderFloatRow(
    title: String,
    value: Float,
    min: Float,
    max: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    step: Float = 0.01f,
    unit: String = "",
    summary: String? = null,
    defaultValue: Float? = null,
    defaultLabel: String? = null,
    enabled: Boolean = true,
) {
    val scale = if (step > 0f) 1f / step else 1f
    fun tick(v: Float): Int = (v * scale).roundToInt()
    SettingSliderRow(
        title = title,
        value = tick(value),
        min = tick(min),
        max = tick(max),
        step = 1,
        unit = unit,
        summary = summary,
        defaultValue = defaultValue?.let { tick(it) },
        defaultLabel = defaultLabel,
        enabled = enabled,
        modifier = modifier,
        onValueChange = { onValueChange(it / scale) },
    )
}

/** 双滑杆（同一行两列数值，例如键水平/垂直间距） */
@Composable
fun SettingTwinSliderRow(
    title: String,
    primaryLabel: String,
    primary: Int,
    secondaryLabel: String,
    secondary: Int,
    min: Int,
    max: Int,
    onValueChange: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
    step: Int = 1,
    unit: String = "",
    defaultLabel: String? = null,
    primaryDefault: Int? = null,
    secondaryDefault: Int? = null,
    enabled: Boolean = true,
) {
    var expanded by rememberSaveable(title) { mutableStateOf(false) }
    var p by remember(primary) { mutableFloatStateOf(primary.coerceIn(min, max).toFloat()) }
    var s by remember(secondary) { mutableFloatStateOf(secondary.coerceIn(min, max).toFloat()) }
    val pv = snap(p, min, max, step)
    val sv = snap(s, min, max, step)
    val alpha = if (enabled) 1f else 0.38f

    Column(modifier = modifier.fillMaxWidth().animateContentSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = SettingsDims.RowMinHeight)
                .clickable(enabled = enabled) { expanded = !expanded }
                .padding(horizontal = SettingsDims.RowHorizontal, vertical = SettingsDims.RowVertical),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontSize = SettingsDims.TitleSize,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "$pv / $sv $unit".trim(),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                painter = painterResource(R.drawable.ic_expand_more_24),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                modifier = Modifier
                    .size(18.dp)
                    .rotate(if (expanded) 180f else 0f),
            )
        }
        if (expanded) {
            Column(modifier = Modifier.padding(horizontal = 12.dp)) {
                SliderLabeled(
                    label = primaryLabel,
                    valueLabel = "$pv $unit".trim(),
                    value = p,
                    min = min,
                    max = max,
                    step = step,
                    enabled = enabled,
                    onValueChange = { p = it },
                    onValueChangeFinished = { onValueChange(snap(p, min, max, step), sv) },
                )
                SliderLabeled(
                    label = secondaryLabel,
                    valueLabel = "$sv $unit".trim(),
                    value = s,
                    min = min,
                    max = max,
                    step = step,
                    enabled = enabled,
                    onValueChange = { s = it },
                    onValueChangeFinished = { onValueChange(pv, snap(s, min, max, step)) },
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp),
                ) {
                    if (defaultLabel != null && primaryDefault != null && secondaryDefault != null) {
                        TextButton(
                            onClick = {
                                p = primaryDefault.toFloat()
                                s = secondaryDefault.toFloat()
                                onValueChange(primaryDefault, secondaryDefault)
                            },
                            enabled = enabled,
                        ) { Text(defaultLabel, fontSize = 12.sp) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SliderLabeled(
    label: String,
    valueLabel: String,
    value: Float,
    min: Int,
    max: Int,
    step: Int,
    enabled: Boolean,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = valueLabel,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = min.toFloat()..max.toFloat(),
            steps = if (step > 1) ((max - min) / step - 1).coerceAtLeast(0) else 0,
            enabled = enabled,
        )
    }
}

/** 通用单选弹窗（主题选择等不适合内联的场合） */
@Composable
fun SettingsPickerDialog(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEachIndexed { index, label ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSelect(index)
                                onDismiss()
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = label,
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        if (index == selectedIndex) {
                            Text(
                                text = "✓",
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 15.sp,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/** 首页搜索入口：看起来像输入框，点击进入搜索页 */
@Composable
fun SettingsSearchEntry(
    placeholder: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
        ),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_search_24),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = placeholder,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 快捷开关 chip：选中态用主色容器，未选中是低调的中性容器 */
@Composable
fun SettingsChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        border = if (selected) null else BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
        ),
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

/** 把滑杆浮点值吸附到 [min, max] 上以 [step] 为步长的离散点 */
fun snap(value: Float, min: Int, max: Int, step: Int): Int {
    if (step <= 1) return value.roundToInt().coerceIn(min, max)
    val snapped = min + ((value - min) / step).roundToInt() * step
    return snapped.coerceIn(min, max)
}
