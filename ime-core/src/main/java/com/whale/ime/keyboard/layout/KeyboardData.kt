package com.whale.ime.keyboard.layout

import android.view.KeyEvent
import com.whale.ime.keyboard.layout.InputModeSwitcher
import com.whale.ime.preference.model.KeyboardStyleMode

object KeyboardData {
    val layoutQwertyCn: Map<KeyboardStyleMode, ArrayList<Array<Int>>> = linkedMapOf(
        KeyboardStyleMode.Google to arrayListOf(
            arrayOf(45, 51, 33, 46, 48, 53, 49, 37, 43, 44),
            arrayOf(29, 47, 32, 34, 35, 36, 38, 39, 40),
            arrayOf(KeyEvent.KEYCODE_SHIFT_LEFT, 54, 52, 31, 50, 30, 42, 41, KeyEvent.KEYCODE_DEL)),
        KeyboardStyleMode.Whale to arrayListOf(
            arrayOf(45, 51, 33, 46, 48, 53, 49, 37, 43, 44),
            arrayOf(29, 47, 32, 34, 35, 36, 38, 39, 40),
            arrayOf(75, 54, 52, 31, 50, 30, 42, 41, KeyEvent.KEYCODE_DEL)),
    )

    /**
     * 14 键（QW/ER/TY/UI/OP · AS/DF/GH/JK/L · ZX/CV/BN/M）。
     *
     * 每键的 [android.view.KeyEvent] code 取「该键首字母」（QW→Q、ER→E …），配合
     * `META_CAPS_LOCK_ON` 送出大写键码；键位顺序必须与 `T14KeyMap.CHAR_TO_KEY`
     * 以及 `t14_pinyin.schema.yaml` 的 derive 段一一对应，改键位要同时改这三处。
     * 底行（符号/中英/标点/空格/123/换行）不走这张表，由 `lastRows` 生成。
     */
    val layoutT14Cn: Map<KeyboardStyleMode, ArrayList<Array<Int>>> = linkedMapOf(
        KeyboardStyleMode.Google to arrayListOf(
            arrayOf(KeyEvent.KEYCODE_Q, KeyEvent.KEYCODE_E, KeyEvent.KEYCODE_T,
                KeyEvent.KEYCODE_U, KeyEvent.KEYCODE_O),
            arrayOf(KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_D, KeyEvent.KEYCODE_G,
                KeyEvent.KEYCODE_J, KeyEvent.KEYCODE_L),
            arrayOf(KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_Z, KeyEvent.KEYCODE_C,
                KeyEvent.KEYCODE_B, KeyEvent.KEYCODE_M, KeyEvent.KEYCODE_DEL),
        ),
        KeyboardStyleMode.Whale to arrayListOf(
            arrayOf(KeyEvent.KEYCODE_Q, KeyEvent.KEYCODE_E, KeyEvent.KEYCODE_T,
                KeyEvent.KEYCODE_U, KeyEvent.KEYCODE_O),
            arrayOf(KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_D, KeyEvent.KEYCODE_G,
                KeyEvent.KEYCODE_J, KeyEvent.KEYCODE_L),
            arrayOf(KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_Z, KeyEvent.KEYCODE_C,
                KeyEvent.KEYCODE_B, KeyEvent.KEYCODE_M, KeyEvent.KEYCODE_DEL),
        ),
    )

    val layoutT9Cn: Map<KeyboardStyleMode, ArrayList<Array<Int>>> = linkedMapOf(
        KeyboardStyleMode.Google to  arrayListOf(
            arrayOf(InputModeSwitcher.USER_KEYCODE_LEFT_SYMBOL, KeyEvent.KEYCODE_APOSTROPHE, KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_D, KeyEvent.KEYCODE_DEL),
            arrayOf(KeyEvent.KEYCODE_G, KeyEvent.KEYCODE_J, KeyEvent.KEYCODE_M, InputModeSwitcher.USER_KEYCODE_CURSOR_DIRECTION),
            arrayOf(KeyEvent.KEYCODE_P, KeyEvent.KEYCODE_T, KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_SHIFT_LEFT)),
        KeyboardStyleMode.Whale to arrayListOf(
            arrayOf(InputModeSwitcher.USER_KEYCODE_LEFT_SYMBOL, KeyEvent.KEYCODE_APOSTROPHE, KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_D, KeyEvent.KEYCODE_DEL),
            arrayOf(KeyEvent.KEYCODE_G, KeyEvent.KEYCODE_J, KeyEvent.KEYCODE_M, KeyEvent.KEYCODE_CLEAR),
            arrayOf(KeyEvent.KEYCODE_P, KeyEvent.KEYCODE_T, KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_AT)),
    )

    val layoutHandwritingCn: Map<KeyboardStyleMode, ArrayList<Array<Int>>> = linkedMapOf(
        KeyboardStyleMode.Google to arrayListOf(
            arrayOf(InputModeSwitcher.USER_KEYCODE_LEFT_SYMBOL),
            arrayOf(KeyEvent.KEYCODE_DEL)),
        KeyboardStyleMode.Whale to arrayListOf(
            arrayOf(InputModeSwitcher.USER_KEYCODE_LEFT_SYMBOL),
            arrayOf(KeyEvent.KEYCODE_DEL)),
    )

