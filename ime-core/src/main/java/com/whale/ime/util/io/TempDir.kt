package com.whale.ime.util.io

import com.whale.ime.boot.Launcher
import java.io.File

inline fun <T> withTempDir(block: (File) -> T): T {
    val dir = Launcher.instance.context.cacheDir.resolve(System.currentTimeMillis().toString()).also {
        it.mkdirs()
    }
    try {
        return block(dir)
    } finally {
        dir.deleteRecursively()
    }
}
