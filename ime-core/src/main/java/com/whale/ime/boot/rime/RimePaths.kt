package com.whale.ime.boot.rime

import android.content.Context
import com.whale.ime.config.DictDataVersion
import java.io.File

/**
 * Rime 数据目录的三个位置，全部按调用方传入的 `context` 计算。
 *
 * 刻意不做顶层缓存：这些路径都依赖 `Context`，缓存成 `val`/`var` 会让「谁能先初始化」
 * 变成隐性约束（在 `Launcher.initData` 之前取值就会 NPE）。需要时按需计算即可。
 */
object RimePaths {

    /**
     * 共享词库目录按内置版本号隔离：升级词库 = 新建 rime-v<N+1>，上一版本目录在拷贝完成后删除。
     * 只读，供 librime 的 shared_data_dir / prebuilt_data_dir 使用，每次启动无需重拷、无重部署。
     */
    fun sharedDir(context: Context): File =
        File(context.getExternalFilesDir(null), "rime-v${DictDataVersion.CURRENT_RIME_DICT_DATA_VERSIOM}")

    /** librime 可写目录：userdb、部署产物 build/、installation.yaml 等，与共享词库分离 */
    fun userDir(context: Context): File = File(context.getExternalFilesDir("rime-user").toString())

    /**
     * `userDir/build/` 里的部署完成标记（由 `CorrectionSchema` 在重部署成功后写入）。
     *
     * 没有它的 build 目录 = 部署中途被打断：里面是半套 prism / 词表，运行时会盖掉共享目录的
     * 预编译产物，且 `CorrectionSchema` 只看目录存在与否不会重编，于是重启也不会自愈。
     * 见 `RimeDataInstaller.discardUnfinishedDeploy`。
     */
    const val DEPLOY_COMPLETED_MARK = ".deployed"

    /** 仅用于升级时迁出用户数据 */
    fun legacyDir(context: Context): File = File(context.getExternalFilesDir("rime").toString())
}
