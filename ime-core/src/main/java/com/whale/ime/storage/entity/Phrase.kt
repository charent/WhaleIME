package com.whale.ime.storage.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * 常用语。
 *
 * `t9` / `qwerty` 两列是「首字母快捷码」，在键盘「常用语」面板里展示/编辑；
 * 打开「快捷码上屏常用语」开关后，编码精确命中快捷码时整句会作为第一候选注入
 * （默认关闭，见 `PhraseQuickCodeManager`）。两列保留索引（见 MIGRATION_11_12）。
 */
@Serializable
@Entity(
    tableName = "phrase",
    indices = [Index(value = ["t9"]), Index(value = ["qwerty"])],
)
data class Phrase(
    @PrimaryKey
    @ColumnInfo(name = "content")
    var content: String,
    @ColumnInfo(name = "isKeep")
    var isKeep: Int = 0,
    @ColumnInfo(name = "t9")
    var t9: String,
    @ColumnInfo(name = "qwerty")
    var qwerty: String,
    @ColumnInfo(name = "time")
    val time: Long = System.currentTimeMillis(),
)
