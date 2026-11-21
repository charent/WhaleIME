package com.whale.ime.storage.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import kotlinx.serialization.Serializable

/**
 * 用户词库词条
 *
 * 与常用语（[Phrase]）分开存储：常用语是整句模板，会出现在键盘「常用语」面板；
 * 用户词是"词"，在编码命中（原样编码，或该词读音的任意合法简拼/全拼形态，见
 * `UserWordManager.match`）时注入候选首位，且可能由上万的自动学习结果组成。
 *
 * [Serializable] 供备份（`UserDataBackup`）以 jsonl 形式导出/恢复，与 Room 注解互不影响。
 */
@Serializable
@Entity(
    tableName = "user_word",
    primaryKeys = ["word", "code"],
    indices = [Index("code"), Index("source"), Index("updatedAt")]
)
data class UserWord(
    @ColumnInfo(name = "word")
    var word: String,
    /** 规范化编码：小写字母或数字，不含分隔符 */
    @ColumnInfo(name = "code")
    var code: String,
    /** 产生该编码的 Rime 方案 id，仅用于展示与统计 */
    @ColumnInfo(name = "schema")
    var schema: String = "",
    @ColumnInfo(name = "source")
    var source: Int = SOURCE_MANUAL,
    /** 命中次数（自动累加） */
    @ColumnInfo(name = "hits")
    var hits: Int = 1,
    /** 人工权重，越大越靠前 */
    @ColumnInfo(name = "weight")
    var weight: Int = 0,
    @ColumnInfo(name = "enabled")
    var enabled: Boolean = true,
    @ColumnInfo(name = "createdAt")
    var createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updatedAt")
    var updatedAt: Long = System.currentTimeMillis(),
) {
    companion object {
        const val SOURCE_MANUAL = 0
        const val SOURCE_LEARNED = 1

        /** 候选注入时的排序：人工权重 > 命中次数 > 更新时间 */
        val RANK_COMPARATOR: Comparator<UserWord> =
            compareByDescending<UserWord> { it.weight }
                .thenByDescending { it.hits }
                .thenByDescending { it.updatedAt }
    }
}
