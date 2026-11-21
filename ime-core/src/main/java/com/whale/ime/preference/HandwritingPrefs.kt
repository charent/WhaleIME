package com.whale.ime.preference

import android.content.SharedPreferences
import com.whale.ime.core.R

/**
 * 手写输入（笔迹粗细 / 识别灵敏度 / 自动上屏）。
 *
 * **类名 `Handwriting` 即备份分组 id**，不要改名。
 */
class Handwriting(sharedPreferences: SharedPreferences) :
    ManagedPreferenceCategory(R.string.ime_settings_handwriting, sharedPreferences) {

    val handWritingWidth = int(
        R.string.paint_thickness,
        "hand_writing_width",
        35,
        0,
        100,
        "%",
        defaultLabel = R.string.system_default
    )

    val handWritingSpeed = int(
        R.string.discern_sensitive,
        "hand_writing_speed",
        500,
        300,
        1300,
        "毫秒",
        100,
        defaultLabel = R.string.number_500_ms
    )

    /** 停笔超时后自动把第一候选上屏并清空画布（默认关：仅清空画布，候选保留供点选） */
    val handWritingAutoCommit = switch(
        R.string.handwriting_auto_commit,
        "hand_writing_auto_commit",
        false,
        R.string.handwriting_auto_commit_tips
    )
}
