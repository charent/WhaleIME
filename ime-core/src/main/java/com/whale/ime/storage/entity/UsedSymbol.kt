package com.whale.ime.storage.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "usedSymbol")
data class UsedSymbol(
    @PrimaryKey
    @ColumnInfo(name = "symbol")
    var symbol: String,
    @ColumnInfo(name = "type")
    val type: String = "symbol",  // symbol、emoji、kaomoji
    @ColumnInfo(name = "time")
    val time: Long = System.currentTimeMillis(),
)