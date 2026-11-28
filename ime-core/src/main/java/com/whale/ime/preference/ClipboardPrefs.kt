package com.whale.ime.preference

import android.content.SharedPreferences
import com.whale.ime.core.R
import com.whale.ime.preference.model.ClipboardLayoutMode
import java.util.Locale

/** 剪贴板历史记录上限：默认 500 条，最小 50 条，最大 1000 条，滑动步进 50 条 */
const val CLIPBOARD_HISTORY_LIMIT_DEFAULT = 500
const val CLIPBOARD_HISTORY_LIMIT_MIN = 50
const val CLIPBOARD_HISTORY_LIMIT_MAX = 1000
const val CLIPBOARD_HISTORY_LIMIT_STEP = 50

/** 剪贴板内容保留时长：默认 10 分钟，最小 30 秒，最大 60 分钟，滑动步进 30 秒 */
const val CLIPBOARD_EXPIRE_DURATION_DEFAULT = 600
const val CLIPBOARD_EXPIRE_DURATION_MIN = 30
const val CLIPBOARD_EXPIRE_DURATION_MAX = 3600
const val CLIPBOARD_EXPIRE_DURATION_STEP = 30

/**
 * 保留时长的读数：不足 1 分钟按秒、整分钟按分钟、其余保留一位小数。
 *
 * 值本身按秒存（30 秒的粒度），但用户读的是「10 分钟」这种量级，
 * 直接把 600 秒摆在设置行上很别扭，所以走 [ManagedPreferenceUi.SeekBarInt.labelFormatter]。
 */
internal fun formatExpireDuration(seconds: Int): String = when {
    seconds < 60 -> "$seconds 秒"
    seconds % 60 == 0 -> "${seconds / 60} 分钟"
    else -> String.format(Locale.getDefault(), "%.1f 分钟", seconds / 60f)
}

/**
 * 剪贴板监听、历史上限、建议条与布局。
 *
 * **类名 `Clipboard` 即备份分组 id**，不要改名。
 */
class Clipboard(sharedPreferences: SharedPreferences) :
    ManagedPreferenceCategory(R.string.clipboard, sharedPreferences) {

    val clipboardListening = switch(R.string.clipboard_listening, "clipboard_enable", true)
    val clipboardHistoryLimit = int(
        R.string.clipboard_limit,
        "clipboard_limit",
        CLIPBOARD_HISTORY_LIMIT_DEFAULT,
        CLIPBOARD_HISTORY_LIMIT_MIN,
        CLIPBOARD_HISTORY_LIMIT_MAX,
        "条",
        CLIPBOARD_HISTORY_LIMIT_STEP,
        defaultLabel = R.string.num_500
    ) { clipboardListening.getValue() }
    val clipboardSuggestion = switch(
        R.string.clipboard_suggestion, "clipboard_suggestion", true
    ) { clipboardListening.getValue() }
    val clipboardItemTimeout = int(
        R.string.clipboard_suggestion_timeout,
        "clipboard_item_timeout",
        30,
        10,
        200,
        "秒"
    ) { clipboardListening.getValue() && clipboardSuggestion.getValue() }

    /**
     * 内容自动失效：到期后把内容从系统剪贴板移除（长按粘贴也拿不到）。
     *
     * 注意与上面的 [clipboardItemTimeout] 区分：那个管的是候选栏「剪贴板提示」气泡活多久，
     * 这个管的是复制的内容本身还能不能被粘贴。
     */
    val clipboardExpireEnable = switch(
        R.string.clipboard_expire_enable,
        "clipboard_expire_enable",
        true,
        summary = R.string.clipboard_expire_enable_summary
    ) { clipboardListening.getValue() }

    val clipboardExpireDuration = int(
        R.string.clipboard_expire_duration,
        "clipboard_expire_duration",
        CLIPBOARD_EXPIRE_DURATION_DEFAULT,
        CLIPBOARD_EXPIRE_DURATION_MIN,
        CLIPBOARD_EXPIRE_DURATION_MAX,
        unit = "秒",
        step = CLIPBOARD_EXPIRE_DURATION_STEP,
        defaultLabel = R.string.num_10_minutes,
        labelFormatter = ::formatExpireDuration
    ) { clipboardListening.getValue() && clipboardExpireEnable.getValue() }

    val clipboardExpireKeepHistory = switch(
        R.string.clipboard_expire_keep_history,
        "clipboard_expire_keep_history",
        true,
        summary = R.string.clipboard_expire_keep_history_summary
    ) { clipboardListening.getValue() && clipboardExpireEnable.getValue() }

    val clipboardLayoutCompact = list(
        R.string.clipboard_layout_compact_mode,
        "clipboard_layout_mode",
        ClipboardLayoutMode.ListView,
        ClipboardLayoutMode,
        listOf(
            ClipboardLayoutMode.ListView,
            ClipboardLayoutMode.GridView,
            ClipboardLayoutMode.FlexboxView
        ),
        listOf(
            R.string.clipboard_layout_mode_list,
            R.string.clipboard_layout_mode_grid,
            R.string.clipboard_layout_mode_flexbox
        )
    ) {
        clipboardListening.getValue()
    }
}
