package com.whale.ime.compose.keyboard

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.whale.ime.preference.AppPrefs
import com.whale.ime.keyboard.metrics.Environment
import com.whale.ime.util.thread.ThreadPoolUtils
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 手写板会话：笔迹采集 + 渲染 + 识别触发。
 *
 * 规则：
 * - 坐标序列是 `x, y, x, y, ..., -1, 0` 的 `Short` 列表（`HWEngine.recognitionData` 的入参格式）；
 * - 抬笔即识别、停笔 `1200 - handWritingSpeed` ms 后自动清空笔迹、
 *   停笔后再次落笔视为「确认当前候选」；
 * - 笔迹宽度仍按速度反比（快细慢粗），只是把 Bezier 细分换成按采样点直接描边，
 *   视觉差异小于 1px，换来的是每帧不再分配 Path 对象。
 */
class HandwritingSession(
    private val onRecognize: (MutableList<Short?>) -> Unit,
    private val onCommitCurrentCandidate: () -> Unit,
) {
    /** 当前字的笔迹（每个元素是一条线的采样点），用于绘制 */
    val strokes = mutableStateListOf<List<TimedOffset>>()

    /**
     * 笔迹内容版本号：每追加一个采样点（以及每次清空/成笔）自增。
     *
     * [strokes] 只在抬笔时才 add，书写中的那一笔存在 [currentStroke] 里（普通 List），
     * 所以书写过程中没有任何快照状态变化，绘制层不会失效，表现为「抬笔后才出现笔迹」。
     * 绘制层读取本字段即可在落笔/移动时逐点重绘。
     */
    var inkRevision by mutableIntStateOf(0)
        private set

    /** 待识别点序列（`x, y, x, y, ..., -1, 0`） */
    private val points: MutableList<Short?> = ArrayList()

    private var lastUpTime = 0L
    private var lastPoint: TimedOffset? = null
    private var currentStroke: MutableList<TimedOffset>? = null

    /** 抬笔超时自动清空画布的定时任务 */
    private var idleClearFuture: ScheduledFuture<*>? = null

    /** 停笔判定窗口：`1200 - handWritingSpeed` ms */
    private val idleTimeoutMs: Long
        get() = (1200L - AppPrefs.getInstance().handwriting.handWritingSpeed.getValue()).coerceAtLeast(200L)

    /** 停笔超时后是否自动把第一候选上屏（设置→手写→停笔后自动上屏，默认关） */
    private val autoCommit: Boolean
        get() = AppPrefs.getInstance().handwriting.handWritingAutoCommit.getValue()

    private val maxWidthDp: Float
        get() = AppPrefs.getInstance().handwriting.handWritingWidth.getValue() * 22 / 100f

    fun onDown(position: Offset) {
        val now = System.currentTimeMillis()
        idleClearFuture?.cancel(false)
        idleClearFuture = null
        if (lastUpTime != 0L && now - lastUpTime > idleTimeoutMs) {
            // 停笔超时后再落笔 = 进入新字；开自动上屏时先确认当前候选
            if (autoCommit) onCommitCurrentCandidate()
            points.clear()
            strokes.clear()
        }
        startStroke(position, now)
    }

    fun onMove(position: Offset) {
        appendPoint(position, System.currentTimeMillis())
    }

    fun onUp(position: Offset) {
        if (currentStroke == null) {
            startStroke(position, System.currentTimeMillis())
        }
        appendPoint(position, System.currentTimeMillis())
        currentStroke?.let { strokes.add(it.toList()) }
        currentStroke = null
        inkRevision++
        lastUpTime = System.currentTimeMillis()
        // 笔画结束标记
        points.add(-1)
        points.add(0)
        onRecognize(points)
        scheduleIdleClear()
    }

    /**
     * 最后一笔抬笔后 [idleTimeoutMs] 内没有再落笔，视为书写完成：
     * 清空画布（自动上屏开关打开时同时把第一候选上屏）。
     */
    private fun scheduleIdleClear() {
        idleClearFuture?.cancel(false)
        idleClearFuture = ThreadPoolUtils.scheduleInDaemon({
            if (currentStroke != null) return@scheduleInDaemon
            if (autoCommit) onCommitCurrentCandidate()
            points.clear()
            strokes.clear()
            inkRevision++
            lastUpTime = 0L
        }, idleTimeoutMs, TimeUnit.MILLISECONDS)
    }

    private fun startStroke(position: Offset, now: Long) {
        val stroke = mutableListOf<TimedOffset>()
        val point = TimedOffset(position.x, position.y, now)
        stroke.add(point)
        currentStroke = stroke
        points.add(position.x.toInt().toShort())
        points.add(position.y.toInt().toShort())
        lastPoint = point
        inkRevision++
    }

    private fun appendPoint(position: Offset, now: Long) {
        val stroke = currentStroke ?: run {
            startStroke(position, now)
            return
        }
        val point = TimedOffset(position.x, position.y, now)
        stroke.add(point)
        points.add(position.x.toInt().toShort())
        points.add(position.y.toInt().toShort())
        lastPoint = point
        inkRevision++
    }

    /** 清空笔迹（识别完 / 超时） */
    fun clearInk() {
        strokes.clear()
        currentStroke = null
        lastPoint = null
        inkRevision++
    }

    /** 供绘制层读取的完整笔迹（含正在书写的那一笔） */
    fun allStrokes(): List<List<TimedOffset>> =
        if (currentStroke == null) strokes.toList()
        else strokes.toList() + listOf(currentStroke!!.toList())

    /**
     * 绘制层专用快照：读取 [inkRevision] 建立快照订阅，再取完整笔迹。
     *
     * 书写中的那一笔不写入 [strokes]，只有 [inkRevision] 会变化；
     * 绘制方必须在自己的快照作用域里调用本方法（而不是 [allStrokes]），
     * 否则书写过程中没有状态变化，笔迹要等抬笔才刷新。
     */
    fun inkSnapshot(): List<List<TimedOffset>> {
        val revision = inkRevision
        val snapshot = allStrokes()
        return if (revision >= 0) snapshot else emptyList()
    }

    /**
     * 笔迹基础宽度（px）。整体偏细：设置值 × 0.3，实际绘制宽度只在基础值的
     * 0.75–1.25 倍之间随速度微调，快细慢粗的幅度刻意收窄，避免慢速段堆出墨点。
     */
    fun strokeWidthFor(p0: TimedOffset, p1: TimedOffset): Float {
        val dt = (p1.time - p0.time).coerceAtLeast(1L)
        val distance = sqrt((p1.x - p0.x) * (p1.x - p0.x) + (p1.y - p0.y) * (p1.y - p0.y))
        val velocity = (distance / dt).coerceIn(0f, 1.5f)
        return dpToPx(maxWidthDp) * (1.25f - 0.5f * velocity / 1.5f)
    }

    private fun dpToPx(dp: Float): Float {
        val density = com.whale.ime.boot.Launcher.instance.context.resources.displayMetrics.density
        return (density * dp).roundToInt().toFloat().coerceAtLeast(1f)
    }
}

