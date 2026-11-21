package com.whale.ime.preference

import android.media.AudioManager
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import com.whale.ime.boot.Launcher
import com.whale.ime.preference.model.FeedbackMode
import com.whale.ime.util.android.audioManager

/**
 * 按键反馈：音效 + 触感。
 *
 * 两条通道共用一套语义（[FeedbackMode]）：
 * - **跟随系统**：完全交给系统通道。震动走 `USAGE_TOUCH`（即系统按键触感那条通道），
 *   声音先看系统「触摸提示音」开关；系统的关了就没有反馈，音量/强度也由系统决定。
 * - **自定义**：应用内档位（1..[FEEDBACK_LEVEL_MAX]）说了算。震动走默认（UNKNOWN）通道，
 *   **不受系统「触感反馈」总开关管辖**；声音直接调 `playSoundEffect` 的音量，同样不看系统开关。
 */
object InputFeedbacks {

    private const val TAG = "WhaleHaptic"

    /**
     * 自定义档位 → 震动时长（ms）。
     *
     * 按键触感要「短促但不至于感觉不到」：20ms 以下的单次震动在不少机型（转子马达、
     * 起振较慢的线性马达）上根本转不起来，这正是「开了触感却没反应」的直接原因。
     */
    private val VIBRATION_DURATIONS = longArrayOf(0L, 20L, 30L, 42L, 58L, 80L)

    /** 自定义档位 → 振幅（1..255）；不支持振幅控制的马达会忽略这一维，只体现时长差异 */
    private val VIBRATION_AMPLITUDES = intArrayOf(0, 100, 140, 180, 220, 255)

    /** 自定义档位 → 音效音量（0f..1f） */
    private val SOUND_VOLUMES = floatArrayOf(0f, 0.2f, 0.4f, 0.6f, 0.8f, 1f)

    private val soundEnabled by AppPrefs.getInstance().feedback.soundEnabled
    private val soundMode by AppPrefs.getInstance().feedback.soundMode
    private val soundLevel by AppPrefs.getInstance().feedback.soundLevel

    private val vibrationEnabled by AppPrefs.getInstance().feedback.vibrationEnabled
    private val vibrationMode by AppPrefs.getInstance().feedback.vibrationMode
    private val vibrationLevel by AppPrefs.getInstance().feedback.vibrationLevel

    private val context get() = Launcher.instance.context

    private val vibrator: Vibrator? by lazy {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            ?: @Suppress("DEPRECATION") context.getSystemService(Vibrator::class.java)
    }

    /** 系统按键触感通道：受系统「触感反馈」开关与强度管辖 */
    private val touchAttributes: VibrationAttributes by lazy {
        VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH)
    }

    private val audioManager by lazy { Launcher.instance.context.audioManager }

    /** 系统「触摸提示音」开关；跟随系统模式下据此决定要不要出声 */
    private val systemSoundEffectsEnabled: Boolean
        get() = runCatching {
            Settings.System.getInt(context.contentResolver, Settings.System.SOUND_EFFECTS_ENABLED, 1) != 0
        }.getOrDefault(true)

    /** 触感档位是否可用且有反馈，仅用于调试日志 */
    private fun logLevel(tag: String, mode: FeedbackMode, level: Int, ampl: Int = 0, dur: Long = 0L) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "$tag mode=$mode level=$level amp=$ampl dur=$dur")
        }
    }

    /** 按键触感 */
    fun hapticFeedback() {
        if (!vibrationEnabled) return
        val mode = vibrationMode
        val level = vibrationLevel.coerceIn(FEEDBACK_LEVEL_MIN, FEEDBACK_LEVEL_MAX)
        if (mode == FeedbackMode.Custom) {
            logLevel("haptic", mode, level, VIBRATION_AMPLITUDES[level], VIBRATION_DURATIONS[level])
        } else {
            logLevel("haptic", mode, level)
        }
        val vib = vibrator ?: return
        if (!vib.hasVibrator()) return
        runCatching {
            if (mode == FeedbackMode.FollowSystem) {
                vib.vibrate(systemHapticEffect(vib), touchAttributes)
            } else {
                // 不带 attributes = UNKNOWN 通道：系统「触感反馈」关掉时依然会震
                vib.vibrate(
                    VibrationEffect.createOneShot(
                        VIBRATION_DURATIONS[level],
                        VIBRATION_AMPLITUDES[level],
                    )
                )
            }
        }.onFailure {
            Log.w(TAG, "vibrate failed: mode=$mode level=$level", it)
        }
    }

    /** 系统按键触感效果：优先取系统预置 CLICK，机型不支持时退化成一次短震（仍走 TOUCH 通道） */
    private fun systemHapticEffect(vib: Vibrator): VibrationEffect {
        val supported = runCatching {
            vib.areEffectsSupported(VibrationEffect.EFFECT_CLICK)[0] == Vibrator.VIBRATION_EFFECT_SUPPORT_YES
        }.getOrDefault(false)
        return if (supported) {
            VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
        } else {
            VibrationEffect.createOneShot(35L, 200)
        }
    }

    enum class SoundEffect {
        Standard, SpaceBar, Delete, Return
    }

    /** 按键音效 */
    fun soundEffect(effect: SoundEffect) {
        if (!soundEnabled) return
        val fx = effect.audioFx()
        when (soundMode) {
            FeedbackMode.FollowSystem -> {
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "sound follow-system enabled=${systemSoundEffectsEnabled}")
                }
                if (systemSoundEffectsEnabled) audioManager.playSoundEffect(fx, -1f)
            }
            FeedbackMode.Custom -> {
                val level = soundLevel.coerceIn(FEEDBACK_LEVEL_MIN, FEEDBACK_LEVEL_MAX)
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "sound custom level=$level volume=${SOUND_VOLUMES[level]}")
                }
                audioManager.playSoundEffect(fx, SOUND_VOLUMES[level])
            }
        }
    }

    private fun SoundEffect.audioFx(): Int = when (this) {
        SoundEffect.Standard -> AudioManager.FX_KEYPRESS_STANDARD
        SoundEffect.SpaceBar -> AudioManager.FX_KEYPRESS_SPACEBAR
        SoundEffect.Delete -> AudioManager.FX_KEYPRESS_DELETE
        SoundEffect.Return -> AudioManager.FX_KEYPRESS_RETURN
    }

    /**
     * 震动
     */
    @JvmStatic
    fun tryVibrate() {
        hapticFeedback()
    }

    /**
     * 播放按键按下的声音
     */
    @JvmStatic
    fun tryPlayKeyDown(code: Int = 0) {
        val effect = when (code) {
            KeyEvent.KEYCODE_DEL -> SoundEffect.Delete
            KeyEvent.KEYCODE_SPACE -> SoundEffect.SpaceBar
            KeyEvent.KEYCODE_ENTER -> SoundEffect.Return
            else -> SoundEffect.Standard
        }
        soundEffect(effect)
    }

}
