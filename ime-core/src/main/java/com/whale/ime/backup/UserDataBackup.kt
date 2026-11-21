package com.whale.ime.backup

import android.content.SharedPreferences
import androidx.annotation.StringRes
import com.whale.ime.core.R
import com.whale.ime.boot.Launcher
import com.whale.ime.theme.ThemeManager
import com.whale.ime.storage.WhaleDatabase
import com.whale.ime.storage.entity.Clipboard
import com.whale.ime.storage.entity.Phrase
import com.whale.ime.storage.entity.SideSymbol
import com.whale.ime.storage.entity.SkbFun
import com.whale.ime.storage.entity.UsedSymbol
import com.whale.ime.storage.entity.UserWord
import com.whale.ime.preference.AppPrefs
import com.whale.ime.dispatch.InputDispatcher
import com.whale.ime.userword.UserWordManager
import com.whale.ime.phrase.PhraseQuickCodeManager
import com.whale.ime.util.android.appVersionName
import com.whale.ime.util.android.defaultSharedPreferences
import com.whale.ime.util.android.versionCodeCompat
import com.whale.ime.util.io.withTempDir
import com.whale.ime.engine.RimeEngine
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import com.whale.ime.boot.rime.RimePaths

/**
 * 用户数据的选择性备份 / 恢复。
 *
 * 备份包（zip，format v2）：
 * ```
 * manifest.json                 # 格式版本 + 分类清单（categoryId -> 条目数），第一个 entry
 * settings/preferences.json     # 每个偏好组一个对象，值带类型标记
 * dict/user_word.jsonl          # 手动添加的词
 * dict/learned_word.jsonl       # 自动学习的词
 * clipboard/clipboard.jsonl
 * phrase/phrase.jsonl
 * symbols/side_symbol.jsonl / used_symbol.jsonl / skbfun.jsonl
 * themes/…                      # 自定义主题 json 与背景图
 * rime-user/…                   # Rime 用户目录（userdb / 自定义 yaml，不含 build）
 * ```
 *
 * 与旧版（`shared_prefs` + `databases` + `external` 整目录覆盖）不兼容：那个包会把
 * `rime-v<N>` 共享词库副本（约 200MB）一起打包，且只能全量恢复，因此彻底换成按分类导出。
 */
object UserDataBackup {

    const val FORMAT_VERSION = 2

    /** 设置项按偏好组拆分，manifest 里以 `prefs:<groupId>` 记录 */
    const val PREF_GROUP_PREFIX = "prefs:"

    private const val MANIFEST_NAME = "manifest.json"
    private const val SETTINGS_NAME = "settings/preferences.json"
    private const val WORD_MANUAL_NAME = "dict/user_word.jsonl"
    private const val WORD_LEARNED_NAME = "dict/learned_word.jsonl"
    private const val CLIPBOARD_NAME = "clipboard/clipboard.jsonl"
    private const val PHRASE_NAME = "phrase/phrase.jsonl"
    private const val SIDE_SYMBOL_NAME = "symbols/side_symbol.jsonl"
    private const val USED_SYMBOL_NAME = "symbols/used_symbol.jsonl"
    private const val SKBFUN_NAME = "symbols/skbfun.jsonl"
    private const val THEME_PREFIX = "themes/"
    private const val RIME_PREFIX = "rime-user/"

    /** 备份包里的元信息；写在 zip 第一个 entry，导入前只读它就能展示可恢复内容 */
    @Serializable
    data class Manifest(
        val format: Int = FORMAT_VERSION,
        val createdAt: Long = 0L,
        val appVersion: String = "",
        val versionCode: Long = 0L,
        /** categoryId（含 `prefs:<groupId>`）-> 条目数 */
        val categories: Map<String, Int> = emptyMap(),
    )

    /** 冲突处理：覆盖 = 先清空目标数据再写入；合并 = 只补目标里没有的条目 */
    enum class ImportMode { OVERWRITE, MERGE }

