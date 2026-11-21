package com.whale.ime.storage.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "clipboard")
data class Clipboard(
    @PrimaryKey
    @ColumnInfo(name = "content")
    var content: String,
    @ColumnInfo(name = "isKeep")
    var isKeep: Int = 0,
    @ColumnInfo(name = "time")
    val time: Long = System.currentTimeMillis(),
)