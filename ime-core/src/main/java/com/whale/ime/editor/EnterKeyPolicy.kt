package com.whale.ime.editor

import android.text.InputType
import android.view.inputmethod.EditorInfo
import com.whale.ime.preference.AppPrefs
import com.whale.ime.util.LogUtil

/**
 * 回车键「聊天发送」策略。
 *
 * 开关打开后只把**聊天输入框**的回车键改成「发送」：键帽显示中文「发送」，
 * 点击 performEditorAction([EditorInfo.IME_ACTION_SEND])，长按换行。
 * 其余输入框（地址栏、搜索栏、密码框、单行文本、数字框…）以及开关关闭时
 * 一律沿用原有 imeAction 逻辑，不做任何改变。
 *
 * 判定顺序（越靠前越可靠）：
 * 1. 宿主显式上报 [EditorInfo.IME_ACTION_SEND]；
 * 2. 已知聊天应用包名（微信 / QQ / 飞书 / 钉钉…）；
 * 3. 多行文本 + `flagNoEnterAction` + 没有显式 action —— 这是聊天输入框的典型组合
 *    （宿主不希望回车被输入法改成发送按钮，所以显式禁掉），普通多行编辑框不会带这个 flag。
 * 搜索 / 跳转类 action 一律排除，避免把同一应用内的搜索框误判成聊天框。
 *
 * [sendInChat] 是实时计算的：开关改完后只要键盘缓存失效
 * （`KeyboardManager.clearKeyboard()`），键帽就会按最新状态重绘，不必重启输入法。
 */
object EnterKeyPolicy {

    private const val TAG = "EnterKeyPolicy"

    /** 聊天应用包名白名单：仅在宿主没有上报 actionSend 时兜底 */
    private val CHAT_PACKAGES = setOf(
        "com.tencent.mm",             // 微信
        "com.tencent.mobileqq",       // QQ
        "com.tencent.tim",            // TIM
        "com.ss.android.lark",        // 飞书
        "com.alibaba.android.rimet",  // 钉钉
        "com.tencent.wework",         // 企业微信
    )

    /** 当前编辑框信息：每个输入会话由 ImeService.onStartInput 刷新 */
    private var editorInfo: EditorInfo? = null

    fun update(editorInfo: EditorInfo?) {
        this.editorInfo = editorInfo
        if (editorInfo != null && AppPrefs.getInstance().input.enterKeySendInChat.getValue()) {
            // 只在开关打开时打一行：真机上判断「某个聊天应用为什么没切成发送」用
            LogUtil.d(
                TAG,
                "pkg=${editorInfo.packageName} inputType=0x${editorInfo.inputType.toString(16)} " +
                        "imeOptions=0x${editorInfo.imeOptions.toString(16)} " +
                        "chat=${isChatEditor(editorInfo)}"
            )
        }
    }

    /** 当前编辑框是否走「回车发送」：开关打开 + 聊天输入框 */
    val sendInChat: Boolean
        get() {
            val info = editorInfo ?: return false
            if (!AppPrefs.getInstance().input.enterKeySendInChat.getValue()) return false
            return isChatEditor(info)
        }

    private fun isChatEditor(info: EditorInfo): Boolean {
        val type = info.inputType
        val cls = type and InputType.TYPE_MASK_CLASS
        // 数字 / 电话 / 日期类编辑器没有聊天语义
        if (cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE ||
            cls == InputType.TYPE_CLASS_DATETIME
        ) {
            return false
        }
        if (cls == InputType.TYPE_CLASS_TEXT) {
            // 密码、网址、邮箱：无论宿主上报什么 action 都不当聊天框
            when (type and InputType.TYPE_MASK_VARIATION) {
                InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_URI,
                InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> return false
            }
        } else if (cls != InputType.TYPE_NULL) {
            // TYPE_NULL 是自定义输入框的常见上报方式，只有 actionSend 能证明它是发送语义
            return false
        }

        val action = info.imeOptions and EditorInfo.IME_MASK_ACTION
        if (action == EditorInfo.IME_ACTION_SEND) return true
        // 搜索 / 去往：地址栏、搜索框，保持原有回车 / 搜索图标
        if (action == EditorInfo.IME_ACTION_SEARCH || action == EditorInfo.IME_ACTION_GO) return false

        // 兜底一：已知聊天应用没有上报 actionSend（多数是 flagNoEnterAction + actionNone）
        val pkg = info.packageName?.toString()
        if (pkg != null && CHAT_PACKAGES.contains(pkg)) return true

        // 兜底二：多行文本 + 显式禁掉回车动作 —— 聊天输入框的典型特征，
        // 普通多行编辑框（备忘录 / 便签）不会带这个 flag。
        val multiline = (type and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0
        val noEnterAction = (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        return multiline && noEnterAction
    }
}