    data class ImportResult(
        /** categoryId -> 实际写入条目数 */
        val counts: Map<String, Int>,
        /** 恢复了 Rime 用户目录，需要重启应用才生效 */
        val needsRestart: Boolean,
    )

    /** 导出页展示的各分类条目数 */
    data class Stats(
        val manualWords: Int = 0,
        val learnedWords: Int = 0,
        val clipboard: Int = 0,
        val phrases: Int = 0,
        val sideSymbols: Int = 0,
        val usedSymbols: Int = 0,
        val themes: Int = 0,
    )

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    /** jsonl 必须一行一条，不能再 prettyPrint */
    private val lineJson = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    private val context get() = Launcher.instance.context

    private val themeDir get() = File(context.getExternalFilesDir(null), "theme")

    private val rimeUserDir get() = RimePaths.userDir(context)

    // ---------------------------------------------------------------- 统计

    fun stats(): Stats {
        val db = WhaleDatabase.instance
        return Stats(
            manualWords = db.userWordDao().countQuery("", UserWord.SOURCE_MANUAL),
            learnedWords = db.userWordDao().countQuery("", UserWord.SOURCE_LEARNED),
            clipboard = db.clipboardDao().getCount(),
            phrases = db.phraseDao().count(),
            sideSymbols = db.sideSymbolDao().getAllSideSymbolPinyin().size +
                    db.sideSymbolDao().getAllSideSymbolNumber().size,
            usedSymbols = db.usedSymbolDao().getCount("symbol") +
                    db.usedSymbolDao().getCount("emoji") +
                    db.usedSymbolDao().getCount("kaomoji"),
            themes = listThemeFiles().count { it.extension == "json" },
        )
    }

    // ---------------------------------------------------------------- 导出

