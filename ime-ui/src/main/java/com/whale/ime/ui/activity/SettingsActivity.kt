package com.whale.ime.ui.activity

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import com.whale.ime.compose.settings.SettingsDest
import com.whale.ime.compose.settings.SettingsNavHost
import com.whale.ime.compose.settings.SettingsTheme
import com.whale.ime.compose.settings.TestInputOverlay
import com.whale.ime.preference.AppPrefs
import com.whale.ime.ui.setup.SetupActivity
import com.whale.ime.util.android.startActivity

/**
 * 设置宿主。
 *
 * - 导航：`navigation-compose`，路由见 [SettingsDest]；
 * - 深链：键盘端通过 intent extra [SettingsDest.EXTRA_DEST] 指定首跳目的地；
 * - 首次启动未同意隐私政策时以隐私页为起点，且不显示返回箭头。
 *
 * 设备加密存储的同步挂在这里的 `onStop`，即整个设置会话结束时。
 */
open class SettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val privacySure = AppPrefs.getInstance().internal.privacyPolicySure.getValue()
        val deepLink = SettingsDest.fromRoute(intent?.getStringExtra(SettingsDest.EXTRA_DEST))
        val startRoute = when {
            !privacySure -> SettingsDest.Privacy.route
            deepLink != null -> deepLink.route
            else -> SettingsDest.Home.route
        }

        setContent {
            SettingsTheme {
                Box {
                    SettingsNavHost(
                        startRoute = startRoute,
                        onExit = { finish() },
                    )
                    // 全局试输入悬浮球：随时可弹出输入法验证设置效果
                    TestInputOverlay()
                }
            }
        }

        // 已同意隐私政策且引导未完成 → 进入引导页
        if (privacySure && SetupActivity.shouldShowUp() && savedInstanceState == null) {
            startActivity<SetupActivity>()
        }
    }

    override fun onStop() {
        AppPrefs.getInstance().syncToDeviceEncryptedStorage()
        super.onStop()
    }
}

class LauncherActivity : SettingsActivity()
