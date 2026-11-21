package com.whale.ime.boot.rime

import android.content.Context
import android.util.Log
import com.whale.ime.config.DictDataVersion
import com.whale.ime.preference.AppPrefs
import java.io.File
import java.io.IOException

/**
 * 一次性把随包 Rime 数据落到设备（`assets/rime` → `rime-v<N>` / `rime-user`）。
 *
 * 共享词库按版本落到 `rime-v<N>`（只读、内容不变），用户可写数据在独立的 `rime-user`：
 * - 平时启动：两个目录都齐备，什么都不做（几个 `stat`），librime 只扫小体量的 user 目录。
 * - 升级词库：**新目录拷完并校验通过**之后才删旧版本目录，一次完成。
 * - 历史版本升级（shared/user 混在 `rime/`）：先迁出 userdb / 自定义 yaml 再拷贝。
 *
 * ## 为什么要「临时目录 + 校验 + 改名」
 *
 * `assets/rime` 约 180MB（62MB 词表 + 71MB 语法模型 + 41MB 笔顺 prism）。升级时旧目录和新目录
 * 会同时存在，设备空间不足 / 进程被杀都会让逐文件拷贝在**中途**抛 `IOException`。旧实现把异常
 * 吞在 `copyFile` 里继续往下走，最后照样写 `.copied` 标记 —— 于是「目录看起来装好了，其实词表是
 * 半截的」：引擎加载词典失败，只剩编码回显，表现为**能出拼音串、候选不全甚至没有候选**，而且
 * 重启 App 也不会修（标记在，`install()` 直接 no-op），只有清 App 数据才会重新拷贝。
 *
 * 现在：拷贝失败就扔掉临时目录、**不写标记**，下次启动 `isReady()` 判定不合格自动重来；拷贝成功
 * 也要先过 [REQUIRED_ARTIFACTS] 的体积校验，再整体改名替换。顺带把「先删旧版本目录再拷贝」提前，
 * 峰值磁盘占用从两份降到一份 —— 空间不足正是拷贝被截断的头号原因。
 */
class RimeDataInstaller(private val context: Context) {

    fun install() {
        cleanupPoisonedDeployOutput()
        val root = context.getExternalFilesDir(null)
        if (root == null) {
            // 外置存储没挂上（未解锁 / 被拔掉）：不要往相对路径上写垃圾，等下次启动
            Log.e(TAG, "external files dir unavailable, skip rime data install")
            return
        }
        val sharedDir = RimePaths.sharedDir(context)
        val userDir = RimePaths.userDir(context)
        val legacyDir = RimePaths.legacyDir(context)

        discardUnfinishedDeploy(userDir)
        // 上次拷贝残留（进程被杀 / 磁盘满）：半成品目录绝不能参与 isReady 判定
        deleteTempDirs(root)

        if (isReady(sharedDir)) return

        if (legacyDir.exists() && !userDir.exists()) migrateLegacyUserData(legacyDir, userDir)

        // 先删旧版本目录再拷贝：峰值从「两份 180MB」降到「一份」。这一步之后拷贝失败也不会更糟：
        // 标记没写，下次启动照样重来。
        deleteStaleVersionDirs(root, sharedDir)

        val staging = File(root, sharedDir.name + TEMP_SUFFIX)
        try {
            staging.deleteRecursively()
            copyAssetDir(ASSET_ROOT, staging)
            verify(staging)
            if (sharedDir.exists() && !sharedDir.deleteRecursively()) {
                throw IOException("can't remove ${sharedDir.path}")
            }
            if (!staging.renameTo(sharedDir)) {
                throw IOException("can't rename ${staging.path} -> ${sharedDir.path}")
            }
            // 标记最后写：只有内容校验通过的目录才算「装好了」，之后每次启动只做几次 stat
            File(sharedDir, MARKER).writeText(DictDataVersion.CURRENT_RIME_DICT_DATA_VERSIOM.toString())
            AppPrefs.getInstance().internal.dataDictVersion.setValue(DictDataVersion.CURRENT_RIME_DICT_DATA_VERSIOM)
            // 词库换了，旧的部署产物（prism 记的是旧词表 checksum）必须一并清掉，
            // 否则运行时优先加载用户目录里的旧 prism，盖掉新词库的预编译产物。
            // 重新部署由 CorrectionSchema.syncFromPrefs() 在“产物缺失”时补做。
            File(userDir, USER_BUILD_DIR).deleteRecursively()
            legacyDir.deleteRecursively()
            Log.i(TAG, "rime data installed: ${sharedDir.name}")
        } catch (e: Exception) {
            // 不留半成品：临时目录清掉、标记不写，下次启动 isReady() 为 false 会重来
            Log.e(TAG, "install rime data failed, retry on next launch", e)
            runCatching { staging.deleteRecursively() }
            runCatching { if (!isReady(sharedDir)) sharedDir.deleteRecursively() }
        }
    }

