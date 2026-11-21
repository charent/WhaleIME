package com.whale.ime.preference

import android.content.SharedPreferences
import com.whale.ime.core.R
import com.whale.ime.preference.model.DoublePinyinSchemaMode

/**
 * 输入行为（中文 / 混合 / 纠错 / 英文 / 表情 / 符号 / 回车 / 用户词典）。
 *
 * **类名即备份分组 id**（`provider::class.java.simpleName` 写进备份包 manifest），不要改名。
 */
class Input(sharedPreferences: SharedPreferences) :
    ManagedPreferenceCategory(R.string.setting_ime_input, sharedPreferences) {

    val titleChinese = category(R.string.chinese_input_setting)

    val chineseFanTi = switch(
        R.string.setting_jian_fan, "chinese_jian_fan_enable", false
    )

    val doublePYSchemaMode =
        list(
            R.string.double_pinyin_schema_mode,
            "double_pinyin_schema_mode",
            DoublePinyinSchemaMode.flypy,
            DoublePinyinSchemaMode,
            listOf(
                DoublePinyinSchemaMode.flypy,
                DoublePinyinSchemaMode.natural,
                DoublePinyinSchemaMode.abc,
                DoublePinyinSchemaMode.mspy,
                DoublePinyinSchemaMode.sogou,
                DoublePinyinSchemaMode.ziguang,
            ),
            listOf(
                R.string.double_pinyin_flypy_plus,
                R.string.double_pinyin_natural,
                R.string.double_pinyin_abc,
                R.string.double_pinyin_mspy,
                R.string.double_pinyin_sougou,
                R.string.double_pinyin_ziguang,
            )
        )

    val chinesePrediction = switch(
        R.string.chinese_association, "chinese_association_enable", true
    )

    val chinesePredictionDate = switch(
        R.string.chinese_association_date, "chinese_association_date_enable", true
    )

    val titleMixedInput = category(R.string.mixed_input_setting)

    // 中文 ↔ 数字交界处自动补一个空格（我买了3个 → 我买了 3 个）
    val chineseDigitSpace = switch(
        R.string.chinese_digit_space,
        "chinese_digit_space_enable",
        true,
        R.string.chinese_digit_space_summary
    )

    // 中文 ↔ 字母交界处自动补一个空格（中文hello → 中文 hello）
    val chineseEnglishSpace = switch(
        R.string.chinese_english_space,
        "chinese_english_space_enable",
        true,
        R.string.chinese_english_space_summary
    )

    val titlePinyinCorrection = category(R.string.pinyin_correction)

    // 拼音拼写纠错（引擎原生 corrector，schema 的 translator/enable_correction）：
    // 邻键容错进音节图，开关经 CorrectionSchema 落成 pinyin.custom.yaml patch
    val pinyinCorrectionEnable = switch(
        R.string.pinyin_correction_enable,
        "pinyin_correction_enable",
        true,
        R.string.pinyin_correction_enable_summary
    )

    // 模糊音：schema speller/algebra 的 derive 扩展（zongguo -> 中国），默认关闭
    val pinyinFuzzyEnable = switch(
        R.string.pinyin_fuzzy_enable,
        "pinyin_fuzzy_enable",
        false,
        R.string.pinyin_fuzzy_enable_summary
    )

    // j/q/x/y 后的 v 视作 u（qv -> qu 去）：schema speller/algebra 追加
    // derive/^([jqxy])u/$1v/，让 qv 与 qu 落在同一音节节点上（真正等价，不是纠错候选）。
    // 默认开启；只作用于全键拼音方案（双拼的 v 是真实键位，九键没有字母 v）
    val pinyinVAsUEnable = switch(
        R.string.pinyin_v_as_u_enable,
        "pinyin_v_as_u_enable",
        true,
        R.string.pinyin_v_as_u_enable_summary
    )

    val titleEnglish = category(R.string.EnglishInput)

    //输出英文单词:英文补全
    val abcSearchEnglishCell = switch(
        R.string.search_english_cell, "search_english_cell_enable", true
    )

    val abcSpaceAuto = switch(
        R.string.space_auto, "abc_space_auto_enable", false
    ){
        abcSearchEnglishCell.getValue()
    }

    // 英文句首自动大写（hello world. this is → Hello world. This is）
    // 默认关闭；补全与直输两种英文键盘都生效，所以不挂在 abcSearchEnglishCell 上
    val englishSentenceCase = switch(
        R.string.english_sentence_case,
        "english_sentence_case_enable",
        false,
        R.string.english_sentence_case_summary
    )

    // 英文标点后自动补空格（hf.Hds → hf. Hds），默认关闭
    val englishPunctSpace = switch(
        R.string.english_punct_space,
        "english_punct_space_enable",
        false,
        R.string.english_punct_space_summary
    )

    val titleEmoji = category(R.string.emoji_setting)
    val emojiInput = switch(
        R.string.emoji_input, "emoji_input_enable", true
    )

    val titleSymbol = category(R.string.symbol_setting)
    val symbolPairInput = switch(
        R.string.symbol_pair_input, "symbol_pair_input_enable", true
    )

    val titleEnterKey = category(R.string.enter_key_setting)

    // 聊天输入框（微信 / QQ / 飞书 / 钉钉）回车键改成「发送」：点击发送、长按换行。
    // 默认关闭；关闭时完全沿用原有 imeAction 逻辑，其它输入框也一律不受影响。
    val enterKeySendInChat = switch(
        R.string.enter_key_send_in_chat,
        "enter_key_send_in_chat",
        false,
        R.string.enter_key_send_in_chat_summary
    )

    val titleUserDict = category(R.string.user_dict)

    // 自定义词参与候选
    val userDictEnable = switch(
        R.string.user_dict_enable,
        "user_dict_enable",
        true,
        summary = R.string.user_dict_enable_summary
    )

    // 输入时选了非首位候选，自动记入用户词库
    val userDictAutoLearn = switch(
        R.string.user_dict_auto_learn,
        "user_dict_auto_learn",
        true,
        summary = R.string.user_dict_auto_learn_summary
    )

    val titlePhrases = category(R.string.phrases)

    /**
     * 常用语快捷码进候选。默认关闭：常用语是整句模板，`zm` 这类简短首字母
     * 很容易和内置词库抢首位，只有用户明确打开才让它在编码精确命中时置顶。
     */
    val phraseQuickCodeCandidate = switch(
        R.string.phrase_quick_code_candidate,
        "phrase_quick_code_candidate_enable",
        false,
        summary = R.string.phrase_quick_code_candidate_summary
    )
}
