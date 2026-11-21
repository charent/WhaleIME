package com.whale.ime.compose.settings.ui

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 设置界面尺寸令牌。
 *
 * 所有设置页只能从这里取间距/字号，避免各页面各写一套 magic number
 * （旧实现里 20dp/16sp/56dp 散落在每个文件里，改一次要全库搜）。
 */
object SettingsDims {
    /** 页面左右留白 */
    val PageHorizontal = 16.dp

    /** 宽屏下内容区最大宽度：超过后居中留白，避免设置行被拉得过长 */
    val ContentMaxWidth = 720.dp

    /** 列表顶部/底部留白（不含系统栏 inset） */
    val PageTop = 4.dp
    val PageBottom = 40.dp

    /** 分组卡片圆角 */
    val CardCorner = 24.dp

    /** 卡片内容上下内缩，让首尾行不贴边 */
    val CardInnerVertical = 4.dp

    /** 行左右内边距 */
    val RowHorizontal = 16.dp
    val RowVertical = 10.dp
    val RowMinHeight = 56.dp

    /** 分组标题与上/下卡片之间的间距 */
    val SectionTitleTop = 20.dp
    val SectionTitleBottom = 8.dp

    /** 前导图标（Home 入口卡） */
    val IconBox = 40.dp
    val IconSize = 22.dp

    val TitleSize = 15.sp
    val SummarySize = 12.sp
    val SummaryLineHeight = 17.sp
}
