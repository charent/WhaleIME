package com.whale.ime.compose.settings

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.whale.ime.compose.LocalImeTheme
import com.whale.ime.compose.keyboard.KeyGrid
import com.whale.ime.compose.keyboard.LocalImePalette
import com.whale.ime.compose.keyboard.imePaletteOf
import com.whale.ime.theme.Theme
import com.whale.ime.keyboard.layout.InputModeSwitcher
import com.whale.ime.preference.AppPrefs
import com.whale.ime.keyboard.metrics.Environment
import com.whale.ime.keyboard.builder.KeyboardBuilder
import kotlin.math.roundToInt

/**
 * 键盘实时预览。
 *
 * 复用 [KeyGrid] 与同一份 `SoftKeyboard` 数据：预览走的就是真实渲染路径。
 *
 * 缩放方式：容器按 [scale] 缩小。键位几何随 KeyGrid 内部的
 * `viewSize / skbSize` 自动缩放；键帽文字/图标由 [KeyGrid] 的
 * `textScale`（取 scaleX/scaleY 均值）同比例缩小，否则全尺寸文字
 * 会挤进半尺寸键帽里叠字。
 */
@Composable
fun KeyboardPreview(
    theme: Theme,
    modifier: Modifier = Modifier,
    scale: Float = 0.5f,
) {
    val env = remember { Environment.instance }
    val density = LocalDensity.current
    val widthDp = with(density) { (env.skbWidth * scale).roundToInt().toDp() }
    val heightDp = with(density) { (env.skbHeight * scale).roundToInt().toDp() }

    val background = remember(theme) {
        runCatching {
            theme.backgroundDrawable(AppPrefs.getInstance().keyboardSetting.keyBorder.getValue())
                .toImageBitmap(env.skbWidth, env.skbHeight)
        }.getOrNull()
    }
    val keyboard = remember {
        runCatching {
            val layout = AppPrefs.getInstance().internal.inputDefaultMode.getValue() and
                    InputModeSwitcher.MASK_SKB_LAYOUT
            KeyboardBuilder.instance.getSoftKeyboard(layout)
        }.getOrNull()
    }

    // 必须同时下发调色板：KeyGrid 的键帽 / 功能键 / 回车色都从 LocalImePalette 取，
    // 只给 LocalImeTheme 会退回「默认浅色调色板」，预览就成了深底 + 白键帽，与真实键盘不符
    val palette = remember(theme) { imePaletteOf(theme) }

    CompositionLocalProvider(
        LocalImeTheme provides theme,
        LocalImePalette provides palette,
    ) {
        Box(
            modifier = modifier
                .size(widthDp, heightDp)
                .clipToBounds(),
        ) {
            if (background != null) {
                Image(
                    bitmap = background,
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (keyboard != null) {
                KeyGrid(keyboard = keyboard, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

/**
 * 预览卡片：把 [KeyboardPreview] 装进设置页统一样式的圆角卡片里。
 *
 * [revision] 变化时整块预览重建——键盘尺寸/键位是快照，
 * 间距、圆角这类改动刷新偏好并不会更新已缓存的 `SoftKeyboard`。
 */
@Composable
fun KeyboardPreviewCard(
    theme: Theme,
    modifier: Modifier = Modifier,
    revision: Int = 0,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            key(revision) {
                KeyboardPreview(theme = theme)
            }
        }
    }
}

private fun Drawable.toImageBitmap(width: Int, height: Int): ImageBitmap {
    val bitmap = Bitmap.createBitmap(
        width.coerceAtLeast(1),
        height.coerceAtLeast(1),
        Bitmap.Config.ARGB_8888,
    )
    val canvas = Canvas(bitmap)
    setBounds(0, 0, canvas.width, canvas.height)
    draw(canvas)
    return bitmap.asImageBitmap()
}
