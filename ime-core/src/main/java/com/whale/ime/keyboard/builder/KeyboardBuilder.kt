package com.whale.ime.keyboard.builder

import android.view.KeyEvent
import com.whale.ime.theme.ThemeManager
import com.whale.ime.keyboard.model.SoftKey
import com.whale.ime.keyboard.model.SoftKeyToggle
import com.whale.ime.keyboard.model.SoftKeyboard
import com.whale.ime.keyboard.model.ToggleState
import com.whale.ime.keyboard.layout.KeyPreset
import com.whale.ime.keyboard.layout.KeyboardData
import com.whale.ime.keyboard.layout.InputModeSwitcher
import com.whale.ime.preference.AppPrefs
import com.whale.ime.preference.model.DoublePinyinSchemaMode
import com.whale.ime.keyboard.metrics.Environment
import com.whale.ime.keyboard.layout.doubleAbcMnemonicPreset
import com.whale.ime.keyboard.layout.doubleFlyMnemonicPreset
import com.whale.ime.keyboard.layout.doubleMSMnemonicPreset
import com.whale.ime.keyboard.layout.doubleNaturalMnemonicPreset
import com.whale.ime.keyboard.layout.doubleSogouMnemonicPreset
import com.whale.ime.keyboard.layout.doubleZiguangMnemonicPreset
import com.whale.ime.preference.model.KeyboardStyleMode
import java.util.LinkedList
import com.whale.ime.config.SchemaIds

/**
 * 键盘加载类  包括中文9键  中文26键 英文26键
 */
class KeyboardBuilder private constructor() {
    private var rimeValue: String? = null
    private var mSkbValue: Int = 0
    private var numberLine: Boolean = false
    private var keyboardStyleMode: KeyboardStyleMode = KeyboardStyleMode.Whale
    fun clearKeyboardMap() {
        mSoftKeyboardMap.clear()
    }