    fun export(
        dest: OutputStream,
        categories: Set<String>,
        prefGroups: Set<String> = emptySet(),
        timestamp: Long = System.currentTimeMillis(),
    ): Result<Manifest> = runCatching {
        val db = WhaleDatabase.instance
        val counts = linkedMapOf<String, Int>()

        val words = if (BackupCategory.USER_DICT.id in categories ||
            BackupCategory.LEARNED_WORDS.id in categories
        ) db.userWordDao().getAll() else emptyList()
        val manualWords = if (BackupCategory.USER_DICT.id in categories)
            words.filter { it.source == UserWord.SOURCE_MANUAL } else emptyList()
        val learnedWords = if (BackupCategory.LEARNED_WORDS.id in categories)
            words.filter { it.source == UserWord.SOURCE_LEARNED } else emptyList()
        val clipboard = if (BackupCategory.CLIPBOARD.id in categories)
            db.clipboardDao().getAll() else emptyList()
        val phrases = if (BackupCategory.PHRASE.id in categories)
            db.phraseDao().getAll() else emptyList()
        val sideSymbols = if (BackupCategory.SYMBOLS.id in categories)
            db.sideSymbolDao().getAllSideSymbolPinyin() +
                    db.sideSymbolDao().getAllSideSymbolNumber() else emptyList()
        val usedSymbols = if (BackupCategory.SYMBOLS.id in categories)
            db.usedSymbolDao().getAllUsedSymbol() +
                    db.usedSymbolDao().getAllSymbolEmoji() +
                    db.usedSymbolDao().getAllSymbolKaomoji() else emptyList()
        val skbFuns = if (BackupCategory.SYMBOLS.id in categories)
            db.skbFunDao().getAll() else emptyList()

        val settings = if (BackupCategory.SETTINGS.id in categories)
            collectPreferences(prefGroups) else emptyMap()
        val themeFiles = if (BackupCategory.THEME.id in categories)
            listThemeFiles() else emptyList()
        val rimeFiles = if (BackupCategory.RIME_USER.id in categories)
            listRimeUserFiles() else emptyList()

        counts[BackupCategory.USER_DICT.id] = manualWords.size
        counts[BackupCategory.LEARNED_WORDS.id] = learnedWords.size
        counts[BackupCategory.CLIPBOARD.id] = clipboard.size
        counts[BackupCategory.PHRASE.id] = phrases.size
        counts[BackupCategory.SYMBOLS.id] = sideSymbols.size + usedSymbols.size + skbFuns.size
        counts[BackupCategory.THEME.id] = themeFiles.size
        counts[BackupCategory.RIME_USER.id] = rimeFiles.size
        settings.forEach { (groupId, values) ->
            counts[PREF_GROUP_PREFIX + groupId] = values.size
        }
        counts.entries.removeAll { it.value <= 0 }

        val manifest = Manifest(
            createdAt = timestamp,
            appVersion = context.appVersionName(),
            versionCode = context.packageManager
                .getPackageInfo(context.packageName, 0).versionCodeCompat,
            categories = counts,
        )

        // Rime 的 userdb 是 SQLite，写入过程中复制可能拿到半写状态：先收起引擎，写完再拉起来。
        val stopRime = rimeFiles.isNotEmpty() && RimeEngine.isStarted()
        if (stopRime) runCatching { RimeEngine.destroy() }
        try {
            ZipOutputStream(dest.buffered()).use { zip ->
                zip.putNextEntry(ZipEntry(MANIFEST_NAME))
                zip.write(json.encodeToString(manifest).toByteArray(Charsets.UTF_8))
                zip.closeEntry()

                writeJsonLines(zip, WORD_MANUAL_NAME, manualWords)
                writeJsonLines(zip, WORD_LEARNED_NAME, learnedWords)
                writeJsonLines(zip, CLIPBOARD_NAME, clipboard)
                writeJsonLines(zip, PHRASE_NAME, phrases)
                writeJsonLines(zip, SIDE_SYMBOL_NAME, sideSymbols)
                writeJsonLines(zip, USED_SYMBOL_NAME, usedSymbols)
                writeJsonLines(zip, SKBFUN_NAME, skbFuns)

                if (settings.isNotEmpty()) {
                    zip.putNextEntry(ZipEntry(SETTINGS_NAME))
                    zip.write(encodePreferences(settings).toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
                writeFileTree(zip, THEME_PREFIX, themeDir, themeFiles)
                writeFileTree(zip, RIME_PREFIX, rimeUserDir, rimeFiles)
            }
        } finally {
            if (stopRime) runCatching { InputDispatcher.resetIme() }
        }
        manifest
    }

    // ---------------------------------------------------------------- 导入

    /** 只读 manifest：让导入页先展示「这个包里有什么」再决定恢复哪些 */
    fun inspect(src: InputStream): Result<Manifest> = runCatching {
        ZipInputStream(src.buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == MANIFEST_NAME) {
                    val text = zip.readBytes().toString(Charsets.UTF_8)
                    return@runCatching json.decodeFromString<Manifest>(text)
                }
                entry = zip.nextEntry
            }
        }
        throw IllegalStateException(context.getString(R.string.exception_user_data_metadata))
    }

