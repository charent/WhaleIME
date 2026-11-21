package com.whale.ime.util

import java.util.regex.Pattern

object StringUtils {
    /**
     * 判断字符串是不是字母
     */
    @JvmStatic
    fun isLetter(str: String?): Boolean {
        val pattern = Pattern.compile("[a-zA-Z]*")
        return pattern.matcher(str.toString()).matches()
    }

    /**
     * 判断字符串是不是英文
     */
    @JvmStatic
    fun isEnglishWord(str: String?): Boolean {
        val pattern = Pattern.compile("[a-zA-Z ]*")
        return pattern.matcher(str.toString()).matches()
    }

    @JvmStatic
    fun isNumber(str: String?): Boolean {
        if(str.isNullOrBlank())return false
        val pattern = Pattern.compile("^[+-]?\\d*(\\.\\d*)?\$")
        return pattern.matcher(str).matches()
    }

    fun isChineseEnd(input: String): Boolean {
        val chineseEndPattern = "[\\u4e00-\\u9fff]\$".toRegex()
        return chineseEndPattern.find(input) != null
    }

    /** emoji 组合里不改变语义的修饰码点：变体选择符、零宽连接符、键帽、肤色、标签字符 */
    private fun isEmojiModifier(cp: Int): Boolean =
        cp in 0xFE00..0xFE0F || cp == 0x200D || cp == 0x20E3 ||
            cp in 0x1F3FB..0x1F3FF || cp in 0xE0020..0xE007F

    /**
     * 按「可见字符」计长度：一个码点算一个，跳过 [isEmojiModifier] 那些修饰码点。
     *
     * `String.length` 数的是 UTF-16 code unit：`😄` 是代理对（2）、`❄️`/`❤️` 额外带一个
     * 变体选择符（2 个码点 / 3 个 code unit）、CJK 扩展 B 区汉字也是 2。
     * 凡是要判断「这是几个字」（单字 / 两字词 / 候选分桶 / 单字过滤）的地方都走这里，
     * 否则一个 emoji 会被当成两字词，抢到候选栏前面去。
     */
    @JvmStatic
    fun glyphCount(text: String?): Int {
        if (text.isNullOrEmpty()) return 0
        var index = 0
        var count = 0
        while (index < text.length) {
            val cp = text.codePointAt(index)
            index += Character.charCount(cp)
            if (isEmojiModifier(cp)) continue
            count++
        }
        return count
    }

    /**
     * 是否 emoji 的码点（不含修饰符）。
     *
     * 只收真正的 pictograph 区：`0x2190..` 的箭头、`0x2300..` 的键盘符号（⌘⌫⏎）、
     * `0x25A0..` 的几何图形（▪□▶）虽然也被 Unicode 标为 emoji，但在输入法里更常作普通
     * 符号候选出现，误判会让它们被当成某条候选的 emoji 孪生项。宁可漏判：
     * 漏判的 emoji 只是一条普通单字候选（[glyphCount] 下不再被抬到最前），
     * 误判则会打乱正常符号候选的位置。
     */
    private fun isPictograph(cp: Int): Boolean =
        cp in 0x1F000..0x1FAFF ||   // 表情、人物、手势、动物、食物、交通、符号补充、国旗
            cp in 0x2600..0x27BF ||     // 杂项符号 + 装饰符号（❤ ❄ ✅ ☀）
            cp in 0x2B00..0x2BFF ||     // ⬆ ⬇ ⭐ ⭕
            cp == 0x00A9 || cp == 0x00AE || cp == 0x203C || cp == 0x2049 ||
            cp == 0x2122 || cp == 0x2139 || cp == 0x24C2 ||
            cp == 0x3030 || cp == 0x303D || cp == 0x3297 || cp == 0x3299

    /**
     * 判断整串是否「纯 emoji」。
     *
     * 用途：Rime 的 `simplifier@emoji` 会给词条追加一条 emoji 孪生候选（`笑` 后面跟 `😄`），
     * 候选排序/过滤需要把这条认出来，才能不把它当普通候选参与长度分桶。
     * 组合序列（`❤️`、`👍🏻`、`👨‍👩‍👧`、`1️⃣`）里的修饰码点会被跳过；
     * 含字母、数字、汉字、全角标点的一律返回 false。
     */
    @JvmStatic
    fun isEmojiOnly(text: String?): Boolean {
        if (text.isNullOrEmpty()) return false
        var index = 0
        var pictographs = 0
        while (index < text.length) {
            val cp = text.codePointAt(index)
            index += Character.charCount(cp)
            if (isEmojiModifier(cp)) continue
            if (!isPictograph(cp)) return false
            pictographs++
        }
        return pictographs > 0
    }

    // 标点全角半角关系
    // 1)半角字符(除空格外)是从33(0x21)开始到126(0x7E)结束;
    // 2)与半角字符对应的全角字符是从65281(unicode编码的0xFF01)开始到65374(unicode编码的0xFF3E)结束；
    // 3)其中半角的空格是32(0x20).对应的全角空格是12288(unicode编码的0x3000)；
    // 4)其中半角的句号是65377(0xFF61).对应的全角空格是12290(unicode编码的0xFF61)；
    // 5)半角和全角的关系很明显,除空格外的字符偏移量都是是65248(65281-33 = 65248)
    const val SBC_SPACE = 12288 // 全角空格 12288
        .toChar()
    const val DBC_SPACE = 32 //半角空格 32
        .toChar()
    const val SBC_PERIOD = 12290 // 全角句号
        .toChar()
    const val DBC_PERIOD = 65377 // 半角句号
        .toChar()
    const val ASCII_START = 30.toChar()
    const val ASCII_END = 126.toChar()
    const val UNICODE_START = 65278.toChar()
    const val UNICODE_END = 65374.toChar()
    const val DBC_SBC_STEP = 65248 // 全角半角转换间隔
        .toChar()

    // 全角转半角
    private fun sbc2dbc(src: Char): Char {
        return if (src == SBC_SPACE) {
            DBC_SPACE
        } else if (src == SBC_PERIOD) {
            DBC_PERIOD
        } else {
            if (src in UNICODE_START..UNICODE_END) {
                (src.code - DBC_SBC_STEP.code).toChar()
            } else src
        }
    }

    // 全角转半角
    @JvmStatic
    fun sbc2dbcCase(src: String?): String? {
        if (src == null) {
            return null
        }
        val c = src.toCharArray()
        for (i in c.indices) {
            c[i] = sbc2dbc(c[i])
        }
        return String(c)
    }

    /**
     * 判断是否是半角符号
     * 排除数字、大小写字母
     */
    fun isDBCSymbol(src: String?): Boolean {
        if (src == null || src.length > 1) {
            return false
        }
        val c = src[0]
        return c.code in 32..47 || c.code in 58..64 || c.code in 91..96 || c.code in 123..126
    }

}
