package com.whale.ime.dispatch

import com.whale.ime.engine.RimeEngine
import com.whale.ime.rime.CandidateListItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 词库解码操作对象。
 *
 * 状态只有一条通道：不可变快照 [candidateState]，
 * 取值统一走 [candidates]，避免两套状态出现短暂不一致。
 */
object DictDecoder {

    var activeCandidate = 0  //当前显示候选词位置
    var activeCandidateBar = 0  //当前显示候选词位置

    /**
     * 候选快照。
     *
     * @param generation 整表替换时 +1，追加时不变（视图据此区分「重绑」和「增量插入」）
     * @param prefixRevision 拼音前缀（T9 左侧候选拼音）变化时 +1
     */
    data class CandidateState(
        val candidates: List<CandidateListItem> = emptyList(),
        val generation: Int = 0,
        val composingText: String = "",
        val isAssociate: Boolean = false,
        val prefixRevision: Int = 0,
        /** 分段选择里已经确认的字（候选栏展示成"已选 + 当前段"） */
        val pickedSegmentText: String = "",
        /**
         * 候选来自「刚刚复制的内容」。
         * 这类候选没有编码可看，用户看不出它从哪来，候选栏据此在气泡里标注来源。
         */
        val isClipboardSuggestion: Boolean = false,
    )

    private val _candidateState = MutableStateFlow(CandidateState())
    val candidateState: StateFlow<CandidateState> = _candidateState.asStateFlow()

    // 是否是联想词
    var isAssociate = false

    /**
     * 候选列表的"代"：**整表替换**时 +1，**追加**时不变。
     *
     * 视图据此区分两种刷新：代变了说明是新的一次输入（可以全量重绑、滚回顶部）；
     * 代没变但条数变多，说明只是往后追加了一批，应该 `notifyItemRangeInserted`
     * ——`notifyDataSetChanged` 会重绑所有可见项并打断正在进行的滑动，滚到边界时每加一批就卡一下。
     */
    var generation: Int = 0
        private set

    /**
     * 当前该展示的组合串（编码胶囊）。
     *
     * [RimeEngine.showComposition] 是引擎组合串的**加工产物**（T9 键位转拼音、双拼补全、
     * 按候选读音重写），只在按键路径上随引擎刷新。引擎侧的 composition 一旦被清掉
     * （切方案、上屏、`clearComposition`），它就是上一轮的残留值：快照若照抄，
     * 候选栏会剩下一个孤零零的拼音胶囊，而候选列表早已为空。
     *
     * 因此以「引擎是否仍持有组合串」为准：引擎没有编码就视为没有编码。
     */
    private fun currentComposingText(): String {
        val display = runCatching { RimeEngine.showComposition }.getOrDefault("")
        if (display.isEmpty()) return ""
        val engine = runCatching { RimeEngine.compositionText }.getOrDefault("")
        return if (engine.isEmpty()) "" else display
    }

    /**
     * 分段组句已确认的字。候选栏把它拼在组合串前面展示（「布|si」），
     * 由 KeyDispatcher 在分段选择过程中维护，[reset] 时清空。
     */
    var pickedSegmentText: String = ""

    private fun publish(
        list: List<CandidateListItem>,
        composing: String = currentComposingText(),
        clipboardSuggestion: Boolean = false,
    ) {
        generation++
        _candidateState.value = CandidateState(
            candidates = list,
            generation = generation,
            composingText = composing,
            isAssociate = isAssociate,
            prefixRevision = _candidateState.value.prefixRevision,
            pickedSegmentText = pickedSegmentText,
            isClipboardSuggestion = clipboardSuggestion,
        )
    }

    /** 只追加、不换代：候选被追加一批（翻页预取） */
    private fun publishAppend(list: List<CandidateListItem>) {
        _candidateState.value = _candidateState.value.copy(
            candidates = list,
            composingText = currentComposingText(),
            isAssociate = isAssociate,
        )
    }

    private fun bumpPrefix() {
        _candidateState.value = _candidateState.value.copy(
            prefixRevision = _candidateState.value.prefixRevision + 1,
            composingText = currentComposingText(),
        )
    }

