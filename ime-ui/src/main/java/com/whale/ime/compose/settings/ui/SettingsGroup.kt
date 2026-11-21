package com.whale.ime.compose.settings.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 卡片式分组。
 *
 * 视觉：圆角 24dp 的 `surfaceContainerLow` 卡片 + 1dp 描边（浅色下卡片与背景对比很弱，
 * 描边负责给出界限；深色下描边同时避免卡片“糊”进背景）。
 *
 * 组内行之间不画分割线：卡片本身就承担了分组语义，靠行高与图标对齐保持节奏，
 * 这也和 iOS 分组列表、Material You 的 grouped list 一致。
 */
@Composable
fun SettingsGroup(
    title: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (!title.isNullOrEmpty()) {
            SettingsGroupHeader(title = title)
        }
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 0.dp),
            shape = RoundedCornerShape(SettingsDims.CardCorner),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
            ),
        ) {
            Column(
                modifier = Modifier.padding(vertical = SettingsDims.CardInnerVertical),
                content = content,
            )
        }
    }
}

/** 分组标题（卡片上方的小标签），可带尾部操作 */
@Composable
fun SettingsGroupHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    androidx.compose.foundation.layout.Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = 8.dp,
                end = 8.dp,
                top = SettingsDims.SectionTitleTop,
                bottom = SettingsDims.SectionTitleBottom,
            ),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}
