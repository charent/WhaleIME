package com.whale.ime.engine

import android.view.KeyEvent
import com.whale.ime.boot.Launcher
import com.whale.ime.keyboard.layout.InputModeSwitcher
import com.whale.ime.userword.UserWordManager
import com.whale.ime.preference.AppPrefs
import com.whale.ime.util.StringUtils
import com.whale.ime.rime.CandidateListItem
import com.whale.ime.rime.Rime
import com.whale.ime.engine.InputKey
import com.whale.ime.engine.KeyRecordStack
import com.whale.ime.engine.CustomEngine
import com.whale.ime.engine.schema.DoublePinYinUtils
import com.whale.ime.engine.schema.T9PinYinUtils
import com.whale.ime.engine.schema.T14KeyUtils
import java.util.Locale
import com.whale.ime.config.SchemaIds

object RimeEngine {
    private val keyRecordStack = KeyRecordStack()
    private var pinyins: Array<String> = emptyArray() // 候选词界面的候选拼音列表
    var showCandidates: List<CandidateListItem> = emptyList() // 所有待展示的候选词

    /**
     * 与 [showCandidates] 一一对应：每一项在 Rime 原始候选数组里的下标，-1 表示自定义候选（不经过 Rime）。
     * 剔除"编码本身"候选后，展示下标与 Rime 下标不再相等，选词必须查这张表。
     * 这里的下标是**全局下标**（`candidate_list_*` 的顺序），与 `select_candidate` 的入参一致。
     */
    private var rimeIndexes: IntArray = IntArray(0)

    /**
     * 每敲一个键取多少条候选 —— 这个值直接等于「引擎要翻译 + 过滤多少个候选」。
     *
     * **不要随手调大。** `Menu::Prepare(n)` 会把候选流一路 Next 到第 n 条，长输入下
     * 每个音节切分桶都要枚举完，边际成本随序号急剧上升。实测（模拟器 arm64 翻译层，
     * 绝对值比真机慢 3~5 倍，倍数关系一致；1 屏 ≈ 8 条）：
     *
     * ```
     * 输入码长度   取 10 条    取 30 条    取 100 条
     * nib           0.4ms      27ms       256ms
     * nibunzhey     1.2ms      70ms       316ms
     * nibunzheydw   6.7ms      82ms       319ms
     * ```
     *
     * 也就是说首屏多取的那些候选，用户一条都看不到，却让单键成本涨了两个数量级
     * （端到端 `nibunzheydw` 从 ~80ms 涨到 ~450ms，删除键同样被拖慢）。
     * 一屏候选栏最多显示 8~10 条，取 12 条留一点余量即可；不够的部分交给
     * [appendNextPage] 在滑动时增量补齐。
     */
    private const val FIRST_BATCH_SIZE = 12

    /**
     * 滚动到尾部时一次补多少条。
     *
     * 这条路径发生在滑动过程中（UI 侧走增量插入、不重绑），但同样跑在主线程，
     * 取 100 条会让滑动中掉帧（长输入下单批 300ms+）。30 条足够铺满整屏，
     * 且单批成本可控，滑到底再补下一批。
     */
    private const val LOAD_MORE_SIZE = 30

    /**
     * 候选展示档位（见 [arrangeForDisplay]）：数值即展示优先级，越小越靠前。
     *
     * 档位只用来表达**引擎排序无法表达**的信息，档内一律沿用引擎顺序。
     * 引擎的候选顺序就是词频顺序（`script_translator.cc`：
     * `quality = exp(词条对数权重) + initial_quality`，纠错候选再乘 `0.01^编辑距离`），
     * 因此「小 6.4e6」远大于「西奥 9e3」，引擎本来就把常用单字排在最前。
     * UI 侧再用任何「字数/长度」规则重排，都等于拿一个与词频无关的启发式覆盖它。
     */
    /**
     * 希腊字母「无精确命中」档位：`epsilon` / `omicron` / `aomikerong` 这类码在词库里
     * 没有任何同码词条，用户敲这么长基本就是要那个字母，直接排最前。
     */
    private const val TIER_GREEK_TOP = -1

    private const val TIER_CHINESE = 0

    /**
     * 希腊字母「让位」档位：码已经精确命中中文词条（`miu` → 谬、`pai` → 派）或英文词条
     * （`alpha` / `pi`）时，用户打的本来就是那个词，字母只排在中文档之后、英文杂项之前，
     * 既看得见又不抢首选。
     */
    private const val TIER_GREEK = 1

    /**
     * 英文候选档位：中文拼音模式里词库附带的英文单词（ged、gear…）整体排在全部中文之后，
     * 不与中文候选混排（3 个字母 ≠ 3 个字，"ged" 抢进「杰洛特」那一档很怪）。
     */
    private const val TIER_ENGLISH = 2

    /** 下一次从全量候选列表的哪个全局下标开始取 */
    private var candidateOffset = 0

    /** 全量候选列表是否已经取完（上一批不足 [CANDIDATE_PAGE_SIZE]） */
    private var candidatesExhausted = true

    var showComposition: String = "" // 候选词上方展示的拼音
    var preCommitText: String = "" // 待提交的文字
    /** 本次联想候选的检索键（已上屏文本），选联想词时只上屏它后面的续写部分 */
    private var associateKey: String = ""
    private var customPhraseSize: Int = 0 // 自定义引擎候选词长度
    const val MASK_CASE_LOWER = 0

    /** 自定义候选（用户词库 / 常用语 / 日期联想）在候选栏上的标记，点击时直接提交文本 */
    const val CUSTOM_TAG = "📋"

    /** 等式计算候选的标记，点击时先删掉编辑框里的等式原文再提交整个候选文本 */
    const val EXPR_TAG = "🧮"

    /**
     * 希腊字母候选的标记，点击时直接提交字母本身、不经过 Rime 选词（同 [CUSTOM_TAG]）。
     *
     * 特意用非 ASCII 字母：候选面板的左栏读音会拿 comment 当拼音解析（`[a-z]+`），
     * 标记不能长得像读音，否则那里会多出一条叫 "greek" 的假读音条目。
     */
    const val GREEK_TAG = "α"

    /**
     * 剔除"编码本身"后若已无其它候选，是否保留它作为唯一可上屏项。
     * i / u / v 这类编码本身没有汉字候选，删掉就什么都不剩了。
     */
    private const val KEEP_ECHO_WHEN_ALONE = true

    /** 单字母也成立的真实音节：分段里出现它们不算简拼 */
    private val SINGLE_LETTER_SYLLABLES = setOf("a", "e", "o")

    private var charCase = 0x0000