    /**
     * 重置候选词
     *
     * 组合串显式置空，不再回读 [RimeEngine.showComposition]：重置的语义就是「这一次输入作废」，
     * 而调用方（[InputDispatcher.reset]、`KeyDispatcher.resetToIdleState`）此前是**先发布
     * 快照、后清引擎**，回读到的必然是上一轮残留的拼音，且清完引擎后无人重发，胶囊就永久留在候选栏。
     */
    fun reset() {
        isAssociate = false
        pickedSegmentText = ""
        activeCandidate = 0
        activeCandidateBar = 0
        publish(emptyList(), composing = "")
    }

    val candidates: List<CandidateListItem>
        get() = _candidateState.value.candidates

    val isCandidatesEmpty: Boolean
        get() = candidates.isEmpty()

    val candidateSize: Int
        get() = candidates.size

    // 增加拼写字符，重置候选词状态
    fun inputAction() {
        activeCandidate = 0
        activeCandidateBar = 0
        isAssociate = false
    }

    /**
     * 选择拼音
     * @param position 选择的position
     */
    fun selectPrefix(position: Int) {
        activeCandidate = 0
        activeCandidateBar = 0
        bumpPrefix()
    }

    val prefixs: Array<String>  //获取拼音组合
        get() = RimeEngine.getPrefixs()

    /**
     * 删除
     */
    fun deleteAction() {
        activeCandidate = 0
        activeCandidateBar = 0
    }

    val composingStrForDisplay: String   //获取显示的拼音字符串/
        get() = RimeEngine.showComposition

    val composingStrForCommit: String   // 获取输入的拼音字符串
        get() = RimeEngine.showComposition.replace("'", "")
            .ifEmpty { RimeEngine.compositionText }        // 有编码但没候选时（i/u/v）也要能按编码上屏
            .ifEmpty { getCandidate(0)?.text ?: "" }

    /**
     * 引擎是否仍在组合态。与 [isCandidatesEmpty] 不同：
     * i / u / v 这类编码会出现"有编码、没候选"，此时不能当成"没在输入"把组合串清掉。
     */
    val isComposing: Boolean
        get() = RimeEngine.hasComposition

    val nextPageCandidates: Int   // 获取下一页的候选词
        get() {
            val fresh = RimeEngine.appendNextPage()
            // 追加不算换"代"：视图会走增量插入，不重绑、不动滚动位置
            if (fresh > 0) publishAppend(RimeEngine.showCandidates)
            return fresh
        }

    /**
     * 选择一个候选词，且重新获取候选词列表
     */
    fun chooseDecodingCandidate(candId: Int): String {
        activeCandidate = 0
        activeCandidateBar = 0
        var candidate: String
        if (!InputDispatcher.isFinish || isAssociate) { // Rime和联想
            if (candId >= 0) InputDispatcher.getWordSelectedWord(candId)
            val newCandidates = RimeEngine.showCandidates
            candidate = if (newCandidates.isNotEmpty()) RimeEngine.preCommitText
            else if (candId in 0..<candidateSize) RimeEngine.preCommitText.ifEmpty { candidates[candId].text }
            else ""
            publish(newCandidates)
        } else {  // 手写
            candidate = if (candId in 0..<candidateSize) candidates[candId].text else ""
            reset()
        }
        return candidate
    }

    /**
     * 对输入的拼音进行查询。
     */
    fun updateDecodingCandidate() {
        activeCandidate = 0
        activeCandidateBar = 0
        publish(RimeEngine.showCandidates)
    }

    /**
     * 候选内容被就地改写（插入/提升纠错候选、按可信度重排）：按"整表替换"发布。
     * 这类改动可能落在列表中间，不能当作"追加"去做增量插入。
     */
    fun notifyCandidatesChanged() {
        publish(RimeEngine.showCandidates)
    }

    /**
     * 获得指定的候选词
     */
    fun getCandidate(candId: Int): CandidateListItem? = candidates.getOrNull(candId)

    // 更新候选词
    fun cacheCandidates(
        words: Array<CandidateListItem>,
        associate: Boolean = false,
        clipboardSuggestion: Boolean = false,
    ) {
        isAssociate = associate
        activeCandidate = 0
        activeCandidateBar = 0
        publish(words.asList(), clipboardSuggestion = clipboardSuggestion)
    }

    /**
     * 根据输入的字符查询候选词
     */
    fun getAssociateWord(words: String) {
        isAssociate = true
    }
}
