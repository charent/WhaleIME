package com.whale.ime.compose.settings.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.whale.ime.ui.BuildConfig
import com.whale.ime.ui.R
import com.whale.ime.compose.settings.SettingsDest
import com.whale.ime.compose.settings.string
import com.whale.ime.compose.settings.ui.SettingNavRow
import com.whale.ime.compose.settings.ui.SettingsGroup
import com.whale.ime.compose.settings.ui.SettingsScaffold
import com.whale.ime.backup.PrivacyPolicy
import com.whale.ime.preference.AppPrefs
import com.whale.ime.ui.setup.SetupActivity
import com.whale.ime.util.android.appVersionName
import com.whale.ime.util.android.startActivity
import kotlinx.serialization.json.Json
import com.whale.ime.config.AppLinks

/** 关于页：品牌头 + 版本信息（崩溃日志导出在「高级与数据」里） */
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    onPrivacy: () -> Unit,
) {
    val context = LocalContext.current
    // 版本号取安装包本身的值（唯一来源是 app 模块的 versionName）
    val androidVersionName = remember(context) { context.appVersionName() }

    SettingsScaffold(
        title = string(SettingsDest.About.title),
        onBack = onBack,
    ) {
        // 品牌卡没有分组标题，与下一张卡之间要自己留出间距，否则两张卡片会贴在一起
        item {
            SettingsGroup {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = string(R.string.app_name),
                        style = MaterialTheme.typography.titleMedium,
                        fontSize = 18.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        item {
            SettingsGroup {
                SettingNavRow(
                    title = string(R.string.privacy_policy),
                    onClick = onPrivacy,
                )
                SettingNavRow(
                    title = string(R.string.source_code),
                    summary = string(R.string.github_repo),
                    onClick = { open(context, AppLinks.WHALE_IME_REPO) },
                )
                SettingNavRow(
                    title = string(R.string.license),
                    summary = "MIT license",
                    onClick = { open(context, AppLinks.LICENSE_URL) },
                )
            }
        }

        item {
            SettingsGroup(title = string(R.string.app_version)) {
                // 三行统一为「标题 + 下一行的值」：
                // 值不再放在行尾，行高一致，箭头也就落在同一水平线上（不会一行居中对齐、一行偏下）
                SettingNavRow(
                    title = string(R.string.version),
                    summary = androidVersionName,
                    onClick = { open(context, "${AppLinks.WHALE_IME_REPO}/releases/latest") },
                )
                SettingNavRow(
                    title = string(R.string.build_git_hash),
                    summary = BuildConfig.AppCommitHead,
                    onClick = {
                        val commit = BuildConfig.AppCommitHead.substringBefore('-')
                        open(context, "${AppLinks.WHALE_IME_REPO}/commit/$commit")
                    },
                )
                SettingNavRow(
                    title = string(R.string.build_time),
                    summary = BuildConfig.AppBuildTime,
                    onClick = {},
                    enabled = false,
                )
            }
        }

        item { Spacer(Modifier.height(12.dp)) }
    }
}

/** 隐私政策（正文来自 `raw/privacypolicy.json`） */
@Composable
fun PrivacyPolicyScreen(
    onBack: (() -> Unit)?,
    onAccepted: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val policies = remember {
        runCatching {
            val json = context.resources.openRawResource(R.raw.privacypolicy)
                .bufferedReader().use { it.readText() }
            Json.decodeFromString<List<PrivacyPolicy>>(json)
        }.getOrDefault(emptyList())
    }
    val firstRun = remember { !AppPrefs.getInstance().internal.privacyPolicySure.getValue() }

    SettingsScaffold(
        title = string(SettingsDest.Privacy.title),
        onBack = onBack,
    ) {
        item {
            SettingsGroup {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    policies.forEach { policy ->
                        Text(
                            text = policy.name,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = policy.description,
                            fontSize = 13.sp,
                            lineHeight = 20.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(14.dp))
                    }
                }
            }
        }
        if (firstRun) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 20.dp, start = 4.dp, end = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Button(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            AppPrefs.getInstance().internal.privacyPolicySure.setValue(true)
                            if (SetupActivity.shouldShowUp()) context.startActivity<SetupActivity>()
                            onAccepted?.invoke()
                        },
                    ) { Text(string(R.string.privacy_policy_sure)) }
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        onClick = { (context as? android.app.Activity)?.finish() },
                    ) { Text(string(R.string.privacy_policy_cancel)) }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

private fun open(context: android.content.Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }
}
