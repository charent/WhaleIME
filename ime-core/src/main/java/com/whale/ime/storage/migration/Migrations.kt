package com.whale.ime.storage.migration

import androidx.room.migration.Migration

/**
 * Room 迁移登记处。**新增迁移只改这个文件 + `WhaleDatabase` 的 `@Database(version = ...)`。**
 *
 * 当前库版本为 **1**（唯一基线），没有任何历史迁移：`ALL` 为空数组，
 * 旧版本的库文件会被直接重建（见 `WhaleDatabase` 的 `fallbackToDestructiveMigration`）。
 *
 * 下一次改表结构时：
 *
 * 1. 在这里加一段迁移，版本号必须首尾相接：
 *    ```kotlin
 *    private val MIGRATION_1_2 = object : Migration(1, 2) {
 *        override fun migrate(db: SupportSQLiteDatabase) {
 *            db.execSQL("ALTER TABLE ...")
 *        }
 *    }
 *    ```
 * 2. 追加到 [ALL] 末尾（**顺序即版本链，不要重排、不要插队**）；
 * 3. 把 `WhaleDatabase` 的 `@Database(version = 1)` 同步改成 2；
 * 4. 实体结构以 `storage/entity` 为准，表名全部显式声明，改类名/包名不影响磁盘结构。
 *
 * 迁移里需要默认数据（宫格顺序、侧边栏符号等）时从 `WhaleDatabase` 取，
 * 保持「默认数据只有一处定义」。
 */
object Migrations {

    /** 按声明顺序应用；顺序即版本链，不要重排 */
    val ALL: Array<Migration> = emptyArray()
}
