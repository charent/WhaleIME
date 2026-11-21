package com.whale.ime.compose.keyboard

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.whale.ime.compose.ImeController
import com.whale.ime.compose.LocalImeTheme
import com.whale.ime.preference.AppPrefs
import com.whale.ime.dispatch.DictDecoder
import com.whale.ime.util.StringUtils
import com.whale.ime.engine.RimeEngine
import kotlinx.coroutines.flow.distinctUntilChanged
import com.whale.ime.preference.InputFeedbacks

/**
 * 全屏候选列表（候选栏右侧箭头「更多」进入）。
 *
 * 用 `LazyVerticalGrid` 排布，点选直接上屏。
 *
 * 左侧栏（读音导航）：
 * - 顶栏是**完整输入分词**（`b'si`，纠错段带 `?`），点它清空读音筛选、回到全部候选
 *   （当前候选若是把读音写回引擎换来的，一并把用户的按键还回去）；
 * - 下面是候选实际命中的首读音（原始 + 纠错读音，纠错读音带 `?`），点一下只看该读音
 *   的候选（词 + 字），再点一下取消；
 * - 长按读音 = 无条件把它当成首音节写回引擎重新组句，用于「我确实想打 bei'si」这类纠正；
 * - 底部「单字」只保留单字候选，与上面的读音筛选叠加。
 *
 * **点读音什么时候要改输入**（[RimeEngine.applyReadingSyllable]）：读音的拼写不在用户
 * 敲的键里、候选池里也没有「正是这个读音」的候选时（`be` 下的 `bei` / `bao` —— 它们是
 * 引擎对 `be` 的简拼、补全解释，见 [RimeEngine.canFilterReading]）。这一类读音只会以
 * 跨段整词出现（贝尔 `bei'er`、保额 `bao'e`），被/背/北、包/报/保 这些**字**根本没被
 * 引擎产出过，在候选列表上过滤永远只剩整词。把读音写回引擎（等价于「按这个音重打一遍」）
 * 才拿得到该读音自己的字，并且可还原：再点一次该读音、或点顶栏，按键会被写回去。
 *
 * 拼写本来就来自按键的读音（`xian` 里的 `xi`、`bei` 本身）仍然只做筛选：多数时候用户
 * 只是想「看看这个读音下有哪些候选」，没必要把输入改掉。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CandidatesPanel(controller: ImeController) {
    val theme = LocalImeTheme.current
    val textColor = Color(theme.keyTextColor)
    val accentColor = Color(theme.functionKeyBackgroundColor)
    // 与候选栏共用「候选字大小 / 粗细」两项设置（设置 → 键盘 → 字体）
    val keyboardSetting = AppPrefs.getInstance().keyboardSetting
    val candidateFontScale = keyboardSetting.candidateFontScale.getValue() / 100f
    val candidateWeight = FontWeight(keyboardSetting.candidateFontWeight.getValue().weight)
    val candidateState by DictDecoder.candidateState.collectAsStateWithLifecycle()
    var charOnly by remember { mutableStateOf(false) }
    // null = 不筛选（显示全部候选）
    var selectedSyllable by remember { mutableStateOf<String?>(null) }
    // null = 未进入分段选择；否则是被聚焦的段下标（见 KeyDispatcher.focusSegment）
    var focusedSegment by remember { mutableStateOf<Int?>(null) }
    // 进入分段选择时把分段快照下来：聚焦后组合串只剩一段，不能再从 preedit 里切
    var segmentSnapshot by remember { mutableStateOf<List<String>>(emptyList()) }
    // 已确认（消费掉）的段，用于置灰且禁止再点
    var pickedSegments by remember { mutableStateOf<Set<Int>>(emptySet()) }
    val gridState = rememberLazyGridState()

    val composition = RimeEngine.compositionText
    val segments = remember(composition) {
        composition.split("'").filter { it.isNotBlank() }
    }
    val readings = remember(candidateState.candidates, composition) {
        deriveReadings(candidateState.candidates, composition)
    }
    // 输入变化后旧读音可能已经不存在了，及时清掉筛选，避免列表空白
    LaunchedEffect(readings) {
        val current = selectedSyllable
        if (current != null && readings.none { it.syllable == current }) {
            selectedSyllable = null
        }
    }
    // 联想词只属于候选栏：输入真的结束（组合串为空）之后才收回面板。
    // 分段选择中间那一步上屏后还剩组合串（si），此时必须留在面板继续选。
    LaunchedEffect(candidateState.isAssociate) {
        if (candidateState.isAssociate && RimeEngine.compositionText.isEmpty()) {
            controller.switchToInputKeyboard()
        }
    }

    // 引擎侧的分段态消失（用户继续按键、或已上屏）时，UI 同步退出。
    // 注意只能看引擎状态：聚焦后组合串本来就只剩这一段，
    // 用 `segments.size < 2` 判断会把刚生效的聚焦立刻清掉。
    LaunchedEffect(composition) {
        if (focusedSegment != null && !RimeEngine.isSegmentFocused) {
            focusedSegment = null
        }
    }

    // 首屏只取一批（见 RimeEngine.FIRST_BATCH_SIZE），滑到底再增量补齐。
    // 不补的话全屏候选页会永远停在前 12 条。
    // 用 snapshotFlow 而不是 LaunchedEffect(首个可见下标)：进页面时 layoutInfo 还没算出来，
    // 一进来就 return 的话不会再重启，候选永远补不上。
    LaunchedEffect(gridState) {
        snapshotFlow {
            val info = gridState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            last to info.totalItemsCount
        }.distinctUntilChanged().collect { (last, total) ->
            if (total > 0 && total < CANDIDATE_PANEL_MAX &&
                last >= total - PANEL_LOAD_MORE_LOOKAHEAD
            ) {
                DictDecoder.nextPageCandidates
            }
        }
    }

    Row(modifier = Modifier.fillMaxSize()) {
        if (segments.size >= 2 || readings.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .width(56.dp)
                    .fillMaxHeight()
                    .padding(start = 4.dp, top = 6.dp, bottom = 6.dp),
            ) {
                // 顶栏：完整输入分词（b'si），点它退出分段选择、回到全部候选
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (selectedSyllable == null && focusedSegment == null) accentColor
                            else Color.Transparent
                        )
                        .clickable {
                            selectedSyllable = null
                            if (focusedSegment != null) {
                                focusedSegment = null
                                controller.dispatcher.restoreSegments()
                            }
                            // 顶栏显示的是被读音改写过的组合串：点一下还原成用户敲的键
                            if (RimeEngine.isReadingRewritten) {
                                controller.dispatcher.clearReadingSyllable()
                            }
                        }
                        .padding(horizontal = 3.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    ImeAutoSizeText(
                        text = composition.ifEmpty { "\u2014" },
                        color = textColor,
                        baseFontSize = 12.sp,
                        modifier = Modifier.heightIn(min = 22.dp),
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    // 分段：`b'si` → b / si，点一段只看这一段的字词（见 focusSegment）。
                    // 已确认（消费掉）的段置灰且不可点 —— 那段已经定了。
                    val displaySegments = when {
                        segmentSnapshot.isNotEmpty() -> segmentSnapshot
                        // 面板收起后再打开：本地快照已随组合丢失，从引擎的分段态恢复
                        RimeEngine.isSegmentFocused -> RimeEngine.focusedSegmentsSnapshot
                        else -> segments
                    }
                    if (displaySegments.size >= 2) {
                        displaySegments.forEachIndexed { index, segment ->
                            val focused = focusedSegment == index
                            val picked = index in pickedSegments
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 32.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (focused) accentColor else Color.Transparent)
                                    .clickable(enabled = !picked) {
                                        if (picked) return@clickable
                                        InputFeedbacks.tryPlayKeyDown()
                                        InputFeedbacks.tryVibrate()
                                        selectedSyllable = null
                                        if (focused) {
                                            focusedSegment = null
                                            segmentSnapshot = emptyList()
                                            pickedSegments = emptySet()
                                            controller.dispatcher.restoreSegments()
                                        } else {
                                            if (segmentSnapshot.isEmpty()) {
                                                segmentSnapshot = segments
                                                pickedSegments = emptySet()
                                            }
                                            focusedSegment = index
                                            controller.dispatcher.focusSegment(
                                                displaySegments,
                                                index
                                            )
                                        }
                                    }
                                    .padding(horizontal = 3.dp),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                ImeAutoSizeText(
                                    text = segment,
                                    color = when {
                                        picked -> textColor.copy(alpha = 0.35f)
                                        focused -> textColor
                                        else -> textColor
                                    },
                                    fontWeight = FontWeight.Medium,
                                    baseFontSize = 14.sp,
                                    modifier = Modifier.heightIn(min = 24.dp),
                                )
                            }
                        }
                    }
                    // 读音细选：候选实际命中的读音（含纠错读音，带 `?`）
                    if (readings.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                                .height(1.dp)
                                .background(textColor.copy(alpha = 0.12f)),
                        )
                        readings.forEach { reading ->
                            val selected = selectedSyllable == reading.syllable
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 30.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (selected) accentColor else Color.Transparent)
                                    .combinedClickable(
                                        onClick = {
                                            InputFeedbacks.tryPlayKeyDown()
                                            InputFeedbacks.tryVibrate()
                                            when {
                                                // 取消筛选；若当前候选是读音改写换来的，顺手还原按键
                                                selected -> {
                                                    selectedSyllable = null
                                                    if (RimeEngine.isReadingRewritten) {
                                                        controller.dispatcher
                                                            .clearReadingSyllable()
                                                    }
                                                }
                                                // 读音的拼写不在用户敲的键里（简拼/补全解释出的读音）、
                                                // 候选池里也没有这个读音本身：过滤只能筛出跨段整词，
                                                // 必须写回引擎重查，才看得到这个读音自己的字
                                                !RimeEngine.canFilterReading(reading.syllable) &&
                                                    controller.dispatcher.applyReadingSyllable(
                                                        reading.syllable
                                                    ) -> {
                                                    selectedSyllable = reading.syllable
                                                }
                                                else -> selectedSyllable = reading.syllable
                                            }
                                        },
                                        onLongClick = {
                                            // 长按：无条件认下这个读音，写回引擎重新组句
                                            InputFeedbacks.tryPlayKeyDown()
                                            InputFeedbacks.tryVibrate()
                                            if (controller.dispatcher.applyReadingSyllable(
                                                    reading.syllable
                                                )
                                            ) {
                                                selectedSyllable = reading.syllable
                                            }
                                        },
                                    )
                                    .padding(horizontal = 3.dp),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                ImeAutoSizeText(
                                    // 纠错读音带 `?`：这个读音要改动用户敲的键才能成立
                                    text = if (reading.correction) "${reading.syllable}?" else reading.syllable,
                                    color = if (reading.correction) {
                                        textColor.copy(alpha = 0.7f)
                                    } else {
                                        textColor.copy(alpha = 0.85f)
                                    },
                                    baseFontSize = 13.sp,
                                    modifier = Modifier.heightIn(min = 22.dp),
                                )
                            }
                        }
                    }
                }
                // 「单字」过滤固定在左栏底部，不随读音滚动
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (charOnly) accentColor else Color(theme.keyBackgroundColor))
                        .clickable { charOnly = !charOnly }
                        .padding(horizontal = 3.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    ImeAutoSizeText(
                        text = "单字",
                        color = if (charOnly) textColor else textColor.copy(alpha = 0.55f),
                        baseFontSize = 14.sp,
                        modifier = Modifier.heightIn(min = 24.dp),
                    )
                }
            }
        }
        val displayed = candidateState.candidates.withIndex().filter { indexed ->
            val item = indexed.value
            val isSingle = StringUtils.glyphCount(item.text) == 1 &&
                !StringUtils.isEmojiOnly(item.text)
            if (charOnly && !isSingle) return@filter false
            val wanted = selectedSyllable ?: return@filter true
            val first = item.comment
                ?.split("'")
                ?.firstOrNull()
                ?.trim()
                ?.trimEnd('?')
                ?: return@filter false
            first == wanted
        }
        if (displayed.isEmpty()) {
            // 筛选后没有候选（读音只对应词、或单字开关与读音叠加过窄）：兜底展示全部
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                ImeAutoSizeText(
                    text = "该读音下没有${if (charOnly) "单字" else "候选"}",
                    color = textColor.copy(alpha = 0.6f),
                    baseFontSize = 14.sp,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            LazyVerticalGrid(
                // 88dp 保证 6~7 字的长候选只轻微缩字就能完整放下；宽屏自动多列
                columns = GridCells.Adaptive(minSize = 88.dp),
                state = gridState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                itemsIndexed(displayed) { _, indexed ->
                    val index = indexed.index
                    val item = indexed.value
                    // 纠错候选（comment 以 "?" 结尾）：与候选栏一致，在右上角画问号角标
                    val isCorrection = item.comment?.endsWith("?") == true
                    // 与候选栏同理：格子按 index 复用会让抬手后仍在淡出的 ripple
                    // 落到刷新后挤到同一格的另一条候选上，所以把节点绑定到候选文本。
                    key(item.text) {
                        Box(
                            modifier = Modifier
                                .heightIn(min = 40.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    InputFeedbacks.tryPlayKeyDown()
                                    InputFeedbacks.tryVibrate()
                                    if (focusedSegment != null) {
                                        // 分段组句：这一段的字先攒着，切到下一段；全部选完才上屏
                                        // （已选的字和整串拼接都在 dispatcher 里，面板收起也不会丢）
                                        val current = focusedSegment!!
                                        // 面板收起过：本地快照跟着组合一起丢了，从引擎补回来
                                        if (segmentSnapshot.isEmpty() && RimeEngine.isSegmentFocused) {
                                            segmentSnapshot = RimeEngine.focusedSegmentsSnapshot
                                        }
                                        pickedSegments = pickedSegments + current
                                        val next = controller.dispatcher.pickSegmentCandidate(
                                            index,
                                            current,
                                            segmentSnapshot.size,
                                        )
                                        if (next >= 0) {
                                            focusedSegment = next
                                        } else {
                                            focusedSegment = null
                                            segmentSnapshot = emptyList()
                                            pickedSegments = emptySet()
                                        }
                                    } else {
                                        controller.dispatcher.chooseAndUpdate(index)
                                    }
                                }
                                .padding(horizontal = 3.dp, vertical = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            // 长候选不截断：按可用宽度自动缩字号完整显示
                            ImeAutoSizeText(
                                text = item.text,
                                color = textColor,
                                baseFontSize = (18f * candidateFontScale).sp,
                                fontWeight = candidateWeight,
                                // 角标不压字：尾侧留出问号的位置，长候选随之缩字
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(end = if (isCorrection) 11.dp else 0.dp),
                            )
                            if (isCorrection) {
                                CorrectionBadge(
                                    fontSizeSp = 18f * candidateFontScale,
                                    color = textColor.copy(alpha = 0.6f),
                                    modifier = Modifier.align(Alignment.TopEnd),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 距离尾部还剩几条时开始补取下一批 */
