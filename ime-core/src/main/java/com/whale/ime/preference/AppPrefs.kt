package com.whale.ime.preference

import android.content.SharedPreferences
import androidx.annotation.StringRes
import androidx.core.content.edit
import com.whale.ime.boot.Launcher
import com.whale.ime.config.SchemaIds
import com.whale.ime.core.R
import com.whale.ime.keyboard.layout.InputModeSwitcher
import com.whale.ime.preference.model.FullDisplayCenterMode
import com.whale.ime.preference.model.FullDisplayKeyMode
import com.whale.ime.util.android.Density
import com.whale.ime.util.android.defaultSharedPreferences

/**
 * 一组建档偏好：[id] 是 provider 的类名（跨版本稳定），[title] 是设置页上的分组名，
 * [keys] 是该组当前注册的全部 preference key。
 */
data class PreferenceGroup(
    val id: String,
    @StringRes val title: Int,
    val keys: Set<String>,
)

/**
 * 偏好总入口：只负责**装配**各分类与变更广播。
 *
 * 分类各自一个文件（[Input] / [KeyboardSetting] / [Feedback] / [Clipboard] /
 * [Handwriting] / [Voice] / [Other]），本机的运行期状态放在 [Internal]。
 * 新增设置项请加到对应分类文件，不要在 [AppPrefs] 里直接声明。
 */
class AppPrefs(private val sharedPreferences: SharedPreferences) {

    /**
     * 本机内部状态（备份不导出）：版本标记、剪贴板游标、键盘几何的横屏档、
     * 悬浮键盘留白、自动备份目标目录等。
     */
    class Internal(sharedPreferences: SharedPreferences) : ManagedPreferenceInternal(sharedPreferences) {
        val pinyinModeRime = string("input_method_pinyin_mode_rime", SchemaIds.SCHEMA_ZH_QWERTY) //拼音输入模式记录，记录引擎
        val inputDefaultMode = int("input_default_method_mode", InputModeSwitcher.MODE_QWERTY_CHINESE)   //默认输入法类型
        val inputMethodPinyinMode = int("input_method_pinyin_mode", InputModeSwitcher.MODE_QWERTY_CHINESE)  // 保存中文输入法类型
        // 已装好的 Rime 词库版本（仅记录，便于排查；要不要重装由 RimeDataInstaller 按目录内容判定）
        val dataDictVersion = long("rime_dict_data_version", 0)
        // rime-user/build 清理版本：设备端部署会写出空 prism，需要一次性删掉（见 RimeDataInstaller）
        val rimeUserBuildCleanupVersion = long("rime_user_build_cleanup_version", 0)
        // 常用语种子版本：升级时按版本补齐新增短句（内容主键 + INSERT IGNORE，不动已有行）
        val phraseSeedVersion = int("phrase_seed_version", 0)
        // 键盘高度比例的横屏档：竖屏档见 keyboardSetting.keyboardHeightRatio（有设置 UI）
        val keyboardHeightRatioLandscape = float("keyboard_height_ratio_landscape", 0.5f)     //键盘高度比例:横屏
        val candidatesHeightRatio = float("candidates_height_ratio", 0.035f)     //候选词栏高度比例
        val candidatesHeightRatioLandscape = float("candidates_height_ratio_landscape", 0.06f)     //候选词栏高度比例:横屏
        val keyboardModeFloat = bool("keyboard_mode_float", false)     // 悬浮模式
        val keyboardModeFloatLandscape = bool("keyboard_mode_float_landscape", false)// 悬浮模式:横屏
        val keyboardBottomPaddingFloat = int("keyboard_padding_bottom", Density.dip2px(100))     //竖屏悬浮模式底边距
        val keyboardRightPaddingFloat = int("keyboard_padding_right", Density.dip2px(20))     //竖屏悬浮模式右边距
        val keyboardBottomPaddingLandscapeFloat = int("keyboard_padding_bottom_landscape", Density.dip2px(50))     //横屏悬浮模式底边距
        val keyboardRightPaddingLandscapeFloat = int("keyboard_padding_right_landscape", Density.dip2px(20))     //横屏悬浮模式右边距
        val keyboardRightPadding = int("keyboard_padding_right_normal", Density.dip2px(0))     //竖屏非悬浮右边距
        val clipboardUpdateTime = long("clipboard_update_time", 0)     //剪切板更新时间
        val clipboardUpdateContent = string("clipboard_update_content","")     //剪切板更新内容
        val fullDisplayKeyboardEnable = bool("full_display_keyboard_enable", true)     //全面屏键盘优化
        val fullDisplayKeyModeLeft = string("full_display_key_mode_left", FullDisplayKeyMode.SwitchIme.name)     //全面屏键盘优化:左键盘
        val fullDisplayKeyModeRight = string("full_display_key_mode_right", FullDisplayKeyMode.Clipboard.name)     //全面屏键盘优化：右键盘
        val fullDisplayCenterMode = string("full_display_center_mode", FullDisplayCenterMode.MoveCursor.name)     //全面屏键盘优化：中间区域

