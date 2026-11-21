package com.whale.ime.compose.settings

import com.whale.ime.theme.ManagedThemePreferenceUi
import com.whale.ime.theme.ThemeManager
import com.whale.ime.vendor.pinyin4j.PinyinHelper
import com.whale.ime.preference.AppPrefs
import com.whale.ime.preference.ManagedPreferenceProvider
import com.whale.ime.preference.ManagedPreferenceUi

/** 一条可搜索的设置项 */
data class SettingsSearchEntry(
    val dest: SettingsDest,
    /** 该页在首页所属的分组（与首页卡片上的分组名同源） */
    val groupTitle: String,
    val title: String,
    val key: String,
    val summary: String,
    /** 标题的拼音首字母，用于「sz」「mhy」这类输入 */
    val initials: String,
)

/**
 * 设置项索引。
 *
 * 直接遍历各 provider 的 UI 元数据生成，不维护第二份清单：
 * 新增一个设置项就自动可被搜索到。
 *
 * 副标题里的「所在分组」取自 [SettingsHomeMap]，所以首页改分组/改文案时搜索结果自动跟随，
 * 不会出现「首页叫 A、搜出来写 B」。
 */
object SettingsSearchIndex {

    /**
     * 开关所属页与 provider 不同的项。
     *
     * 用户词库的两个开关数据上属于 `InputPrefs`（备份分组 id 不能动），
     * 但 UI 渲染在「用户词库」页，搜到后要跳到那里。
     */
    private val destOverrides: Map<String, SettingsDest> by lazy {
        val input = AppPrefs.getInstance().input
        mapOf(
            input.userDictEnable.key to SettingsDest.UserDict,
            input.userDictAutoLearn.key to SettingsDest.UserDict,
        )
    }

    fun build(resolve: (Int) -> String): List<SettingsSearchEntry> {
        val appPrefs = AppPrefs.getInstance()
        val providers: List<Pair<ManagedPreferenceProvider, SettingsDest>> = listOf(
            appPrefs.input to SettingsDest.Input,
            appPrefs.feedback to SettingsDest.Feedback,
            appPrefs.keyboardSetting to SettingsDest.Keyboard,
            appPrefs.clipboard to SettingsDest.Clipboard,
            appPrefs.handwriting to SettingsDest.Handwriting,
            appPrefs.voice to SettingsDest.Voice,
            appPrefs.other to SettingsDest.Advanced,
            ThemeManager.prefs to SettingsDest.Theme,
        )

        val entries = mutableListOf<SettingsSearchEntry>()
        providers.forEach { (provider, providerDest) ->
            provider.managedPreferencesUi.forEach { ui ->
                val title = ui.searchTitle(resolve) ?: return@forEach
                val dest = destOverrides[ui.key] ?: providerDest
                entries += SettingsSearchEntry(
                    dest = dest,
                    groupTitle = SettingsHomeMap.sectionTitleOf(dest)?.let(resolve).orEmpty(),
                    title = title,
                    key = ui.key,
                    summary = ui.searchSummary(resolve),
                    initials = runCatching {
                        PinyinHelper.getPinYinHeadChar(title).lowercase()
                    }.getOrDefault(""),
                )
            }
        }
        return entries
    }

    fun search(entries: List<SettingsSearchEntry>, query: String): List<SettingsSearchEntry> {
        val raw = query.trim()
        if (raw.isEmpty()) return emptyList()
        val lower = raw.lowercase()
        val ascii = lower.all { it in 'a'..'z' }
        return entries.filter { entry ->
            entry.title.contains(raw, ignoreCase = true) ||
                    entry.groupTitle.contains(raw, ignoreCase = true) ||
                    entry.summary.contains(raw, ignoreCase = true) ||
                    entry.key.contains(lower) ||
                    (ascii && entry.initials.contains(lower))
        }
    }
}

private fun ManagedPreferenceUi.searchTitle(resolve: (Int) -> String): String? = when (this) {
    is ManagedPreferenceUi.Switch -> resolve(title)
    is ManagedPreferenceUi.StringList<*> -> resolve(title)
    is ManagedPreferenceUi.SeekBarInt -> resolve(title)
    is ManagedPreferenceUi.SeekBarFloat -> resolve(title)
    is ManagedPreferenceUi.EditTextInt -> resolve(title)
    is ManagedPreferenceUi.TwinSeekBarInt -> resolve(title)
    is ManagedThemePreferenceUi -> resolve(title)
    else -> null
}

/** 只有开关带摘要，其余控件没有可匹配的说明文字 */
private fun ManagedPreferenceUi.searchSummary(resolve: (Int) -> String): String =
    (this as? ManagedPreferenceUi.Switch)?.summary?.let(resolve).orEmpty()
