package com.whale.ime.compose

import com.whale.ime.preference.AppPrefs
import com.whale.ime.preference.model.KeyboardSymbolSlideUpMod
import com.whale.ime.keyboard.metrics.Environment

/**
 * 键盘手势参数集中处。
 *
 * 注意：这里的值都是**运行时读取**（跟随设置页即时生效），
 * 因此调用点不要在 `remember` 里缓存，直接在手势回调里取。
 */
object GestureTuning {

    /** 长按连发的首次延迟 */
    const val REPEAT_START_DELAY_MS = 400L

    /** 长按连发的重复间隔 */
    const val REPEAT_INTERVAL_MS = 50L

    /** 删除键左滑触发「清空」的最小横向距离 */
    const val DELETE_SWIPE_MIN_PX = 20f

    /**
     * 判定纵向滑动时，纵向位移相对横向位移的倍数。
     */
    const val VERTICAL_DOMINANCE = 1.5f

    /** 菜单面板上滑触发阈值 = 小标点阈值 × 该倍数 */
    const val MENU_SLIDE_UP_MULTIPLIER = 2f

    /** 浮动键盘移动条判定「拖动了」的最小位移（dp） */
    const val MOVE_BAR_THRESHOLD_DP = 10f

    /**
     * 长按弹层拖动选中时，每滑动多少「一格」切换一个候选。
     *
     * 取值小于 1 表示放大灵敏度：0.2 就是手指滑过五分之一格宽即换一列。
     * 不能用整格判定——弹层只有 4~5 列且居中在按键上，靠边的按键（数字弹层挂在
     * 右下角「123」上）起手时手指离屏幕边缘只剩不到一格宽，按整格判定永远滑不到
     * 最右那两列（3/4、8/9 就是这样选不到的）。手指按住时的抖动只有几像素，
     * 0.2 格（1080 宽屏约 22px）仍然不会误切换。
     */
    const val POPUP_SLIDE_STEP_RATIO = 0.2f

    /** 长按触发弹层的超时，跟随用户设置 */
    val longPressTimeoutMs: Long
        get() = AppPrefs.getInstance().keyboardSetting.longPressTimeout.getValue().toLong()

    /**
     * 上滑触发小标点上屏的距离阈值。
     */
    val symbolSlideUpThreshold: Float
        get() {
            val base = Environment.instance.heightForCandidatesArea
            val divisor = when (AppPrefs.getInstance().keyboardSetting.symbolSlideUpMod.getValue()) {
                KeyboardSymbolSlideUpMod.SHORT -> 3f
                KeyboardSymbolSlideUpMod.MEDIUM -> 2f
                else -> 1f
            }
            return base / divisor
        }

    /** 上滑唤起菜单面板的距离阈值 */
    val menuSlideUpThreshold: Float
        get() = symbolSlideUpThreshold * MENU_SLIDE_UP_MULTIPLIER

    /** 空格/0 键左右滑动移动光标的步进阈值 */
    val spaceSwipeSpeed: Float
        get() = AppPrefs.getInstance().keyboardSetting.spaceSwipeMoveCursorSpeed.getValue().toFloat()

    /** 是否允许上滑小标点（跟随「键盘显示符号」开关） */
    val symbolSlideUpEnabled: Boolean
        get() = AppPrefs.getInstance().keyboardSetting.keyboardSymbol.getValue()

    /** 是否允许空格滑动移动光标 */
    val spaceSwipeMoveCursor: Boolean
        get() = AppPrefs.getInstance().keyboardSetting.spaceSwipeMoveCursor.getValue()

    /** 是否显示按键气泡 */
    val balloonEnabled: Boolean
        get() = AppPrefs.getInstance().keyboardSetting.keyboardBalloonShow.getValue()
}
