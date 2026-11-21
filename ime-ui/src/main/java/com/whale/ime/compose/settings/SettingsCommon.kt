package com.whale.ime.compose.settings

import android.view.ViewTreeObserver
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.whale.ime.ui.R
import com.whale.ime.preference.ManagedPreference
import com.whale.ime.preference.ManagedPreferenceProvider
import com.whale.ime.ui.setup.SetupActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun string(resId: Int): String = stringResource(resId)

/**
 * 单行级偏好订阅。
 *
 * 旧实现用一个页面级自增 revision 驱动整页重组：改任意一项 → 全页重组。
 * 现在每一行只订阅自己那一项，开关动画、滑杆回显都只影响自己。
 */
@Composable
fun <T : Any> rememberPreferenceValue(pref: ManagedPreference<T>): T {
    val state = remember(pref) { mutableStateOf(pref.getValue()) }
    DisposableEffect(pref) {
        val listener = ManagedPreference.OnChangeListener<T> { _, value -> state.value = value }
        pref.registerOnChangeListener(listener)
        onDispose { pref.unregisterOnChangeListener(listener) }
    }
    return state.value
}

/**
 * 订阅一组 provider 的所有偏好，返回随任意偏好变化自增的版本号。
 *
 * 只用于求值「这一行当前是否可用」（`enableUiOn` 依赖别的偏好），
 * **不要再拿它驱动取值**——取值走 [rememberPreferenceValue]。
 */
@Composable
fun rememberPreferencesRevision(providers: List<ManagedPreferenceProvider>): Int {
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(providers) {
        val listener = ManagedPreference.OnChangeListener<Any> { _, _ -> revision++ }
        providers.forEach { provider ->
            provider.managedPreferences.values.forEach { it.registerOnChangeListener(listener) }
        }
        onDispose {
            providers.forEach { provider ->
                provider.managedPreferences.values.forEach { it.unregisterOnChangeListener(listener) }
            }
        }
    }
    return revision
}

/**
 * 输入法引导是否已全部完成，随页面回到前台重新求值。
 *
 * `SetupActivity.shouldShowUp()` 读的是系统输入法列表（`InputMethodManager` / `Settings.Secure`），
 * 不是 Compose state。设置页在引导页之下早就完成过一次组合，用户从系统「启用输入法」页或
 * 输入法选择器返回时没有任何 state 变化，直接调用会一直显示「启用输入法」入口。
 *
 * 这里把结果放进 state，并在 ON_RESUME 与窗口重新获得焦点时重估；系统写回默认输入法的时机
 * 可能晚于 resume，所以再延迟补刷几次。
 */
@Composable
fun rememberImeSetupDone(): Boolean {
    var done by remember { mutableStateOf(!SetupActivity.shouldShowUp()) }
    var tick by remember { mutableIntStateOf(0) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) tick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val view = LocalView.current
    DisposableEffect(view) {
        val listener = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
            if (hasFocus) tick++
        }
        view.viewTreeObserver.addOnWindowFocusChangeListener(listener)
        onDispose { view.viewTreeObserver.removeOnWindowFocusChangeListener(listener) }
    }

    LaunchedEffect(tick) {
        if (tick == 0) return@LaunchedEffect
        repeat(4) {
            done = !SetupActivity.shouldShowUp()
            if (done) return@LaunchedEffect
            delay(250)
        }
    }
    return done
}

/**
 * 把一组 provider 的全部偏好恢复成声明时的默认值。
 *
 * 这里必须绕开泛型：`ManagedPreference<*>` 无法直接调用 `setValue(T)`。
 * 运行时泛型被擦除，而每个具体实现的 `setValue` 内部就是
 * `putXxx(key, value as T)`，传回声明时的 `defaultValue` 永远是正确类型。
 */
@Suppress("UNCHECKED_CAST")
fun resetManagedPreferences(
    providers: List<ManagedPreferenceProvider>,
    onChanged: (String) -> Unit,
) {
    providers.forEach { provider ->
        provider.managedPreferences.values.forEach { pref ->
            runCatching {
                (pref as ManagedPreference<Any>).setValue(pref.defaultValue)
            }
            onChanged(pref.key)
        }
    }
}

/** 顶栏「恢复默认」：确认后重置本页所有偏好并给出 Snackbar 反馈 */
@Composable
fun SettingsResetAction(
    providers: List<ManagedPreferenceProvider>,
    snackbarHostState: SnackbarHostState,
    onChanged: (String) -> Unit = { SettingsEffects.onChanged(it) },
) {
    var confirming by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val doneMessage = string(R.string.settings_reset_page_done)

    IconButton(onClick = { confirming = true }) {
        Icon(
            painter = painterResource(R.drawable.ic_menu_reset),
            contentDescription = string(R.string.settings_reset_page),
        )
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(string(R.string.settings_reset_page)) },
            text = { Text(string(R.string.settings_reset_page_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    resetManagedPreferences(providers, onChanged)
                    scope.launch {
                        snackbarHostState.showSnackbar(doneMessage)
                    }
                }) { Text(string(R.string.settings_reset_page)) }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) {
                    Text(string(R.string.cancel))
                }
            },
        )
    }
}
