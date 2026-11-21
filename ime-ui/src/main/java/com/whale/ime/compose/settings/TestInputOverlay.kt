package com.whale.ime.compose.settings

import android.content.Intent
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.whale.ime.ui.R
import com.whale.ime.preference.AppPrefs
import com.whale.ime.ui.setup.SetupActivity
import com.whale.ime.util.android.startActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 设置页试输入浮层：全局悬浮球 + 底部试输入面板。
 *
 * 悬浮球可拖动，位置持久化到 [AppPrefs.Internal]；点击弹出 ModalBottomSheet，
 * 内含多行输入框——聚焦后自动弹起当前输入法，可直接试用打字/候选/表情等效果。
 * 输入内容只存在 Compose state 里，面板关闭即丢弃，绝不落盘。
 */
@Composable
fun TestInputOverlay(modifier: Modifier = Modifier) {
    var sheetOpen by remember { mutableStateOf(false) }
    TestInputBall(
        visible = !sheetOpen,
        onClick = { sheetOpen = true },
        modifier = modifier,
    )
    if (sheetOpen) {
        TestInputSheet(onDismiss = { sheetOpen = false })
    }
}

private const val BALL_SIZE_DP = 52
private const val BALL_MARGIN_PX = 24f

/** 可拖动悬浮球；位置以左上角 px 坐标持久化，越界时收敛回屏内 */
@Composable
private fun TestInputBall(
    visible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!visible) return
    val prefs = AppPrefs.getInstance().internal
    val density = LocalDensity.current
    var container by remember { mutableStateOf(IntSize.Zero) }
    var offset by remember {
        mutableStateOf(
            Offset(
                prefs.testBallX.getValue(),
                prefs.testBallY.getValue(),
            )
        )
    }
    val ballPx = with(density) { BALL_SIZE_DP.dp.toPx() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { container = it }
    ) {
        // 未拖动过（<0）时放在右侧 1/3 高度处；拖出屏外时收敛回屏内
        val defaultX = container.width - ballPx - BALL_MARGIN_PX
        val defaultY = container.height / 3f
        val maxX = (container.width - ballPx).coerceAtLeast(0f)
        val maxY = (container.height - ballPx).coerceAtLeast(0f)
        val clamped = if (offset.x < 0f || container.width == 0) {
            Offset(defaultX, defaultY)
        } else {
            Offset(offset.x.coerceIn(0f, maxX), offset.y.coerceIn(0f, maxY))
        }

        Box(
            modifier = Modifier
                .offset { IntOffset(clamped.x.roundToInt(), clamped.y.roundToInt()) }
                .size(BALL_SIZE_DP.dp)
                .background(
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.92f),
                    shape = CircleShape,
                )
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        offset = Offset(offset.x + dragAmount.x, offset.y + dragAmount.y)
                    }
                }
                .clickable {
                    prefs.testBallX.setValue(clamped.x)
                    prefs.testBallY.setValue(clamped.y)
                    onClick()
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_ui_keyboard),
                contentDescription = stringResource(R.string.test_input_title),
                tint = Color.White,
                modifier = Modifier.size(26.dp),
            )
        }
    }
}

/**
 * 试输入面板：本输入法不是当前默认输入法时显示启用引导，否则显示输入框。
 * 输入内容仅保存在内存 state，关闭面板即丢弃。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TestInputSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    var text by remember { mutableStateOf("") }
    val imeReady = remember { isCurrentIme(context) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        if (imeReady) {
            TestInputContent(
                text = text,
                onTextChange = { text = it },
                onClear = { text = "" },
                onDismiss = onDismiss,
            )
        } else {
            EnableImeGuide()
        }
    }
}

@Composable
private fun TestInputContent(
    text: String,
    onTextChange: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        // 等 Sheet 展开后再要焦点，弹起当前输入法
        delay(400)
        runCatching { focusRequester.requestFocus() }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 32.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.test_input_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onClear) { Text(stringResource(R.string.test_input_clear)) }
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
        Text(
            text = stringResource(R.string.test_input_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester),
            minLines = 4,
            maxLines = 8,
        )
        Spacer(Modifier.height(4.dp))
    }
}

/** 未启用本输入法时的引导卡片 */
@Composable
private fun EnableImeGuide() {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.test_input_enable_ime_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.test_input_enable_ime_summary),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = { context.startActivity<SetupActivity>() }) {
            Text(stringResource(R.string.test_input_enable_ime_action))
        }
    }
}

/** 本输入法是否为系统当前选中的默认输入法 */
private fun isCurrentIme(context: android.content.Context): Boolean {
    val default = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.DEFAULT_INPUT_METHOD,
    ) ?: return false
    return default.startsWith("${context.packageName}/")
}
