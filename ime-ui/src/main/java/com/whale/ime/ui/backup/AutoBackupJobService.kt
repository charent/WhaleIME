package com.whale.ime.ui.backup

import android.app.job.JobParameters
import android.app.job.JobService
import com.whale.ime.boot.Launcher
import java.util.concurrent.Executors

/**
 * 周期备份任务。由 [AutoBackupScheduler] 注册，失败时交给系统退避重试。
 *
 * 备份是纯本地 IO（写 SAF 目录），不需要网络；执行完 `jobFinished` 时若 `wantsReschedule`
 * 为 true，系统会按退避策略重新调度。
 */
class AutoBackupJobService : JobService() {

    private val executor = Executors.newSingleThreadExecutor()

    override fun onStartJob(params: JobParameters): Boolean {
        executor.execute {
            val ok = runCatching { AutoBackup.runNow(Launcher.instance.context) }.isSuccess
            jobFinished(params, !ok)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true
}
