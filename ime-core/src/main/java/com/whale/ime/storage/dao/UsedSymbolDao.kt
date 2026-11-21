package com.whale.ime.storage.dao

import androidx.room.Dao
import androidx.room.Query
import com.whale.ime.storage.BaseDao
import com.whale.ime.storage.entity.UsedSymbol

@Dao
interface UsedSymbolDao : BaseDao<UsedSymbol> {
    @Query("select * from usedSymbol where type = 'symbol' ORDER BY time DESC")
    fun getAllUsedSymbol(): List<UsedSymbol>

    @Query("select * from usedSymbol where type = 'emoji' ORDER BY time DESC")
    fun getAllSymbolEmoji(): List<UsedSymbol>

    @Query("select * from usedSymbol where type = 'kaomoji' ORDER BY time DESC")
    fun getAllSymbolKaomoji(): List<UsedSymbol>

    @Query("SELECT COUNT(*) FROM usedSymbol where type = :type")
    fun getCount(type: String): Int

    @Query("DELETE FROM usedSymbol WHERE symbol IN ( SELECT symbol FROM usedSymbol WHERE type = :type ORDER BY time ASC LIMIT :overflow)")
    fun deleteOldest(type: String, overflow: Int)

    @Query("delete from usedSymbol")
    fun deleteAll()
}