    /** 引擎是否已经启动过：Rime.startup 是整套引擎装配，重复调用会让按键明显停顿 */
    private var engineStarted = false

    /**
     * 一次输入过程中用户选过的候选，用于判断是否属于"手动纠正"并落库到用户词库。
     * code 取第一次选择前的组合串（Rime 逐段消费组合串，第一次的快照天然就是整串编码）。
     */
    private class PendingSelect(val code: String, val text: String, val isFirstReal: Boolean)
    private val pendingSelects = mutableListOf<PendingSelect>()

    fun init() {
        Rime.getInstance(false)
        engineStarted = true
    }

    fun selectSchema(mod: String): Boolean {
        keyRecordStack.clear()
        charCase = MASK_CASE_LOWER
        if (!engineStarted) {
            Rime.startup(Launcher.instance.context, false)
            engineStarted = true
        } else if (Rime.getCurrentRimeSchema() == mod) {
            // 已经在这个方案上（例如反复点「中/EN」「123」再切回来），不必再切一次词典
            return true
        }
        // 换方案等于换词典
        return Rime.selectSchema(mod)
    }

    fun getCurrentRimeSchema(): String {
        return Rime.getCurrentRimeSchema()
    }

    /**
     * 是否输入完毕
     */
    fun isFinish(): Boolean {
        return keyRecordStack.isEmpty()
    }

    fun onNormalKey(event: KeyEvent) {
        val keyCode = event.keyCode
        val keyChar = if (keyCode == KeyEvent.KEYCODE_APOSTROPHE) if (isFinish()) '/'.code else '\''.code
            else event.unicodeChar
        // 用户继续按键 = 放弃分段选择，回到整串组合；
        // 读音试打也到此为止：改写后的组合串就是用户现在的输入，不再提供还原
        clearSegmentFocus()
        readingRewriteInput = null
        if (keyRecordStack.pushKey(event)) Rime.processKey(keyChar, event.action)
        updateCandidatesOrCommitText()
    }

    fun onDeleteKey() {
        clearSegmentFocus()
        readingRewriteInput = null
        keyRecordStack.processDelAction()
        updateCandidatesOrCommitText()
    }

    fun selectCandidate(index: Int): String? {
        val picked = showCandidates.getOrNull(index) ?: return null
        val rimeIndex = rimeIndexes.getOrNull(index) ?: -1
        if (rimeIndex < 0) return null // 自定义候选由 UI 直接提交，不进 Rime
        val code = Rime.compositionText
        Rime.selectCandidate(rimeIndex)
        keyRecordStack.pushCandidateSelectAction()
        if (code.isNotBlank() && picked.text.isNotBlank()) {
            // 「首个真实候选」不能再用 customPhraseSize 当下标：希腊字母注入项可能被提到
            // 列表最前（TIER_GREEK_TOP），把紧随其后的中文候选误判成"非首位"，
            // 一次正常选词就会被记成用户纠正。
            pendingSelects.add(PendingSelect(code, picked.text, index == firstRealCandidateIndex()))
        }
        return updateCandidatesOrCommitText()
    }

    /** 展示列表里第一条来自 Rime 的候选（跳过 📋 自定义项与希腊字母注入项）；没有则 -1 */
    private fun firstRealCandidateIndex(): Int =
        showCandidates.indexOfFirst { it.comment != CUSTOM_TAG && it.comment != GREEK_TAG }

    // ---------------------------------------------------------------- 分段选择

    /** 分段选择态：聚焦时的原始输入（raw input，如 "bsi"）与各段（如 [b, si]） */
    private var focusedOriginalInput: String? = null
    private var focusedSegments: List<String> = emptyList()
    private var focusedIndex = -1

    /**
     * 读音试打前的原始输入（null = 当前组合串没有被读音改写）。
     *
     * 候选页读音栏点一个读音 = 把这串键换成该读音、回引擎重新检索（见
     * [applyReadingSyllable]）。改写会覆盖用户敲的键，所以把原始输入留在这里，
     * 取消读音（[clearReadingSyllable]）时原样写回去。
     */
    private var readingRewriteInput: String? = null

    /** 当前输入的分段（与 preedit 的 `'` 切分一致；空段已剔除） */
    fun segments(): List<String> =
        Rime.compositionText.split("'").filter { it.isNotBlank() }

    /** 是否处于「分段选择」态（此时候选只属于某一段） */
    val isSegmentFocused: Boolean
        get() = focusedSegments.isNotEmpty()

    /** 放弃分段选择：不恢复输入（用于用户继续按键/删键时） */
    private fun clearSegmentFocus() {
        focusedSegments = emptyList()
        focusedIndex = -1
        focusedOriginalInput = null
    }

    /**
     * 分段选择：把输入收窄为第 [index] 段，取该段自己的候选。
     *
     * 为什么必须收窄输入而不是筛候选：简拼段（`b`）只覆盖 1 个字节，`b'si` 的候选桶几乎
     * 全是 `bei'si` 这种跨段整词，段内单字（被/吧/把/比/不…）根本没有机会出现 ——
     * 它们只在「该段是输入末尾」时才会被当作预测词产出。收窄成 `b` 之后，
     * 引擎给出的正是这一段的全部字/词，选完再把剩余段写回去，就得到
     * 「先选 b 的字、再选 si 的字」的分段输入体验。
     */
    fun focusSegment(segments: List<String>, index: Int): Boolean {
        if (segments.size < 2 || index !in segments.indices) return false
        if (!segments.all { it.matches(Regex("[a-z]+")) }) return false
        if (focusedSegments.isEmpty()) focusedOriginalInput = Rime.getInput()
        // 进入分段态后组合串由分段接管，读音试打（以及它的还原快照）到此结束
        readingRewriteInput = null
        focusedSegments = segments
        focusedIndex = index
        Rime.setInput(segments[index])
        updateCandidatesOrCommitText()
        return true
    }

    /** 当前聚焦的段下标（-1 = 不在分段态） */
    val focusedSegmentIndex: Int
        get() = focusedIndex

    /** 分段总数（不在分段态时为 0），配合 [focusSegment] 逐段推进 */
    val focusedSegmentCount: Int
        get() = focusedSegments.size

    /**
     * 分段态下的完整分段快照。
     *
     * 聚焦后组合串只剩当前这一段，`compositionText` 反推不出整串分段；
     * 候选页收起再打开（UI 的本地快照已随组合丢失）时只能从这里恢复分段列表。
     */
    val focusedSegmentsSnapshot: List<String>
        get() = focusedSegments

