package com.whale.ime.util.android

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences

inline fun <reified T : Activity> Context.startActivity(setupIntent: Intent.() -> Unit = {}) {
    startActivity(Intent(this, T::class.java).apply(setupIntent))
}

/**
 * 默认 SharedPreferences 的读取入口。
 *
 * 不依赖 androidx.preference，按 `PreferenceManager` 的命名规则直接取：
 * 文件名固定为 `<packageName>_preferences`，保证**历史数据继续可读**。
 * 该命名规则一旦改动，用户所有设置/词库索引都会"丢失"（实际是读到了新文件）。
 */
fun Context.defaultSharedPreferences(): SharedPreferences =
    getSharedPreferences("${packageName}_preferences", Context.MODE_PRIVATE)
