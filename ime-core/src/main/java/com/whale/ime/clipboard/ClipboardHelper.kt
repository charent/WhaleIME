package com.whale.ime.clipboard

import android.content.ClipboardManager.OnPrimaryClipChangedListener
import com.whale.ime.boot.Launcher
import com.whale.ime.storage.WhaleDatabase
import com.whale.ime.storage.entity.Clipboard
import com.whale.ime.preference.AppPrefs
import com.whale.ime.preference.CLIPBOARD_HISTORY_LIMIT_MAX
import com.whale.ime.preference.CLIPBOARD_HISTORY_LIMIT_MIN
import com.whale.ime.util.android.clipboardManager
import kotlin.math.max

/**
 * 按字符数截断正文；截断点落在代理对中间时回退一位，避免存进半个 emoji。
 *
 * 落库与「内容是否到期」的比对都用它，两边截断规则必须一致（见 [ClipboardExpiry]）。
 */
internal fun String.takeContent(length: Int): String {
    if (this.length <= length) return this
    val end = if (this[length - 1].isHighSurrogate()) length - 1 else length
    return substring(0, end)
}

/**
 * 剪切板监听
 * 移除使用广播监听方式，解决部分手机后台无法启动监听服务异常(API level 31)。
 */
object ClipboardHelper : OnPrimaryClipChangedListener {

    /** 单条剪贴板正文的保存上限（与数据库 TEXT 字段的常规上限一致） */
    const val MAX_CONTENT_LENGTH = 65535

    fun init() {
        Launcher.instance.context.clipboardManager.addPrimaryClipChangedListener(this)
    }

    override fun onPrimaryClipChanged() {
        // 自己清空系统剪贴板（内容到期）不算一次复制，否则会把清空动作当成新内容记下来
        if (ClipboardExpiry.isClearing) return
        val isClipboardListening = AppPrefs.getInstance().clipboard.clipboardListening.getValue()
        if(isClipboardListening) {
           val item = Launcher.instance.context.clipboardManager.primaryClip?.getItemAt(0)
            item?.takeIf { it.text?.isNotBlank() == true }?.let {
                    val data = it.text.toString().takeContent(MAX_CONTENT_LENGTH)
                    WhaleDatabase.instance.clipboardDao().insert(Clipboard(content = data))
                    // 兼容历史遗留的低值配置，保证与设置项的滑动范围一致
                    val limit = AppPrefs.getInstance().clipboard.clipboardHistoryLimit.getValue()
                        .coerceIn(CLIPBOARD_HISTORY_LIMIT_MIN, CLIPBOARD_HISTORY_LIMIT_MAX)
                    val num = max(WhaleDatabase.instance.clipboardDao().getCount() - limit, 0)
                    WhaleDatabase.instance.clipboardDao().deleteOldest(num)
                    // 内容自动失效：顺手清掉已过期的旧内容（不保留历史时也在这里落库删除），
                    // 并按这条新记录重排到期时刻
                    ClipboardExpiry.refresh()
                    if (AppPrefs.getInstance().clipboard.clipboardSuggestion.getValue()) {
                        AppPrefs.getInstance().internal.clipboardUpdateTime.setValue(System.currentTimeMillis())
                        AppPrefs.getInstance().internal.clipboardUpdateContent.setValue(data)
                    }
                }
        }
    }
}
