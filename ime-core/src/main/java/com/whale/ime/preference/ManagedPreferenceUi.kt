package com.whale.ime.preference

import androidx.annotation.StringRes
import com.whale.ime.preference.ManagedPreference.StringLikeCodec

/**
 * 偏好项的 **UI 元数据**。
 *
 * 这里不持有任何 `androidx.preference` 类型，也不负责创建 View：
 * 它只是「怎么描述这一行」的纯数据，由 `SettingsList` 翻译成 Compose 控件。
 * 数据侧（取值/写值/变更通知）仍是 [ManagedPreference]，两层完全解耦。
 */
abstract class ManagedPreferenceUi(
    val key: String,
    private val enableUiOn: (() -> Boolean)? = null,
    /**
     * 冷门项：渲染时收进「高级选项」折叠区，避免默认视图被低频设置淹没。
     */
    val advanced: Boolean = false,
) {

    fun isEnabled() = enableUiOn?.invoke() ?: true

    /** 分组标题 */
    class Category(
        @StringRes
        val title: Int,
        enableUiOn: (() -> Boolean)? = null
    ) : ManagedPreferenceUi("", enableUiOn)

    /** 开关 */
    class Switch(
        @StringRes
        val title: Int,
        key: String,
        val defaultValue: Boolean,
        @StringRes
        val summary: Int? = null,
        enableUiOn: (() -> Boolean)? = null,
        advanced: Boolean = false,
    ) : ManagedPreferenceUi(key, enableUiOn, advanced)

    /** 单选（≤4 项渲染为分段控件，>4 项渲染为下拉菜单） */
    class StringList<T : Any>(
        @StringRes
        val title: Int,
        key: String,
        val defaultValue: T,
        val codec: StringLikeCodec<T>,
        val entryValues: List<T>,
        @StringRes
        val entryLabels: List<Int>,
        enableUiOn: (() -> Boolean)? = null,
        advanced: Boolean = false,
    ) : ManagedPreferenceUi(key, enableUiOn, advanced) {

        /** 已持久化的原始字符串对应第几项（找不到返回 -1） */
        fun indexOfRaw(raw: String?): Int =
            entryValues.indexOfFirst { codec.encode(it) == raw }
    }

    /** 数字输入（范围超出 500 档时使用） */
    class EditTextInt(
        @StringRes
        val title: Int,
        key: String,
        val defaultValue: Int,
        val min: Int,
        val max: Int,
        val unit: String = "",
        enableUiOn: (() -> Boolean)? = null,
        advanced: Boolean = false,
    ) : ManagedPreferenceUi(key, enableUiOn, advanced)

    /** 单滑杆（行内展开，拖动即预览，松手生效） */
    class SeekBarInt(
        @StringRes
        val title: Int,
        key: String,
        val defaultValue: Int,
        val min: Int,
        val max: Int,
        val unit: String = "",
        val step: Int = 1,
        @StringRes
        val defaultLabel: Int? = null,
        enableUiOn: (() -> Boolean)? = null,
        advanced: Boolean = false,
    ) : ManagedPreferenceUi(key, enableUiOn, advanced)

    /**
     * 浮点滑杆（比例类设置，例如键盘高度占屏幕的比例）。
     *
     * 渲染时按 [step] 换算成整数档位复用单滑杆控件，所以 [min]/[max]/[step]
     * 必须是「步数的整数倍」，否则首尾档位会吸附偏差。
     */
    class SeekBarFloat(
        @StringRes
        val title: Int,
        key: String,
        val defaultValue: Float,
        val min: Float,
        val max: Float,
        val unit: String = "",
        val step: Float = 0.01f,
        @StringRes
        val defaultLabel: Int? = null,
        enableUiOn: (() -> Boolean)? = null,
        advanced: Boolean = false,
    ) : ManagedPreferenceUi(key, enableUiOn, advanced)

    /** 双滑杆（键水平/垂直间距，唯一使用点 [com.whale.ime.theme.ThemePrefs]） */
    class TwinSeekBarInt(
        @StringRes
        val title: Int,
        @StringRes
        val label: Int,
        key: String,
        val defaultValue: Int,
        @StringRes
        val secondaryLabel: Int,
        val secondaryKey: String,
        val secondaryDefaultValue: Int,
        val min: Int,
        val max: Int,
        val unit: String = "",
        val step: Int = 1,
        @StringRes
        val defaultLabel: Int? = null,
        enableUiOn: (() -> Boolean)? = null,
        advanced: Boolean = false,
    ) : ManagedPreferenceUi(key, enableUiOn, advanced)
}
