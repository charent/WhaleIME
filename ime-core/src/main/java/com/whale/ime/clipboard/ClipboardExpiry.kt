package com.whale.ime.clipboard

import com.whale.ime.boot.Launcher
import com.whale.ime.preference.AppPrefs
import com.whale.ime.preference.CLIPBOARD_EXPIRE_DURATION_MAX
import com.whale.ime.preference.CLIPBOARD_EXPIRE_DURATION_MIN
import com.whale.ime.preference.ManagedPreference
import com.whale.ime.storage.WhaleDatabase
import com.whale.ime.storage.dao.ClipboardDao
import com.whale.ime.util.android.clipboardManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 剪贴板内容自动失效。
 *
 * 到期要做两件事：
 * 1. 清空系统剪贴板——这是「系统长按粘贴也拿不到」的唯一手段（[android.content.ClipboardManager.clearPrimaryClip]）；
 * 2. 「不保留到历史」时把过期记录从库里删掉（置顶行不删，见 [ClipboardDao.deleteExpired]）。
 *    注意这一步**不追溯**：只有「关掉这个开关之后复制的内容」才会被删（[keepHistorySince]），
 *    关开关这个动作本身不会清空已有的历史。
 *
 * 设计要点：
 * - **到期时刻不落库**，由 `clipboard.time + 当前保留时长` 推导。改时长对已有记录立即生效，
 *   数据库也因此不用升版本（`clipboard` 表结构不变，备份包不用动）。
 * - **同一时刻只有一个 [Job]**：每次复制/设置变更都取消旧的、按「最近的边界」重排一个，
 *   不用给每条记录挂定时器。边界只有两种——最新记录的到期（清系统剪贴板）与最老可删记录的
 *   到期（删历史，仅在不保留历史时）。
 * - **幂等**：[refresh] 先处理已经过期的内容再排下一次，所以进程重启、设置改动、
 *   甚至系统时间回拨之后随便多调几次都不会错。
 *
 * 已知取舍：定时器活在进程内，进程被系统回收后到期动作会顺延到下次进程启动（首次 [refresh]）。
 * IME 是系统绑定服务，存活率很高；换来的是零额外组件、零唤醒、零权限。
 */
object ClipboardExpiry {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 下一个到期边界；同一时刻只保留一个 */
    private var job: Job? = null

    /**
     * 正在清空系统剪贴板。
     *
     * 清空本身会回调 [ClipboardHelper.onPrimaryClipChanged]，置位期间把它当成系统事件忽略；
     * 清空后 `primaryClip` 为 null，即使回调晚到也不会被记成一条空记录。
     */
    @Volatile
    var isClearing: Boolean = false
        private set

    /**
     * 「不保留到历史」只在开关被关闭之后复制的内容上生效。
     *
     * 关开关这个动作本身不能清空历史，所以把关闭时刻记下来当作分界点：
     * 更早的记录就算过期也永不追溯删除（系统剪贴板照样会被清空）。
     */
    fun keepHistorySince(): Long = runCatching {
        AppPrefs.getInstance().internal.clipboardExpireKeepSince.getValue()
    }.getOrDefault(0L)

    /** 记录开关翻转：关掉「保留到历史」的那一刻打上分界点 */
    private val keepHistoryListener = ManagedPreference.OnChangeListener<Boolean> { _, value ->
        if (!value) {
            runCatching {
                AppPrefs.getInstance().internal.clipboardExpireKeepSince
                    .setValue(System.currentTimeMillis())
            }
        }
        refresh()
    }

    /** 进程启动时调用一次：注册开关监听 + 补做到期动作 + 排下一次 */
    fun init() {
        AppPrefs.getInstance().clipboard.clipboardExpireKeepHistory
            .registerOnChangeListener(keepHistoryListener)
        refresh()
    }

    /** 功能是否生效：必须同时满足「记录剪贴板历史」与「内容自动失效」 */
    fun isEnabled(): Boolean = runCatching {
        val clipboard = AppPrefs.getInstance().clipboard
        clipboard.clipboardListening.getValue() && clipboard.clipboardExpireEnable.getValue()
    }.getOrDefault(false)

    /** 保留时长（毫秒）；功能关闭时为 0 */
    fun durationMs(): Long {
        if (!isEnabled()) return 0L
        val seconds = runCatching {
            AppPrefs.getInstance().clipboard.clipboardExpireDuration.getValue()
        }.getOrDefault(CLIPBOARD_EXPIRE_DURATION_MIN)
        return seconds.coerceIn(CLIPBOARD_EXPIRE_DURATION_MIN, CLIPBOARD_EXPIRE_DURATION_MAX) * 1000L
    }