    fun import(
        src: InputStream,
        categories: Set<String>,
        prefGroups: Set<String> = emptySet(),
        mode: ImportMode = ImportMode.OVERWRITE,
    ): Result<ImportResult> = runCatching {
        withTempDir { dir ->
            ZipInputStream(src.buffered()).use { zip -> extract(zip, dir) }
            val manifest = File(dir, MANIFEST_NAME).takeIf { it.isFile }
                ?.let { json.decodeFromString<Manifest>(it.readText(Charsets.UTF_8)) }
                ?: throw IllegalStateException(context.getString(R.string.exception_user_data_metadata))
            if (manifest.format > FORMAT_VERSION) {
                throw IllegalStateException(context.getString(R.string.backup_error_newer_format))
            }

            val db = WhaleDatabase.instance
            val counts = linkedMapOf<String, Int>()
            var needsRestart = false

            if (BackupCategory.USER_DICT.id in categories) {
                val words = readJsonLines<UserWord>(dir, WORD_MANUAL_NAME)
                counts[BackupCategory.USER_DICT.id] = importWords(
                    db, words, UserWord.SOURCE_MANUAL, mode
                )
            }
            if (BackupCategory.LEARNED_WORDS.id in categories) {
                val words = readJsonLines<UserWord>(dir, WORD_LEARNED_NAME)
                counts[BackupCategory.LEARNED_WORDS.id] = importWords(
                    db, words, UserWord.SOURCE_LEARNED, mode
                )
            }
            if (BackupCategory.CLIPBOARD.id in categories) {
                counts[BackupCategory.CLIPBOARD.id] =
                    importClipboard(db, readJsonLines<Clipboard>(dir, CLIPBOARD_NAME), mode)
            }
            if (BackupCategory.PHRASE.id in categories) {
                counts[BackupCategory.PHRASE.id] =
                    importPhrases(db, readJsonLines<Phrase>(dir, PHRASE_NAME), mode)
            }
            if (BackupCategory.SYMBOLS.id in categories) {
                counts[BackupCategory.SYMBOLS.id] = importSymbols(
                    db,
                    readJsonLines<SideSymbol>(dir, SIDE_SYMBOL_NAME),
                    readJsonLines<UsedSymbol>(dir, USED_SYMBOL_NAME),
                    readJsonLines<SkbFun>(dir, SKBFUN_NAME),
                    mode,
                )
            }
            if (BackupCategory.SETTINGS.id in categories) {
                val applied = importSettings(dir, prefGroups)
                counts.putAll(applied)
                // 设置项写入会立刻刷新界面，但引擎侧（简繁/模糊音/双拼方案）不会自愈，
                // 提示重启一次而不是悄悄留下半生效状态
                if (applied.isNotEmpty()) needsRestart = true
            }
            if (BackupCategory.THEME.id in categories) {
                counts[BackupCategory.THEME.id] = importThemes(dir, mode)
            }
            if (BackupCategory.RIME_USER.id in categories) {
                needsRestart = importRimeUser(dir) || needsRestart
                counts[BackupCategory.RIME_USER.id] = countFiles(File(dir, "rime-user"))
            }

            // 词库与主题的进程内缓存：不重载的话键盘仍在用旧索引
            if (BackupCategory.USER_DICT.id in categories ||
                BackupCategory.LEARNED_WORDS.id in categories
            ) {
                runCatching { UserWordManager.reload() }
            }
            if (BackupCategory.THEME.id in categories) {
                runCatching { ThemeManager.refreshThemes() }
            }
            counts.entries.removeAll { it.value <= 0 }
            ImportResult(counts, needsRestart)
        }
    }

    private fun importWords(
        db: WhaleDatabase,
        words: List<UserWord>,
        source: Int,
        mode: ImportMode,
    ): Int {
        if (words.isEmpty()) return 0
        val dao = db.userWordDao()
        if (mode == ImportMode.OVERWRITE) {
            dao.deleteBySource(source)
            dao.insertAll(words)
            return words.size
        }
        val existing = dao.getAll().mapTo(HashSet()) { it.word to it.code }
        val incoming = words.filter { (it.word to it.code) !in existing }
        dao.insertAll(incoming)
        return incoming.size
    }

    private fun importClipboard(db: WhaleDatabase, items: List<Clipboard>, mode: ImportMode): Int {
        if (items.isEmpty()) return 0
        val dao = db.clipboardDao()
        if (mode == ImportMode.OVERWRITE) {
            dao.deleteAll()
            dao.insertAll(items)
            return items.size
        }
        val existing = dao.getAll().mapTo(HashSet()) { it.content }
        val incoming = items.filter { it.content !in existing }
        dao.insertAll(incoming)
        return incoming.size
    }

    private fun importPhrases(db: WhaleDatabase, items: List<Phrase>, mode: ImportMode): Int {
        if (items.isEmpty()) return 0
        val dao = db.phraseDao()
        if (mode == ImportMode.OVERWRITE) {
            dao.deleteAll()
            dao.insertAll(items)
            // 快捷码索引跟着导入结果重建（开关打开时常用语会进候选）
            PhraseQuickCodeManager.reload()
            return items.size
        }
        // INSERT IGNORE：保留本地改过的快捷码与置顶状态
        dao.insertIgnore(items)
        PhraseQuickCodeManager.reload()
        return items.size
    }

