package com.whale.ime.keyboard.metrics

import android.content.Context
import com.whale.ime.boot.Launcher
import com.whale.ime.preference.AppPrefs
import kotlin.math.max
import kotlin.math.min
import com.whale.ime.util.android.Density

/**
 * Global environment configurations for showing soft keyboard and candidate
 * view. All original dimension values are defined in float, and the real size
 * is calculated from the float values of and screen size. In this way, this
 * input method can work even when screen size is changed.
 * 该类保存布局的一些尺寸。比如：屏幕的宽度、屏幕的高度
 * 、按键的高度、候选词区域的高度、按键气泡宽度比按键宽度大的差值、按键气泡高度比按键高度大的差值、正常按键中文本的大小
 * 、功能按键中文本的大小、正常按键气泡中文本的大小、功能按键气泡中文本的大小。
 */
class Environment private constructor() {
    var systemNavbarWindowsBottom = 0 // 导航栏高度
    var mScreenWidth = 0 // 屏幕的宽度
    var mScreenHeight = 0 // 屏幕的高度
    var inputAreaHeight = 0 // 键盘区域高度
    var inputAreaWidth = 0 // 键盘区域宽度
    var skbWidth = 0 // 键盘按键区域、候选词区域宽度
        private set
    var skbHeight = 0 // 键盘按键区高度
        private set
    var holderWidth = 0 // 单手模式下键盘占位区域宽度
        private set
    var heightForCandidatesArea = 0 // 整条候选带（拼音条 + 候选行）的高度，手势阈值与物理键盘候选栏沿用
    var heightForcomposing = 0 // 候选词拼音区域的高度
    var heightForCandidates = 0 // 候选行（胶囊那一行）的高度
    var candidatesBottomGap = 0 // 候选词栏与键盘之间的间隔（避免选词误触键盘）
    var composingOverhang = 0 // 拼音气泡凸出条高度：候选栏上方的透明带，宿主内容会延伸到条下
    var heightForFullDisplayBar = 0 // 智能导航栏高度
    var heightForKeyboardMove = 0 // 悬浮键盘移动条高度
    var keyTextSize = 0 // 正常按键中文本的大小
    var keyTextSmallSize = 0 // 正常按键中文本的大小,小值
    var keyFunctionTextSize = 0 // 功能键说明文字（分词 / 符号 / 中·EN / 123）的大小
    var candidateTextSize = 0f // 候选词字体大小
    var composingTextSize = 0f // 候选词字体大小
    var isLandscape = false //键盘是否横屏
    var keyXMargin = 0 //键盘按键水平间距
    var keyYMargin = 0 //键盘按键垂直间距
    // 按键 + 候选词+拼音总高度，值是相对于竖屏宽度，横屏高度。
    private var keyboardHeightRatio = 0f
    /**
     * 键盘高度比例的换算基准（px）：[skbHeight] = 本值 × 比例。
     * 编辑键盘浮层要靠它把拖动位移换算回比例，所以必须与 initData 用的是同一个数值
     * （悬浮模式取缩小后的基准，不能直接用 [mScreenHeight]）。
     */
    var screenHeightVertical = 0
        private set

    init {
        initData()
    }

