package com.whale.ime.compose.settings

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.whale.ime.boot.Launcher
import com.whale.ime.clipboard.ClipboardExpiry
import com.whale.ime.keyboard.state.KeyboardManager
import com.whale.ime.keyboard.layout.InputModeSwitcher
import com.whale.ime.preference.AppPrefs
import com.whale.ime.dispatch.InputDispatcher
import com.whale.ime.keyboard.metrics.Environment
import com.whale.ime.ui.activity.LauncherActivity
import com.whale.ime.keyboard.builder.KeyboardBuilder
import com.whale.ime.engine.schema.CorrectionSchema
import com.whale.ime.config.SchemaIds

/**
 * 设置变更的副作用收敛点。
 *
 * 旧实现里每个设置页各自 `DisposableEffect` 注册监听，并在页面里重复
 * 「`Environment.initData()` + `clearKeyboardMap()` + `clearKeyboard()`」三连；
 * 现在由渲染层统一回调 [onChanged]，新增设置项只要 key 落进下面的集合就自动生效，
 * 不会再出现「改了设置但键盘没刷新」的漏挂。
 */
object SettingsEffects {

    /** 改字号/间距/圆角/键盘样式：键盘环境尺寸与缓存都要重算 */
    private val KeyboardRebuildKeys = setOf(
        // 尺寸（键盘高度比例 / 与屏幕底部的留白）
        "keyboard_height_ratio", "keyboard_padding_bottom_normal",
        // 字体
        "candidate_font_scale", "candidate_font_weight",
        "keyboard_font_size", "key_letter_font_weight",
        "key_symbol_font_scale", "key_symbol_font_weight",
        "key_function_font_scale", "key_function_font_weight",
        "candidate_size", "candidates_bottom_gap",
        "keyboard_balloon_show_enable",
        // 键盘外观（ThemePrefs）
        "keyboard_style_mod", "keyboard_symbol_show_enable", "keyboard_symbol_slide_up_mod",
        "key_border", "keyboard_key_margin_x", "keyboard_key_margin_y", "key_radius",
        "keyboard_abc_number_line_enable",
    )

    /** 仅需要重建键盘（不涉及尺寸重算） */
    private val KeyboardInvalidateKeys = setOf(
        "full_display_keyboard_enable",
        "full_display_key_mode_left",
        "full_display_key_mode_right",
        "full_display_center_mode",
        "keyboard_double_input_pinyin_enable",
        "keyboard_swap_lang_number_key_enable",
        "space_swipe_move_cursor",
        "swipe_move_cursor_speed",
        "keyboard_menu_lock_english_enable",
        "keyboard_one_handed_mod_enable",
        "keyboard_one_handed_mod",
        "keyboard_mnemonic_show_enable",
        // 回车键「发送」：只影响键帽状态与点击语义，键盘数据重取即可
        "enter_key_send_in_chat",
    )

    /** Rime option：简繁、表情 */
    private val ImeOptionKeys = setOf(
        "chinese_jian_fan_enable",
        "emoji_input_enable",
    )

    /** 需要切换 Rime 方案（双拼） */
    private val SchemaKeys = setOf("double_pinyin_schema_mode")

    /** 需要重写 schema patch（纠错 / 模糊音 / v 键等同 u） */
    private val CorrectionKeys = setOf(
        "pinyin_correction_enable",
        "pinyin_fuzzy_enable",
        "pinyin_v_as_u_enable",
    )

    /** 剪贴板内容自动失效：开关、时长、是否保留历史都要重排到期定时器 */
    private val ClipboardExpiryKeys = setOf(
        "clipboard_enable",
        "clipboard_expire_enable",
        "clipboard_expire_duration",
        "clipboard_expire_keep_history",
    )

    /** 统一的变更入口：按 key 分发到对应副作用 */
    fun onChanged(key: String) {
        when (key) {
            in KeyboardRebuildKeys -> rebuildKeyboard()
            in KeyboardInvalidateKeys -> KeyboardManager.instance.clearKeyboard()
            in ImeOptionKeys -> applyImeOption()
            in SchemaKeys -> applyPinyinSchema()
            in CorrectionKeys -> applyCorrection()
            in ClipboardExpiryKeys -> ClipboardExpiry.refresh()
        }
    }

    /** 重算键盘环境尺寸并让所有键盘缓存失效 */
    fun rebuildKeyboard() {
        Environment.instance.initData()
        KeyboardBuilder.instance.clearKeyboardMap()
        KeyboardManager.instance.clearKeyboard()
    }

    /** 简繁 / 表情等 Rime option 立即推给引擎 */
    fun applyImeOption() {
        InputDispatcher.nativeUpdateImeOption()
    }

    /** 双拼方案切换：引擎侧切 schema */
    fun applyPinyinSchema() {
        val mode = AppPrefs.getInstance().input.doublePYSchemaMode.getValue()
        InputModeSwitcher.switchModeForSetting(
            Pair(
                InputModeSwitcher.MASK_SKB_LAYOUT_QWERTY_PINYIN,
                SchemaIds.SCHEMA_ZH_DOUBLE_FLYPY + mode,
            )
        )
    }

    /** 纠错 / 模糊音：把开关落成 pinyin.custom.yaml patch 并重部署（内部后台执行） */
    fun applyCorrection() {
        CorrectionSchema.onSettingChanged(Launcher.instance.context)
    }

    /** 隐藏桌面图标：动态启用/禁用 LauncherActivity 组件 */
    fun applyLauncherIconVisibility(context: Context, hide: Boolean) {
        val componentName = ComponentName(context.packageName, LauncherActivity::class.java.name)
        context.packageManager.setComponentEnabledSetting(
            componentName,
            if (hide) PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            else PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP,
        )
    }
}