    private fun importSymbols(
        db: WhaleDatabase,
        sideSymbols: List<SideSymbol>,
        usedSymbols: List<UsedSymbol>,
        skbFuns: List<SkbFun>,
        mode: ImportMode,
    ): Int {
        if (sideSymbols.isEmpty() && usedSymbols.isEmpty() && skbFuns.isEmpty()) return 0
        val sideDao = db.sideSymbolDao()
        if (mode == ImportMode.OVERWRITE) {
            sideDao.deleteAll("pinyin")
            sideDao.deleteAll("number")
            db.usedSymbolDao().deleteAll()
            db.skbFunDao().deleteAll()
        }
        // 侧边符号按 rowid 决定显示顺序：先清空再插入才能保住备份里的顺序
        if (mode == ImportMode.MERGE && sideSymbols.isNotEmpty()) {
            val existing = (sideDao.getAllSideSymbolPinyin() + sideDao.getAllSideSymbolNumber())
                .mapTo(HashSet()) { it.symbol }
            sideSymbols.filter { it.symbol !in existing }
                .takeIf { it.isNotEmpty() }?.let { sideDao.insertAll(it) }
            usedSymbols.takeIf { it.isNotEmpty() }?.let { db.usedSymbolDao().insertAll(it) }
            skbFuns.takeIf { it.isNotEmpty() }?.let { db.skbFunDao().insertAll(it) }
        } else {
            sideSymbols.takeIf { it.isNotEmpty() }?.let { sideDao.insertAll(it) }
            usedSymbols.takeIf { it.isNotEmpty() }?.let { db.usedSymbolDao().insertAll(it) }
            skbFuns.takeIf { it.isNotEmpty() }?.let { db.skbFunDao().insertAll(it) }
        }
        return sideSymbols.size + usedSymbols.size + skbFuns.size
    }

    private fun importSettings(dir: File, prefGroups: Set<String>): Map<String, Int> {
        val file = File(dir, SETTINGS_NAME)
        if (!file.isFile) return emptyMap()
        val root = runCatching { json.parseToJsonElement(file.readText(Charsets.UTF_8)).jsonObject }
            .getOrElse { return emptyMap() }
        val groups = AppPrefs.getInstance().backupPreferenceGroups().associateBy { it.id }
        val editor = sharedPreferences().edit()
        val counts = linkedMapOf<String, Int>()
        root.forEach { (groupId, element) ->
            if (groupId !in prefGroups) return@forEach
            val group = groups[groupId] ?: return@forEach
            val values = element as? JsonObject ?: return@forEach
            var applied = 0
            values.forEach { (key, meta) ->
                // 版本漂移：只恢复当前版本仍注册的 key，避免写回已下线的配置
                if (key in group.keys && meta is JsonObject && writePref(editor, key, meta)) applied++
            }
            if (applied > 0) counts[PREF_GROUP_PREFIX + groupId] = applied
        }
        editor.apply()
        if (counts.isNotEmpty()) {
            AppPrefs.getInstance().syncToDeviceEncryptedStorage()
        }
        return counts
    }

    private fun importThemes(dir: File, mode: ImportMode): Int {
        val source = File(dir, "themes")
        if (!source.isDirectory) return 0
        val target = themeDir.also { it.mkdirs() }
        if (mode == ImportMode.OVERWRITE) {
            target.listFiles()?.forEach { it.deleteRecursively() }
        }
        var count = 0
        source.walkTopDown().filter { it.isFile }.forEach { file ->
            val dest = File(target, file.relativeTo(source).invariantSeparatorsPath)
            if (mode == ImportMode.MERGE && dest.exists()) return@forEach
            dest.parentFile?.mkdirs()
            file.copyTo(dest, overwrite = true)
            if (dest.extension == "json") count++
        }
        return count
    }

