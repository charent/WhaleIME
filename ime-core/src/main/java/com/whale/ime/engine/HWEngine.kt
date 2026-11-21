package com.whale.ime.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import com.whale.ime.handwriting.HCCRRecognizer
import com.whale.ime.boot.Launcher
import com.whale.ime.callback.IHandWritingCallBack
import com.whale.ime.vendor.pinyin4j.PinyinHelper
import com.whale.ime.vendor.pinyin4j.format.HanyuPinyinCaseType
import com.whale.ime.vendor.pinyin4j.format.HanyuPinyinOutputFormat
import com.whale.ime.vendor.pinyin4j.format.HanyuPinyinToneType
import com.whale.ime.vendor.pinyin4j.format.HanyuPinyinVCharType
import com.whale.ime.rime.CandidateListItem
import com.whale.ime.util.thread.ThreadPoolUtils

/**
 * 手写识别引擎（开源 Handwritten：MobileNetV2 → NCNN INT8，GB2312 一级 3755 字）。
 *
 * 对外接口：
 *  - [isAvailable]：模型懒加载成功即可用；失败不缓存，下次查询重试
 *  - [recognitionData]：`x,y,x,y,...,-1,0` 的 Short 点列（-1,0 为笔画分隔符）→ top-k 候选
 *
 * 渲染管线与上游 Android demo 对齐（PIL 同源）：
 * 白底黑字、包围盒外扩后按长边缩放到 360px 画布、笔画宽 4.4%（≈16px）、
 * 关抗锯齿 + 直线段；灰度化后的 bbox/归一化由 native preprocess.c 完成。
 */
object HWEngine {

    private const val TAG = "HWEngine"

    /** 模型渲染画布长边（与 Python demo 360 一致） */
    private const val RENDER_TARGET_SIZE = 360

    /** 模型输入笔画宽占画布宽比例（Python 16px / 360） */
    private const val RENDER_STROKE_RATIO = 0.044f

    private val mHanyuPinyinOutputFormat: HanyuPinyinOutputFormat = HanyuPinyinOutputFormat().apply {
        caseType = HanyuPinyinCaseType.LOWERCASE
        toneType = HanyuPinyinToneType.WITH_TONE_MARK
        vCharType = HanyuPinyinVCharType.WITH_U_UNICODE
    }

    @Volatile
    private var recognizer: HCCRRecognizer? = null

    /** 当前实际使用的推理后端（"Vulkan" / "CPU"），引擎未加载时为 null。调试展示用 */
    @Volatile
    var backendName: String? = null
        private set

    val isAvailable: Boolean
        get() {
            recognizer?.let { return true }
            return synchronized(this) {
                recognizer?.let { return true }
                runCatching {
                    HCCRRecognizer.loadNativeLibrary("hccr_jni")
                    HCCRRecognizer(
                        Launcher.instance.context.assets,
                        "hccr/model.ncnn.param", "hccr/model.ncnn.bin", "hccr/charset.json"
                    )
                }.onFailure {
                    Log.w(TAG, "手写模型初始化失败，手写输入将被禁用", it)
                }.getOrNull()?.also {
                    recognizer = it
                    backendName = runCatching { it.backend }.getOrDefault("CPU")
                    Log.i(TAG, "手写推理后端: $backendName")
                } != null
            }
        }

    fun recognitionData(strokes: MutableList<Short?>, recogResult: IHandWritingCallBack) {
        if (!isAvailable) {
            Log.w(TAG, "recognitionData: 引擎不可用")
            return
        }
        ThreadPoolUtils.executeSingleton {
            runCatching { recognize(strokes) }
                .onSuccess { items ->
                    if (items.isNotEmpty()) recogResult.onSucess(items.toTypedArray())
                }
                .onFailure { Log.w(TAG, "手写识别失败", it) }
        }
    }