    /**
     * 分段组句：取当前段第 [candidateIndex] 个候选的文本，并把输入推进到下一段。
     *
     * **只取文本，不提交**：分段的字由调用方攒着，等所有段都选完再一次性上屏
     * （中间提交会触发主机的 commit 回调、联想，把剩余段的候选冲掉）。
     * [nextSegment] 传 -1 表示这是最后一段，此时清空组合串收尾。
     *
     * @param composedWord 调用方按段序拼好的整串。全部段选完时按它记用户词库
     *   （见 [learnComposedWord]）；为 null 时退回本段文本。
     */
    fun pickSegmentCandidate(
        candidateIndex: Int,
        nextSegment: Int,
        composedWord: String? = null,
    ): String? {
        if (focusedSegments.isEmpty()) return null
        val picked = showCandidates.getOrNull(candidateIndex) ?: return null
        // 单段选中不是「纠正」，不写用户词库（否则 b'si 的 b → 被 会被当成用户词）；
        // 整串拼完再记，见 learnComposedWord。
        clearPendingLearning()
        if (nextSegment in focusedSegments.indices) {
            focusedIndex = nextSegment
            Rime.setInput(focusedSegments[nextSegment])
        } else {
            learnComposedWord(composedWord ?: picked.text)
            clearSegmentFocus()
            Rime.clearComposition()
        }
        updateCandidatesOrCommitText()
        return picked.text
    }

    /**
     * 分段组句完成：把用户逐段拼出来的整串记进用户词库。
     *
     * 这是用户自己造了一个词（yulejia → 娱乐家）：引擎里没有这条词条（否则它早就作为候选
     * 出现了），不记下来下次输入同一串还得再拼一遍。码取分段开始时的原始输入，注入时按
     * 整串编码命中（`UserWordManager.match` 会先把 preedit 里的 `'` 去掉）。
     *
     * 简拼段（`b'si` 的 `b`）不记：压缩码对不同词复用，记下来只会互相挤占，
     * 而且拼出来的整串未必是用户想要的那个词。
     */
    private fun learnComposedWord(word: String) {
        val code = focusedOriginalInput ?: return
        if (!isFullSpelling(focusedSegments)) return
        UserWordManager.learn(code, word, Rime.getCurrentRimeSchema())
    }

    /** 分段是否都是完整音节（出现简拼/缩写段就返回 false） */
    private fun isFullSpelling(segments: List<String>): Boolean =
        segments.size >= 2 &&
            segments.all { it.length >= 2 || it in SINGLE_LETTER_SYLLABLES }

    /** 退出分段选择，把最初的分段输入写回去 */
    fun restoreSegments(): Boolean {
        val original = focusedOriginalInput ?: return false
        focusedSegments = emptyList()
        focusedIndex = -1
        focusedOriginalInput = null
        Rime.setInput(original)
        updateCandidatesOrCommitText()
        return true
    }

    /**
     * 从全量候选列表里取一段，并同步推进分页游标。
     *
     * [offset] 是 librime 的候选全局下标，返回的第 i 项对应下标 `offset + i`；
     * 返回条数不足 [limit] 即说明后面没有了。
     */
    private fun fetchCandidates(offset: Int, limit: Int): List<CandidateListItem> {
        val batch = Rime.getCandidates(offset, limit)
        candidateOffset = offset + batch.size
        candidatesExhausted = batch.size < limit
        return batch
    }

    /**
     * 是否英文输入形态：语言切到 EN（english 方案），或当前 Rime 方案就是 english。
     *
     * 中文形态（拼音 / 双拼 / 九键 / 笔画 / 手写）的键盘没有可用的 Shift 大写输入，
     * 候选与上屏一律**保持词库原文**的大小写 —— 词库存 `GPU` 就显示并上屏 `GPU`，
     * 存 `ok` 就显示 `ok`，客户端不替它做大小写改写。
     */
    private val isEnglishInputMode: Boolean
        get() = InputModeSwitcher.isEnglish ||
            Rime.getCurrentRimeSchema() == SchemaIds.SCHEMA_EN

    /** 按当前大小写状态改写候选文本（仅英文方案下的 Shift / CapsLock 输出） */
    private fun applyCharCase(items: List<CandidateListItem>) {
        if (!isEnglishInputMode) return
        when (charCase) {
            KeyEvent.META_SHIFT_ON -> items.forEach { item ->
                item.text = item.text.lowercase().replaceFirstChar {
                    if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
                }
            }
            KeyEvent.META_CAPS_LOCK_ON -> items.forEach { item -> item.text = item.text.uppercase() }
            else -> items.forEach { item ->
                if (item.text.any { it.isUpperCase() }) item.text = item.text.lowercase()
            }
        }
    }

    /**
     * 首屏候选整理：**只做引擎排序表达不了的两件事，其余一律沿用引擎顺序**。
     *
     * 1. 自定义候选（[CUSTOM_TAG]，indexes 为 -1 的前缀段）是用户自己加的，永远在最前；
     * 2. 中文模式下纯字母的英文词条（词库附带的 ged / gear…）整体后移到最后档 ——
     *    例外是「整串原样命中的英文词」（输入 code 命中 code），那是用户在打这个词，
     *    保持引擎给的位置（通常第 1），不参与后移。
     *
     * 为什么不再「按字数分桶」——`2字词 > 单字 > 3字词 > 长词` 的规则
     * 与「用户打了几个音节」完全无关，正是错序的根因。
     * 单音节输入下，凡是双字词（含另一条切分的同音词、纠错词）都会被抬到所有单字前面：
     * - `xiao` → 西奥(xi'ao 精确切分)、细胞?(xibao 插 b)、洗澡?(xizao 插 z) 占满首屏，
     *   而引擎排在第 1~5 的小(15.67)/笑(15.09)/消(13.58)/校(13.34)/晓(12.87) 全被挤出；
     * - `quan` → 去看(qu+kan 插 k) 压过 全(14.55)/权(12.95)。
     * 这两类词的对数权重都在 7~9 之间，比常用单字低 3~9 个数量级，
     * 引擎的 `exp(权重) + initial_quality` 早就把它们排在后面，是重排把它们捞了上来。
     *
     * emoji 候选（`simplifier@emoji` 产出，紧跟源词）不单独成档，而是**继承源词的档位**，
     * 排序后仍贴在那条词后面：若按 UTF-16 code unit 数字数，`😄`（代理对）、
     * `❄️`（带变体选择符）会被算成「2 字词」拆到源词前面，打单个字母时首屏被 emoji 铺满。
     *
     * 纯英文方案直接返回：那里所有候选都是英文，引擎顺序即词频顺序（a / an / and…），
     * 再按字数分桶只会把常用短词顺序打乱。
     *
     * 只整理首屏列表；翻页追加的候选本来就是尾部长尾，不再排序。
     * [indexes] 与 [items] 平行，同步重排以维持 select_candidate 全局下标映射。
     */
    /**
     * 输入「今天 / 明天 / 昨天…」这类日期词时，直接在**候选栏**里补上对应的日期候选
     * （与 `rq` 给出的日期同一种格式、同一条 [CustomEngine.dayWordDateCandidates] 路径），
     * 不必先上屏再从联想里点。
     *
     * 位置必须紧跟在那个日期词**后面**：放在最前面的话，空格/首选就会把日期当首选词上屏，
     * 而用户敲 `jintian` 想要的显然是「今天」。候选带 [CUSTOM_TAG]，点选时由
     * KeyDispatcher 直接提交文本并清组合串（不经过 Rime 选词）。
     */
    private fun injectDayWordDateCandidates(
        items: ArrayList<CandidateListItem>,
        indexes: ArrayList<Int>,
        seen: HashSet<String>,
    ) {
        val anchor = items.indexOfFirst { CustomEngine.dayWordDateCandidates(it.text).isNotEmpty() }
        if (anchor < 0) return
        var insertAt = anchor + 1
        CustomEngine.dayWordDateCandidates(items[anchor].text).forEach { text ->
            if (seen.add(text.lowercase(Locale.ROOT))) {
                items.add(insertAt, CandidateListItem(CUSTOM_TAG, text))
                indexes.add(insertAt, -1)
                insertAt++
            }
        }
    }

