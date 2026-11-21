package com.whale.ime.ui.backup

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.whale.ime.backup.BackupCategory
import com.whale.ime.backup.UserDataBackup
import com.whale.ime.preference.AppPrefs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 自动备份：把用户数据写进用户授权的 SAF 目录，由 [AutoBackupJobService] 周期触发。
 *
 * 内容固定为「默认勾选项」（用户词典 + 自动学习词 + 剪贴板历史）——和备份页上
 * 默认勾选的分类一致；设置项、主题、Rime userdb 这些要么机器相关，要么体积大，
 * 让用户在手动导出时按需选择。
 *
 * 目录用 `ACTION_OPEN_DOCUMENT_TREE` 授权并 `takePersistableUriPermission`，
 * 因此进程重启、设备重启后仍然可写；不需要任何存储权限。
 */
object AutoBackup {

    /** 保留最近几份自动备份 */
    const val KEEP_COUNT = 5

    private const val FILE_PREFIX = "whaleIme_auto_"
    private const val MIME_TYPE = "application/zip"

    fun isConfigured(): Boolean {
        val internal = AppPrefs.getInstance().internal
        return internal.autoBackupEnabled.getValue() && internal.autoBackupTreeUri.getValue().isNotEmpty()
    }

    /** 立即执行一次（阻塞，只在后台线程调用） */
    fun runNow(context: Context): Result<Uri> = runCatching {
        val internal = AppPrefs.getInstance().internal
        val tree = internal.autoBackupTreeUri.getValue().takeIf { it.isNotEmpty() }
            ?: error("auto backup dir not configured")
        val uri = Uri.parse(tree)
        val created = createDocument(context, uri, fileName())
            ?: error("createDocument failed: $uri")
        context.contentResolver.openOutputStream(created)
            ?.use { UserDataBackup.export(it, BackupCategory.DefaultSelection).getOrThrow() }
            ?: error("openOutputStream failed: $created")
        internal.autoBackupLastTime.setValue(System.currentTimeMillis())
        runCatching { prune(context, uri) }
        created
    }

    private fun fileName(): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())
        return "$FILE_PREFIX$stamp.zip"
    }

    private fun createDocument(context: Context, treeUri: Uri, name: String): Uri? {
        val parent = DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        )
        return DocumentsContract.createDocument(context.contentResolver, parent, MIME_TYPE, name)
    }

    /** 只保留最新的 [KEEP_COUNT] 份自动备份（文件名前缀 + 时间戳，字典序即时间序） */
    private fun prune(context: Context, treeUri: Uri) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        )
        val docs = mutableListOf<Pair<String, String>>() // documentId to displayName
        context.contentResolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(1) ?: continue
                if (!name.startsWith(FILE_PREFIX)) continue
                docs += cursor.getString(0) to name
            }
        }
        docs.sortedByDescending { it.second }
            .drop(KEEP_COUNT)
            .forEach { (id, _) ->
                runCatching {
                    DocumentsContract.deleteDocument(
                        context.contentResolver,
                        DocumentsContract.buildDocumentUriUsingTree(treeUri, id),
                    )
                }
            }
    }
}

/** 按当前设置注册/取消周期任务；设置一变就调用（见 `SettingsEffects`） */
object AutoBackupScheduler {

    private const val JOB_ID = 0x5748

    fun reschedule(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        scheduler.cancel(JOB_ID)
        if (!AutoBackup.isConfigured()) return
        val days = AppPrefs.getInstance().internal.autoBackupIntervalDays.getValue().coerceAtLeast(1)
        val intervalMillis = days.toLong() * 24L * 60L * 60L * 1000L
        val job = JobInfo.Builder(JOB_ID, ComponentName(context, AutoBackupJobService::class.java))
            .setPeriodic(intervalMillis)
            // 设备重启后继续调度（需要 RECEIVE_BOOT_COMPLETED，已在 manifest 声明）
            .setPersisted(true)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)
            .build()
        runCatching { scheduler.schedule(job) }
    }
}