    private fun recognize(strokes: MutableList<Short?>): List<CandidateListItem> {
        val rec = recognizer ?: return emptyList()
        val gray = renderGray(strokes) ?: run {
            Log.w(TAG, "renderGray 返回空（无有效笔画）")
            return emptyList()
        }
        val input = FloatArray(64 * 64)
        if (!rec.preprocess(gray.bytes, gray.width, gray.height, input)) {
            Log.w(TAG, "nativePreprocess 失败: ${gray.width}x${gray.height}")
            return emptyList()
        }
        return rec.predict(input, 10).mapNotNull { result ->
            val text = result.text
            if (text.isEmpty() || text == "?") return@mapNotNull null
            CandidateListItem(
                PinyinHelper.toHanYuPinyin(text, mHanyuPinyinOutputFormat, "'").ifEmpty { text },
                text
            )
        }
    }

    private class GrayImage(val bytes: ByteArray, val width: Int, val height: Int)

    /**
     * 点列 → 灰度图（白底黑字）。不用 View：纯离屏 Bitmap + Canvas（ARGB_8888）。
     * bbox 外扩后按长边缩放到 360 画布；bbox 裁切/归一化由 native preprocess.c 完成。
     */
    private fun renderGray(strokes: MutableList<Short?>): GrayImage? {
        // 解析为笔画列表
        val parsed = ArrayList<FloatArray>(8)
        var current = ArrayList<Float>(64)
        var i = 0
        val size = strokes.size
        while (i + 1 < size) {
            val x = strokes[i] ?: break
            val y = strokes[i + 1] ?: break
            if (x == (-1).toShort() && y == (0).toShort()) {
                if (current.size >= 2) parsed.add(current.toFloatArray())
                current = ArrayList(64)
            } else {
                current.add(x.toFloat()); current.add(y.toFloat())
            }
            i += 2
        }
        if (current.size >= 2) parsed.add(current.toFloatArray())
        if (parsed.isEmpty()) return null

        // 包围盒
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (stroke in parsed) {
            var j = 0
            while (j < stroke.size) {
                val x = stroke[j]; val y = stroke[j + 1]
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                j += 2
            }
        }
        val boxW = maxX - minX
        val boxH = maxY - minY
        if (boxW <= 0f && boxH <= 0f) return null

        // bbox 外扩 10% + 1px，按长边缩放到目标画布
        val padX = boxW * 0.1f + 1f
        val padY = boxH * 0.1f + 1f
        val srcW = boxW + padX * 2
        val srcH = boxH + padY * 2
        val scale = RENDER_TARGET_SIZE / maxOf(srcW, srcH)
        val dstW = (srcW * scale).toInt().coerceIn(1, RENDER_TARGET_SIZE)
        val dstH = (srcH * scale).toInt().coerceIn(1, RENDER_TARGET_SIZE)

        val bitmap = Bitmap.createBitmap(dstW, dstH, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        val canvas = Canvas(bitmap)
        val paint = Paint().apply {
            color = Color.BLACK
            isAntiAlias = false // PIL line 无 AA
            style = Paint.Style.STROKE
            strokeWidth = RENDER_TARGET_SIZE * RENDER_STROKE_RATIO
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        // 世界坐标 → 画布：先平移 bbox 到原点（含 padding），再缩放
        canvas.translate(padX * scale, padY * scale)
        canvas.scale(scale, scale)
        canvas.translate(-minX, -minY)
        for (stroke in parsed) {
            if (stroke.size == 2) {
                // 单点：BUTT 线段不可见，画小圆点补偿
                canvas.drawPoint(stroke[0], stroke[1], paint.apply { style = Paint.Style.FILL })
                paint.style = Paint.Style.STROKE
                continue
            }
            val path = android.graphics.Path()
            path.moveTo(stroke[0], stroke[1])
            var j = 2
            while (j < stroke.size) {
                path.lineTo(stroke[j], stroke[j + 1]) // 直线段，与 PIL 一致
                j += 2
            }
            canvas.drawPath(path, paint)
        }
        // ARGB → 灰度（与上游 demo 的 299/587/114 一致）
        val pixels = IntArray(dstW * dstH)
        bitmap.getPixels(pixels, 0, dstW, 0, 0, dstW, dstH)
        bitmap.recycle()
        val gray = ByteArray(dstW * dstH)
        for (idx in pixels.indices) {
            val p = pixels[idx]
            gray[idx] = ((299 * ((p shr 16) and 0xFF) + 587 * ((p shr 8) and 0xFF) +
                    114 * (p and 0xFF)) / 1000).toByte()
        }
        return GrayImage(gray, dstW, dstH)
    }
}