    /** 目录存在、带版本标记、且关键产物体积达标（= 上次拷贝完整走完） */
    private fun isReady(sharedDir: File): Boolean {
        if (!File(sharedDir, MARKER).exists()) return false
        val broken = REQUIRED_ARTIFACTS.filter { (path, minBytes) ->
            File(sharedDir, path).length() < minBytes
        }
        if (broken.isEmpty()) return true
        Log.w(TAG, "${sharedDir.name} incomplete (${broken.joinToString { it.first }}), recopy")
        return false
    }

    /**
     * 一次性清理被设备端部署污染的产物目录 `rime-user/build/`（按版本号 gate，只跑一次）。
     *
     * 这个目录是 librime 部署的写入目标。本项目各方案只有预编译的 `*.prism.bin` /
     * `*.table.bin`（assets 没有词典源），设备端重编 prism 取不到音节表，会写出**空 prism**；
     * 运行时解析器优先读用户目录，空 prism 盖掉共享目录里的好 prism，现象是
     * 「拼音串正常显示、候选一个都没有」。删掉即回到只读共享目录的预编译产物。
     */
    private fun cleanupPoisonedDeployOutput() {
        val flag = AppPrefs.getInstance().internal.rimeUserBuildCleanupVersion
        if (flag.getValue() >= USER_BUILD_CLEANUP_VERSION) return
        val buildDir = File(RimePaths.userDir(context), USER_BUILD_DIR)
        if (buildDir.exists()) {
            val deleted = runCatching { buildDir.deleteRecursively() }
                .onFailure { Log.w(TAG, "wipe rime-user/build failed: $it") }
                .getOrDefault(false)
            Log.i(TAG, "wipe rime-user/build: deleted=$deleted")
        }
        flag.setValue(USER_BUILD_CLEANUP_VERSION)
    }

    /**
     * 上次设备端重部署（prism / 词表重编）中途被打断：`rime-user/build` 里只留下半套产物。
     *
     * 运行时 prism / 词表优先读用户目录，半套产物会盖掉共享目录的预编译件，现象就是
     * 「拼音串正常、候选残缺」；而 `CorrectionSchema` 只按「目录在不在」判断要不要重编，
     * 于是重启永远修不好，只有清 App 数据（整目录消失）才恢复。没完成标记就整目录丢弃，
     * 退回共享目录的预编译产物，随后的 `syncFromPrefs()` 会补一次干净的重部署。
     */
    private fun discardUnfinishedDeploy(userDir: File) {
        val buildDir = File(userDir, USER_BUILD_DIR)
        if (!buildDir.isDirectory) return
        if (File(buildDir, RimePaths.DEPLOY_COMPLETED_MARK).exists()) return
        val deleted = runCatching { buildDir.deleteRecursively() }.getOrDefault(false)
        Log.w(TAG, "discard unfinished rime deploy (no completion mark): deleted=$deleted")
    }

    /** 历史目录是 shared/user 混用的，只迁出属于用户的数据：userdb（自学习词库）与 *.custom.yaml */
    private fun migrateLegacyUserData(legacyDir: File, userDir: File) {
        legacyDir.listFiles { f -> f.isDirectory && f.name.endsWith(".userdb") }?.forEach { db ->
            db.copyRecursively(File(userDir, db.name), overwrite = true)
        }
        legacyDir.listFiles { f -> f.isFile && f.name.endsWith(".custom.yaml") }?.forEach { f ->
            f.copyTo(File(userDir, f.name), overwrite = true)
        }
    }

