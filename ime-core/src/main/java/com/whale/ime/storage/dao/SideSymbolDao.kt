package com.whale.ime.storage.dao

import androidx.room.Dao
import androidx.room.Query
import com.whale.ime.storage.BaseDao
import com.whale.ime.storage.entity.SideSymbol

@Dao
interface SideSymbolDao : BaseDao<SideSymbol> {
    /** 按插入顺序返回（rowid 升序 = 侧边栏自上而下的顺序） */
    @Query("select * from side_symbol where type = 'number' order by rowid")
    fun getAllSideSymbolNumber(): List<SideSymbol>

    @Query("select * from side_symbol where type = 'pinyin' order by rowid")
    fun getAllSideSymbolPinyin(): List<SideSymbol>

    @Query("delete from side_symbol where type = :type")
    fun deleteAll(type: String = "pinyin")
}
