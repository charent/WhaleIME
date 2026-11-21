package com.whale.ime.util.android

import android.content.Context
import android.content.pm.PackageInfo

val PackageInfo.versionCodeCompat: Long
    get() = longVersionCode

/**
 * 安装包的 versionName（运行时从 PackageManager 读）。
 *
 * 版本号的唯一来源是 app 模块 build.gradle 里的 versionName。不要在库里用
 * BuildConfig.versionName：库模块自己生成的那份与最终 APK 的 versionName 不是
 * 同一个值（历史上格式还不一样，设置页「关于」显示的因此是错的）。
 */
fun Context.appVersionName(): String =
    runCatching { packageManager.getPackageInfo(packageName, 0).versionName }
        .getOrNull()
        .orEmpty()