    private fun keepsHistory(): Boolean = runCatching {
        AppPrefs.getInstance().clipboard.clipboardExpireKeepHistory.getValue()
    }.getOrDefault(true)

    /**
     * 处理已过期内容 + 重排下一个边界。
     *
     * 三个调用点：复制成功（[ClipboardHelper]）、设置变更（`SettingsEffects`）、进程启动（[Launcher]）。
     */
    @Synchronized
    fun refresh() {
        // 先取消：本次会重新算边界，旧的可能已经不成立了
        job?.cancel()
        job = null
        if (!isEnabled()) return
        val dao = runCatching { WhaleDatabase.instance.clipboardDao() }.getOrNull() ?: return
        val now = System.currentTimeMillis()
        sweep(dao, now)
        scheduleNext(dao, now)
    }

    /**
     * 单条被用户删除：系统剪贴板里正放着它的话，一起清掉。
     *
     * 删库不会让系统剪贴板里的内容消失（那是系统的事），不主动清的话，
     * 别的 App 长按还能粘贴到刚被删掉的那条。只在功能生效时接管，关闭时保持原有行为。
     */
    fun onContentRemoved(content: String) {
        if (!isEnabled()) return
        if (currentClipboardText() == content) clearSystemClipboard()
        // 被删的是不是最新那条都重排一次：到期边界取决于剩下的记录
        refresh()
    }

    /**
     * 整表即将被清空：系统剪贴板里要是正放着我们记录过的内容，一起清掉。
     *
     * **必须在删库之前调用**——删完就查不到「这条是不是我们记录的」了。
     */
    fun clearSystemClipboardIfRecorded() {
        if (!isEnabled()) return
        val current = currentClipboardText() ?: return
        val recorded = runCatching {
            WhaleDatabase.instance.clipboardDao().countByContent(current)
        }.getOrDefault(0)
        if (recorded > 0) clearSystemClipboard()
    }

    /** 到点动作：清系统剪贴板 + 按设置删历史。只做已经过期的，未来的一律不动 */
    private fun sweep(dao: ClipboardDao, now: Long) {
        val duration = durationMs()
        if (duration <= 0L) return
        val expireBefore = now - duration
        runCatching {
            val current = currentClipboardText()
            // 只有当系统剪贴板里确实还是「我们记录过、且已经过期」的内容时才清，
            // 避免把别的应用刚复制的东西一起清掉
            if (current != null && dao.countExpired(current, expireBefore) > 0) {
                clearSystemClipboard()
            }
        }
        if (!keepsHistory()) {
            runCatching { dao.deleteExpired(expireBefore, keepHistorySince()) }
        }
    }

    /**
     * 排下一个边界，只取**未来**的时刻。
     *
     * 已经过去的边界一定已经被 [sweep] 处理过（`refresh` 里先 sweep 再排），
     * 所以这里过滤掉过去时刻，既不会空转，也不会漏掉「还剩一点时间」的记录。
     * 没有任何未来边界时（功能关掉 / 只剩置顶的过期行）就不挂定时器，等到下次 [refresh]。
     */
    private fun scheduleNext(dao: ClipboardDao, now: Long) {
        val duration = durationMs()
        if (duration <= 0L) return
        val boundaries = buildList {
            dao.newestTime()?.let { add(it + duration) }
            if (!keepsHistory()) dao.oldestDeletableTime(keepHistorySince())?.let { add(it + duration) }
        }
        val next = boundaries.filter { it > now }.minOrNull() ?: return
        job = scope.launch {
            delay(next - now)
            // 到点：清过期 + 重排下一次（refresh 会取消当前 Job，之后没有挂起点，安全）
            refresh()
        }
    }

    /** 系统剪贴板正文；与落库时同样按 [ClipboardHelper.MAX_CONTENT_LENGTH] 截断后再比对 */
    private fun currentClipboardText(): String? = runCatching {
        Launcher.instance.context.clipboardManager.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.text
            ?.toString()
            ?.takeIf { it.isNotBlank() }
            ?.takeContent(ClipboardHelper.MAX_CONTENT_LENGTH)
    }.getOrNull()

    private fun clearSystemClipboard() {
        isClearing = true
        try {
            runCatching { Launcher.instance.context.clipboardManager.clearPrimaryClip() }
        } finally {
            isClearing = false
        }
    }
}