    private fun arrangeForDisplay(items: ArrayList<CandidateListItem>, indexes: ArrayList<Int>) {
        val start = customPhraseSize
        if (items.size - start < 2) return
        // 英文方案下全部候选都是英文，没有需要后移的对象；重排只会破坏词频顺序
        val chineseMode = !isEnglishInputMode
        if (!chineseMode) return
        val tiers = IntArray(items.size)
        val orderKeys = Array(items.size) { ORDER_KEY_DEFAULT }
        // WhaleIME 2026-11-09: 用户敲的整串**就是**这条英文词条时（code / hello / music），
        // 它不算「词库里混进来的英文杂词」。引擎侧对这种「原样命中的整词」给了正的
        // initial_quality（改键候选则被压到其下，见 rime-script-translator-whale.cc），
        // 本该排第一；这里再把它后移到中文档，就等于把引擎的结论推翻 —— 现象就是
        // `code` 的第一屏全是 ci'de? / ce'de? 这类纠错，code 本身连影子都没有。
        // WhaleIME 2026-11-14: 上面这条豁免只在「输入不是拼音」时成立。`nu` 本身是合法
        // 音节，词库里又恰好有英文条目 `nu`（以及希腊字母 `ν`），按原逻辑它们会一直压在
        // 怒/弩 前面 —— 用户在打中文，不是在打英文。
        //
        // 判据必须收紧到「整串被**完整拼写**解释」：候选的 comment（引擎给的真实读音）
        // 去掉隔音符号后与输入完全相同，且不是纠错（comment 末尾 `?`）。只看「有没有
        // 汉字候选」会把简拼命中也算进来 —— `ok` 会命中「欧克」（ou ke 的简拼），于是
        // 英文 `ok` 被莫名后移；而 `nu` 下「怒/弩」的 comment 就是 `nu`，正好该后移。
        val rawInput = Rime.getInput()
        val normalizedInput = rawInput.replace("'", "").replace(" ", "")
        val hasExactChinese = (start until items.size).any { i ->
            val item = items[i]
            item.text.any { isCjk(it) } &&
                !item.comment.endsWith("?") &&
                item.comment.replace("'", "").replace(" ", "") == normalizedInput
        }
        // 希腊字母让位判据：码已经精确命中一条中文词条（miu → 谬、pai → 派）或一条同码
        // 英文词条（alpha / pi），说明用户打的本来就是那个词，字母不该抢首选。
        // 都没命中（epsilon / omicron / aomikerong）时才排最前 —— 那些码本来就没有别的候选。
        //
        // 九键 / 14 键下用户敲的是键码（MGT / QO），候选的读音与英文词条都是拼音
        // （miu / alpha），直接比字符串永远不等，必须先把读音转成键码再比，
        // 否则字母会一直抢首位。
        val greekSchema = Rime.getCurrentRimeSchema()
        val keyCodeMode = SchemaIds.isKeyCodeSchema(greekSchema)
        fun inputHit(reading: String): Boolean {
            if (reading.isEmpty()) return false
            val plain = reading.replace("'", "").replace(" ", "")
            if (plain.equals(normalizedInput, ignoreCase = true)) return true
            return keyCodeMode &&
                GreekAlphabet.toKeyCode(plain, greekSchema).equals(normalizedInput, ignoreCase = true)
        }
        val exactWordHit = (start until items.size).any { i ->
            val item = items[i]
            if (item.comment == GREEK_TAG || item.comment.endsWith("?")) return@any false
            if (item.text.any { isCjk(it) }) inputHit(item.comment)
            else isEnglishCandidate(item.text) && inputHit(item.text)
        }
        var anchorTier = TIER_CHINESE
        var anchorKey = ORDER_KEY_DEFAULT
        for (i in start until items.size) {
            val item = items[i]
            // 希腊字母自成一档：不参与下面的英文后移，也不参与 emoji 的"继承源词档位"
            if (item.comment == GREEK_TAG) {
                tiers[i] = if (exactWordHit) TIER_GREEK else TIER_GREEK_TOP
                orderKeys[i] = ORDER_KEY_DEFAULT
                continue
            }
            val text = item.text
            // 非 emoji 项自己定档；emoji 沿用上一条（源词）的档位，从而与源词同档、随源词走
            if (!StringUtils.isEmojiOnly(text)) {
                val english = isEnglishCandidate(text)
                val exactInput = english && text.equals(rawInput, ignoreCase = true)
                val demote = english && (!exactInput || hasExactChinese)
                anchorTier = if (demote) TIER_ENGLISH else TIER_CHINESE
                anchorKey = if (demote) englishOrderKey(text) else ORDER_KEY_DEFAULT
            }
            tiers[i] = anchorTier
            orderKeys[i] = anchorKey
        }
        val order = (start until items.size).sortedWith(
            // 稳定排序：同档内保持引擎顺序（词频 + 纠错惩罚），英文档再按长度/字母序排
            compareBy({ tiers[it] }, { orderKeys[it] })
        )
        val sortedItems = ArrayList<CandidateListItem>(items.size)
        val sortedIndexes = ArrayList<Int>(indexes.size)
        for (i in 0 until start) {
            sortedItems.add(items[i]); sortedIndexes.add(indexes[i])
        }
        for (i in order) {
            sortedItems.add(items[i]); sortedIndexes.add(indexes[i])
        }
        items.clear(); items.addAll(sortedItems)
        indexes.clear(); indexes.addAll(sortedIndexes)
    }