    private fun importRimeUser(dir: File): Boolean {
        val source = File(dir, "rime-user")
        if (!source.isDirectory) return false
        // userdb 是 SQLite，引擎持有时覆盖会写坏：先收起引擎，由调用方提示重启
        if (RimeEngine.isStarted()) runCatching { RimeEngine.destroy() }
        val target = rimeUserDir
        // 保留 build/（部署产物，可从共享词库重建），其余（userdb、custom.yaml）整体替换
        target.listFiles()?.filter { it.name != "build" }?.forEach { it.deleteRecursively() }
        source.walkTopDown().filter { it.isFile }.forEach { file ->
            val dest = File(target, file.relativeTo(source).invariantSeparatorsPath)
            dest.parentFile?.mkdirs()
            file.copyTo(dest, overwrite = true)
        }
        // 引擎已被收起：立刻按新目录重新拉起来。不然用户点「稍后重启」继续打字时，
        // 按键路径会打到已销毁的 native 实例上。
        runCatching { InputDispatcher.resetIme() }
        return true
    }

    // ---------------------------------------------------------------- 偏好编解码

    private fun sharedPreferences(): SharedPreferences = context.defaultSharedPreferences()

    /**
     * 按组收集偏好原值。
     *
     * 只取 [AppPrefs.backupPreferenceGroups] 里登记过的 key：备份不带 internal 组，
     * 版本标记、剪贴板游标、自动备份目录这些本机状态不会被搬走。
     */
    private fun collectPreferences(prefGroups: Set<String>): Map<String, Map<String, JsonObject>> {
        val all = sharedPreferences().all
        val result = linkedMapOf<String, Map<String, JsonObject>>()
        AppPrefs.getInstance().backupPreferenceGroups().forEach { group ->
            if (group.id !in prefGroups) return@forEach
            val values = linkedMapOf<String, JsonObject>()
            group.keys.forEach { key ->
                if (key in all) encodePrefValue(all[key])?.let { values[key] = it }
            }
            if (values.isNotEmpty()) result[group.id] = values
        }
        return result
    }

    private fun encodePreferences(data: Map<String, Map<String, JsonObject>>): String {
        val root = buildJsonObject {
            data.forEach { (groupId, values) ->
                putJsonObject(groupId) { values.forEach { (key, value) -> put(key, value) } }
            }
        }
        return json.encodeToString(JsonObject.serializer(), root)
    }

    private fun encodePrefValue(value: Any?): JsonObject? = when (value) {
        is Boolean -> buildJsonObject { put("t", "bool"); put("v", value) }
        is Int -> buildJsonObject { put("t", "int"); put("v", value) }
        is Long -> buildJsonObject { put("t", "long"); put("v", value) }
        is Float -> buildJsonObject { put("t", "float"); put("v", value) }
        is String -> buildJsonObject { put("t", "string"); put("v", value) }
        is Set<*> -> buildJsonObject {
            put("t", "string_set")
            putJsonArray("v") { value.forEach { add(JsonPrimitive(it.toString())) } }
        }
        else -> null
    }

    private fun writePref(editor: SharedPreferences.Editor, key: String, value: JsonObject): Boolean {
        val raw = value["v"] ?: return false
        return when (value["t"]?.jsonPrimitive?.contentOrNull) {
            "bool" -> { editor.putBoolean(key, raw.jsonPrimitive.boolean); true }
            "int" -> { editor.putInt(key, raw.jsonPrimitive.int); true }
            "long" -> { editor.putLong(key, raw.jsonPrimitive.long); true }
            "float" -> { editor.putFloat(key, raw.jsonPrimitive.float); true }
            "string" -> { editor.putString(key, raw.jsonPrimitive.content); true }
            "string_set" -> {
                val array = raw as? JsonArray ?: return false
                editor.putStringSet(
                    key,
                    array.mapNotNull { element ->
                        (element as? JsonPrimitive)?.takeIf { it.isString }?.content
                    }.toSet(),
                )
                true
            }
            else -> false
        }
    }

