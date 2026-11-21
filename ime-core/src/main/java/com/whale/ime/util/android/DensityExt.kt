package com.whale.ime.util.android

import android.util.TypedValue
import com.whale.ime.boot.Launcher

/**
 * 屏幕密度换算。
 *
 * 只做 dp/px/sp 的单位换算，不涉及任何输入业务语义；按键音效与震动属于
 * `com.whale.ime.preference.InputFeedbacks`。
 */
object Density {

    /**
     * 根据手机的分辨率从 dp 的单位 转成为 px(像素)
     */
    @JvmStatic
    fun dip2px(dpValue: Float): Int {
        val scale = Launcher.instance.context.resources.displayMetrics.density
        return (dpValue * scale + 0.5f).toInt()
    }

    @JvmStatic
    fun dip2px(dpValue: Int): Int {
        val scale = Launcher.instance.context.resources.displayMetrics.density
        return (dpValue * scale + 0.5f).toInt()
    }

    @JvmStatic
    fun px2sp(px: Int): Float {
        val scale = Launcher.instance.context.resources.displayMetrics.density
        return px / (scale + 1)
    }

    /**
     * 根据手机的分辨率从 px(像素) 的单位 转成为 dp
     */
    @JvmStatic
    fun px2dip(pxValue: Int): Float {
        val scale = Launcher.instance.context.resources.displayMetrics.density
        return pxValue / scale + 0.5f
    }

    @JvmStatic
    fun sp2px(sp: Int): Int {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp.toFloat(), Launcher.instance.context.resources.displayMetrics).toInt()
    }
}