    /** 清理上一版本目录，回收 100MB+ 磁盘（必须在拷贝前做，否则升级时峰值翻倍） */
    private fun deleteStaleVersionDirs(root: File, current: File) {
        root.listFiles { f -> f.isDirectory && f.name.startsWith(VERSION_PREFIX) && f.name != current.name }
            ?.forEach { dir ->
                val deleted = runCatching { dir.deleteRecursively() }.getOrDefault(false)
                Log.i(TAG, "remove stale rime dir ${dir.name}: deleted=$deleted")
            }
    }

    /** 上次拷贝被中断留下的临时目录 */
    private fun deleteTempDirs(root: File) {
        root.listFiles { f -> f.isDirectory && f.name.endsWith(TEMP_SUFFIX) }
            ?.forEach { dir ->
                val deleted = runCatching { dir.deleteRecursively() }.getOrDefault(false)
                Log.i(TAG, "remove unfinished rime dir ${dir.name}: deleted=$deleted")
            }
    }

    /**
     * 逐个文件同步 assets（不支持目录软链，递归即可）。
     *
     * 所有异常一律上抛：[install] 需要知道这次拷贝失败，才能不写标记、下次重来。
     */
    private fun copyAssetDir(assetDir: String, dest: File) {
        val children = context.assets.list(assetDir)
        if (children.isNullOrEmpty()) {
            copyAssetFile(assetDir, dest)
            return
        }
        if (!dest.isDirectory && !dest.mkdirs()) {
            throw IOException("mkdirs failed: ${dest.path}")
        }
        for (name in children) {
            copyAssetDir("$assetDir/$name", File(dest, name))
        }
    }

    private fun copyAssetFile(assetPath: String, dest: File) {
        dest.parentFile?.mkdirs()
        val copied = context.assets.open(assetPath).use { input ->
            dest.outputStream().use { out -> input.copyTo(out, COPY_BUFFER_SIZE) }
        }
        // 压缩存储的 asset 拿不到长度（openFd 会抛），能拿到就顺手比一下：抓「写了一半」的文件
        val expected = runCatching { context.assets.openFd(assetPath).use { it.length } }.getOrNull()
        if (expected != null && copied != expected) {
            throw IOException("asset truncated: $assetPath ($copied/$expected)")
        }
    }

    private fun verify(dir: File) {
        val broken = REQUIRED_ARTIFACTS.filter { (path, minBytes) ->
            File(dir, path).length() < minBytes
        }
        if (broken.isNotEmpty()) {
            throw IOException("asset copy incomplete: ${broken.joinToString { it.first }}")
        }
    }

    private companion object {
        private const val TAG = "RimeDeploy"

        private const val ASSET_ROOT = "rime"
        private const val MARKER = ".copied"
        private const val USER_BUILD_DIR = "build"
        private const val VERSION_PREFIX = "rime-v"
        private const val TEMP_SUFFIX = ".tmp"
        private const val COPY_BUFFER_SIZE = 64 * 1024

        /** `rime-user/build/` 清理版本：需要再次清理历史污染产物时递增 */
        private const val USER_BUILD_CLEANUP_VERSION = 1L

        /**
         * 装好之后必须存在的产物。
         *
         * 数值是**下限**而非精确体积，只用来抓「词表被截断 / 文件根本没拷过来」这类毁灭性损坏
         * （真实体积见 `scripts/precompile-dict.ps1` 的产物清单：词表 62MB、笔顺 prism 41MB、
         * 语法模型 71MB）。缺失时按「没装好」处理，重新拷贝一次。
         */
        private val REQUIRED_ARTIFACTS = listOf(
            "default.yaml" to 200L,
            "pinyin.schema.yaml" to 1000L,
            "t9_pinyin.schema.yaml" to 4000L,
            "t14_pinyin.schema.yaml" to 4000L,
            "english.schema.yaml" to 1000L,
            "zh-hans.gram" to 8L * 1024 * 1024,
            "build/pinyin.table.bin" to 8L * 1024 * 1024,
            "build/pinyin.prism.bin" to 8L * 1024,
            "build/t9_pinyin.prism.bin" to 8L * 1024,
            "build/t14_pinyin.prism.bin" to 8L * 1024,
            "build/english.table.bin" to 64L * 1024,
            "build/english.prism.bin" to 64L * 1024,
            "build/stroke.table.bin" to 256L * 1024,
            "build/stroke.prism.bin" to 1024L * 1024,
        )
    }
}