    fun initData(context: Context = Launcher.instance.context) {
        val resources = context.resources
        val dm = resources.displayMetrics
        mScreenWidth = dm.widthPixels
        mScreenHeight = dm.heightPixels
        isLandscape = mScreenHeight <= mScreenWidth
        var screenWidthVertical = mScreenWidth
        screenHeightVertical = mScreenHeight
        if(keyboardModeFloat){
            screenWidthVertical = (min(dm.widthPixels, dm.heightPixels) * 3f / 4).toInt()
            screenHeightVertical = (max(dm.widthPixels, dm.heightPixels) * 3f / 4).toInt()
        }
        val oneHandedMod = AppPrefs.getInstance().keyboardSetting.oneHandedModSwitch.getValue()
        // 键盘占位宽度（用于单手模式），值是相对于竖屏宽度，横屏高度。
        holderWidth = if (oneHandedMod) (screenWidthVertical * 0.2f).toInt() else 0
        skbWidth = screenWidthVertical - holderWidth
        inputAreaWidth = skbWidth
        var candidatesHeightRatio = AppPrefs.getInstance().internal.candidatesHeightRatio.getValue()
        // 按键 + 后续高度，值是相对于竖屏宽度，横屏高度。
        if(isLandscape && !keyboardModeFloat){
            inputAreaWidth = mScreenWidth
            skbWidth = (skbWidth * 0.7).toInt()
            keyboardHeightRatio = AppPrefs.getInstance().internal.keyboardHeightRatioLandscape.getValue()
            candidatesHeightRatio = AppPrefs.getInstance().internal.candidatesHeightRatioLandscape.getValue()
        } else {
            keyboardHeightRatio = AppPrefs.getInstance().keyboardSetting.keyboardHeightRatio.getValue()
        }
        skbHeight = (screenHeightVertical * keyboardHeightRatio).toInt()
        val keyboardSetting = AppPrefs.getInstance().keyboardSetting
        heightForcomposing = (screenHeightVertical * candidatesHeightRatio *
                keyboardSetting.candidateTextSize.getValue() / 100f).toInt()
        heightForCandidates = (heightForcomposing * 1.9).toInt()
        heightForCandidatesArea = (heightForcomposing * 2.9).toInt()
        candidatesBottomGap = Density.dip2px(keyboardSetting.candidatesBottomGap.getValue())
        // 拼音气泡走了「凸出到候选栏上方」的画法：原来栏内那行拼音不再占栏高，
        // 而是变成窗口顶部一条透明带（气泡带）。带高 = 原拼音行 + 少量留白，
        // 保证气泡（文字 + 上下内边距）能完整落在带内、不被窗口顶边裁掉。
        composingOverhang = heightForcomposing + Density.dip2px(4)
        composingTextSize = Density.px2sp (heightForcomposing)
        // 候选字号独立于栏高缩放（candidateFontScale），但要夹在候选行高以内：
        // 字一旦高过胶囊所在的那一行，胶囊会被裁切、相邻候选互相压字。
        val candidateFontScale = keyboardSetting.candidateFontScale.getValue() / 100f
        // sp → px 的换算含系统字体缩放（scaledDensity = density * fontScale）
        val maxCandidateTextSize = heightForCandidates * CANDIDATE_TEXT_MAX_OF_BAR /
                dm.scaledDensity.coerceAtLeast(0.1f)
        candidateTextSize = min(
            Density.px2sp(heightForCandidates) * candidateFontScale,
            maxCandidateTextSize
        )
        // 全面屏优化条：取「键盘高度的 1/4」与 44dp 的较小值。
        // 之前只按 1/4 缩放，30% 键盘时就有 69dp（181px）的空白条；键盘拉高到 45%~60% 后
        // 会涨到 100dp 以上，看起来就是「键盘被抬高、下方露出大片空白」。
        heightForFullDisplayBar = min((skbHeight * 0.25f).toInt(), Density.dip2px(44))
        heightForKeyboardMove = (heightForCandidatesArea * 0.2f).toInt()

        keyXMargin = (keyboardSetting.keyXMargin.getValue() / 1000f * skbWidth).toInt()
        keyYMargin = (keyboardSetting.keyYMargin.getValue() / 1000f * skbHeight).toInt()

        // 键帽净高：四行等分（SoftKey.heightF 默认 0.25）再减掉上下间距。键帽文字一旦超过它，
        // 绘制时会被 constraints 裁字、和上划小符号叠在一起，所以三类字号都按它夹一个上限。
        val keyCapHeight = (skbHeight * KEY_CAP_HEIGHT_RATIO - 2f * keyYMargin)
            .coerceAtLeast(skbHeight * 0.05f)
        val keyboardFontSizeRatio = keyboardSetting.keyboardFontSize.getValue() / 100f
        // 键帽字号：字母 0.077、上方小标点 0.052（标点绘制时还会乘 KeyGrid 的 SYMBOL_SIZE_RATIO）、
        // 功能键说明文字取字母的 0.8
        keyTextSize = min(
            skbHeight * 0.077f * keyboardFontSizeRatio,
            keyCapHeight * KEY_TEXT_MAX_OF_CAP
        ).toInt()
        keyTextSmallSize = min(
            skbHeight * 0.052f * keyboardFontSizeRatio *
                    keyboardSetting.keySymbolFontScale.getValue() / 100f,
            keyCapHeight * SYMBOL_TEXT_MAX_OF_CAP
        ).toInt()
        keyFunctionTextSize = min(
            skbHeight * 0.077f * FUNCTION_TEXT_RATIO * keyboardFontSizeRatio *
                    keyboardSetting.keyFunctionFontScale.getValue() / 100f,
            keyCapHeight * FUNCTION_TEXT_MAX_OF_CAP
        ).toInt()
        inputAreaHeight = skbHeight + candidatesBarHeight
    }

