package com.whale.ime.preference

import android.content.SharedPreferences
import com.whale.ime.core.R
import com.whale.ime.keyboard.metrics.Environment
import com.whale.ime.preference.model.HalfWidthSymbolsMode
import com.whale.ime.preference.model.KeyboardFontWeight
import com.whale.ime.preference.model.KeyboardOneHandedMod
import com.whale.ime.preference.model.KeyboardSymbolSlideUpMod

/**
 * 键盘外观与交互（尺寸 / 字号 / 间距 / 圆角 / 手势）。
 *
 * **类名 `KeyboardSetting` 即备份分组 id**，不要改名。
 */
class KeyboardSetting(sharedPreferences: SharedPreferences) :
    ManagedPreferenceCategory(R.string.setting_ime_keyboard, sharedPreferences) {

    // 尺寸：键盘按键区占屏幕的比例 + 键盘与屏幕底部的留白。
    // 键盘上「编辑键盘」浮层拖动的就是这两项，这里是手动微调的入口。
    val titleSize = category(R.string.setting_keyboard_size)

    /** 键盘高度：按键区占屏幕高度的比例，越大键帽越高；横屏另有 internal.keyboardHeightRatioLandscape */
    val keyboardHeightRatio = float(
        R.string.setting_keyboard_height_ratio,
        "keyboard_height_ratio",
        0.3f,
        Environment.KEYBOARD_HEIGHT_RATIO_MIN,
        Environment.KEYBOARD_HEIGHT_RATIO_MAX,
        "%",
        0.01f
    )

    /** 键盘与屏幕底部之间的留白（dp），越大键盘整体越靠上（0 = 贴着导航栏） */
    val keyboardBottomGap = int(
        R.string.setting_keyboard_bottom_gap,
        "keyboard_padding_bottom_normal",
        0,
        0,
        Environment.KEYBOARD_BOTTOM_GAP_MAX_DP,
        "dp"
    )

    // 字体：候选字与键帽文字（字母 / 上方符号 / 功能键说明）的大小、粗细分开调。
    // 键帽字号的上限在 Environment 里按键帽净高夹紧，避免字号大过键帽导致布局错乱。
    val titleFont = category(R.string.setting_keyboard_font)

    /**
     * 候选字字号（相对默认候选字）：只缩放文字，不改候选栏高度。
     * 上限受候选行高限制（见 Environment），更大的字请配合「候选栏高度」一起调。
     */
    val candidateFontScale = int(
        R.string.candidate_font_size,
        "candidate_font_scale",
        85,
        60,
        150,
        "%",
        defaultLabel = R.string.system_default
    )

    val candidateFontWeight = list(
        R.string.candidate_font_weight,
        "candidate_font_weight",
        KeyboardFontWeight.NORMAL,
        KeyboardFontWeight,
        listOf(
            KeyboardFontWeight.NORMAL,
            KeyboardFontWeight.MEDIUM,
            KeyboardFontWeight.BOLD
        ),
        listOf(
            R.string.font_weight_normal,
            R.string.font_weight_medium,
            R.string.font_weight_bold
        )
    )

    /** 字母字号：键帽主字符（字母、数字、单字）的字号 */
    val keyboardFontSize = int(
        R.string.keyboard_font_size,
        "keyboard_font_size",
        100,
        70,
        170,
        "%"
    )

    val keyboardLetterFontWeight = list(
        R.string.keyboard_letter_font_weight,
        "key_letter_font_weight",
        KeyboardFontWeight.NORMAL,
        KeyboardFontWeight,
        listOf(
            KeyboardFontWeight.NORMAL,
            KeyboardFontWeight.MEDIUM,
            KeyboardFontWeight.BOLD
        ),
        listOf(
            R.string.font_weight_normal,
            R.string.font_weight_medium,
            R.string.font_weight_bold
        )
    )

    /** 键帽上方小符号（上划标点）的字号 */
    val keySymbolFontScale = int(
        R.string.keyboard_symbol_font_size,
        "key_symbol_font_scale",
        100,
        50,
        150,
        "%",
        defaultLabel = R.string.system_default
    )

    val keySymbolFontWeight = list(
        R.string.keyboard_symbol_font_weight,
        "key_symbol_font_weight",
        KeyboardFontWeight.NORMAL,
        KeyboardFontWeight,
        listOf(
            KeyboardFontWeight.NORMAL,
            KeyboardFontWeight.MEDIUM,
            KeyboardFontWeight.BOLD
        ),
        listOf(
            R.string.font_weight_normal,
            R.string.font_weight_medium,
            R.string.font_weight_bold
        )
    )

    /** 功能键说明文字（分词 / 符号 / 中·EN / 123）的字号 */
    val keyFunctionFontScale = int(
        R.string.keyboard_function_font_size,
        "key_function_font_scale",
        100,
        50,
        150,
        "%",
        defaultLabel = R.string.system_default
    )

    val keyFunctionFontWeight = list(
        R.string.keyboard_function_font_weight,
        "key_function_font_weight",
        KeyboardFontWeight.NORMAL,
        KeyboardFontWeight,
        listOf(
            KeyboardFontWeight.NORMAL,
            KeyboardFontWeight.MEDIUM,
            KeyboardFontWeight.BOLD
        ),
        listOf(
            R.string.font_weight_normal,
            R.string.font_weight_medium,
            R.string.font_weight_bold
        )
    )

    val titleInteraction = category(R.string.setting_keyboard_interaction)

    val candidateTextSize = int(
        R.string.candidate_size_input_setting,
        "candidate_size",
        55,
        25,
        100,
        "%",
        defaultLabel = R.string.system_default
    )

    // 候选词栏与键盘之间的间隔，避免选词时误触键盘第一行
    val candidatesBottomGap = int(
        R.string.candidates_bottom_gap,
        "candidates_bottom_gap",
        6,
        0,
        24,
        "dp"
    )

    val keyboardBalloonShow = switch(R.string.keypopup_input_settings, "keyboard_balloon_show_enable", false)

    val longPressTimeout = int(
        R.string.long_press_timeout,
        "long_press_timeout",
        400,
        100,
        700,
        "毫秒",
        50,
        defaultLabel = R.string.number_400_ms,
        advanced = true,
    )


    val abcNumberLine = switch(R.string.engish_full_keyboard, "keyboard_abc_number_line_enable", false)

    /** 26 键 / 14 键底行：「中/EN」与「123」整键互换位置 */
    val swapLangNumberKey = switch(
        R.string.keyboard_swap_lang_number_key,
        "keyboard_swap_lang_number_key_enable",
        false,
        R.string.keyboard_swap_lang_number_key_summary,
    )

    val keyboardDoubleInputKey = switch(R.string.keyboard_double_input_key, "keyboard_double_input_pinyin_enable", true)

    val keyboardMnemonic = switch(
        R.string.keyboard_mnemonic_show, "keyboard_mnemonic_show_enable", false, advanced = true
    )

    val spaceSwipeMoveCursor = switch(R.string.space_swipe_move_cursor, "space_swipe_move_cursor", true)

    val spaceSwipeMoveCursorSpeed = int(
        R.string.swipe_move_cursor_speed,
        "swipe_move_cursor_speed",
        10,
        1,
        50,
        "px",
        advanced = true,
    )

    // 锁定英语键盘:锁定后，切换到英语键盘，下次弹出键盘使用英语模式
    val keyboardLockEnglish = switch(
        R.string.keyboard_menu_lock_english,
        "keyboard_menu_lock_english_enable",
        false,
        advanced = true,
    )

    val oneHandedModSwitch = switch(R.string.keyboard_one_handed_mod, "keyboard_one_handed_mod_enable", false)

    val oneHandedMod = list(
        R.string.keyboard_one_handed_mod,
        "keyboard_one_handed_mod",
        KeyboardOneHandedMod.LEFT,
        KeyboardOneHandedMod,
        listOf(
            KeyboardOneHandedMod.LEFT,
            KeyboardOneHandedMod.RIGHT
        ),
        listOf(
            R.string.keyboard_one_handed_mod_left,
            R.string.keyboard_one_handed_mod_right
        )
    ) {
        oneHandedModSwitch.getValue()
    }

    // 键盘外观：键帽上方符号、按键间距 / 圆角（原在主题页「键盘效果」，与主题无关，随布局一起调）
    val titleKeyLook = category(R.string.setting_ime_keyboard_show)

    val keyboardSymbol =
        switch(R.string.keyboard_symbol_show, "keyboard_symbol_show_enable", true)

    val symbolSlideUpMod = list(
        R.string.keyboard_symbol_slide_up_mod,
        "keyboard_symbol_slide_up_mod",
        KeyboardSymbolSlideUpMod.MEDIUM,
        KeyboardSymbolSlideUpMod,
        listOf(
            KeyboardSymbolSlideUpMod.SHORT,
            KeyboardSymbolSlideUpMod.MEDIUM,
            KeyboardSymbolSlideUpMod.LONG
        ),
        listOf(
            R.string.keyboard_symbol_slide_up_short,
            R.string.keyboard_symbol_slide_up_medium,
            R.string.keyboard_symbol_slide_up_long,
        )
    ) {
        keyboardSymbol.getValue()
    }

    val keyBorder = switch(R.string.key_border, "key_border", true)

    val keyXMargin: ManagedPreference.PInt
    val keyYMargin: ManagedPreference.PInt

    init {
        val (primary, secondary) = twinInt(
            R.string.keyboard_key_margin,
            R.string.key_horizontal_margin,
            "keyboard_key_margin_x",
            7,
            R.string.key_vertical_margin,
            "keyboard_key_margin_y",
            25,
            0,
            100,
            "",
            defaultLabel = R.string.system_default
        ) {
            keyBorder.getValue()
        }
        keyXMargin = primary
        keyYMargin = secondary
    }

    val keyRadius = int(
        R.string.key_radius,
        "key_radius",
        20,
        0,
        60,
        "dp"
    ) {
        keyBorder.getValue()
    }

    val halfWidthSymbolsMode = list(
        R.string.half_width_symbols_tips,
        "half_width_symbols_tips",
        HalfWidthSymbolsMode.All,
        HalfWidthSymbolsMode,
        listOf(
            HalfWidthSymbolsMode.All,
            HalfWidthSymbolsMode.OnlyUsed,
            HalfWidthSymbolsMode.None
        ),
        listOf(
            R.string.half_width_symbols_tips_all,
            R.string.half_width_symbols_tips_only_used,
            R.string.half_width_symbols_tips_none
        ),
        advanced = true,
    )

    val showVirtualKeyboardOnPhysicalKeyboard = switch(
        R.string.show_virtual_keyboard_with_external,
        "show_virtual_keyboard_with_external",
        false,
        advanced = true,
    )
}
