package com.whale.ime.boot

import android.annotation.SuppressLint
import android.content.Context
import com.whale.ime.boot.rime.RimeDataInstaller
import com.whale.ime.clipboard.ClipboardExpiry
import com.whale.ime.clipboard.ClipboardHelper
import com.whale.ime.config.SchemaIds
import com.whale.ime.dispatch.InputDispatcher
import com.whale.ime.emoji.EmojiFont
import com.whale.ime.emoji.WhaleEmojiCompat
import com.whale.ime.keyboard.layout.InputModeSwitcher
import com.whale.ime.phrase.PhraseQuickCodeManager
import com.whale.ime.preference.AppPrefs
import com.whale.ime.storage.WhaleDatabase
import com.whale.ime.theme.ThemeManager
import com.whale.ime.userword.UserWordManager
import com.whale.ime.util.android.defaultSharedPreferences
import com.whale.ime.util.thread.ThreadPoolUtils
import com.whale.ime.engine.schema.CorrectionSchema
import com.whale.ime.vendor.pinyin4j.PinyinHelper

class Launcher {
    lateinit var context: Context
        private set

    fun initData(context: Context) {
        this.context = context
        currentInit()
        onInitDataChildThread()
    }

    private fun currentInit() {
        // pinyin4j 的拼音表来自 assets/pinyindb，必须在任何 PinyinHelper 调用之前注入；
        // UserWordManager.load() 在子线程里就会用到它。
        PinyinHelper.init(context.assets)
        AppPrefs.init(context.defaultSharedPreferences())
        migrateRemovedLx17InputMode()
        ThemeManager.init(context.resources.configuration)
        WhaleDatabase.instance.sideSymbolDao().getAllSideSymbolPinyin()  //操作一次查询，提前创建数据库，避免使用时才创建数据库
        ClipboardHelper.init()
        // 进程重启后补做到期动作（上次进程被杀时可能没来得及清系统剪贴板），并按当前设置重排下一次
        ClipboardExpiry.init()
    }

    /**
     * 乱序17 已下线（键盘布局与 `double_pinyin_ls17` 方案都不再发布）。
     * 历史版本把它记在偏好里：不清掉的话启动时会停在已不存在的布局/方案上，输入全乱。
     * 统一回落到中文26键，语言位保持不变。
     */
    private fun migrateRemovedLx17InputMode() {
        val prefs = AppPrefs.getInstance()
        if (prefs.internal.pinyinModeRime.getValue() == LEGACY_SCHEMA_LX17) {
            prefs.internal.pinyinModeRime.setValue(SchemaIds.SCHEMA_ZH_QWERTY)
        }
        listOf(prefs.internal.inputDefaultMode, prefs.internal.inputMethodPinyinMode).forEach { mode ->
            if (mode.getValue() and InputModeSwitcher.MASK_SKB_LAYOUT == LEGACY_LAYOUT_LX17) {
                mode.setValue(
                    (mode.getValue() and InputModeSwitcher.MASK_SKB_LAYOUT.inv()) or
                        InputModeSwitcher.MASK_SKB_LAYOUT_QWERTY_PINYIN
                )
            }
        }
    }

    /**
     * 可以在子线程初始化的操作
     */
    private fun onInitDataChildThread() {
        ThreadPoolUtils.executeSingleton {
            RimeDataInstaller(context).install()
            InputDispatcher.resetIme()  // 解决词库复制慢，导致先调用初始化问题
            UserWordManager.load()      // 用户词库载入内存索引，供候选注入高频查询
            PhraseQuickCodeManager.load() // 常用语快捷码索引（开关打开时参与候选注入）
            // 纠错/模糊音开关落地为 pinyin.custom.yaml patch；内容没变时是纯文件比对，零开销
            CorrectionSchema.syncFromPrefs(context)
            WhaleEmojiCompat.init(context)
            // 随包 emoji 字体（10MB）预热：首次解析有几十毫秒，别留给表情面板第一次展开
            EmojiFont.prewarm(context)
            // 自动备份的 JobScheduler 重建不属于 core：AutoBackupScheduler 与
            // AutoBackupJobService 都在 UI 层，由宿主 app 在 Launcher.initData 之后调用
            // AutoBackupScheduler.reschedule(context) 完成（见 BaseApplication）。
            // 深色模式跟随系统是平台默认行为：界面全部是 Compose + values-night，
        }
    }

    companion object {
        /** 已下线的乱序17 方案与键盘布局，仅用于升级时清理历史偏好 */
        private const val LEGACY_SCHEMA_LX17 = "double_pinyin_ls17"
        private const val LEGACY_LAYOUT_LX17 = 0x6000

        @SuppressLint("StaticFieldLeak")
        val instance = Launcher()
    }
}
