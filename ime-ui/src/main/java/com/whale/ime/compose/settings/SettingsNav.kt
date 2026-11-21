package com.whale.ime.compose.settings

import androidx.annotation.StringRes
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.whale.ime.ui.R
import com.whale.ime.compose.settings.screens.AboutScreen
import com.whale.ime.compose.settings.screens.AdvancedSettingsScreen
import com.whale.ime.compose.settings.screens.BackupSettingsScreen
import com.whale.ime.compose.settings.screens.ClipboardSettingsScreen
import com.whale.ime.compose.settings.screens.FeedbackSettingsScreen
import com.whale.ime.compose.settings.screens.FullDisplayKeyboardScreen
import com.whale.ime.compose.settings.screens.HandwritingSettingsScreen
import com.whale.ime.compose.settings.screens.HomeScreen
import com.whale.ime.compose.settings.screens.InputSettingsScreen
import com.whale.ime.compose.settings.screens.KeyboardSettingsScreen
import com.whale.ime.compose.settings.screens.PrivacyPolicyScreen
import com.whale.ime.compose.settings.screens.SettingsSearchScreen
import com.whale.ime.compose.settings.screens.SidebarSymbolScreen
import com.whale.ime.compose.settings.screens.ThemeScreen
import com.whale.ime.compose.settings.screens.UserDictScreen
import com.whale.ime.compose.settings.screens.VoiceSettingsScreen

/**
 * 设置导航目的地。
 *
 * 键盘端的深链不再用 `NavDeepLinkBuilder`（nav-compose 不兼容该 API），
 * 改为显式 intent extra，见 [EXTRA_DEST] 与 `AppUtil.launchSettingsToXxx`。
 *
 * route 字符串是深链协议的一部分：只允许新增，不要改名/删除。
 */
enum class SettingsDest(val route: String, @StringRes val title: Int) {
    Home("home", R.string.settings),
    Input("input", R.string.setting_ime_input),
    Handwriting("handwriting", R.string.ime_settings_handwriting),
    Voice("voice", R.string.ime_settings_voice),
    UserDict("userDict", R.string.user_dict),
    Theme("theme", R.string.theme_and_appearance),
    Feedback("feedback", R.string.keyboard_feedback),
    Keyboard("keyboard", R.string.setting_ime_keyboard),
    Clipboard("clipboard", R.string.clipboard),
    FullDisplay("fullDisplay", R.string.full_display_keyboard),
    Advanced("advanced", R.string.settings_entry_advanced),
    Backup("backup", R.string.backup_and_restore),
    About("about", R.string.about),
    Privacy("privacy", R.string.privacy_policy),
    Search("search", R.string.settings_search_title),
    Sidebar("sidebar", R.string.setting_ime_prefixs);

    companion object {
        /** 键盘端跳转用的 intent extra key */
        const val EXTRA_DEST = "com.whale.ime.extra.SETTINGS_DEST"

        fun fromRoute(route: String?): SettingsDest? = entries.find { it.route == route }
    }
}

/**
 * 首页入口。
 *
 * 标题一律取 [SettingsDest.title]（页面顶栏用的也是它），这里只补充图标与目录式摘要：
 * 旧实现在 `settings_entry_*` 里另存一份标题，页面改名后就和首页对不上。
 */
data class SettingsHomeEntry(
    val dest: SettingsDest,
    val iconRes: Int,
    @StringRes val summaryRes: Int,
)

/** 首页分组：首页渲染与搜索结果里的「所在分组」共用这一份清单 */
data class SettingsHomeSection(
    @StringRes val titleRes: Int,
    val entries: List<SettingsHomeEntry>,
)

/**
 * 首页信息架构（唯一的入口清单）。
 *
 * 分组按「输入 → 键盘与外观 → 剪贴板与数据 → 其他」递进：
 * 每个入口只出现一次，且分组名描述的是业务对象，而不是「高级」这类重要性标签。
 */
object SettingsHomeMap {

    val sections = listOf(
        SettingsHomeSection(
            R.string.settings_section_input,
            listOf(
                SettingsHomeEntry(
                    SettingsDest.Input, R.drawable.ic_ui_language,
                    R.string.settings_entry_input_summary,
                ),
                SettingsHomeEntry(
                    SettingsDest.UserDict, R.drawable.ic_ui_dict,
                    R.string.settings_entry_dict_summary,
                ),
            ),
        ),
        SettingsHomeSection(
            R.string.settings_section_appearance,
            listOf(
                SettingsHomeEntry(
                    SettingsDest.Keyboard, R.drawable.ic_ui_keyboard,
                    R.string.settings_entry_keyboard_summary,
                ),
                SettingsHomeEntry(
                    SettingsDest.Theme, R.drawable.ic_ui_theme,
                    R.string.settings_entry_theme_summary,
                ),
                SettingsHomeEntry(
                    SettingsDest.Sidebar, R.drawable.ic_ui_sidebar,
                    R.string.setting_ime_prefixs_summary,
                ),
                SettingsHomeEntry(
                    SettingsDest.Feedback, R.drawable.ic_ui_speaker,
                    R.string.settings_entry_feedback_summary,
                ),
            ),
        ),
        SettingsHomeSection(
            R.string.settings_section_data,
            listOf(
                SettingsHomeEntry(
                    SettingsDest.Clipboard, R.drawable.ic_ui_clipboard,
                    R.string.settings_entry_clipboard_summary,
                ),
                SettingsHomeEntry(
                    SettingsDest.Backup, R.drawable.ic_ui_layers,
                    R.string.settings_entry_backup_summary,
                ),
            ),
        ),
        SettingsHomeSection(
            R.string.settings_section_other,
            listOf(
                SettingsHomeEntry(
                    SettingsDest.Advanced, R.drawable.ic_ui_setting,
                    R.string.settings_entry_advanced_summary,
                ),
                SettingsHomeEntry(
                    SettingsDest.About, R.drawable.ic_ui_info,
                    R.string.settings_entry_about_summary,
                ),
            ),
        ),
    )

