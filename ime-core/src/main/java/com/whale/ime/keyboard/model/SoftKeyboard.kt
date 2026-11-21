package com.whale.ime.keyboard.model

import com.whale.ime.keyboard.metrics.Environment
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
/**
 * Class used to represent a soft keyboard definition, including the height, the
 * background image, the image for high light, the keys, etc.
 * 一个软件盘的定义，包括按键的排列布局，宽度高度。
 * The width of the soft keyboard. 键盘的宽度
 * The height of the soft keyboard. 键盘的高度
 */
class SoftKeyboard(var mKeyRows: List<List<SoftKey>>) {
    // 按键左右间隔距离
    val keyXMargin = Environment.instance.keyXMargin
    // 按键上下间隔距离
    val keyYMargin = Environment.instance.keyYMargin

    /**
     * 宽键布局系数：主键宽（出现最多的键宽占比）与 26 键字母键宽（0.099）的比值开平方，
     * 26 键 / 英文全键等普通布局恒为 1。
     *
     * T9 / 数字 / 笔画这几类布局列少键宽，和 26 键用同一份像素间距时，
     * 横向只剩一道缝、纵向那份空隙相对键宽又太大，看上去就是「横挤竖松」。
     * 这里取「键宽比的平方根」做温和放大（T9 约 1.45 倍），完全等比会在宽键上
     * 显得空；同时宽键布局的纵向间距收窄一档（[yMarginForRender]），
     * 最终横竖间距在宽键上基本相等。26 键不受影响。
     */
    val wideKeyScale: Float = run {
        val widths = mKeyRows.flatMap { row -> row.map { it.widthF } }
        if (widths.isEmpty()) return@run 1f
        val counts = widths.groupingBy { (it * 1000f).roundToInt() }.eachCount()
        val maxCount = counts.values.max()
        // 并列最频繁时取最窄那档：手写键盘这种混合布局不该被侧栏宽键带偏
        val modeWidthF = counts.filterValues { it == maxCount }.keys.min() / 1000f
        sqrt((modeWidthF / QWERTY_KEY_WIDTH_F).coerceIn(1f, MAX_WIDE_KEY_SCALE))
    }

    private val wideLayout = wideKeyScale > 1.001f

    /**
     * 键帽渲染时的横向内缩：宽键布局按键自身键宽温和放大，空格行等窄键
     * （宽度不到主键宽）只按自身比例放大，不会被相邻宽键的间距带歪。
     */
    fun xMarginForRender(key: SoftKey): Int =
        if (!wideLayout) keyXMargin
        else (keyXMargin * min(key.widthF / QWERTY_KEY_WIDTH_F, wideKeyScale)).roundToInt()

    /** 键帽渲染时的纵向内缩：宽键布局收窄，和放大的横向间距凑成一致的空隙 */
    val yMarginForRender: Int
        get() = if (!wideLayout) keyYMargin else (keyYMargin * WIDE_KEY_Y_MARGIN_SCALE).roundToInt()

    /**
     * 根据坐标查找按键，如果坐标在某个按键区域内，就返回这个按键，如果坐标不在所有的按键区域内，返回离它最近的按键。
     * 可以在判断坐标在某个按键区域内的时候，并且加上判断离它最近的按键，这样就只需要一次遍历就行了。
     */
    fun mapToKey(x: Int, y: Int): SoftKey? {
        for (element in mKeyRows) {
            for (sKey in element) {
                if (sKey.mLeft <= x && sKey.mTop <= y && sKey.mRight > x && sKey.mBottom > y) return sKey
            }
        }
        return null
    }

    /**
     * 根据code值查询按键，由于符号键无code&部分键盘可能存在重复键，因此该方式可能无法精确查询。
     */
    fun getKeyByCode(code: Int): SoftKey? {
        for (keyRow in mKeyRows) {
            for (sKey in keyRow) {
                if (sKey.code == code) return sKey
            }
        }
        return null
    }

    private companion object {
        /** 26 键字母键的宽度占比，横向间距换算的基准（见 [wideKeyScale]） */
        const val QWERTY_KEY_WIDTH_F = 0.099f

        /** 开方前放大比的上限：文本编辑这类超宽键布局不至于把横向间距放到离谱 */
        const val MAX_WIDE_KEY_SCALE = 2.2f

        /** 宽键布局纵向间距的收窄系数：横竖间距在宽键上大小一致 */
        const val WIDE_KEY_Y_MARGIN_SCALE = 0.6f
    }
}
