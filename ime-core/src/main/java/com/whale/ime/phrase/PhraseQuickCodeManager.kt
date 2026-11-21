package com.whale.ime.phrase

import com.whale.ime.storage.WhaleDatabase
import java.util.Locale

/**
 * 常用语快捷码的内存索引。
 *
 * `phrase` 表的 `qwerty` / `t9` 两列是常用语的首字母快捷码（「我的电话是」→ `wddh`），
 * 原先只用于「常用语」面板展示。打开「快捷码上屏常用语」后，整串编码精确命中就把
 * 整句常用语塞进候选（见 `CustomEngine.processPhrase`）——候选注入在**每一次按键**
 * 都会发生，所以这里像 `UserWordManager` 一样把编码 → 正文预先建好索引，查询零 IO。
 *
 * 与 `UserWordManager` 的分工：那个管 `user_word`（用户词库），这个只管 `phrase`（常用语）。
 * 索引由 `load()` 在启动时载入，常用语增删改之后调用 [reload] 保持同步。
 */
object PhraseQuickCodeManager {

    /** 单次输入最多注入的候选数 */
    const val MAX_INJECT = 3

    private val lock = Any()

    /** 快捷码（小写）→ 常用语正文，顺序与「常用语」面板一致（置顶优先、新的在前） */
    @Volatile
    private var index: Map<String, List<String>> = emptyMap()

    @Volatile
    private var loaded = false

    private val dao get() = WhaleDatabase.instance.phraseDao()

    /** 启动时在后台线程调用：把常用语快捷码全部载入内存 */
    fun load() {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            rebuildLocked()
            loaded = true
        }
    }

    /** 常用语增删改之后调用，让索引与 `phrase` 表保持一致 */
    fun reload() {
        synchronized(lock) {
            rebuildLocked()
            loaded = true
        }
    }

    /**
     * 编码 → 常用语正文。
     *
     * @param code 组合串（全键是字母、九键是数字），大小写不敏感
     */
    fun match(code: String, limit: Int = MAX_INJECT): List<String> {
        if (!loaded || code.isEmpty()) return emptyList()
        return index[code.lowercase(Locale.ROOT)]?.take(limit).orEmpty()
    }

    private fun rebuildLocked() {
        val map = HashMap<String, MutableList<String>>()
        val rows = runCatching { dao.getAll() }.getOrDefault(emptyList())
        rows.forEach { phrase ->
            addLocked(map, phrase.qwerty, phrase.content)
            // 九键按键串：面板里没有字母键，只能靠 t9 命中
            addLocked(map, phrase.t9, phrase.content)
        }
        index = map
    }

    private fun addLocked(
        map: MutableMap<String, MutableList<String>>,
        code: String,
        content: String,
    ) {
        val key = code.trim().lowercase(Locale.ROOT)
        if (key.isEmpty() || content.isEmpty()) return
        val list = map.getOrPut(key) { mutableListOf() }
        if (content !in list) list.add(content)
    }
}
