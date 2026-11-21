package com.whale.ime.userword

/**
 * 用户词条的编码规范化与长度约束。
 *
 * 编码只保留 `a-z0-9`（统一小写），长度与词语长度都受这里的常量约束：
 * 词条的增删改（[UserWordManager]）与设置页的编辑框共用同一套规则。
 *
 * 词库的导入导出不再走独立的文本格式，统一由「备份与恢复」页的 zip 包承担
 * （见 `com.whale.ime.backup.UserDataBackup`）。
 */
object UserWordCodec {

    const val MAX_WORD_LENGTH = 20
    const val MAX_CODE_LENGTH = 24

    fun normalizeCode(raw: String): String {
        val sb = StringBuilder(raw.length)
        raw.forEach { c ->
            when (c) {
                in 'a'..'z', in 'A'..'Z' -> sb.append(c.lowercaseChar())
                in '0'..'9' -> sb.append(c)
            }
        }
        return sb.toString()
    }
}
