package com.whale.ime.userword

import com.whale.ime.storage.WhaleDatabase
import com.whale.ime.storage.entity.UserWord
import com.whale.ime.vendor.pinyin4j.PinyinHelper
import com.whale.ime.vendor.pinyin4j.format.HanyuPinyinOutputFormat
import com.whale.ime.vendor.pinyin4j.format.HanyuPinyinToneType
import com.whale.ime.vendor.pinyin4j.format.HanyuPinyinVCharType
import com.whale.ime.preference.AppPrefs
import com.whale.ime.userword.UserWordCodec
import com.whale.ime.engine.schema.T14KeyUtils
import com.whale.ime.engine.schema.T9PinYinUtils
import java.util.Locale
import com.whale.ime.config.SchemaIds

/**
 * 用户词库的唯一读写入口。
 *
 * 为什么要有内存索引：`CustomEngine.processPhrase()` 在**每一次按键**都会被调用，
 * 而 Room 是 `allowMainThreadQueries()` 打开的；词库上万条时逐次查库会直接卡输入。
 * 因此启动时把启用中的词条全部索引进内存（code -> 前 8 条），查询零 IO，写操作同步维护索引。
 *
 * 索引有两套，回答的是两个不同问题：
 * - `index`（编码 -> 词）：**用户当时是按哪串键把这个词教会/加进来的**。等值命中，最快；
 * - `abbrevIndex`（读音 -> 词）：**这个词本身怎么读**。同一个词在 Rime 里的合法输入形态
 *   是「每个音节取它的任意前缀」的组合（`shide` / `shid` / `shd` / `sd` / `shi'd` 都对），
 *   只认其中一串就会漏（见 [WordForm]）。
 */
object UserWordManager {

    /** 词库软上限，超出后按「权重低、命中少、更久未用」淘汰自动学习的词 */
    const val MAX_WORDS = 20000

    /** 单次输入最多注入的候选数 */
    const val MAX_INJECT = 3

    const val MAX_CODE_LENGTH = 24
    private const val MAX_PER_CODE = 8
    private const val MIN_LEARN_WORD_LENGTH = 2
    private const val MAX_LEARN_WORD_LENGTH = 6
    private const val LEARN_DEBOUNCE_MS = 2000L

    /** 只给不超过这个长度的词做读音推导：再长就是整句，缩写匹配没有意义 */
    private const val MAX_FORM_LENGTH = 8

    private val lock = Any()
    private val index = HashMap<String, MutableList<UserWord>>()

    /**
     * 一个词的读音形态：逐字给出该字的全部读音（多音字给多个写法）。
     *
     * 为什么需要它：用户词库原先只按「产生它的那串按键」精确匹配，同一个词换一种合法输入就
     * 命中不了 —— 词库里加「是的」时编码自动填成首字母 `sd`，用户打 `shi'd`（规范化后 `shid`）
     * 永远看不到它；自动学习记的是当时的 `shid`，改成全拼 `shide` 又失效。
     * 「按键串」不是词的属性，读音才是，所以缩写匹配按读音做（见 [matchesAbbreviation]）。
     */
    private class WordForm(val word: UserWord, val syllables: List<List<String>>)

    /** 拼音首字母 -> 词形。先按输入首字母粗筛，再做音节前缀 DP，避免每次按键全表扫描 */
    private val abbrevIndex = HashMap<Char, MutableList<WordForm>>()

    /** 单字读音缓存：同一个字会在上万条词里反复出现，拼音表只该查一次 */
    private val charPinyinCache = HashMap<Char, List<String>>()

    private val pinyinFormat = HanyuPinyinOutputFormat().apply {
        toneType = HanyuPinyinToneType.WITHOUT_TONE
        vCharType = HanyuPinyinVCharType.WITH_V
    }

    /** (word|code) -> 上次学习时间，防止连点重复落库 */
    private val recentLearned = HashMap<String, Long>()

    @Volatile
    private var loaded = false

    @Volatile
    private var total = 0

    private val dao get() = WhaleDatabase.instance.userWordDao()

    enum class SaveResult { OK, EMPTY_WORD, INVALID_CODE, DUPLICATE }

    // ---------------------------------------------------------------- 索引