    private fun loadBaseSkb(skbValue: Int): SoftKeyboard {
        keyboardStyleMode = ThemeManager.prefs.keyboardStyleMode.getValue()
        mSkbValue = skbValue
        // shift键状态
        // 直输状态
        val shiftToggleStates = LinkedList<ToggleState>()
        shiftToggleStates.add(ToggleState(0))
        shiftToggleStates.add(ToggleState(1))
        shiftToggleStates.add(ToggleState(2))
        // 拼写模式
        shiftToggleStates.add(ToggleState(3))
        shiftToggleStates.add(ToggleState(4))
        shiftToggleStates.add(ToggleState(5))

        val softKeyboard: SoftKeyboard?
        numberLine = AppPrefs.getInstance().keyboardSetting.abcNumberLine.getValue()
        val rows: MutableList<List<SoftKey>> = LinkedList()
        if (numberLine) {
            val qwertyKeys = createNumberLineKeys(arrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 0))
            rows.add(qwertyKeys.asList())
        }
        when(skbValue){
            InputModeSwitcher.MASK_SKB_LAYOUT_QWERTY_PINYIN -> {  // 1000  拼音全键
                rimeValue = AppPrefs.getInstance().internal.pinyinModeRime.getValue()
                var keyBeans = mutableListOf<SoftKey>()
                val keys = when (rimeValue) {
                    SchemaIds.SCHEMA_ZH_DOUBLE_FLYPY + DoublePinyinSchemaMode.mspy,
                    SchemaIds.SCHEMA_ZH_DOUBLE_FLYPY + DoublePinyinSchemaMode.sogou,
                    SchemaIds.SCHEMA_ZH_DOUBLE_FLYPY + DoublePinyinSchemaMode.ziguang -> arrayListOf(
                        arrayOf(45, 51, 33, 46, 48, 53, 49, 37, 43, 44),
                        arrayOf(29, 47, 32, 34, 35, 36, 38, 39, 40),
                        arrayOf(74, 54, 52, 31, 50, 30, 42, 41, KeyEvent.KEYCODE_DEL),)
                    else -> {
                        KeyboardData.layoutQwertyCn[keyboardStyleMode]!!
                    }
                }
                var qwertyKeys = createQwertyPYKeys(keys[0])
                keyBeans.addAll(qwertyKeys)
                rows.add(keyBeans)
                keyBeans = LinkedList()
                qwertyKeys = createQwertyPYKeys(keys[1])
                qwertyKeys.first().apply {
                    mLeftF = 0.06f
                }
                keyBeans.addAll(qwertyKeys)
                rows.add(keyBeans)
                keyBeans = LinkedList()
                qwertyKeys = createQwertyPYKeys(keys[2])
                qwertyKeys.first().apply {
                    widthF = 0.147f
                }
                qwertyKeys.last().apply {
                    widthF = 0.147f
                }
                if(keyboardStyleMode == KeyboardStyleMode.Google) {
                    val softKeyToggle = createKeyToggle(KeyEvent.KEYCODE_SHIFT_LEFT)
                    softKeyToggle.widthF = 0.147f
                    softKeyToggle.setToggleStates(shiftToggleStates)
                    qwertyKeys[0] = softKeyToggle
                }
                keyBeans.addAll(qwertyKeys)
                rows.add(keyBeans)
                keyBeans = lastRows(skbValue)
                rows.add(keyBeans)
            }
            InputModeSwitcher.MASK_SKB_LAYOUT_T9_PINYIN -> {  // 2000  T9键键
                var keyBeans: MutableList<SoftKey> = LinkedList()
                val keys = KeyboardData.layoutT9Cn[keyboardStyleMode]!!
                var t9Key = createT9Keys(keys[0])
                t9Key.first().apply {
                    widthF = 0.18f
                    heightF = 0.75f
                }
                t9Key.last().widthF = 0.18f
                keyBeans.addAll(t9Key)
                rows.add(keyBeans)
                keyBeans = LinkedList()
                t9Key = createT9Keys(keys[1])
                t9Key.first().mLeftF = 0.185f
                t9Key.last().widthF = 0.18f
                keyBeans.addAll(t9Key)
                rows.add(keyBeans)
                keyBeans = LinkedList()
                t9Key = createT9Keys(keys[2])
                t9Key.first().mLeftF = 0.185f
                t9Key.last().apply {
                    widthF = 0.18f
                    stateId = 7
                }
                keyBeans.addAll(t9Key)
                rows.add(keyBeans)
                keyBeans = lastRows(skbValue)
                rows.add(keyBeans)

            }
            InputModeSwitcher.MASK_SKB_LAYOUT_T14_PINYIN -> {  // 9000  14 键
                val keys = KeyboardData.layoutT14Cn[keyboardStyleMode]!!
                // 行1/行2：QW/ER/TY/UI/OP 与 AS/DF/GH/JK/L，五列均分
                for (rowIndex in 0..1) {
                    val keyBeans = LinkedList<SoftKey>()
                    createT14Keys(keys[rowIndex]).forEach {
                        it.widthF = 0.196f
                        keyBeans.add(it)
                    }
                    rows.add(keyBeans)
                }
                // 行3：shift + ZX/CV/BN/M + 退格。字母键比上面两行窄，
                // 两侧功能键沿用 26 键的 0.145，整行总宽与字母行对齐。
                val keyBeans = LinkedList<SoftKey>()
                keys[2].forEach { code ->
                    if (code == KeyEvent.KEYCODE_SHIFT_LEFT) {
                        keyBeans.add(createKeyToggle(code).apply {
                            widthF = 0.145f
                            setToggleStates(shiftToggleStates)
                        })
                    } else {
                        createT14Keys(arrayOf(code)).forEach { key ->
                            key.widthF = if (code == KeyEvent.KEYCODE_DEL) 0.145f else 0.17f
                            keyBeans.add(key)
                        }
                    }
                }
                rows.add(keyBeans)
                rows.add(lastRows(skbValue))
            }
            InputModeSwitcher.MASK_SKB_LAYOUT_HANDWRITING -> {// 3000 手写键盘
                var keyBeans: MutableList<SoftKey> = LinkedList()
                val keys = KeyboardData.layoutHandwritingCn[keyboardStyleMode]!!
                var handwritingKey = createHandwritingKey(keys[0][0]) // 符号站位
                handwritingKey.mLeftF = 0.815f
                handwritingKey.heightF = 0.50f
                keyBeans.add(handwritingKey)
                rows.add(keyBeans)
                keyBeans = LinkedList()
                handwritingKey = createHandwritingKey(keys[1][0])
                handwritingKey.mLeftF = 0.815f
                keyBeans.add(handwritingKey)
                rows.add(keyBeans)
                keyBeans = lastRows(skbValue)
                rows.add(keyBeans)
            }
            InputModeSwitcher.MASK_SKB_LAYOUT_QWERTY_ABC -> {// 4000 英文全键
                var keyBeans: MutableList<SoftKey> = LinkedList()
                val keys = KeyboardData.layoutQwertyEn[keyboardStyleMode]!!
                var qwertyKeys = createQwertyKeys(keys[0])
                keyBeans.addAll(qwertyKeys)
                rows.add(keyBeans)
                keyBeans = LinkedList()
                qwertyKeys = createQwertyKeys(keys[1])
                qwertyKeys.first().apply {
                    mLeftF = 0.06f
                }
                keyBeans.addAll(qwertyKeys)
                rows.add(keyBeans)
                keyBeans = LinkedList()
                val softKeyToggle = createKeyToggle(KeyEvent.KEYCODE_SHIFT_LEFT)
                softKeyToggle.widthF = 0.147f
                softKeyToggle.setToggleStates(shiftToggleStates)
                keyBeans.add(softKeyToggle)
                keyBeans.addAll(createQwertyKeys(keys[2]))
                keyBeans.last().apply {
                    widthF = 0.147f
                }
                rows.add(keyBeans)
                keyBeans = lastRows(skbValue)
                // 语言键状态：按 code 定位，避免行内键位调整后「倒数第二个」指到别的键
                keyBeans.firstOrNull { it.code == InputModeSwitcher.USER_KEYCODE_LANG }?.stateId = 1
                rows.add(keyBeans)
            }
            InputModeSwitcher.MASK_SKB_LAYOUT_NUMBER -> {  // 5000 数字键盘
                var keyBeans: MutableList<SoftKey> = LinkedList()
                val keys =  KeyboardData.layoutT9Number[keyboardStyleMode]!!
                var t9Keys = createT9NumberKeys(keys[0])
                t9Keys.first().apply {
                    widthF = 0.18f
                    heightF = 0.75f
                }
                t9Keys.last().widthF = 0.18f
                keyBeans.addAll(t9Keys)
                rows.add(keyBeans)

                keyBeans = LinkedList()
                t9Keys = createT9NumberKeys(keys[1])
                t9Keys.first().mLeftF = 0.185f
                t9Keys.last().widthF = 0.18f
                keyBeans.addAll(t9Keys)
                rows.add(keyBeans)

                keyBeans = LinkedList()
                t9Keys = createT9NumberKeys(keys[2])
                t9Keys.first().mLeftF = 0.185f
                t9Keys.last().widthF = 0.18f
                keyBeans.addAll(t9Keys)
                rows.add(keyBeans)
                keyBeans = lastRows(skbValue)
                rows.add(keyBeans)
            }
            InputModeSwitcher.MASK_SKB_LAYOUT_STROKE -> {  // 7000  笔画键盘
                var keyBeans: MutableList<SoftKey> = LinkedList()
                val keys =  KeyboardData.layoutStrokeCn[keyboardStyleMode]!!
                var t9Key = createT9Keys(keys[0])
                t9Key.first().apply {
                    widthF = 0.18f
                    heightF = 0.75f
                }
                t9Key.last().widthF = 0.18f
                keyBeans.addAll(t9Key)
                rows.add(keyBeans)
                keyBeans = LinkedList()
                t9Key = createT9Keys(keys[1])
                t9Key.first().mLeftF = 0.185f
                t9Key.last().widthF = 0.18f
                keyBeans.addAll(t9Key)
                rows.add(keyBeans)
                keyBeans = LinkedList()
                t9Key = createT9Keys(keys[2])
                t9Key.first().mLeftF = 0.185f
                t9Key.last().widthF = 0.18f
                keyBeans.addAll(t9Key)
                rows.add(keyBeans)
                keyBeans = lastRows(skbValue)
                rows.add(keyBeans)
            }
            InputModeSwitcher.MASK_SKB_LAYOUT_TEXTEDIT -> {     // 8000 文本编辑键盘
                var keyBeans: MutableList<SoftKey> = LinkedList()
                val keys =  KeyboardData.layoutTextEdit[keyboardStyleMode]!!
                var editKeys = createTextEditKeys(keys[0])
                editKeys[0].heightF = 0.75f
                editKeys[2].heightF = 0.75f
                keyBeans.addAll(editKeys)
                rows.add(keyBeans)
                keyBeans = LinkedList()
                editKeys = createTextEditKeys(keys[1])
                editKeys[0].mLeftF = 0.25f
                editKeys[1].mLeftF = 0.75f
                keyBeans.addAll(editKeys)
                rows.add(keyBeans)
                keyBeans = LinkedList()
                editKeys = createTextEditKeys(keys[2])
                editKeys[0].mLeftF = 0.25f
                editKeys[1].mLeftF = 0.75f
                keyBeans.addAll(editKeys)
                rows.add(keyBeans)
                keyBeans = LinkedList()
                editKeys = createTextEditKeys(keys[3])
                editKeys[0].widthF = 0.33f
                editKeys[1].widthF = 0.33f
                editKeys[2].widthF = 0.33f
                keyBeans.addAll(editKeys)
                rows.add(keyBeans)
            }
        }
        softKeyboard = getSoftKeyboard(rows, numberLine)
        mSoftKeyboardMap[skbValue] = softKeyboard
        return softKeyboard
    }

    // 键盘最后一行（各键盘统一，数字键盘稍微不同）
    private fun lastRows(skbValue: Int): MutableList<SoftKey> {
        val enterToggleStates = listOf(ToggleState("去往", 2), ToggleState("搜索", 3), ToggleState("发送", 4),
            ToggleState("下一个", 5), ToggleState("完成", 6), ToggleState("上一个", 7),
            // 聊天发送模式：单独的 stateId，不复用宿主 imeAction 的 4（短语编辑态也在用 4）
            ToggleState("发送", InputModeSwitcher.TOGGLE_STATE_ENTER_SEND))
        val softKeyToggle = createKeyToggle(KeyEvent.KEYCODE_ENTER)
        softKeyToggle.widthF = 0.18f
        softKeyToggle.stateId = 0
        softKeyToggle.setToggleStates(enterToggleStates)
        val keyBeans = mutableListOf<SoftKey>()
        val t9Keys = when(skbValue){
            InputModeSwitcher.MASK_SKB_LAYOUT_T9_PINYIN, InputModeSwitcher.MASK_SKB_LAYOUT_HANDWRITING, InputModeSwitcher.MASK_SKB_LAYOUT_STROKE ->{
                if(keyboardStyleMode == KeyboardStyleMode.Google){
                    createT9Keys(arrayOf(InputModeSwitcher.USER_KEYCODE_NUMBER, InputModeSwitcher.USER_KEYCODE_LEFT_COMMA, InputModeSwitcher.USER_KEYCODE_LANG,
                        KeyEvent.KEYCODE_SPACE, InputModeSwitcher.USER_KEYCODE_LEFT_PERIOD))
                } else {
                    createT9Keys(arrayOf(InputModeSwitcher.USER_KEYCODE_SYMBOL, InputModeSwitcher.USER_KEYCODE_NUMBER,
                            KeyEvent.KEYCODE_SPACE, InputModeSwitcher.USER_KEYCODE_LANG))
                }
            }
            InputModeSwitcher.MASK_SKB_LAYOUT_QWERTY_ABC,
            InputModeSwitcher.MASK_SKB_LAYOUT_T14_PINYIN -> {
                if(keyboardStyleMode == KeyboardStyleMode.Google){
                    createQwertyKeys(arrayOf(InputModeSwitcher.USER_KEYCODE_NUMBER, InputModeSwitcher.USER_KEYCODE_LEFT_COMMA, InputModeSwitcher.USER_KEYCODE_LANG,
                        KeyEvent.KEYCODE_SPACE, InputModeSwitcher.USER_KEYCODE_LEFT_PERIOD))
                } else {
                    // 雅燕风格：符号、中/EN、,!、空格、.?、123，与主流输入法一致
                    createQwertyKeys(arrayOf(InputModeSwitcher.USER_KEYCODE_SYMBOL, InputModeSwitcher.USER_KEYCODE_LANG,
                        InputModeSwitcher.USER_KEYCODE_LEFT_COMMA, KeyEvent.KEYCODE_SPACE, InputModeSwitcher.USER_KEYCODE_LEFT_PERIOD,
                        InputModeSwitcher.USER_KEYCODE_NUMBER))
                }
            }
            InputModeSwitcher.MASK_SKB_LAYOUT_NUMBER -> {
                createT9NumberKeys(arrayOf(InputModeSwitcher.USER_KEYCODE_SYMBOL, KeyEvent.KEYCODE_SPACE, 7, InputModeSwitcher.USER_KEYCODE_RETURN))
            }
            else -> { //0x1000 InputModeSwitcherManager.MASK_SKB_LAYOUT_QWERTY_PINYIN
                if(keyboardStyleMode == KeyboardStyleMode.Google){
                    createQwertyPYKeys(arrayOf(InputModeSwitcher.USER_KEYCODE_NUMBER, InputModeSwitcher.USER_KEYCODE_LEFT_COMMA, InputModeSwitcher.USER_KEYCODE_LANG,
                        KeyEvent.KEYCODE_SPACE, InputModeSwitcher.USER_KEYCODE_LEFT_PERIOD))
                } else {
                    // 雅燕风格：符号、中/EN、，！、空格、。？、123，与主流输入法一致
                    createQwertyPYKeys(arrayOf(InputModeSwitcher.USER_KEYCODE_SYMBOL, InputModeSwitcher.USER_KEYCODE_LANG,
                        InputModeSwitcher.USER_KEYCODE_LEFT_COMMA, KeyEvent.KEYCODE_SPACE, InputModeSwitcher.USER_KEYCODE_LEFT_PERIOD,
                        InputModeSwitcher.USER_KEYCODE_NUMBER))
                }
            }
        }
        if (keyboardStyleMode == KeyboardStyleMode.Google) {
            // 底行与默认风格保持同一套键宽：123 / ，！/ 中·EN / 空格 / 。？
            // 语言键不再画地球图标（stateId = 2），改走 KeyGrid 里的「中/EN」文字排版，
            // 所以按默认风格给它 0.12，空格让出这 0.02，整行总宽不变。
            when (t9Keys.size) {
                6 -> {
                    t9Keys[0].widthF = 0.185f;t9Keys[1].widthF = 0.1f
                    t9Keys[2].widthF = 0.12f;t9Keys[3].widthF = 0.21f
                    t9Keys[4].widthF = 0.1f;t9Keys[5].widthF = 0.1f
                    softKeyToggle.widthF = 0.185f
                }
                5 -> {
                    t9Keys[0].widthF = 0.185f;t9Keys[1].widthF = 0.1f
                    t9Keys[2].widthF = 0.12f;t9Keys[3].widthF = 0.31f
                    t9Keys[4].widthF = 0.1f;softKeyToggle.widthF = 0.185f
                }
                else -> {
                    softKeyToggle.widthF = 0.18f
                    t9Keys[0].widthF = 0.18f;t9Keys[1].widthF = 0.21f
                    t9Keys[2].widthF = 0.21f;t9Keys[3].widthF = 0.21f
                }
            }
        } else {
            if (t9Keys.size == 6) {
                // 符号、中/EN、，！、空格、。？、123：空格收窄，给新增的「。？」让位
                softKeyToggle.widthF = 0.147f
                t9Keys[0].widthF = 0.147f;t9Keys[1].widthF = 0.12f
                t9Keys[2].widthF = 0.1f;t9Keys[3].widthF = 0.258f
                t9Keys[4].widthF = 0.1f;t9Keys[5].widthF = 0.115f
            } else if (t9Keys.size == 5) {
                softKeyToggle.widthF = 0.147f
                t9Keys[0].widthF = 0.147f;t9Keys[1].widthF = 0.099f
                t9Keys[2].widthF = 0.099f;t9Keys[3].widthF = 0.396f
                t9Keys[4].widthF = 0.099f
            } else {
                // 4 键底行（9 键：符号 / 123 / 空格 / 中·EN，数字键：符号 / 空格 / 0 / 返回）：
                // 中间那格不再靠拉宽（原来是 0.336）去吃掉左右两个键，三格统一成上方数字键的
                // 0.21，和上面的键列对齐；首位「符号」沿用左列宽 0.18
                t9Keys[0].widthF = 0.18f;t9Keys[1].widthF = 0.21f
                t9Keys[2].widthF = 0.21f;t9Keys[3].widthF = 0.21f
            }
        }
        swapLangNumberKeys(skbValue, t9Keys)
        keyBeans.addAll(t9Keys)
        keyBeans.add(softKeyToggle)
        return keyBeans
    }

    /**
     * 26 键 / 14 键底行：「中/EN」与「123」互换位置（功能、文案、状态跟着键走）。
     *
     * 键宽留在各自的槽位上——两种风格里两个键的宽度并不相同，若连宽度一起挪，
     * 整行的起始位置与上方字母行就会对不齐。9 键等底行没有这两个键的布局直接跳过。
     */
    private fun swapLangNumberKeys(skbValue: Int, keys: Array<SoftKey>) {
        if (skbValue != InputModeSwitcher.MASK_SKB_LAYOUT_QWERTY_PINYIN &&
            skbValue != InputModeSwitcher.MASK_SKB_LAYOUT_QWERTY_ABC &&
            skbValue != InputModeSwitcher.MASK_SKB_LAYOUT_T14_PINYIN
        ) return
        if (!AppPrefs.getInstance().keyboardSetting.swapLangNumberKey.getValue()) return
        val langIndex = keys.indexOfFirst { it.code == InputModeSwitcher.USER_KEYCODE_LANG }
        val numberIndex = keys.indexOfFirst { it.code == InputModeSwitcher.USER_KEYCODE_NUMBER }
        if (langIndex < 0 || numberIndex < 0 || langIndex == numberIndex) return
        val langKey = keys[langIndex]
        val numberKey = keys[numberIndex]
        val langWidth = langKey.widthF
        langKey.widthF = numberKey.widthF
        numberKey.widthF = langWidth
        keys[langIndex] = numberKey
        keys[numberIndex] = langKey
    }

    fun changeSKBNumberRow() {
        for (skbValue in mSoftKeyboardMap.keys) {
            loadBaseSkb(skbValue)
        }
    }

    fun getSoftKeyboard(skbValue: Int): SoftKeyboard {
        var softKeyboard = mSoftKeyboardMap[skbValue]
        if (softKeyboard == null) {
            softKeyboard = loadBaseSkb(skbValue)
        }
        return softKeyboard
    }

    /** 生成键盘布局，主要用于计算键盘边界 */
    private fun getSoftKeyboard(rows: List<List<SoftKey>>, isNumberRow: Boolean): SoftKeyboard {
        var lastKeyBottom = 0f
        var lastKeyRight: Float
        var lastKeyTop: Float
        for (rowBean in rows) {
            lastKeyTop = lastKeyBottom  // 新行top为上一行bottom
            lastKeyRight = 0.005f // 新行x从0.005开始
            for (keyBean in rowBean) {
                var keyXPos = keyBean.mLeftF
                var keyYPos = keyBean.mTopF
                val keyWidth = keyBean.widthF
                val keyHeight = keyBean.heightF
                if(keyXPos == -1f || keyYPos == -1f || isNumberRow) {
                    if (keyXPos == -1f) keyXPos = lastKeyRight
                    if (keyYPos == -1f) keyYPos = lastKeyTop
                    if (isNumberRow) {
                        keyBean.setKeyDimensions(keyXPos, keyYPos/1.2f, keyHeight/1.2f)
                    } else {
                        keyBean.setKeyDimensions(keyXPos, keyYPos)
                    }
                }
                keyBean.setSkbCoreSize(Environment.instance.skbWidth, Environment.instance.skbHeight)
                lastKeyRight = keyXPos + keyWidth
                lastKeyTop = keyYPos
                lastKeyBottom = keyYPos + keyHeight
            }
        }
        return SoftKeyboard(rows)
    }

    private fun createT9Keys(codes: Array<Int>): Array<SoftKey> {
        val softKeys = mutableListOf<SoftKey>()
        val keyPreset =  if(mSkbValue == 0x7000) KeyPreset.strokeKeyPreset else KeyPreset.t9PYKeyPreset
        for(code in codes){
            val labels = keyPreset[code]
            softKeys.add(SoftKey(code = code, label = labels?.getOrNull(0) ?: "", labelSmall = labels?.getOrNull(1)?: "").apply {
                widthF = 0.21f
            })
        }
        return softKeys.toTypedArray()
    }

    private fun createT14Keys(codes: Array<Int>): Array<SoftKey> {
        val softKeys = mutableListOf<SoftKey>()
        val keyPreset = KeyPreset.t14KeyPreset
        for (code in codes) {
            val labels = keyPreset[code]
            softKeys.add(SoftKey(
                code = code,
                label = labels?.getOrNull(0) ?: "",
                labelSmall = labels?.getOrNull(1) ?: ""
            ).apply {
                widthF = 0.196f
            })
        }
        return softKeys.toTypedArray()
    }

    private fun createT9NumberKeys(codes: Array<Int>): Array<SoftKey> {
        val softKeys = mutableListOf<SoftKey>()
        val keyPreset = KeyPreset.t9NumberKeyPreset
        for(code in codes){
            val labels = keyPreset[code]
            softKeys.add(SoftKey(code = code, label = labels?.getOrNull(0) ?: "", labelSmall = labels?.getOrNull(1) ?: "").apply {
                widthF = 0.21f
            })
        }
        return softKeys.toTypedArray()
    }

    private fun createQwertyPYKeys(codes: Array<Int>): Array<SoftKey> {
        val keyMnemonicPreset = when (rimeValue) {
            SchemaIds.SCHEMA_ZH_DOUBLE_FLYPY + DoublePinyinSchemaMode.flypy -> doubleFlyMnemonicPreset
            SchemaIds.SCHEMA_ZH_DOUBLE_FLYPY + DoublePinyinSchemaMode.abc -> doubleAbcMnemonicPreset
            SchemaIds.SCHEMA_ZH_DOUBLE_FLYPY + DoublePinyinSchemaMode.mspy -> doubleMSMnemonicPreset
            SchemaIds.SCHEMA_ZH_DOUBLE_FLYPY + DoublePinyinSchemaMode.natural -> doubleNaturalMnemonicPreset
            SchemaIds.SCHEMA_ZH_DOUBLE_FLYPY + DoublePinyinSchemaMode.sogou -> doubleSogouMnemonicPreset
            SchemaIds.SCHEMA_ZH_DOUBLE_FLYPY + DoublePinyinSchemaMode.ziguang -> doubleZiguangMnemonicPreset
            else -> emptyMap()
        }
        val softKeys = mutableListOf<SoftKey>()
        val keyPreset = if(numberLine)KeyPreset.qwertyPYKeyPreset else KeyPreset.qwertyPYKeyNumberPreset
        for(code in codes){
            val labels = keyPreset[code]
            softKeys.add(SoftKey(code = code, label = labels?.getOrNull(0) ?: "", labelSmall = labels?.getOrNull(1) ?: "", keyMnemonic = keyMnemonicPreset[code] ?: "").apply {
                widthF = 0.099f
            })
        }
        return softKeys.toTypedArray()
    }

    private fun createQwertyKeys(codes: Array<Int>): Array<SoftKey> {
        val softKeys = mutableListOf<SoftKey>()
        val keyPreset = if(numberLine)KeyPreset.qwertyKeyPreset else KeyPreset.qwertyKeyNumberPreset
        for(code in codes){
            val labels = keyPreset[code]
            softKeys.add(SoftKey(code = code, label = labels?.getOrNull(0) ?: "", labelSmall = labels?.getOrNull(1) ?: "", keyMnemonic = labels?.getOrNull(2) ?: "").apply {
                widthF = 0.099f
            })
        }
        return softKeys.toTypedArray()
    }

    private fun createHandwritingKey(code: Int): SoftKey {
        val keyPreset = if(numberLine)KeyPreset.qwertyPYKeyPreset else KeyPreset.qwertyPYKeyNumberPreset
        val labels = keyPreset[code]
        return SoftKey(code = code, label = labels?.getOrNull(0) ?: "", labelSmall = labels?.getOrNull(1) ?: "").apply {
            widthF = 0.18f
        }
    }

    private fun createNumberLineKeys(codes: Array<Int>): Array<SoftKey> {
        val softKeys = mutableListOf<SoftKey>()
        for(code in codes) {
            val softKey = SoftKey(label = code.toString()).apply {
                widthF = 0.099f
                heightF = 0.2f
            }
            softKeys.add(softKey)
        }
        return softKeys.toTypedArray()
    }

    private fun createTextEditKeys(codes: Array<Int>): Array<SoftKey> {
        val softKeys = mutableListOf<SoftKey>()
        val keyPreset = KeyPreset.textEditKeyPreset
        for(code in codes){
            val labels = keyPreset[code]
            softKeys.add(SoftKey(code = code, label = labels?.getOrNull(0) ?: "").apply {
                widthF = 0.2475f
            })
        }
        return softKeys.toTypedArray()
    }

    private fun createKeyToggle(code: Int): SoftKeyToggle {
        return SoftKeyToggle(code)
    }

    companion object {
        private var mInstance: KeyboardBuilder? = null
        private val mSoftKeyboardMap = HashMap<Int, SoftKeyboard?>() //缓存所有可用键盘
        @JvmStatic
        val instance: KeyboardBuilder
            get() {
                if (null == mInstance) mInstance = KeyboardBuilder()
                return mInstance!!
            }
    }
}