private const val PANEL_LOAD_MORE_LOOKAHEAD = 8

/** 全屏候选页的候选上限：再多对用户没有意义，只会拖慢补取 */
private const val CANDIDATE_PANEL_MAX = 120

/** 左栏读音：syllable = 完整读音，correction = 该读音改动了用户敲的键（comment 带 `?`） */
private data class Reading(val syllable: String, val correction: Boolean)

/**
 * 左侧读音栏条目。
 *
 * 只有**完整音节**才进列表：`b` 这种简拼段不算读音（它对应用户还没打完的意图，
 * 点它只会得到几条零碎单字），由顶栏的完整分词负责「不筛选」。
 * 同一个读音既可能来自精确命中，也可能来自纠错：只要有一条带 `?`，就按纠错呈现。
 */
private fun deriveReadings(
    candidates: List<com.whale.ime.rime.CandidateListItem>,
    composition: String,
): List<Reading> {
    val result = LinkedHashMap<String, Boolean>()
    for (item in candidates) {
        if (result.size >= 12) break
        val comment = item.comment ?: continue
        val correction = comment.endsWith("?")
        val first = comment.split("'").firstOrNull()?.trim()?.trimEnd('?') ?: continue
        if (!first.matches(Regex("[a-z]+"))) continue
        if (first.length < 2) continue  // 简拼段不当作读音
        val previous = result[first]
        result[first] = (previous ?: true) && correction
    }
    return result.map { Reading(it.key, it.value) }
}
