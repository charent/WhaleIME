package com.whale.ime.storage.dao

import androidx.room.Dao
import androidx.room.Query
import com.whale.ime.storage.BaseDao
import com.whale.ime.storage.entity.SkbFun

@Dao
interface SkbFunDao : BaseDao<SkbFun> {
    @Query("select * from skbfun  where isKeep = 0 ORDER BY position ASC")
    fun getAllMenu(): List<SkbFun>

    @Query("select * from skbfun  where isKeep = 1 ORDER BY position ASC")
    fun getALlBarMenu(): List<SkbFun>

    @Query("select * from skbfun where name = :name AND isKeep = 1")
    fun getBarMenu(name: String):SkbFun?

    /** 备份用：常驻工具栏 + 功能宫格全量导出 */
    @Query("select * from skbfun ORDER BY isKeep ASC, position ASC")
    fun getAll(): List<SkbFun>

    @Query("delete from skbfun")
    fun deleteAll()
}