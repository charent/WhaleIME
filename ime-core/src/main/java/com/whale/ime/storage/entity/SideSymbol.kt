package com.whale.ime.storage.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * 侧边栏符号。
 *
 * 显示的就是上屏的（一个符号只有一个值），按 rowid 即插入顺序自上而下排列。
 */
@Serializable
@Entity(tableName = "side_symbol")
data class SideSymbol(
    @PrimaryKey
    @ColumnInfo(name = "symbol")
    var symbol: String,
    @ColumnInfo(name = "type")
    val type: String = "pinyin",
)
