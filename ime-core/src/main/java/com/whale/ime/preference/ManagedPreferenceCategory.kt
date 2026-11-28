
package com.whale.ime.preference

import android.content.SharedPreferences
import androidx.annotation.StringRes

/**
 * 偏好声明 DSL。
 *
 * 注意参数顺序：`enableUiOn` 必须保持在最后一位——大量调用点用它做 trailing lambda
 * （`switch(...) { 依赖项.getValue() }`），把 [advanced] 放在它后面会让 lambda
 * 绑到 Boolean 上。
 */
abstract class ManagedPreferenceCategory(
    @StringRes val title: Int,
    protected val sharedPreferences: SharedPreferences
) : ManagedPreferenceProvider() {

    protected fun category(
        @StringRes
        title: Int,
        enableUiOn: (() -> Boolean)? = null
    ){
        val ui = ManagedPreferenceUi.Category(title, enableUiOn)
        ui.registerUi()
    }

    protected fun switch(
        @StringRes
        title: Int,
        key: String,
        defaultValue: Boolean,
        @StringRes
        summary: Int? = null,
        advanced: Boolean = false,
        enableUiOn: (() -> Boolean)? = null,
    ): ManagedPreference.PBool {
        val pref = ManagedPreference.PBool(sharedPreferences, key, defaultValue)
        val ui = ManagedPreferenceUi.Switch(
            title, key, defaultValue, summary, enableUiOn, advanced
        )
        pref.register()
        ui.registerUi()
        return pref
    }

    protected fun <T : Any> list(
        @StringRes
        title: Int,
        key: String,
        defaultValue: T,
        codec: ManagedPreference.StringLikeCodec<T>,
        entryValues: List<T>,
        @StringRes
        entryLabels: List<Int>,
        advanced: Boolean = false,
        enableUiOn: (() -> Boolean)? = null,
    ): ManagedPreference.PStringLike<T> {
        val pref = ManagedPreference.PStringLike(sharedPreferences, key, defaultValue, codec)
        val ui = ManagedPreferenceUi.StringList(
            title, key, defaultValue, codec, entryValues, entryLabels, enableUiOn, advanced
        )
        pref.register()
        ui.registerUi()
        return pref
    }

    protected fun int(
        @StringRes
        title: Int,
        key: String,
        defaultValue: Int,
        min: Int = 0,
        max: Int = Int.MAX_VALUE,
        unit: String = "",
        step: Int = 1,
        @StringRes
        defaultLabel: Int? = null,
        labelFormatter: ((Int) -> String)? = null,
        advanced: Boolean = false,
        enableUiOn: (() -> Boolean)? = null,
    ): ManagedPreference.PInt {
        val pref = ManagedPreference.PInt(sharedPreferences, key, defaultValue)
        // Int can overflow when min < 0 && max == Int.MAX_VALUE
        val ui = if ((max.toLong() - min.toLong()) / step.toLong() >= 500L)
            ManagedPreferenceUi.EditTextInt(
                title, key, defaultValue, min, max, unit, enableUiOn, advanced
            )
        else
            ManagedPreferenceUi.SeekBarInt(
                title, key, defaultValue, min, max, unit, step, defaultLabel,
                labelFormatter, enableUiOn, advanced
            )
        pref.register()
        ui.registerUi()
        return pref
    }

    protected fun float(
        @StringRes
        title: Int,
        key: String,
        defaultValue: Float,
        min: Float,
        max: Float,
        unit: String = "",
        step: Float = 0.01f,
        @StringRes
        defaultLabel: Int? = null,
        advanced: Boolean = false,
        enableUiOn: (() -> Boolean)? = null,
    ): ManagedPreference.PFloat {
        val pref = ManagedPreference.PFloat(sharedPreferences, key, defaultValue)
        val ui = ManagedPreferenceUi.SeekBarFloat(
            title, key, defaultValue, min, max, unit, step, defaultLabel, enableUiOn, advanced
        )
        pref.register()
        ui.registerUi()
        return pref
    }

    protected fun twinInt(
        @StringRes
        title: Int,
        @StringRes
        label: Int,
        key: String,
        defaultValue: Int,
        @StringRes
        secondaryLabel: Int,
        secondaryKey: String,
        secondaryDefaultValue: Int,
        min: Int,
        max: Int,
        unit: String = "",
        step: Int = 1,
        @StringRes
        defaultLabel: Int? = null,
        advanced: Boolean = false,
        enableUiOn: (() -> Boolean)? = null,
    ): Pair<ManagedPreference.PInt, ManagedPreference.PInt> {
        val primary = ManagedPreference.PInt(
            sharedPreferences,
            key, defaultValue,
        )
        val secondary = ManagedPreference.PInt(
            sharedPreferences,
            secondaryKey, secondaryDefaultValue
        )
        val ui = ManagedPreferenceUi.TwinSeekBarInt(
            title,
            label, key, defaultValue,
            secondaryLabel, secondaryKey, secondaryDefaultValue,
            min, max, unit, step, defaultLabel, enableUiOn, advanced
        )
        primary.register()
        secondary.register()
        ui.registerUi()
        return primary to secondary
    }
}