    // ---------------------------------------------------------------- 文件

    private fun listThemeFiles(): List<File> =
        themeDir.takeIf { it.isDirectory }
            ?.walkTopDown()?.filter { it.isFile }?.toList()
            ?: emptyList()

    private fun listRimeUserFiles(): List<File> =
        rimeUserDir.takeIf { it.isDirectory }
            ?.walkTopDown()
            ?.filter { it.isFile && !it.relativeTo(rimeUserDir).invariantSeparatorsPath.startsWith("build/") }
            ?.toList()
            ?: emptyList()

    private fun countFiles(dir: File): Int =
        dir.takeIf { it.isDirectory }?.walkTopDown()?.count { it.isFile } ?: 0

    private fun writeFileTree(
        zip: ZipOutputStream,
        prefix: String,
        root: File,
        files: List<File>,
    ) {
        files.forEach { file ->
            val relative = runCatching { file.relativeTo(root).invariantSeparatorsPath }
                .getOrElse { file.name }
            zip.putNextEntry(ZipEntry(prefix + relative))
            file.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
    }

    private inline fun <reified T> writeJsonLines(zip: ZipOutputStream, name: String, items: List<T>) {
        if (items.isEmpty()) return
        zip.putNextEntry(ZipEntry(name))
        items.forEach { item ->
            zip.write(lineJson.encodeToString(item).toByteArray(Charsets.UTF_8))
            zip.write('\n'.code)
        }
        zip.closeEntry()
    }

    private inline fun <reified T> readJsonLines(dir: File, name: String): List<T> {
        val file = File(dir, name)
        if (!file.isFile) return emptyList()
        return file.useLines { lines ->
            lines.filter { it.isNotBlank() }
                .mapNotNull { runCatching { lineJson.decodeFromString<T>(it) }.getOrNull() }
                .toList()
        }
    }

    private fun extract(zip: ZipInputStream, destDir: File) {
        val canonicalDest = destDir.canonicalPath + File.separator
        var entry = zip.nextEntry
        while (entry != null) {
            val file = File(destDir, entry.name)
            // 防目录穿越：解压结果必须落在临时目录内（含分隔符，避免 rc 前缀误判）
            if (file.canonicalPath + File.separator != canonicalDest &&
                !file.canonicalPath.startsWith(canonicalDest)
            ) {
                throw SecurityException(entry.name)
            }
            if (entry.isDirectory) {
                file.mkdirs()
            } else {
                file.parentFile?.mkdirs()
                file.outputStream().use { zip.copyTo(it) }
            }
            entry = zip.nextEntry
        }
    }
}

/**
 * 备份分类。`SETTINGS` 只是「设置项」这个总开关，实际条目按 [AppPrefs.backupPreferenceGroups]
 * 拆成 `prefs:<groupId>` 写进 manifest，导入页可以逐组勾选。
 */
enum class BackupCategory(
    val id: String,
    @StringRes val title: Int,
    /** 默认勾选：只带「用户词典 + 剪贴板」这类真正属于用户的内容 */
    val defaultSelected: Boolean,
    val advanced: Boolean = false,
) {
    USER_DICT("user_dict", R.string.backup_category_user_dict, true),
    LEARNED_WORDS("learned_words", R.string.backup_category_learned, true),
    CLIPBOARD("clipboard", R.string.backup_category_clipboard, true),
    PHRASE("phrase", R.string.backup_category_phrase, false),
    SYMBOLS("symbols", R.string.backup_category_symbols, false),
    SETTINGS("settings", R.string.backup_category_settings, false),
    THEME("theme", R.string.backup_category_theme, false),
    RIME_USER("rime_user", R.string.backup_category_rime_user, false, advanced = true);

    companion object {
        val DefaultSelection: Set<String> =
            entries.filter { it.defaultSelected }.mapTo(LinkedHashSet()) { it.id }

        fun of(id: String): BackupCategory? = entries.find { it.id == id }
    }
}


