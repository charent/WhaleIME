package com.whale.ime.preference

import android.content.SharedPreferences
import com.whale.ime.core.R
import com.whale.ime.preference.model.ClipboardLayoutMode

/** 剪贴板历史记录上限：默认 500 条，最小 50 条，最大 1000 条，滑动步进 50 条 */
const val CLIPBOARD_HISTORY_LIMIT_DEFAULT = 500
const val CLIPBOARD_HISTORY_LIMIT_MIN = 50
const val CLIPBOARD_HISTORY_LIMIT_MAX = 1000
const val CLIPBOARD_HISTORY_LIMIT_STEP = 50

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
