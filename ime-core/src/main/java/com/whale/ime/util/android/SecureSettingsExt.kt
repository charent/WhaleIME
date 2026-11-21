

package com.whale.ime.util.android

import android.provider.Settings
import com.whale.ime.boot.Launcher

fun getSecureSettings(name: String): String? {
    return Settings.Secure.getString(Launcher.instance.context.contentResolver, name)
}
