package com.whale.ime.preference.model

import com.whale.ime.preference.ManagedPreference

/**
 * 按键反馈（音效 / 触感）的来源模式。
 *
 * - [FollowSystem]：完全交给系统通道——系统的「触摸提示音 / 触感反馈」开关关闭时就没有反馈，
 *   音量与强度跟随系统设置；
 * - [Custom]：应用内档位说了算，不受系统开关限制（系统开关关闭时依然生效）。
 */
enum class FeedbackMode {
    FollowSystem,
    Custom;

    companion object : ManagedPreference.StringLikeCodec<FeedbackMode> {
        override fun decode(raw: String) = valueOf(raw)
    }
}