    val layoutStrokeCn: Map<KeyboardStyleMode, ArrayList<Array<Int>>> = linkedMapOf(
        KeyboardStyleMode.Google to arrayListOf(
            arrayOf(InputModeSwitcher.USER_KEYCODE_LEFT_SYMBOL, KeyEvent.KEYCODE_H, KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_P, KeyEvent.KEYCODE_DEL),
            arrayOf(KeyEvent.KEYCODE_N, KeyEvent.KEYCODE_Z, KeyEvent.KEYCODE_X, KeyEvent.KEYCODE_CLEAR),
            arrayOf(KeyEvent.KEYCODE_APOSTROPHE, KeyEvent.KEYCODE_MINUS, KeyEvent.KEYCODE_EQUALS, KeyEvent.KEYCODE_AT)),
        KeyboardStyleMode.Whale to arrayListOf(
            arrayOf(InputModeSwitcher.USER_KEYCODE_LEFT_SYMBOL, KeyEvent.KEYCODE_H, KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_P, KeyEvent.KEYCODE_DEL),
            arrayOf(KeyEvent.KEYCODE_N, KeyEvent.KEYCODE_Z, KeyEvent.KEYCODE_X, KeyEvent.KEYCODE_CLEAR),
            arrayOf(KeyEvent.KEYCODE_APOSTROPHE, KeyEvent.KEYCODE_MINUS, KeyEvent.KEYCODE_EQUALS, KeyEvent.KEYCODE_AT)),
    )

    val layoutQwertyEn: Map<KeyboardStyleMode, ArrayList<Array<Int>>> = linkedMapOf(
        KeyboardStyleMode.Google to arrayListOf(
            arrayOf(45, 51, 33, 46, 48, 53, 49, 37, 43, 44),
            arrayOf(29, 47, 32, 34, 35, 36, 38, 39, 40),
            arrayOf(54, 52, 31, 50, 30, 42, 41, KeyEvent.KEYCODE_DEL)),
        KeyboardStyleMode.Whale to arrayListOf(
            arrayOf(45, 51, 33, 46, 48, 53, 49, 37, 43, 44),
            arrayOf(29, 47, 32, 34, 35, 36, 38, 39, 40),
            arrayOf(54, 52, 31, 50, 30, 42, 41, KeyEvent.KEYCODE_DEL)),
    )

    val layoutT9Number: Map<KeyboardStyleMode, ArrayList<Array<Int>>> = linkedMapOf(
        KeyboardStyleMode.Google to arrayListOf(
            arrayOf(InputModeSwitcher.USER_KEYCODE_LEFT_SYMBOL, 8, 9, 10, KeyEvent.KEYCODE_DEL),
            arrayOf(11, 12, 13, InputModeSwitcher.USER_KEYCODE_LEFT_PERIOD),
            arrayOf(14, 15, 16, KeyEvent.KEYCODE_SHIFT_LEFT)),
        KeyboardStyleMode.Whale to arrayListOf(
            arrayOf(InputModeSwitcher.USER_KEYCODE_LEFT_SYMBOL, 8, 9, 10, KeyEvent.KEYCODE_DEL),
            // 右侧一列：删除 / @ / .
            arrayOf(11, 12, 13, KeyEvent.KEYCODE_AT),
            arrayOf(14, 15, 16, InputModeSwitcher.USER_KEYCODE_LEFT_PERIOD)),
    )

    val layoutTextEdit: Map<KeyboardStyleMode, ArrayList<Array<Int>>> = linkedMapOf(
        KeyboardStyleMode.Google to arrayListOf(
            arrayOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_RIGHT, InputModeSwitcher.USER_KEYCODE_SELECT_ALL),
            arrayOf(InputModeSwitcher.USER_KEYCODE_SELECT_MODE, InputModeSwitcher.USER_KEYCODE_COPY),
            arrayOf(KeyEvent.KEYCODE_DPAD_DOWN, InputModeSwitcher.USER_KEYCODE_PASTE),
            arrayOf(InputModeSwitcher.USER_KEYCODE_MOVE_START, InputModeSwitcher.USER_KEYCODE_MOVE_END, KeyEvent.KEYCODE_DEL)),
        KeyboardStyleMode.Whale to arrayListOf(
            arrayOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_RIGHT, InputModeSwitcher.USER_KEYCODE_SELECT_ALL),
            arrayOf(InputModeSwitcher.USER_KEYCODE_SELECT_MODE, InputModeSwitcher.USER_KEYCODE_COPY),
            arrayOf(KeyEvent.KEYCODE_DPAD_DOWN, InputModeSwitcher.USER_KEYCODE_PASTE),
            arrayOf(InputModeSwitcher.USER_KEYCODE_MOVE_START, InputModeSwitcher.USER_KEYCODE_MOVE_END, KeyEvent.KEYCODE_DEL)),
    )
}
