package com.whale.ime.preference.model

import com.whale.ime.preference.ManagedPreference


enum class KeyboardMenuMode {
    SwitchKeyboard,
    KeyboardHeight,
    DarkTheme,
    // 已从功能宫格下线（按键效果在设置 → 键盘效果，数字行改在设置 → 键盘），
    // 枚举值保留：旧版本的 skbfun 记录仍可能带这些名字，删掉会让 decode 抛异常
    Feedback,
    NumberRow,
    JianFan,
    LockEnglish,
    SymbolShow,
    CandidatesMore,
    // 同上：双拼提示改到设置 → 键盘
    Mnemonic,
    FlowerTypeface,
    EmojiInput,
    Handwriting,
    Custom,
    SettingsMenu,
    Settings,
    FloatKeyboard,
    OneHanded,
    PinyinT9,
    Pinyin14Jian,
    Pinyin26Jian,
    PinyinHandWriting,
    Pinyin26Double,
    PinyinStroke,
    ClipBoard,
    ClearClipBoard,
    Phrases,
    AddPhrases,
    CloseSKB,
    Emojicon,
    Emoticon,
    LockClipBoard,
    TextEdit;

    companion object : ManagedPreference.StringLikeCodec<KeyboardMenuMode> {
        override fun decode(raw: String): KeyboardMenuMode =
            KeyboardMenuMode.valueOf(raw)
    }
}