    /**
     * 候选栏总高 = 拼音气泡条 + 候选行 + 与键盘的间距。
     *
     * 「间距」不再是一个独立参与布局/点击的节点，而是候选栏自己的内边距：
     * 栏高、点击命中、工具栏垂直居中因此只有一个来源。
     * 注意 [heightForCandidatesArea]（整条候选带）仍是「拼音气泡条 + 候选行 + 间距」的总高，供上滑阈值与物理键盘候选栏使用。
     */
    val candidatesBarHeight: Int
        get() = heightForCandidates + candidatesBottomGap + composingOverhang

    /**
     * 常用语编辑弹层的高度（px）。
     *
     * 编辑常用语时弹出一张浮在候选栏**上方**的卡片（整句正文换行显示 + 快捷码）：
     * - 非悬浮模式：IME 窗口按这个值临时加高，同时 `onComputeInsets` 把 contentTopInsets
     *   也加回同样的值，宿主布局完全不变，弹层像对话框一样盖在宿主内容上；
     * - 悬浮模式：窗口本来就是全屏，浮层随键盘卡片一起向上长。
     *
     * 候选带的高度、位置都不受它影响（输入拼音时的气泡与候选照常可见）。
     */
    val phraseEditorPopupHeight: Int
        get() = Density.dip2px(96)

    var keyBoardHeightRatio: Float
        /**
         * 获取键盘高度比例：相对于屏幕宽度
         */
        get() = keyboardHeightRatio
        /**
         * 设置键盘高度比例：相对于屏幕宽度
         */
        set(keyBoardHeightRatio) {
            keyboardHeightRatio = keyBoardHeightRatio
            if(isLandscape) AppPrefs.getInstance().internal.keyboardHeightRatioLandscape.setValue(keyBoardHeightRatio)
            else AppPrefs.getInstance().keyboardSetting.keyboardHeightRatio.setValue(keyBoardHeightRatio)
        }

    /** 键盘与屏幕底部之间的留白（px）：设置里按 dp 存，这里换算成像素参与布局 */
    val keyboardBottomGapPx: Int
        get() = Density.dip2px(AppPrefs.getInstance().keyboardSetting.keyboardBottomGap.getValue())

    var keyboardModeFloat:Boolean  // 悬浮键盘模式
        get() = if (isLandscape)AppPrefs.getInstance().internal.keyboardModeFloatLandscape.getValue() else AppPrefs.getInstance().internal.keyboardModeFloat.getValue()
        set(isFloatMode) {
            if(isLandscape) AppPrefs.getInstance().internal.keyboardModeFloatLandscape.setValue(isFloatMode)
            else AppPrefs.getInstance().internal.keyboardModeFloat.setValue(isFloatMode)
        }

    val skbAreaHeight:Int
        get() = instance.inputAreaHeight + if(!instance.keyboardModeFloat) {
            instance.keyboardBottomGapPx + instance.systemNavbarWindowsBottom +
            // 全面屏优化条只在竖屏显示，横屏若计入会让窗口比内容高
            if(AppPrefs.getInstance().internal.fullDisplayKeyboardEnable.getValue() && !instance.isLandscape)
                instance.heightForFullDisplayBar else 0
        } else instance.heightForKeyboardMove

    val leftMarginWidth:Int
        get() = if(!instance.keyboardModeFloat) (instance.inputAreaWidth - instance.skbWidth)/2 else 0

    companion object {
        /** 键盘高度比例（按键区 / [screenHeightVertical]）的可调范围：更小会压掉键帽文字 */
        const val KEYBOARD_HEIGHT_RATIO_MIN = 0.15f
        const val KEYBOARD_HEIGHT_RATIO_MAX = 0.6f
        /** 键盘与屏幕底部留白的可调上限（dp）：再大键盘会被推到屏幕中间 */
        const val KEYBOARD_BOTTOM_GAP_MAX_DP = 120

        /** 键帽行高占比（SoftKey.heightF 的默认值） */
        private const val KEY_CAP_HEIGHT_RATIO = 0.25f
        /** 功能键说明文字相对字母字号的比例 */
        private const val FUNCTION_TEXT_RATIO = 0.8f
        // 字号相对键帽净高的上限：字母 72%、功能键 60%、小符号 42%。
        // 再大就会超出键帽（裁字、和上划符号叠字），所以放大设置到达上限后不再生效。
        private const val KEY_TEXT_MAX_OF_CAP = 0.72f
        private const val FUNCTION_TEXT_MAX_OF_CAP = 0.6f
        private const val SYMBOL_TEXT_MAX_OF_CAP = 0.42f
        /** 候选字号相对候选行高的上限 */
        private const val CANDIDATE_TEXT_MAX_OF_BAR = 0.92f

        private var mInstance: Environment? = null
        @JvmStatic
		val instance: Environment
            get() {
                if (null == mInstance) {
                    mInstance = Environment()
                }
                return mInstance!!
            }
    }
}
