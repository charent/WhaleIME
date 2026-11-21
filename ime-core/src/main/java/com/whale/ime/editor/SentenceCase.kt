package com.whale.ime.editor

/**
 * 英文输入的两条「智能」规则（纯函数，状态在 [SmartSpacing]）：
 * - **句首大写**：看光标前最后一个「决定性字符」（[shouldCapitalizeAfter]）；
 * - **标点后补空格**：英文标点后直接接单词时补一个空格（[isEnglishPunctuation]）。
 *
 * 空白不决定大小写，但换行决定；引号、括号同样不决定（`"hello` 里的引号
 * 不改变「这是句首」这个事实），所以它们统一算中性字符，回看时跳过。
 */
object SentenceCase {

    /** 句末标点：只有这些字符之后才算新句子开头（逗号、冒号、分号不算） */
    private const val TERMINATORS = ".!?…。！？"

    /**
     * 后面要补空格的英文标点。
     *
     * 数值场景不必单独排除（`1.5` / `v2.0`）：调用方只对「下一个字符是字母」补空格，
     * 小数点后面接的本来就是数字。
     */
    private const val ENGLISH_PUNCT = ".,!?;:…"

    /** 中性符号：引号与成对括号，不决定大小写 */
    private const val NEUTRAL_CHARS = "\"'“”‘’「」『』‹›()[]{}（）［］【】《》〈〉<>«»"

    /** 换行也算句末，但它本身是决定性字符（回看时不能被跳过） */
    private fun isLineBreak(codePoint: Int): Boolean =
        codePoint == '\n'.code || codePoint == '\r'.code

    fun isTerminator(codePoint: Int): Boolean =
        isLineBreak(codePoint) ||
            (codePoint <= Char.MAX_VALUE.code && codePoint.toChar() in TERMINATORS)

    /** 是不是「后面要补空格」的英文标点 */
    fun isEnglishPunctuation(codePoint: Int): Boolean =
        codePoint <= Char.MAX_VALUE.code && codePoint.toChar() in ENGLISH_PUNCT

    /** 空白、引号、括号这类不参与句首判定的字符 */
    fun isNeutral(codePoint: Int): Boolean {
        if (isLineBreak(codePoint)) return false
        if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) return true
        return codePoint <= Char.MAX_VALUE.code && codePoint.toChar() in NEUTRAL_CHARS
    }

    /** 在 [prev] 之后是否开启新句子；null = 光标前没有任何字符（文档开头） */
    fun shouldCapitalizeAfter(prev: Int?): Boolean = prev == null || isTerminator(prev)

    /** 文本里最后一个决定性字符；全是空白/中性符号时返回 null */
    fun lastDecisiveCodePoint(text: CharSequence): Int? {
        if (text.isEmpty()) return null
        val points = text.toString().codePoints().toArray()
        for (i in points.indices.reversed()) {
            if (!isNeutral(points[i])) return points[i]
        }
        return null
    }

    /**
     * 首字母大写：只改第一个字符，**长度不变**。
     *
     * 长度必须不变：一次提交写进编辑框的净字符数（`committed.length - replaced`）参与
     * 光标位移判定（见 [SmartSpacing.consumeOwnCommit]），换成会展开成多个字符的映射
     * （`ß` → `SS`、`ﬁ` → `Fi`）会把后续判定全部带偏。
     */
    fun capitalizeFirstLetter(text: CharSequence): String {
        if (text.isEmpty()) return ""
        val first = text[0]
        val upper = first.uppercaseChar()
        if (upper == first) return text.toString()
        return buildString(text.length) {
            append(upper)
            append(text, 1, text.length)
        }
    }
}