/** 笔迹绘制：放在顶层，避免成员扩展函数在 `drawWithCache` 的嵌套 receiver 里解析不到 */
fun DrawScope.drawHandwritingStrokes(
    strokes: List<List<TimedOffset>>,
    widthFor: (TimedOffset, TimedOffset) -> Float,
    color: Color,
) {
    strokes.forEach { raw ->
        // 1. 合并过近采样点（落笔/收笔原地停留产生的密集点，是首尾墨点的来源）
        val pts = ArrayList<TimedOffset>(raw.size)
        raw.forEach { p ->
            val last = pts.lastOrNull()
            if (last == null || dist(last, p) >= 1.2f) pts.add(p)
        }
        if (pts.size < 2) return@forEach // 轻点不画墨点

        // 2. 位置去抖：3 点加权平均，压掉手抖锯齿，端点保持原位
        val smooth = ArrayList<TimedOffset>(pts.size)
        for (i in pts.indices) {
            smooth.add(
                when (i) {
                    0, pts.size - 1 -> pts[i]
                    else -> TimedOffset(
                        (pts[i - 1].x + pts[i].x * 2f + pts[i + 1].x) / 4f,
                        (pts[i - 1].y + pts[i].y * 2f + pts[i + 1].y) / 4f,
                        pts[i].time,
                    )
                }
            )
        }

        // 3. 逐段宽度 → 正反两遍指数平滑，粗细渐变而非跳变
        val widths = FloatArray(smooth.size - 1) { i ->
            widthFor(smooth[i], smooth[i + 1]).coerceIn(1f, 48f)
        }
        for (pass in 0..1) {
            val step = if (pass == 0) 1 else -1
            var from = if (pass == 0) 0 else widths.size - 1
            var prev = widths[from]
            while (from in widths.indices) {
                widths[from] = prev + (widths[from] - prev) * 0.45f
                prev = widths[from]
                from += step
            }
        }

        // 4. 端部收细：固定收前后各两段（不按比例，短笔画同样生效），
        //    落笔起锋、收笔出锋，消除圆头墨点
        if (widths.size >= 2) {
            widths[0] *= 0.45f
            widths[widths.size - 1] *= 0.45f
        }
        if (widths.size >= 4) {
            widths[1] *= 0.8f
            widths[widths.size - 2] *= 0.8f
        }

        // 5. 中点折线绘制：相邻段通过中点衔接，转折处自然圆滑
        for (i in widths.indices) {
            drawSmoothSegment(smooth, i, widths[i], color)
        }
    }
}

/** 第 i 段的起止点：以采样点为控制点的中点折线，端点段直接连到首/尾采样点 */
private fun DrawScope.drawSmoothSegment(
    smooth: List<TimedOffset>,
    i: Int,
    width: Float,
    color: Color,
) {
    val a = smooth[i]
    val b = smooth[i + 1]
    val start = if (i == 0) Offset(a.x, a.y) else midOf(smooth[i - 1], b)
    val end = if (i == smooth.size - 2) Offset(b.x, b.y) else midOf(a, smooth[i + 2])
    drawLine(color = color, start = start, end = end, strokeWidth = width.coerceAtLeast(1f), cap = StrokeCap.Round)
}

private fun midOf(a: TimedOffset, b: TimedOffset) = Offset((a.x + b.x) / 2f, (a.y + b.y) / 2f)

private fun dist(a: TimedOffset, b: TimedOffset) =
    sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y))

data class TimedOffset(val x: Float, val y: Float, val time: Long)