    /**
     * 希腊字母候选注入（中文 26 键拼音 + 九键拼音）。
     *
     * 为什么不复用 `opencc/others.txt`：那张表只能翻译**已经存在的候选文本**，`miu`（谬/缪）
     * 这类条目因为「字太短、抢候选」被上游注释掉，`mu` / `nu` 的英文名又会被
     * [arrangeForDisplay] 当英文杂词后移；改数据还要重编词库 + 全量重拷 assets。
     * 这里直接按输入码在候选管线上补两条（小写 + 大写），与词库完全解耦。
     *
     * 两种键盘形态：
     * - 26 键：组合串就是用户敲的拼音，直接与码表比；
     * - 九键：组合串是大写键码串（`miu` → `MGT`，见 t9_pinyin.schema.yaml 的 derive 段），
     *   码表里的拼音先转成键码再比。九键一码多义（`MGT` = miu/niu），全部注入。
     *
     * 双拼不注入：组合串是双拼码，不等于拼音全拼，二期再经 DoublePinYinUtils 转换。
     * 分段选择态也不注入 —— 那时组合串只剩一段，注入会干扰逐段选字。
     */
    private fun appendGreekCandidates(
        merged: ArrayList<CandidateListItem>,
        indexes: ArrayList<Int>,
        seen: HashSet<String>,
        compositionText: String,
    ) {
        if (isSegmentFocused) return
        val letters = greekLettersFor(compositionText)
        if (letters.isEmpty()) return
        for (letter in letters) {
            // 用**原文**占 seen：α 与 Α 的 lowercase 相同，按 lowercase 去重会把大写吞掉
            for (text in arrayOf(letter.lower, letter.upper)) {
                if (seen.add(text)) {
                    merged.add(CandidateListItem(GREEK_TAG, text))
                    indexes.add(-1)
                }
            }
        }
    }

    /** 当前组合串对应的希腊字母：26 键按拼音码、九键/14 键按键码，均要求整串精确相等。 */
    private fun greekLettersFor(compositionText: String): List<GreekAlphabet.Letter> {
        // 组合串可能带音节分隔符（mi'u / M'GT），码表里的码是连写的
        val code = compositionText.replace("'", "").replace(" ", "")
        if (code.isEmpty()) return emptyList()
        if (code.all { it in 'a'..'z' }) {
            return GreekAlphabet.match(code)?.let { listOf(it) } ?: emptyList()
        }
        // 九键 / 14 键键位是大写（A/D/G/J/M/P/T/W 与 Q/E/T/…）：schema 的 alphabet
        // 含大写，derive 出的键码拼写就是全大写串。混了小写说明有段落已被
        // replaceKey 换成拼音，此时不再猜，直接放弃。
        val schema = Rime.getCurrentRimeSchema()
        if (SchemaIds.isKeyCodeSchema(schema) && code.all { it in 'A'..'Z' }) {
            return GreekAlphabet.matchKeyCodes(code, schema)
        }
        return emptyList()
    }

    /**
     * 「非中文的字母类候选」：ASCII 英文词（GPU / ok）以及希腊/西里尔等字母（ν / α）都算。
     *
     * 中文模式里它们都是词库附带的杂项。只按 [StringUtils.isLetter]（ASCII 正则）判断的话，
     * `ν` 不算英文，会留在中文档里排到 你/怒 前面 —— 用户看到的就是一个像 `v` 的怪候选。
     * 空串与纯 emoji 不算。
     */
    private fun isEnglishCandidate(text: String): Boolean {
        if (text.isEmpty() || StringUtils.isEmojiOnly(text)) return false
        return text.all { it.isLetter() && !isCjk(it) }
    }

    /** 汉字（含扩展 A / 兼容区）：`ν` / `α` 这类字母不是 */
    private fun isCjk(c: Char): Boolean =
        c.code in 0x4E00..0x9FFF || c.code in 0x3400..0x4DBF || c.code in 0xF900..0xFAFF

    /**
     * 档内次级排序键：中文候选与「整串原样命中的英文词条」都用这个常量，
     * 相互比较相等，稳定排序下严格保持引擎顺序。
     */
    private const val ORDER_KEY_DEFAULT = "0"

    /**
     * 英文档内的次级排序键：长度升序 + 同长字母序；非英文候选返回 [ORDER_KEY_DEFAULT]，
     * 相互比较相等，稳定排序下维持引擎顺序。
     */
    private fun englishOrderKey(text: String): String =
        if (isEnglishCandidate(text)) {
            val lower = text.lowercase(Locale.ROOT)
            "1" + lower.length.toString().padStart(4, '0') + lower
        } else {
            ORDER_KEY_DEFAULT
        }

    fun selectPinyin(index: Int) {
        val pinyinKey = keyRecordStack.pushPinyinSelectAction(pinyins[index]) ?: return
        Rime.replaceKey(pinyinKey.posInInput, pinyinKey.t9Keys().length, pinyinKey.pinyin())
        updateCandidatesOrCommitText()
    }

    /**
     * 候选页左侧拼音栏：把组合串的第一个音节替换成 [syllable]（原始读音或纠错读音），
     * 替换后重新组句并刷新候选。
     *
     * 坐标系：`Rime.compositionText`（带分隔符的 preedit）首段与 raw input 首段长度相等
     * （分隔符只插在段间），ASCII 组合串里两者下标一致；含非 ASCII 时 [Rime.replaceKey]
     * 会自行拒绝。仅支持全键/九键的拼音组合串（小写字母）。
     */
    fun replaceFirstSyllable(syllable: String): Boolean {
        val ok = rewriteFirstSyllable(syllable)
        if (ok) updateCandidatesOrCommitText()
        return ok
    }

    /** [replaceFirstSyllable] 的裸替换（不刷新候选） */
    private fun rewriteFirstSyllable(syllable: String): Boolean {
        val first = compositionText.split("'").firstOrNull { it.isNotBlank() } ?: return false
        if (!first.matches(Regex("[a-z]+")) || !syllable.matches(Regex("[a-z]+"))) return false
        return Rime.replaceKey(0, first.length, syllable)
    }

