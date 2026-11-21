package com.whale.ime.compose.settings.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.whale.ime.ui.R

/**
 * 设置行基元。
 *
 * 排版约定（所有行共用，别在调用方覆写）：
 * - 标题 [SettingsDims.TitleSize]，摘要 [SettingsDims.SummarySize]；
 * - 最小高度 [SettingsDims.RowMinHeight]，左右 [SettingsDims.RowHorizontal]；
 * - 禁用态整行 38% 透明度（Material 规范的 disabled）；
 * - 尾部永远在右侧居中，箭头是唯一表示“可进入”的视觉线索。
 *
 * [trailingWeight] 给尾部一个宽度上限：右侧是要展示长文本（哈希、时间戳这类）时传它，
 * 让标题先拿到主要宽度，尾部超过上限就自己省略，不会把标题挤成两行。
 */
@Composable
fun SettingRow(
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    enabled: Boolean = true,
    showChevron: Boolean = false,
    iconRes: Int? = null,
    leading: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
    trailingWeight: Float? = null,
) {
    val alpha = if (enabled) 1f else 0.38f
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = SettingsDims.RowMinHeight)
            .then(
                if (onClick != null && enabled) Modifier.clickable(onClick = onClick) else Modifier
            )
            .padding(horizontal = SettingsDims.RowHorizontal, vertical = SettingsDims.RowVertical),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (iconRes != null) {
            SettingIconBox(iconRes = iconRes, enabled = enabled)
            Spacer(Modifier.width(14.dp))
        }
        if (leading != null) {
            leading()
            Spacer(Modifier.width(14.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontSize = SettingsDims.TitleSize,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!summary.isNullOrEmpty()) {
                Spacer(Modifier.size(2.dp))
                Text(
                    text = summary,
                    fontSize = SettingsDims.SummarySize,
                    lineHeight = SettingsDims.SummaryLineHeight,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            // fill = false：尾部不需要上限时按内容宽度排；给了权重则最多占分配到的宽度
            if (trailingWeight != null) {
                Box(
                    modifier = Modifier.weight(trailingWeight, fill = false),
                    contentAlignment = Alignment.CenterEnd,
                ) { trailing() }
            } else {
                trailing()
            }
        }
        if (showChevron) {
            Spacer(Modifier.width(4.dp))
            Icon(
                painter = painterResource(R.drawable.ic_chevron_right_24),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha * 0.8f),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** 前导图标：圆角方块底色，让入口列表有视觉锚点而不是一排灰字 */
@Composable
fun SettingIconBox(
    iconRes: Int,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .size(SettingsDims.IconBox)
            .clip(RoundedCornerShape(12.dp))
            .background(
                MaterialTheme.colorScheme.primaryContainer.copy(
                    alpha = if (enabled) 0.9f else 0.38f
                )
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(SettingsDims.IconSize),
        )
    }
}

/** 开关行：整行可点，右侧开关只做状态展示 */
@Composable
fun SettingSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    enabled: Boolean = true,
    iconRes: Int? = null,
) {
    SettingRow(
        title = title,
        modifier = modifier,
        summary = summary,
        enabled = enabled,
        iconRes = iconRes,
        onClick = { onCheckedChange(!checked) },
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
            )
        },
    )
}

/**
 * 多选行：整行可点，右侧复选框只做状态展示。
 *
 * [indent] 用于「设置项」这类父行下的子项，缩进后视觉上属于上一行。
 */
@Composable
fun SettingCheckRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    enabled: Boolean = true,
    indent: Boolean = false,
) {
    SettingRow(
        title = title,
        modifier = if (indent) modifier.padding(start = 24.dp) else modifier,
        summary = summary,
        enabled = enabled,
        onClick = { onCheckedChange(!checked) },
        trailing = {
            Checkbox(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
            )
        },
    )
}

/** 入口行：带当前值摘要 + 箭头 */
@Composable
fun SettingNavRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    value: String? = null,
    enabled: Boolean = true,
    iconRes: Int? = null,
    leading: @Composable (() -> Unit)? = null,
) {
    val hasValue = !value.isNullOrEmpty()
    SettingRow(
        title = title,
        modifier = modifier,
        summary = summary,
        enabled = enabled,
        iconRes = iconRes,
        leading = leading,
        showChevron = true,
        onClick = onClick,
        trailing = if (hasValue) {
            {
                Text(
                    text = value.orEmpty(),
                    fontSize = SettingsDims.SummarySize,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else null,
        // 值最长只占约 1/3 行宽，剩下的都留给标题：长哈希/时间戳不会把标题挤成两行
        trailingWeight = if (hasValue) 0.5f else null,
    )
}

/** 纯信息行（不可点），用于「设备不支持手写」这类说明 */
@Composable
fun SettingInfoRow(
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
) {
    SettingRow(title = title, modifier = modifier, summary = summary, enabled = false)
}
