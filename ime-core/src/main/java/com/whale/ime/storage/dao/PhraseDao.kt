package com.whale.ime.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.whale.ime.storage.BaseDao
import com.whale.ime.storage.entity.Phrase

@Dao
interface PhraseDao : BaseDao<Phrase> {

    /**
     * 种子补种专用：内容已存在就跳过。
     *
     * 不能用 [BaseDao.insertAll]（REPLACE）：那会把用户改过的快捷码、置顶状态整行覆盖掉。
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertIgnore(phrases: List<Phrase>)

    @Query("select * from phrase ORDER BY isKeep DESC, time DESC")
    fun getAll(): List<Phrase>

    /** 分页查询，与剪贴板列表共用同一套分页加载逻辑 */
    @Query("select * from phrase ORDER BY isKeep DESC, time DESC LIMIT :limit OFFSET :offset")
    fun getPage(limit: Int, offset: Int): List<Phrase>

    @Query("select * from phrase  where qwerty = :index or t9 = :index ORDER BY isKeep DESC, time DESC")
    fun query(index: String): List<Phrase>

    @Query("delete from phrase where content = :content")
    fun deleteByContent(content: String)

    @Query("select * from phrase where content = :content")
    fun queryByContent(content: String): Phrase

    @Query("select count(*) from phrase")
    fun count(): Int

    @Query("delete from phrase")
    fun deleteAll()
}