    /** 当前组合串是否被「读音试打」改写过（[applyReadingSyllable] 之后、取消或继续输入之前） */
    val isReadingRewritten: Boolean
        get() = readingRewriteInput != null

    /**
     * 候选页读音栏：这个读音能不能**只在当前候选列表里筛出来**。
     *
     * 读音栏的语义是「看这个读音的字词」，而字词只能来自引擎按该读音的检索；对着已经产出的
     * 候选做过滤，只有在下面两种情况下才等价：
     * 1. 这段拼写本来就在用户敲的键里（`xian` 里的 `xi`、`bei` 自己）；
     * 2. 候选池里已经有一条**正是这个读音**的候选（comment 去掉纠错标记 `?` 后与读音相同）。
     *
     * 都不成立时过滤必然筛不出东西 —— 这正是 `be` 的坑：引擎对 `be` 的解释是简拼 `b`+`e`
     * （贝尔 `bei'er`、保额 `bao'e`）或补全 `be`→`bei`，按键里没有 `bei`/`bao` 这段拼写，
     * 候选池里也只有跨段整词，没有「正是 bei」的候选 —— 被/背/北、包/报/保 这些**字**
     * 从头到尾没被产出过，怎么筛都只剩整词。这类读音必须 [applyReadingSyllable] 回引擎重查。
     */
    fun canFilterReading(syllable: String): Boolean {
        if (syllable.isEmpty()) return false
        val keys = Rime.getInput().replace("'", "").replace(" ", "")
        if (keys.contains(syllable)) return true
        return showCandidates.any { it.comment.trimEnd('?') == syllable }
    }

    /**
     * 候选页读音栏：把组合串首段换成 [syllable] 回引擎重新检索（等价于「按这个音重打一遍」），
     * 并记住改写前的按键，供 [clearReadingSyllable] 还原。
     *
     * 为什么必须回引擎而不是过滤：`be` 点上 `bei` 要看的是 被/背/北 这些**字**，而引擎在
     * `be` 这个输入下只产出了跨段整词（贝尔/倍儿…），字压根不在候选列表里（见
     * [canFilterReading]）。过滤只能看到「已经存在的候选」，改输入才能让引擎重新组句。
     *
     * 与 [replaceFirstSyllable] 的差别只有两点：记住原始输入（可还原）、只对全键拼音放行。
     * 九键的组合串是大写键码、双拼是双拼码，把读音写回去等于改错键，一律拒绝 ——
     * 调用方（候选面板）拿到 false 时退回原来的纯过滤行为。
     */
    fun applyReadingSyllable(syllable: String): Boolean {
        if (isSegmentFocused) return false
        if (Rime.getCurrentRimeSchema() != SchemaIds.SCHEMA_ZH_QWERTY) return false
        if (!syllable.matches(Regex("[a-z]+"))) return false
        val original = readingRewriteInput ?: Rime.getInput()
        if (!rewriteFirstSyllable(syllable)) return false
        readingRewriteInput = original
        updateCandidatesOrCommitText()
        return true
    }

    /** 取消读音试打：把用户敲的原始按键写回组合串 */
    fun clearReadingSyllable(): Boolean {
        val original = readingRewriteInput ?: return false
        readingRewriteInput = null
        Rime.setInput(original)
        updateCandidatesOrCommitText()
        return true
    }

    /**
     * 追加下一页候选，并同步维护 [rimeIndexes]（去重、索引映射都在这里收口，避免调用方各管一半）。
     *
     * 这里取的是全量列表的全局下标，选词原样交给 `select_candidate`。若把**页内偏移**当索引，
     * 翻到第二页之后选词会落回第一页的同位候选（`menu/page_size: 100`，第 101 项起必现），
     * 页/全局两套语义必须收成一套。
     */
    fun appendNextPage(): Int {
        if (candidatesExhausted) return 0
        val base = candidateOffset
        val page = fetchCandidates(base, LOAD_MORE_SIZE)
        if (page.isEmpty()) return 0
        applyCharCase(page)
        val existing = HashSet<String>(showCandidates.size + page.size)
        showCandidates.forEach { existing.add(it.text.lowercase(Locale.ROOT)) }
        val addItems = ArrayList<CandidateListItem>(page.size)
        val addIndexes = ArrayList<Int>(page.size)
        page.forEachIndexed { i, item ->
            if (existing.add(item.text.lowercase(Locale.ROOT))) {
                addItems.add(item)
                addIndexes.add(base + i)
            }
        }
        if (addItems.isEmpty()) return 0
        showCandidates = showCandidates + addItems
        rimeIndexes = rimeIndexes + addIndexes
        return addItems.size
    }

    fun predictAssociationWords(text: String) {
        associateKey = text
        pinyins = emptyArray()
        if (text.isNotEmpty()) {
            showCandidates = buildList {
                val words = Rime.getAssociateList(text)
                val extras = CustomEngine.predictAssociationWordsChinese(text)
                // 日期这类强建议排最前：候选栏一屏只放得下五六条，放在续写词后面就看不见了
                addAll(extras.leading.map { CandidateListItem("", it) })
                val firstFive = words.take(5)
                addAll(firstFive.filterNotNull().map { CandidateListItem("", it.stripAssociateKey(text)) })
                addAll(extras.trailing.map { CandidateListItem("", it) })
                val remaining = words.drop(5)
                addAll(remaining.filterNotNull().map { CandidateListItem("", it.stripAssociateKey(text)) })
            }.distinctBy { it.text.lowercase(Locale.ROOT) }
            rimeIndexes = IntArray(showCandidates.size) { -1 } // 联想词不走 selectCandidate
            candidateOffset = 0
            candidatesExhausted = true
            showComposition = ""
        }
    }

    /**
     * 联想候选来自 table 前缀匹配，是「检索键 + 续写」的完整词（已上屏"天气"，候选是
     * "天气好"）。上屏文本只补续写部分，候选栏同理只显示"好"，整词展示是冗余信息。
     * 与检索键相同或剥完为空的词条按原文保留。
     */
    private fun String.stripAssociateKey(key: String): String =
        if (length > key.length && startsWith(key)) substring(key.length) else this

    fun selectAssociation(index: Int, selectedText: String) {
        val indexReal = index - customPhraseSize
        Rime.chooseAssociate(indexReal)
        updateCandidatesOrCommitText()
        // 联想候选已剥掉检索键前缀（见 [stripAssociateKey]），selectedText 即续写部分，
        // 直接上屏；仅当候选未剥前缀时（如标点）按原文上屏。
        preCommitText = if (selectedText.startsWith(associateKey)) {
            selectedText.substring(associateKey.length)
        } else {
            selectedText
        }
    }

