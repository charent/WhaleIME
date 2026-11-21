package com.whale.ime.compose.settings.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.whale.ime.theme.Theme
import com.whale.ime.ui.R

/**
 * 主题色卡：一块键盘底色 + 一枚键帽 + 一枚回车的迷你缩略图。
 *
 * 主题名是身份标识，「朱红 / 洋红 / 藕粉」这类近色光看文字分不清，所以列表和选择弹窗里都用色卡先给出视觉锚点。
 */
@Composable
fun ThemeSwatch(
    theme: Theme,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = modifier
            .size(44.dp)
            .clip(shape)
            .background(Color(theme.keyboardColor))
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                },
                shape = shape,
            ),
    ) {
        if (theme.keyboardResources != 0) {
            Image(
                painter = painterResource(theme.keyboardResources),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
        // 键帽
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .width(24.dp)
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color(theme.keyBackgroundColor)),
        )
        // 回车键
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 5.dp, bottom = 5.dp)
                .size(7.dp)
                .clip(CircleShape)
                .background(Color(theme.accentKeyBackgroundColor)),
        )
    }
}

/**
 * 主题选择弹窗：色卡 + 名称。
 *
 * 内置主题已有二十多个，纯文字列表选不出来，所以按 [ThemeSwatch] 铺行；行数多了弹窗内部滚动。
 */
@Composable
fun ThemePickerDialog(
    title: String,
    themes: List<Theme>,
    selectedName: String?,
    onSelect: (Theme) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                themes.forEach { theme ->
                    val selected = theme.name == selectedName
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                            .padding(vertical = 4.dp)
                            .clickable {
                                onSelect(theme)
                                onDismiss()
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ThemeSwatch(theme = theme, selected = selected)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = theme.name,
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (selected) {
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