    /** 某个页面在首页属于哪个分组（搜索结果的副标题用） */
    fun sectionTitleOf(dest: SettingsDest): Int? =
        sections.firstOrNull { section -> section.entries.any { it.dest == dest } }?.titleRes
}

/**
 * 设置导航图。
 *
 * [startRoute] 由 Activity 决定：首次启动未同意隐私政策时直接从 [SettingsDest.Privacy] 起步，
 * 键盘端深链则直接落到目标页（栈里只有该页）。
 */
@Composable
fun SettingsNavHost(
    startRoute: String,
    onExit: () -> Unit,
) {
    val navController = rememberNavController()
    // 返回：能回退就回退，否则结束 Activity
    val back: () -> Unit = {
        if (!navController.popBackStack()) onExit()
    }
    val go: (SettingsDest) -> Unit = { dest ->
        navController.navigateTo(dest)
    }

    NavHost(
        navController = navController,
        startDestination = startRoute,
        enterTransition = {
            fadeIn(tween(220)) + slideInHorizontally(tween(220)) { it / 14 }
        },
        exitTransition = { fadeOut(tween(140)) },
        popEnterTransition = { fadeIn(tween(200)) },
        popExitTransition = {
            fadeOut(tween(160)) + slideOutHorizontally(tween(200)) { it / 14 }
        },
    ) {
        composable(SettingsDest.Home.route) {
            HomeScreen(onNavigate = go, onExit = onExit)
        }
        composable(SettingsDest.Input.route) {
            InputSettingsScreen(
                onBack = back,
                onHandwriting = { go(SettingsDest.Handwriting) },
                onVoice = { go(SettingsDest.Voice) },
            )
        }
        composable(SettingsDest.Handwriting.route) {
            HandwritingSettingsScreen(onBack = back)
        }
        composable(SettingsDest.Voice.route) {
            VoiceSettingsScreen(onBack = back)
        }
        composable(SettingsDest.UserDict.route) {
            UserDictScreen(
                onBack = back,
                onBackup = { go(SettingsDest.Backup) },
            )
        }
        composable(SettingsDest.Theme.route) {
            ThemeScreen(onBack = back)
        }
        composable(SettingsDest.Feedback.route) {
            FeedbackSettingsScreen(onBack = back)
        }
        composable(SettingsDest.Keyboard.route) {
            KeyboardSettingsScreen(
                onBack = back,
                onFullDisplay = { go(SettingsDest.FullDisplay) },
            )
        }
        composable(SettingsDest.Clipboard.route) {
            ClipboardSettingsScreen(onBack = back)
        }
        composable(SettingsDest.FullDisplay.route) {
            FullDisplayKeyboardScreen(onBack = back)
        }
        composable(SettingsDest.Advanced.route) {
            AdvancedSettingsScreen(onBack = back)
        }
        composable(SettingsDest.Backup.route) {
            BackupSettingsScreen(onBack = back)
        }
        composable(SettingsDest.About.route) {
            AboutScreen(onBack = back, onPrivacy = { go(SettingsDest.Privacy) })
        }
        composable(SettingsDest.Privacy.route) {
            // 首次启动时隐私政策是根页面，不显示返回箭头（topLevel 语义）；
            // 同意后清掉隐私页回到首页，取消则结束整个设置页。
            val isRoot = startRoute == SettingsDest.Privacy.route
            PrivacyPolicyScreen(
                onBack = if (isRoot) null else back,
                onAccepted = if (isRoot) {
                    {
                        navController.navigate(SettingsDest.Home.route) {
                            popUpTo(SettingsDest.Privacy.route) { inclusive = true }
                        }
                    }
                } else null,
            )
        }
        composable(SettingsDest.Search.route) {
            SettingsSearchScreen(onBack = back, onNavigate = go)
        }
        composable(SettingsDest.Sidebar.route) {
            SidebarSymbolScreen(onBack = back)
        }
    }
}

private fun NavHostController.navigateTo(dest: SettingsDest) {
    navigate(dest.route) { launchSingleTop = true }
}