        val privacyPolicySure = bool("privacy_policy_sure", false) //是否同意隐私政策

        // 设置页试输入悬浮球位置（px，-1 表示未拖动过、跟随默认位置）
        val testBallX = float("test_input_ball_x", -1f)
        val testBallY = float("test_input_ball_y", -1f)

        // 自动备份：目标目录（SAF tree uri）、周期（天）、上次成功时间。
        // 都是「本机状态」，不属于可携带的用户数据，因此放在 internal（备份不导出）。
        val autoBackupEnabled = bool("auto_backup_enabled", false)
        val autoBackupIntervalDays = int("auto_backup_interval_days", 7)
        val autoBackupTreeUri = string("auto_backup_tree_uri", "")
        val autoBackupLastTime = long("auto_backup_last_time", 0)
    }

    private val providers = mutableListOf<ManagedPreferenceProvider>()

    fun <T : ManagedPreferenceProvider> registerProvider(
        providerF: (SharedPreferences) -> T
    ): T {
        val provider = providerF(sharedPreferences)
        providers.add(provider)
        return provider
    }

    private fun <T : ManagedPreferenceProvider> T.register() = this.apply {
        registerProvider { this@register }
    }

    /**
     * 注册顺序即备份 manifest 的分组顺序（见 [backupPreferenceGroups]），**不要重排**。
     */
    val internal = Internal(sharedPreferences).register()
    val voice = Voice(sharedPreferences).register()
    val handwriting = Handwriting(sharedPreferences).register()
    val input = Input(sharedPreferences).register()
    val feedback = Feedback(sharedPreferences).register()
    val clipboard = Clipboard(sharedPreferences).register()
    val keyboardSetting = KeyboardSetting(sharedPreferences).register()
    val other = Other(sharedPreferences).register()

    private val onSharedPreferenceChangeListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            providers.forEach {
                it.managedPreferences[key]?.fireChange()
            }
        }

    /**
     * 备份用：可按分组导出/恢复的偏好集合。
     *
     * id 取 provider 的类名（`Input` / `KeyboardSetting` / `ThemePrefs` …），
     * 写进备份包的 manifest，跨版本恢复时按 id 精确匹配——所以这些类名不要随意改。
     * `internal`（ManagedPreferenceInternal）不属于 Category，天然被排除：
     * 版本标记、剪贴板游标、自动备份目录这类本机状态不应跨设备搬运。
     */
    fun backupPreferenceGroups(): List<PreferenceGroup> =
        providers.mapNotNull { provider ->
            val category = provider as? ManagedPreferenceCategory ?: return@mapNotNull null
            val keys = provider.managedPreferences.keys
            if (keys.isEmpty()) return@mapNotNull null
            PreferenceGroup(provider::class.java.simpleName, category.title, keys.toSet())
        }

    fun syncToDeviceEncryptedStorage() {
        val ctx = Launcher.instance.context.createDeviceProtectedStorageContext()
        val sp = ctx.defaultSharedPreferences()
        sp.edit {
            internal.managedPreferences.forEach {
                it.value.putValueTo(this@edit)
            }

            input.managedPreferences.forEach {
                it.value.putValueTo(this@edit)
            }
            feedback.managedPreferences.forEach {
                it.value.putValueTo(this@edit)
            }
            voice.managedPreferences.forEach {
                it.value.putValueTo(this@edit)
            }
            handwriting.managedPreferences.forEach {
                it.value.putValueTo(this@edit)
            }
            clipboard.managedPreferences.forEach {
                it.value.putValueTo(this@edit)
            }
            keyboardSetting.managedPreferences.forEach {
                it.value.putValueTo(this@edit)
            }
            other.managedPreferences.forEach {
                it.value.putValueTo(this@edit)
            }
        }
    }

    companion object {
        private var instance: AppPrefs? = null

        /**
         * MUST call before use
         */
        fun init(sharedPreferences: SharedPreferences) {
            if (instance != null)
                return
            instance = AppPrefs(sharedPreferences)
            sharedPreferences.registerOnSharedPreferenceChangeListener(getInstance().onSharedPreferenceChangeListener)
        }

        fun getInstance() = instance!!
    }
}