    /**
     * 后台线程调用：把启用中的词条全部载入内存索引
     */
    fun load() {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            val rows = dao.getAll().filter { it.enabled }
            index.clear()
            abbrevIndex.clear()
            rows.forEach { putLocked(it) }
            total = dao.count()
            loaded = true
        }
    }

    fun isLoaded(): Boolean = loaded

    fun reload() {
        synchronized(lock) {
            loaded = false
        }
        load()
    }

    /**
     * 编码到候选词的映射；同一编码下按 权重 > 命中次数 > 更新时间 排序。
     *
     * 两段命中，顺序即优先级：
     * 1. **原样编码**：用户就是按这串键把这个词教会/加进来的（包括九键的按键串别名），
     *    这是最强的信号，排最前；
     * 2. **读音缩写**：`shide` / `shid` / `shd` / `sd` 都能命中同一个词（见 [matchesAbbreviation]）。
     *    这一段的键不是「某一次按键」，而是「词的读音」，所以同一句话怎么打都认。
     *
     * @param allowAbbreviation 英文方案下传 false：那里的按键串不是拼音，缩写匹配只会误伤
     */
    fun match(
        rawCode: String,
        limit: Int = MAX_INJECT,
        allowAbbreviation: Boolean = true,
    ): List<String> {
        if (!loaded) return emptyList()
        val code = UserWordCodec.normalizeCode(rawCode)
        if (code.isEmpty()) return emptyList()
        synchronized(lock) {
            val words = index[code]
            if (words != null && words.size >= limit) {
                return words.asSequence().map { it.word }.distinct().take(limit).toList()
            }
            val result = LinkedHashSet<String>(limit * 2)
            words?.forEach { result.add(it.word) }
            // 九键的按键串（纯数字）不做读音推导：T9 -> 拼音是多对多的，反推不可靠
            if (allowAbbreviation && result.size < limit &&
                code.length >= 2 && code.all { it in 'a'..'z' }
            ) {
                val bucket = abbrevIndex[code[0]].orEmpty()
                val matched = ArrayList<UserWord>(4)
                for (form in bucket) {
                    if (matchesAbbreviation(code, form.syllables)) matched.add(form.word)
                }
                // 同一首字母下可能有多个词命中，按词条自身的权重/命中次数排
                matched.sortWith(UserWord.RANK_COMPARATOR)
                for (word in matched) {
                    if (result.size >= limit) break
                    result.add(word.word)
                }
            }
            return result.take(limit)
        }
    }

    /**
     * 字母编码额外索引九键 / 14 键的按键串，让同一条词在多种方案下都能命中。
     *
     * 含数字的编码（九键学习得到的）不做反向推导：T9 -> 拼音是多对多的，反推不可靠。
     * 14 键的键码本身是大写字母，派生出来与原串相同，`distinct` 会去重。
     */
    private fun aliasKeys(code: String): List<String> {
        if (code.isEmpty() || code.any { it in '0'..'9' }) return listOf(code)
        val t9 = buildString(code.length) { code.forEach { append(T9PinYinUtils.pinyin2T9Key(it)) } }
        val t14 = buildString(code.length) { code.forEach { append(T14KeyUtils.pinyin2KeyChar(it)) } }
        return listOf(code, t9, t14).distinct()
    }

    private fun putLocked(word: UserWord) {
        aliasKeys(word.code).forEach { key ->
            val list = index.getOrPut(key) { mutableListOf() }
            list.removeAll { it.word == word.word && it.code == word.code }
            if (word.enabled) {
                list.add(word)
                list.sortWith(UserWord.RANK_COMPARATOR)
                if (list.size > MAX_PER_CODE) {
                    while (list.size > MAX_PER_CODE) list.removeAt(list.size - 1)
                }
            }
            if (list.isEmpty()) index.remove(key)
        }
        indexFormLocked(word)
    }

    private fun removeLocked(word: UserWord) {
        aliasKeys(word.code).forEach { key ->
            val list = index[key] ?: return@forEach
            list.removeAll { it.word == word.word && it.code == word.code }
            if (list.isEmpty()) index.remove(key)
        }
        removeFormLocked(word)
    }

    private fun rebuildIndexLocked() {
        index.clear()
        abbrevIndex.clear()
        val rows = dao.getAll().filter { it.enabled }
        rows.forEach { putLocked(it) }
        total = dao.count()
    }

    // ---------------------------------------------------------------- 读音形态

    /**
     * 把一个词登记到读音索引。先清掉旧登记，再按当前读音重新挂一遍
     * （改词、改权重、启用状态切换都走这条路）。
     */
    private fun indexFormLocked(word: UserWord) {
        // 读音只算一次：同一个词重新 learn（hits 变化）时下面还要按同一套读音摘旧登记
        val syllables = syllablesOf(word.word)
        removeForm(syllables, word)
        if (!word.enabled || syllables.isEmpty()) return
        // 首字是多音字时（重 chong/zhong）各读音的首字母可能不同，每个都要挂一份，
        // 否则另一种读法的输入形态永远粗筛不到这个词。
        for (key in syllables[0].map { it[0] }.distinct()) {
            abbrevIndex.getOrPut(key) { mutableListOf() }.add(WordForm(word, syllables))
        }
    }

    private fun removeFormLocked(word: UserWord) = removeForm(syllablesOf(word.word), word)

    private fun removeForm(syllables: List<List<String>>, word: UserWord) {
        if (syllables.isEmpty()) return
        for (key in syllables[0].map { it[0] }.distinct()) {
            val list = abbrevIndex[key] ?: continue
            list.removeAll { it.word.word == word.word && it.word.code == word.code }
            if (list.isEmpty()) abbrevIndex.remove(key)
        }
    }

    /**
     * 逐字的候选读音。任何一个字取不到读音就整词放弃（纯英文/数字/符号词条没有缩写形态，
     * 只走编码精确匹配）。
     */
    private fun syllablesOf(word: String): List<List<String>> {
        if (word.isEmpty() || word.length > MAX_FORM_LENGTH) return emptyList()
        val out = ArrayList<List<String>>(word.length)
        for (c in word) {
            val options = charPinyinCache.getOrPut(c) { readCharPinyin(c) }
            if (options.isEmpty()) return emptyList()
            out.add(options)
        }
        return out
    }

    /**
     * 单字的全部读音（去声调、小写、只留 a-z）。多音字全部保留：
     * 「的」既可能是 de 也可能是 di，保留两种才能让 `shid` 命中「是的」。
     */
    private fun readCharPinyin(c: Char): List<String> {
        if (c !in '\u4e00'..'\u9fff' && c !in '\u3400'..'\u4dbf') return emptyList()
        return try {
            PinyinHelper.toHanYuPinyinMulti(c.toString(), pinyinFormat, ",")
                .split(',')
                .asSequence()
                .map { pinyin -> pinyin.lowercase(Locale.ROOT).filter { it in 'a'..'z' } }
                .filter { it.isNotEmpty() }
                .distinct()
                .toList()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /**
     * 输入串是否是该词的一种合法输入形态：把输入切成若干段，第 i 段必须是第 i 个音节
     * （任一读音）的前缀，且每段至少一个字母。
     *
     * 这样 `shide`（全拼）/ `shid`（第二音节简拼）/ `shd` / `sd`（首字母）/ 混拼全部命中，
     * 与 Rime 的 `abbrev` 规则（任意音节可用前缀缩写）语义一致 —— 用户词库不该要求用户
     * 记住「当初是按哪串键加的词」。
     */
    private fun matchesAbbreviation(input: String, syllables: List<List<String>>): Boolean {
        if (syllables.isEmpty()) return false
        var minLength = 0
        var maxLength = 0
        for (options in syllables) {
            minLength += 1
            maxLength += options.maxOf { it.length }
        }
        if (input.length < minLength || input.length > maxLength) return false
        val size = input.length
        var reachable = BooleanArray(size + 1)
        reachable[0] = true
        for (options in syllables) {
            val next = BooleanArray(size + 1)
            var advanced = false
            for (start in 0 until size) {
                if (!reachable[start]) continue
                for (spelling in options) {
                    val end = minOf(size, start + spelling.length)
                    var pos = start + 1
                    // 逐字母比较：第 pos - 1 个输入字母必须等于该音节拼写的同位置字母
                    while (pos <= end && input[pos - 1] == spelling[pos - 1 - start]) {
                        next[pos] = true
                        advanced = true
                        pos++
                    }
                }
            }
            if (!advanced) return false
            reachable = next
        }
        return reachable[size]
    }

    // ---------------------------------------------------------------- 增删改查

    fun add(
        word: String,
        rawCode: String,
        weight: Int = 0,
        source: Int = UserWord.SOURCE_MANUAL,
        schema: String = ""
    ): SaveResult {
        val trimmed = word.trim()
        if (trimmed.isEmpty() || trimmed.length > UserWordCodec.MAX_WORD_LENGTH) return SaveResult.EMPTY_WORD
        val code = UserWordCodec.normalizeCode(rawCode)
        if (code.isEmpty() || code.length > MAX_CODE_LENGTH) return SaveResult.INVALID_CODE
        if (dao.find(trimmed, code) != null) return SaveResult.DUPLICATE

        val entity = UserWord(
            word = trimmed,
            code = code,
            schema = schema,
            source = source,
            hits = 1,
            weight = weight.coerceIn(0, 9999),
        )
        dao.insert(entity)
        synchronized(lock) {
            total = dao.count()
            putLocked(entity)
        }
        evictIfNeeded()
        return SaveResult.OK
    }

    /**
     * 修改词条。主键（word + code）变化时等价于「删原记录 + 插新记录」；新主键已存在则判重。
     */
    fun update(
        oldWord: String,
        oldCode: String,
        word: String,
        rawCode: String,
        weight: Int
    ): SaveResult {
        val trimmed = word.trim()
        if (trimmed.isEmpty() || trimmed.length > UserWordCodec.MAX_WORD_LENGTH) return SaveResult.EMPTY_WORD
        val code = UserWordCodec.normalizeCode(rawCode)
        if (code.isEmpty() || code.length > MAX_CODE_LENGTH) return SaveResult.INVALID_CODE

        val old = dao.find(oldWord, oldCode) ?: return SaveResult.EMPTY_WORD
        val keyChanged = old.word != trimmed || old.code != code
        if (keyChanged && dao.find(trimmed, code) != null) return SaveResult.DUPLICATE

        val updated = old.copy(
            word = trimmed,
            code = code,
            weight = weight.coerceIn(0, 9999),
            // 用户确认过的词不再算「学习词」，避免被自动淘汰
            source = UserWord.SOURCE_MANUAL,
            updatedAt = System.currentTimeMillis(),
        )
        dao.deleteByKey(old.word, old.code)
        dao.insert(updated)
        synchronized(lock) {
            removeLocked(old)
            putLocked(updated)
            total = dao.count()
        }
        return SaveResult.OK
    }

    fun delete(word: String, code: String) {
        val old = dao.find(word, code) ?: return
        dao.deleteByKey(word, code)
        synchronized(lock) {
            removeLocked(old)
            total = dao.count()
        }
    }

    fun clearAll() {
        dao.deleteAll()
        synchronized(lock) {
            index.clear()
            abbrevIndex.clear()
            charPinyinCache.clear()
            total = 0
        }
    }

    fun clearLearned() {
        dao.deleteBySource(UserWord.SOURCE_LEARNED)
        synchronized(lock) { rebuildIndexLocked() }
    }

    fun count(): Int = total

    fun count(keyword: String, source: Int): Int = dao.countQuery(keyword, source)

    fun page(keyword: String, source: Int, limit: Int, offset: Int): List<UserWord> =
        dao.page(keyword, source, limit, offset)

    fun all(): List<UserWord> = dao.getAll()

    // ---------------------------------------------------------------- 自动学习

    /**
     * 记录一次「用户纠正」：输入 code 后选择了 [word]。
     *
     * @param rawCode 本次输入开始时完整的组合串（如 `nihao`、`64426`、双拼的 `nh`）
     * @param word    用户最终选中的词
     * @param schema  当前 Rime 方案 id
     */
    fun learn(rawCode: String, word: String, schema: String) {
        if (!AppPrefs.getInstance().input.userDictAutoLearn.getValue()) return

        val code = UserWordCodec.normalizeCode(rawCode)
        val trimmed = word.trim()
        if (!isLearnable(trimmed, code)) return
        if (schema == SchemaIds.SCHEMA_EN || schema == SchemaIds.SCHEMA_ZH_HANDWRITING) return

        val now = System.currentTimeMillis()
        val recentKey = "$trimmed|$code"
        synchronized(lock) {
            val last = recentLearned[recentKey]
            if (last != null && now - last < LEARN_DEBOUNCE_MS) return
            recentLearned[recentKey] = now
            if (recentLearned.size > 512) recentLearned.clear()
        }

        val existing = dao.find(trimmed, code)
        if (existing != null) {
            existing.hits += 1
            existing.updatedAt = now
            dao.update(existing)
            synchronized(lock) { putLocked(existing) }
            return
        }

        val entity = UserWord(
            word = trimmed,
            code = code,
            schema = schema,
            source = UserWord.SOURCE_LEARNED,
            hits = 1,
            weight = 0,
        )
        dao.insert(entity)
        synchronized(lock) {
            total = dao.count()
            putLocked(entity)
        }
        evictIfNeeded()
    }

    private fun isLearnable(word: String, code: String): Boolean {
        if (word.isEmpty() || word.length > MAX_LEARN_WORD_LENGTH) return false
        if (code.isEmpty() || code.length > MAX_CODE_LENGTH) return false
        if (word == code) return false
        if (word.any { it.isWhitespace() || it.isISOControl() }) return false
        val cjk = word.count { it in '\u4e00'..'\u9fff' || it in '\u3400'..'\u4dbf' }
        return cjk >= MIN_LEARN_WORD_LENGTH
    }

    private fun evictIfNeeded() {
        synchronized(lock) {
            if (total <= MAX_WORDS) return
            val extra = total - MAX_WORDS
            val weakest = dao.weakestLearned(extra)
            weakest.forEach {
                dao.deleteByKey(it.word, it.code)
                removeLocked(it)
            }
            total = dao.count()
        }
    }
}
