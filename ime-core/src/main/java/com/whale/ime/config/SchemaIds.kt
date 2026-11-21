package com.whale.ime.config

/**
 * Rime 方案 ID（`*.schema.yaml` 的 `schema_id`）。
 *
 * 纯常量，无运行期副作用：不读 Context、不持有状态。
 */
object SchemaIds {
    const val SCHEMA_ZH_T9 = "t9_pinyin" // 拼音九键
    const val SCHEMA_ZH_T14 = "t14_pinyin" // 拼音14键（QW/ER/TY/UI/OP …）
    const val SCHEMA_ZH_QWERTY = "pinyin" // 拼音全键
    const val SCHEMA_EN = "english"         // 英语方案
    const val SCHEMA_ZH_HANDWRITING = "handwriting" // 手写输入
    const val SCHEMA_ZH_DOUBLE_FLYPY = "double_pinyin_" // 小鹤双拼
    const val SCHEMA_ZH_STROKE = "stroke" // 五笔画

    /**
     * 输入串是「大写键码」的方案（九键 / 14键）。
     *
     * 这类方案的组合串不是拼音：键盘送进来的是键位代表字母（九键 ABC 键 → `A`，
     * 14 键 QW 键 → `Q`），由 schema 的 derive 段映射到音节。凡是需要区分
     * 「键码 / 拼音」的地方（replaceKey 还原、组合串展示、希腊字母注入、用户词
     * 别名索引）都应走这个判据，不要各写一份 `== SCHEMA_ZH_T9`。
     */
    fun isKeyCodeSchema(schemaId: String): Boolean =
        schemaId == SCHEMA_ZH_T9 || schemaId == SCHEMA_ZH_T14
}
