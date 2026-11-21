package com.whale.ime.editor

import android.os.SystemClock

/**
 * 混输交界判定的「上一个已上屏字符」缓存，同时服务两套规则：
 * - 中英 / 中数混输自动补空格：看紧邻光标的 [lastCodePoint]；
 * - 英文句首自动大写：看跳过空白与引号括号后的 [lastDecisiveCodePoint]（规则见 [SentenceCase]）。
 *
 * 判定基准是**提交时的原始文本**，不是回读编辑框：
 * - 英文模式下编码是编辑框里的 preedit，回读拿到的是编码尾巴；
 * - 花体字模式下提交出去的汉字已被替换成变形字符，回读也认不出来。
 *
 * 所以只在缓存不可信时（换输入框 / 删除 / 光标被别人挪走）回读一次编辑框兜底，
 * 读不到就放弃改写 —— 宁可不补、不大写，不可补错、改错。
 */
object SmartSpacing {

    /** 交界类型：中文↔数字、中文↔字母、英文标点→字母各有独立开关 */
    enum class Boundary { Digit, Letter, EnglishPunct }

    /** 上屏后的选择回调匹配窗口：超过这个时间就不再认为是自己引起的 */
    private const val COMMIT_SELECTION_WINDOW_MS = 500L

    /** 上一个已上屏的字符（原始文本末字符的码点）；null = 未知，需要回读编辑框 */
    private var lastCodePoint: Int? = null

    /**
     * 光标前最后一个「决定性字符」（跳过空白、引号、括号），英文句首大写判定用它。
     *
     * 与 [lastCodePoint] 分开存：补空格看的是**紧邻**光标的字符，句首判定看的是**有意义**的字符
     * （自动补的空格、刚上屏的括号都不该改变「上一句已经说完没有」）。
     * [decisiveKnown] = false 表示这份缓存不可信（与 [lastCodePoint] 同时失效），需要回读。
     */
    private var lastDecisiveCodePoint: Int? = null
    private var decisiveKnown = false

    /** 上一次提交写入编辑框的净字符数（已扣除被替换的 preedit） */
    private var pendingCommitLength = 0

    /** 上一次提交的时间；0 表示没有待匹配的提交 */
    private var pendingCommitAt = 0L

    fun isCjk(codePoint: Int): Boolean =
        Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN

    /**
     * 两个字符的交界要不要补空格。
     *
     * 只有「中文 ↔ 字母/数字」与「英文标点 → 字母」才返回类型；空白、emoji、
     * 中文 ↔ 中文、字母 ↔ 数字都返回 null（不补）。
     *
     * 英文标点这条只认**字母**：数字前面的点是小数点（`1.5`、`v2.0`、`No.3`），
     * 补空格会把数值拆坏。
     */
    fun boundary(prevCodePoint: Int?, nextCodePoint: Int): Boundary? {
        if (prevCodePoint == null) return null
        val prevCjk = isCjk(prevCodePoint)
        if (prevCjk != isCjk(nextCodePoint)) {
            val other = if (prevCjk) nextCodePoint else prevCodePoint
            return when {
                Character.isDigit(other) -> Boundary.Digit
                Character.isLetter(other) -> Boundary.Letter
                else -> null
            }
        }
        if (!prevCjk &&
            SentenceCase.isEnglishPunctuation(prevCodePoint) &&
            Character.isLetter(nextCodePoint)
        ) {
            return Boundary.EnglishPunct
        }
        return null
    }

    /** 取首个码点（emoji 这类代理对按一个字符算）；空串返回 -1 */
    fun firstCodePoint(text: CharSequence): Int {
        if (text.isEmpty()) return -1
        val first = text[0]
        return if (Character.isHighSurrogate(first) && text.length >= 2) {
            Character.toCodePoint(first, text[1])
        } else {
            first.code
        }
    }

    fun lastCodePointOrNull(): Int? = lastCodePoint

    /** 光标前最后一个决定性字符；只在 [isCacheTrusted] 为 true 时可用 */
    fun lastDecisiveCodePointOrNull(): Int? = lastDecisiveCodePoint

    /** 缓存是否可信：可信时上面两个末字符都能直接用，不必回读编辑框 */
    fun isCacheTrusted(): Boolean = lastCodePoint != null && decisiveKnown

    /**
     * 回读编辑框得到的结果写回缓存。
     *
     * @param lastCodePoint 光标前最后一个字符；null = 已确认光标前没有任何字符
     * @param decisiveCodePoint 光标前最后一个决定性字符；null = 已确认光标前没有任何字符
     */
    fun restore(lastCodePoint: Int?, decisiveCodePoint: Int?) {
        this.lastCodePoint = lastCodePoint
        this.lastDecisiveCodePoint = decisiveCodePoint
        decisiveKnown = true
    }

    /**
     * 记录一次上屏。
     *
     * @param raw 实际提交的原始文本（可能已带自动补的空格）
     * @param committed 真正写进编辑框的文本（可能已转花体字）
     * @param replaced 被这次提交替换掉的 preedit 长度
     */
    fun onCommit(raw: CharSequence, committed: CharSequence, replaced: Int) {
        lastCodePoint = lastCodePoint(raw)
        // 纯空白 / 纯中性符号的提交（自动补的空格、成对括号）不改变「上一个决定性字符」；
        // 缓存本来就不可信时保持不可信 —— 不能把"不知道"当成"文档开头"
        if (decisiveKnown) {
            SentenceCase.lastDecisiveCodePoint(raw)?.let { lastDecisiveCodePoint = it }
        }
        pendingCommitLength = (committed.length - replaced).coerceAtLeast(0)
        pendingCommitAt = SystemClock.uptimeMillis()
    }

    /** 缓存不可信（删除 / 换输入框 / 光标被挪走）：下次提交前回读编辑框 */
    fun invalidate() {
        lastCodePoint = null
        lastDecisiveCodePoint = null
        decisiveKnown = false
        pendingCommitLength = 0
        pendingCommitAt = 0L
    }

    /**
     * 这次选择回调是不是我们自己上屏引起的。
     *
     * 一次提交可能引来多个回调（例如英文模式先替换 preedit、再补一个自动空格），
     * 也可能是多次提交合并成一个回调，所以只要求位移落在 `0..本次净字符数` 内。
     * 返回 false 时调用方应 [invalidate]：光标被用户或宿主挪走了，缓存不再可信。
     */
    fun consumeOwnCommit(oldSelStart: Int, newSelStart: Int): Boolean {
        // 光标没动：编辑框尾部没变，缓存照样可信（输入会话开始时的空回调走这条）
        val delta = newSelStart - oldSelStart
        if (delta == 0) return true
        if (pendingCommitAt == 0L) return false
        val now = SystemClock.uptimeMillis()
        if (now - pendingCommitAt > COMMIT_SELECTION_WINDOW_MS) {
            pendingCommitLength = 0
            pendingCommitAt = 0L
            return false
        }
        return delta in 0..pendingCommitLength
    }

    private fun lastCodePoint(text: CharSequence): Int? {
        if (text.isEmpty()) return null
        val end = text.length
        val last = text[end - 1]
        return if (Character.isLowSurrogate(last) && end >= 2) {
            Character.toCodePoint(text[end - 2], last)
        } else {
            last.code
        }
    }
}
