package com.whale.ime.storage.dao

import androidx.room.Dao
import androidx.room.Query
import com.whale.ime.storage.BaseDao
import com.whale.ime.storage.entity.Clipboard

@Dao
interface ClipboardDao : BaseDao<Clipboard> {

    @Query("select * from clipboard ORDER BY isKeep DESC, time DESC")
    fun getAll(): List<Clipboard>

    /**
     * 分页查询：剪贴板最多保留 1000 条、单条正文最长 65535 字符，
     * 列表界面按页加载，避免一次性把全部正文读进内存。
     */
    @Query("select * from clipboard ORDER BY isKeep DESC, time DESC LIMIT :limit OFFSET :offset")
    fun getPage(limit: Int, offset: Int): List<Clipboard>

    @Query("delete from clipboard where content = :content")
    fun deleteByContent(content: String)

    @Query("delete from clipboard")
    fun deleteAll()

    @Query("SELECT COUNT(*) FROM clipboard")
    fun getCount(): Int

    @Query("DELETE FROM clipboard WHERE content IN ( SELECT content FROM clipboard ORDER BY time ASC LIMIT :overflow)")
    fun deleteOldest(overflow: Int)

    @Query("DELETE FROM clipboard WHERE isKeep = 0")
    fun deleteAllExceptKeep()

    /**
     * 内容自动失效：删除已过期的记录。
     *
     * 只删未置顶的行——置顶是用户的显式意图，到期只清了系统剪贴板，不能顺手把置顶记录也删掉。
     * [expireBefore] = 现在 - 保留时长，与 `time` 同一时间基准（`System.currentTimeMillis()`）。
     * [since] = 关闭「保留到剪贴板历史」的时刻：更早的记录不追溯删除，
     * 关开关这个动作本身不能把历史清空。
     */
    @Query("DELETE FROM clipboard WHERE isKeep = 0 AND time <= :expireBefore AND time >= :since")
    fun deleteExpired(expireBefore: Long, since: Long)

    /**
     * 系统剪贴板里这条内容是否是我们记录过、且已经过期的那条。
     *
     * `content` 是主键（重复复制走 REPLACE，`time` 会刷新），所以这就是一次主键命中。
     */
    @Query("SELECT COUNT(*) FROM clipboard WHERE content = :content AND time <= :expireBefore")
    fun countExpired(content: String, expireBefore: Long): Int

    /** 这条内容是否是我们记录过的（清空历史前判断系统剪贴板要不要一起清） */
    @Query("SELECT COUNT(*) FROM clipboard WHERE content = :content")
    fun countByContent(content: String): Int

    /** 最老的可删记录（置顶行与 [since] 之前的记录都不算）：不保留历史时的下一个到期边界 */
    @Query("SELECT MIN(time) FROM clipboard WHERE isKeep = 0 AND time >= :since")
    fun oldestDeletableTime(since: Long): Long?

    /** 最新记录：系统剪贴板里放的就是它，决定清空系统剪贴板的时刻 */
    @Query("SELECT MAX(time) FROM clipboard")
    fun newestTime(): Long?
}