    fun reset() {
        showCandidates = emptyList()
        rimeIndexes = IntArray(0)
        customPhraseSize = 0
        candidateOffset = 0
        candidatesExhausted = true
        pinyins = emptyArray()
        showComposition = ""
        preCommitText = ""
        associateKey = ""
        pendingSelects.clear()
        keyRecordStack.clear()
        focusedOriginalInput = null
        focusedSegments = emptyList()
        focusedIndex = -1
        readingRewriteInput = null
        Rime.clearComposition()
        if(charCase == KeyEvent.META_SHIFT_ON) charCase = MASK_CASE_LOWER
    }

    /**
     * 一次输入结束（Rime 给出 commit）时结算：整次输入里只要选过非首位候选，就记为用户纠正。
     * 词的合法性、长度、去重、防抖都在 UserWordManager 里做。
     */
    private fun flushLearning() {
        if (pendingSelects.isEmpty()) return
        val code = pendingSelects.first().code
        val word = pendingSelects.joinToString("") { it.text }
        // 自定义候选会被剔重/插在最前，所以"是不是首位"在选词时就按展示位置算好了
        val corrected = pendingSelects.any { !it.isFirstReal }
        pendingSelects.clear()
        if (!corrected) return
        UserWordManager.learn(code, word, Rime.getCurrentRimeSchema())
    }

    /** 走自定义候选（📋）直接提交时不经过 Rime 选词，清掉待结算记录避免串到下一次输入 */
    fun clearPendingLearning() {
        pendingSelects.clear()
    }

    fun destroy() {
        engineStarted = false
        Rime.destroy()
    }

    /** 引擎是否已装配（备份 userdb 前据此判断要不要先释放文件句柄） */
    fun isStarted(): Boolean = engineStarted

    /** 引擎是否仍处于组合态。与"候选是否为空"不同：i / u / v 这类编码有编码、没候选。 */
    val hasComposition: Boolean
        get() = Rime.compositionText.isNotEmpty()

    /** Rime 原始编码串（未做展示处理），用于上屏编码兜底 */
    val compositionText: String
        get() = Rime.compositionText

    /** 英文方案依赖"原字母"候选做补全，这里整段跳过 */
    private fun shouldHideEcho(): Boolean = !isEnglishInputMode

    /**
     * 定位"回声候选"：与编码相同的纯字母候选（忽略大小写、忽略分隔符）。
     * 注意必须忽略大小写：Rime 会把输入码规范化成大写回显（输入 h，echo 候选是 "H"），
     * 而组合串保持小写。
     *
     * 只认**列表最后一条**命中（判据见 [shouldDropEcho]）：librime 1.17 的 echo 候选
     * quality 是 -100（`echo_translator.cc`，最低优先级），它必然排在所有真实候选之后；
     * 而词库里恰好有一条同文本的真实词条时（输入 code 命中英文词 code），那条词条在
     * 引擎里是第 1 名，绝不是回声 —— 旧实现取第一条命中，把 code 本身当回声删掉了。
     * @return 命中项在 Rime 候选数组里的下标，-1 表示没有
     */
    private fun findEchoIndex(candidates: List<CandidateListItem>, composition: String): Int {
        val code = composition.replace("'", "").replace(" ", "")
        if (code.isEmpty() || code.any { it !in 'a'..'z' && it !in 'A'..'Z' }) return -1
        return candidates.indexOfFirst {
            it.text.trim().replace("'", "").equals(code, ignoreCase = true)
        }
    }

    /**
     * 该不该剔掉回声候选。
     *
     * 判据：命中项必须是**整份列表的最后一条**（echo 的 quality 最低，见 [findEchoIndex]），
     * 且列表已经取完。真实词条与编码同文本时它排在前面（甚至第 1），不会被误判成回声。
     * 仍保留 [KEEP_ECHO_WHEN_ALONE]：候选只剩它一个时（i / u / v 这类没有汉字的编码）
     * 必须留着，否则一个能上屏的项都不剩。
     */
    private fun shouldDropEcho(echoIndex: Int, candidates: List<CandidateListItem>): Boolean {
        if (echoIndex < 0) return false
        if (candidates.size <= 1) return !KEEP_ECHO_WHEN_ALONE
        return candidatesExhausted && echoIndex == candidates.lastIndex
    }

