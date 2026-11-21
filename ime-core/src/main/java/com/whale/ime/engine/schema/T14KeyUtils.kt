package com.whale.ime.engine.schema

/**
 * 14 键键位的编码换算（与九键的 [T9PinYinUtils] 同职责）。
 *
 * 数据来自 [T14KeyMap]（由 `gen-t14-algebra.py` 生成，与 schema 的 derive 段、
 * `KeyboardData.layoutT14Cn` 的键位一一对应），这里只负责建索引与查询：
 *
 * - 拼音 → 键码（`pinyin2Key` / `pinyin2KeyChar`）：写给 KeyRecordStack 还原、
 *   用户词库别名索引、希腊字母键码匹配；
 * - 键码 → 拼音（`keyToPinyin`）：候选面板左侧读音栏按前缀反查。
 *
 * 键码与九键一样用大写字母：大写 = 键码、小写 = 拼音，`replaceKey` 把键码段
 * 换成小写拼音后引擎仍能用全拼查到音节图。
 */
object T14KeyUtils {

    /** 音节最长 6 字母（zhuang / chuang），与九键 t9KeyToPinyin 的截断长度一致 */
    private const val MAX_KEY_LEN = 6

    /** 小写字母 → 键码（QW→Q、ER→E … M→M） */
    private val charToKey: Map<Char, Char> = buildMap {
        val raw = T14KeyMap.CHAR_TO_KEY
        var i = 0
        while (i + 1 < raw.length) {
            put(raw[i], raw[i + 1])
            i += 2
        }
    }

    /** 音节 → 键码串 */
    private val syllableToKey: Map<String, String> = buildMap {
        T14KeyMap.SYLLABLES.split(';').forEach { item ->
            val parts = item.trim().split(' ')
            if (parts.size == 2 && parts[0].isNotEmpty() && parts[1].isNotEmpty()) {
                put(parts[0], parts[1])
            }
        }
    }

    /** 键码串 → 候选音节（同键码多音节：DA 既是 da 也是 fa） */
    private val keyToSyllables: Map<String, List<String>> = buildMap {
        val acc = HashMap<String, MutableList<String>>(512)
        syllableToKey.forEach { (syllable, key) ->
            acc.getOrPut(key) { ArrayList(2) }.add(syllable)
        }
        acc.forEach { (key, syllables) -> put(key, syllables.sorted()) }
    }

    /** 模糊音键码规则（`derive/^ZGA$/ZA/`），由 CorrectionSchema 写进 t14 custom patch */
    val fuzzyRules: List<String> = parseRules(T14KeyMap.FUZZY)

    /** j/q/x/y 后的 u 写作 v 的等价键码规则（`derive/^QU$/QC/`） */
    val vAsURules: List<String> = parseRules(T14KeyMap.V_AS_U)

    private fun parseRules(raw: String): List<String> =
        raw.split(';').mapNotNull { item ->
            val parts = item.trim().split('>')
            if (parts.size == 2 && parts[0].isNotEmpty() && parts[1].isNotEmpty()) {
                "derive/^${parts[0]}\$/${parts[1]}/"
            } else null
        }.distinct()

    /** 拼音字母 → 14 键键码；非小写字母原样返回（大写键码/分词符等不变） */
    fun pinyin2KeyChar(c: Char): Char = charToKey[c] ?: c

    /** 拼音（或任意小写串）→ 键码串 */
    fun pinyin2Key(pinyin: String?): String {
        if (pinyin.isNullOrEmpty()) return ""
        return buildString(pinyin.length) { pinyin.forEach { append(pinyin2KeyChar(it)) } }
    }

    /**
     * 键码串 → 可能的拼音，长前缀优先（与九键 `t9KeyToPinyin` 同用途）。
     *
     * 只用于候选页读音栏与 `RimeEngine.pinyins`：同一串键位能切出的音节候选。
     */
    fun keyToPinyin(keyCode: String?): Array<String> {
        if (keyCode.isNullOrEmpty()) return emptyArray()
        val key = if (keyCode.length > MAX_KEY_LEN) keyCode.substring(0, MAX_KEY_LEN) else keyCode
        val result = ArrayList<String>(5)
        for (length in key.length downTo 1) {
            keyToSyllables[key.substring(0, length)]?.let { result.addAll(it) }
        }
        return result.toTypedArray()
    }

    /** 纯键码串（全大写）判定；混了小写说明有段落已被 replaceKey 换成拼音 */
    fun isKeyCode(text: String): Boolean = text.isNotEmpty() && text.all { it in 'A'..'Z' }
}
