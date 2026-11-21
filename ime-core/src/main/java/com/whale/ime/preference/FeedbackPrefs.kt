package com.whale.ime.preference

import android.content.SharedPreferences
import com.whale.ime.core.R
import com.whale.ime.preference.model.FeedbackMode

/** 按键反馈（音效 / 触感）自定义档位：1..5 逐级增强，默认 3（中） */
const val FEEDBACK_LEVEL_MIN = 1
const val FEEDBACK_LEVEL_MAX = 5
const val FEEDBACK_LEVEL_DEFAULT = 3

/**
 * 按键反馈：音效与触感。
 *
 * 开关与档位分开存：关掉只写 `enabled = false`，档位保留，下次打开还是上次的强度。
 * 档位 1..[FEEDBACK_LEVEL_MAX] 逐级增强；模式为 [FeedbackMode.Custom] 时才使用应用内档位，
 * [FeedbackMode.FollowSystem] 则完全交给系统通道（见 InputFeedbacks）。
 *
 * **类名 `Feedback` 即备份分组 id**，不要改名。
 */
class Feedback(sharedPreferences: SharedPreferences) :
    ManagedPreferenceCategory(R.string.keyboard_feedback, sharedPreferences) {

    val soundEnabled = switch(
        R.string.button_sound_volume, "key_press_sound_enabled", false
    )

    val soundMode = list(
        R.string.feedback_mode,
        "key_press_sound_mode",
        FeedbackMode.Custom,
        FeedbackMode,
        listOf(FeedbackMode.FollowSystem, FeedbackMode.Custom),
        listOf(R.string.feedback_mode_follow_system, R.string.feedback_mode_custom),
    ) { soundEnabled.getValue() }

    val soundLevel = int(
        R.string.settings_sound_level,
        "key_press_sound_level",
        FEEDBACK_LEVEL_DEFAULT,
        FEEDBACK_LEVEL_MIN,
        FEEDBACK_LEVEL_MAX,
        "档",
    ) { soundEnabled.getValue() && soundMode.getValue() == FeedbackMode.Custom }

    val vibrationEnabled = switch(
        R.string.button_vibration_amplitude, "key_press_vibration_enabled", false
    )

    val vibrationMode = list(
        R.string.feedback_mode,
        "key_press_vibration_mode",
        FeedbackMode.Custom,
        FeedbackMode,
        listOf(FeedbackMode.FollowSystem, FeedbackMode.Custom),
        listOf(R.string.feedback_mode_follow_system, R.string.feedback_mode_custom),
    ) { vibrationEnabled.getValue() }

    val vibrationLevel = int(
        R.string.settings_vibration_level,
        "key_press_vibration_level",
        FEEDBACK_LEVEL_DEFAULT,
        FEEDBACK_LEVEL_MIN,
        FEEDBACK_LEVEL_MAX,
        "档",
    ) { vibrationEnabled.getValue() && vibrationMode.getValue() == FeedbackMode.Custom }
}
