package com.whale.ime.keyboard.menu

import com.whale.ime.core.R
import com.whale.ime.boot.Launcher
import com.whale.ime.preference.model.KeyboardMenuMode

val keyboardMenuPreset: Map<KeyboardMenuMode, KeyboardMenuItem> = hashMapOf(
    KeyboardMenuMode.Emojicon to KeyboardMenuItem(Launcher.instance.context.getString(R.string.emoji_setting), R.drawable.ic_ui_emoji, KeyboardMenuMode.Emojicon),
    KeyboardMenuMode.SwitchKeyboard to KeyboardMenuItem(Launcher.instance.context.getString(R.string.changeKeyboard), R.drawable.ic_ui_keyboard, KeyboardMenuMode.SwitchKeyboard),
    KeyboardMenuMode.KeyboardHeight to KeyboardMenuItem(Launcher.instance.context.getString(R.string.setting_ime_keyboard_height), R.drawable.ic_ui_height, KeyboardMenuMode.KeyboardHeight),
    KeyboardMenuMode.ClipBoard to KeyboardMenuItem(Launcher.instance.context.getString(R.string.clipboard), R.drawable.ic_ui_clipboard, KeyboardMenuMode.ClipBoard),
    KeyboardMenuMode.Phrases to KeyboardMenuItem(Launcher.instance.context.getString(R.string.phrases), R.drawable.ic_ui_phrases, KeyboardMenuMode.Phrases),
    KeyboardMenuMode.DarkTheme to KeyboardMenuItem(Launcher.instance.context.getString(R.string.keyboard_theme_night), R.drawable.ic_ui_dark, KeyboardMenuMode.DarkTheme),
    KeyboardMenuMode.OneHanded to KeyboardMenuItem(Launcher.instance.context.getString(R.string.keyboard_one_handed_mod), R.drawable.ic_ui_one_hand, KeyboardMenuMode.OneHanded),
    KeyboardMenuMode.JianFan to KeyboardMenuItem(Launcher.instance.context.getString(R.string.setting_jian_fan), R.drawable.ic_ui_fanti, KeyboardMenuMode.JianFan),
    KeyboardMenuMode.FloatKeyboard to KeyboardMenuItem(Launcher.instance.context.getString(R.string.keyboard_menu_float), R.drawable.ic_ui_float, KeyboardMenuMode.FloatKeyboard),
    KeyboardMenuMode.FlowerTypeface to KeyboardMenuItem(Launcher.instance.context.getString(R.string.keyboard_flower_typeface), R.drawable.ic_ui_mars, KeyboardMenuMode.FlowerTypeface),
    KeyboardMenuMode.Custom to KeyboardMenuItem(Launcher.instance.context.getString(R.string.skb_item_custom), R.drawable.ic_ui_custom, KeyboardMenuMode.Custom),
    KeyboardMenuMode.Settings to KeyboardMenuItem(Launcher.instance.context.getString(R.string.skb_item_settings), R.drawable.ic_ui_setting, KeyboardMenuMode.Settings),
    KeyboardMenuMode.CloseSKB to KeyboardMenuItem(Launcher.instance.context.getString(R.string.keyboard_iv_menu_close), R.drawable.ic_menu_arrow_down, KeyboardMenuMode.CloseSKB),
    KeyboardMenuMode.ClearClipBoard to KeyboardMenuItem(Launcher.instance.context.getString(R.string.clipboard_clear), R.drawable.ic_menu_delete, KeyboardMenuMode.ClearClipBoard),
    KeyboardMenuMode.Emoticon to KeyboardMenuItem(Launcher.instance.context.getString(R.string.emoticons), R.drawable.ic_ui_emoticon, KeyboardMenuMode.Emoticon),
    KeyboardMenuMode.AddPhrases to KeyboardMenuItem(Launcher.instance.context.getString(R.string.add_phrases), R.drawable.ic_menu_plus, KeyboardMenuMode.AddPhrases),
    KeyboardMenuMode.LockClipBoard to KeyboardMenuItem(Launcher.instance.context.getString(R.string.lock_view), R.drawable.icon_symbol_lock, KeyboardMenuMode.LockClipBoard),
    KeyboardMenuMode.TextEdit to KeyboardMenuItem(Launcher.instance.context.getString(R.string.menu_text_edit), R.drawable.ic_ui_text_edit, KeyboardMenuMode.TextEdit),
    )