    private fun updateCandidatesOrCommitText(): String? {
        val rimeCommit = Rime.getRimeCommit()
        if (rimeCommit != null) {
            flushLearning()
            keyRecordStack.clear()
            // 上屏 = 这次输入结束：读音试打的还原快照没有意义了（否则下次输入会被写回旧串）
            readingRewriteInput = null
            preCommitText = rimeCommit.commitText
            // 上屏与候选保持一致：中文形态直接用词库原文（词库存 `GPU` 就上屏 `GPU`），
            // 只有英文形态才按 Shift / CapsLock 改写。
            if (isEnglishInputMode) {
                preCommitText = when (charCase) {
                    KeyEvent.META_SHIFT_ON -> preCommitText.lowercase().replaceFirstChar {
                        if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
                    }
                    KeyEvent.META_CAPS_LOCK_ON -> preCommitText.uppercase()
                    else -> preCommitText.lowercase()
                }
            }
            showComposition = ""
            showCandidates = emptyList()
            rimeIndexes = IntArray(0)
            candidateOffset = 0
            candidatesExhausted = true
            return preCommitText
        }
        // 组合串直接读 Rime 缓存的那份：所有会改引擎状态的调用（processKey / replaceKey /
        // selectCandidate / clearComposition / selectSchema）内部都已经刷新过；
        // pushKey 返回 false 的那条路径引擎状态本来就没变。这里不再多拉一次 get_context。
        val candidates = fetchCandidates(0, FIRST_BATCH_SIZE)
        customPhraseSize = 0
        val compositionText = Rime.compositionText
        // 定位"编码本身"候选（echo_translator 的产物）；只有还存在别的候选时才剔掉它，
        // 否则 i / u / v 这类本身没有汉字候选的编码会连一个可上屏的项都不剩。
        val echoIndex = if (shouldHideEcho()) findEchoIndex(candidates, compositionText) else -1
        val hideEcho = shouldDropEcho(echoIndex, candidates)
        showCandidates = when {
            compositionText.isNotBlank() -> {
                val phrase = CustomEngine.processPhrase(compositionText.replace("\'", ""))
                if(InputModeSwitcher.isEnglish && StringUtils.isLetter(compositionText) &&
                    candidates.isNotEmpty() &&
                    !compositionText.equals(candidates.first().text, ignoreCase = true) ){
                    phrase.add(0, compositionText)
                }
                // 自定义候选在前、自带词库在后；相同文本次只保留第一条。
                // 同一次输入里注入词与自带词库可能给出同一个词，重复的候选没有意义。
                val merged = ArrayList<CandidateListItem>(phrase.size + candidates.size)
                val indexes = ArrayList<Int>(phrase.size + candidates.size)
                val seen = HashSet<String>(phrase.size + candidates.size)
                phrase.forEach { text ->
                    if (seen.add(text.lowercase(Locale.ROOT))) {
                        merged.add(CandidateListItem(CUSTOM_TAG, text))
                        indexes.add(-1)
                    }
                }
                customPhraseSize = merged.size
                // 必须在合并自带词库之前注入：这样它既不算进自定义前缀区（仍参与
                // arrangeForDisplay 重排），又能先占住 seen，把 simplifier@emoji 从
                // opencc/others.txt 派生的同名 α 去重掉（否则候选里会出现两条 α）。
                appendGreekCandidates(merged, indexes, seen, compositionText)
                candidates.forEachIndexed { rawIndex, item ->
                    if (hideEcho && rawIndex == echoIndex) return@forEachIndexed
                    if (seen.add(item.text.lowercase(Locale.ROOT))) {
                        merged.add(item)
                        indexes.add(rawIndex)
                    }
                }
                injectDayWordDateCandidates(merged, indexes, seen)
                arrangeForDisplay(merged, indexes)
                rimeIndexes = indexes.toIntArray()
                merged
            }
            else -> {
                rimeIndexes = IntArray(candidates.size) { it }
                candidates
            }
        }
        var count = Rime.compositionText.count { it in 'A'..'Z' }
        if (count > 0) {
            keyRecordStack.forEachReversed { inputKey ->
                if (inputKey is InputKey.T9Key) inputKey.consumed = count-- <= 0
            }
        }
        var composition = getCurrentComposition(candidates)
        // 中文形态一律不动：组合串本来就是拼音小写，候选保持词库原文（`GPU` 就显示 `GPU`）。
        // 九键 / 笔画的 charCase 是 CAPS_LOCK（键盘键位要用），但那只是键位显示，不能
        // 用来改写候选 —— 否则词库里的大写缩写会被整批 uppercase，小写词条被整批 lowercase。
        if (isEnglishInputMode) {
            when (charCase) {
                KeyEvent.META_SHIFT_ON -> {
                    for (item in showCandidates) item.text = item.text.lowercase().replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
                    composition = composition.lowercase().replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
                }
                KeyEvent.META_CAPS_LOCK_ON -> {
                    for (item in showCandidates) item.text = item.text.uppercase()
                    composition = composition.uppercase()
                }
                else -> {
                    // 默认档：候选本来就是小写（英文词码），只有词库里真带大写字母的
                    // 英文词条才需要重建字符串。原来无条件 lowercase 等于每次按键给整批
                    // 候选各分配一个新 String，纯属白做。
                    for (item in showCandidates) {
                        if (item.text.any { it.isUpperCase() }) item.text = item.text.lowercase()
                    }
                    if (composition.any { it.isUpperCase() }) composition = composition.lowercase()
                }
            }
        }
        val rimeSchema = Rime.getCurrentRimeSchema()
        val keyCodeText = Regex("[A-Z]+").find(compositionText.replace("'", ""))?.value ?: ""
        pinyins = when (rimeSchema) {
            SchemaIds.SCHEMA_ZH_T9 -> T9PinYinUtils.t9KeyToPinyin(keyCodeText)
            SchemaIds.SCHEMA_ZH_T14 -> T14KeyUtils.keyToPinyin(keyCodeText)
            else -> emptyArray()
        }
        showComposition = composition
        preCommitText = ""
        return null
    }

    /**
     * 拿到候选词拼音组合
     */
    fun getPrefixs(): Array<String> {
        return pinyins
    }

    /**
     * 候选栏拼音气泡要显示的串。
     *
     * **组合串（preedit）是唯一事实来源**：引擎按「用户实际敲的键」生成它，字符与分段
     * （`'`）与候选面板顶栏、[segments] 用的都是同一个 `Rime.compositionText`。
     *
     * 只有**键位不是拼音**的方案才需要把键位翻译成拼音，而翻译依据只能是候选的 `comment`
     * （引擎给出的该候选真实读音）：九键是数字键、双拼是双拼码。全键拼音的键位本身就是
     * 拼音，因此直接显示组合串。
     *
     * 曾经全键也拿 comment 重拼（旧的 `QwertyPinYinUtils`），那条路本质是错的：
     * comment 属于**首个候选**，它的音节数经常不等于输入段数（纠错候选 `xia'de` 对输入
     * `zi'a'd`，`zip` 取短的一侧），尾部整段输入被直接丢掉 —— 候选栏只剩 `'zi'a`，
     * 候选面板里却还是完整的 `zi'a'd`；拼装时还会在段内插分隔符，于是又冒出前导 `'`。
     */
    private fun getCurrentComposition(candidates: List<CandidateListItem>): String {
        val composition = Rime.compositionText
        val rimeSchema = Rime.getCurrentRimeSchema()
        if(rimeSchema == SchemaIds.SCHEMA_EN) return ""
        if(composition.isEmpty()) return ""
        if(candidates.isEmpty()) return composition
        val comment = candidates.first().comment
        val result =  when {
            comment.isNotBlank() && comment.startsWith("~") -> composition
            // 九键 / 14 键的组合串是大写键码，按首候选读音重拼成拼音展示
            SchemaIds.isKeyCodeSchema(rimeSchema) -> {
                T9PinYinUtils.getT9Composition(composition, comment)
            }
            rimeSchema.startsWith(SchemaIds.SCHEMA_ZH_DOUBLE_FLYPY) -> {
                if(!AppPrefs.getInstance().keyboardSetting.keyboardDoubleInputKey.getValue()) composition
                else DoublePinYinUtils.getDoublePinYinComposition(rimeSchema, composition, comment)
            }
            // 全键拼音（以及笔画等键位即展示码的方案）：组合串自成一体，不再按 comment 重拼
            else -> composition
        }
        return if (!composition.endsWith("'") && result.endsWith("'")) result.dropLast(1) else result
    }

    /**
     * 设置输入法搜索参数
     */
    fun setImeOption(option: String, value: Boolean) {
        Rime.setOption(option, value)
    }

    fun setCharCase(charCase: Int) {
        this.charCase = charCase
    }

}
