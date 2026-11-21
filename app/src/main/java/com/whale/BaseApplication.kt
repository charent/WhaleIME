package com.whale

import android.app.Application
import com.whale.ime.boot.Launcher
import com.whale.ime.ui.backup.AutoBackupScheduler
import com.whale.ime.boot.CrashHandler

/**
 * Applicaiton入口
 * @since 2019/6/18
 */
class BaseApplication : Application(){
    override fun onCreate() {
        super.onCreate()
        CrashHandler.instance.init(this)
        Launcher.instance.initData(baseContext)
        // 系统可能在清理应用数据/被优化时丢掉 JobScheduler 里的持久任务：每次启动重建一次。
        // 这一步依赖 AppPrefs（Launcher.initData 已初始化），且 AutoBackupJobService 属于 UI 层，
        // 所以放在宿主而不是 :ime-core 的 Launcher 里。
        AutoBackupScheduler.reschedule(this)
    }
}
