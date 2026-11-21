package com.whale.ime.engine.schema

object DoublePinYinUtils {
    val double_pinyin_abc = mapOf(
        'a' to "zh",
        'e' to "ch",
        'v' to "sh",
        ';' to "ing",
    )
    val double_pinyin_ziguang = mapOf(
        'u' to "zh",
        'a' to "ch",
        'i' to "sh",
        ';' to "ing",
    )
    val double_pinyin = mapOf(
        'v' to "zh",
        'i' to "ch",
        'u' to "sh",
        ';' to "ing",
    )

    val doublePinyinMap = mapOf(
        "double_pinyin_ziguang" to double_pinyin_abc,
        "double_pinyin_ziguang" to double_pinyin_ziguang,
    )

    fun getDoublePinYinComposition(rimeSchema: String, composition: String, comment: String): String {
        val compositionList = composition.filter { it.code <= 0xFF }.split("'".toRegex())
        // comment 是**首个候选**的读音，音节数不一定等于输入段数（纠错候选常少切/并切一段）。
        // 段数不等时不能 zip：zip 按短的一侧截断，尾部整段输入会被抹掉（候选栏显示 'zi'a，
        // 候选面板里却是完整的 zi'a'd）。对不齐就逐字符展开原始码，宁可显示码也不丢输入。
        val commentList = comment.split("'".toRegex())
        return buildSpannedString {
            append(composition.filter { it.code > 0xFF })
            if(comment.isEmpty() || commentList.size != compositionList.size){
                composition.forEach{  char -> append(doublePinyinMap.getOrElse(rimeSchema){double_pinyin}.getOrElse(char) {char.toString()}) }
            } else commentList.zip(compositionList).forEach { (pinyin, compo) ->
                if(compo.isEmpty()) ""
                else if (compo.length == 1) append(doublePinyinMap.getOrElse(rimeSchema){double_pinyin}.getOrElse(compo[0]) { pinyin.first().toString() })
                else if (compo.length == 2) append(pinyin)
                else if (compo.all { it.isLowerCase() }) append(pinyin)
                else {
                    append(pinyin)
                    compo.drop(2).forEach{  char -> append(doublePinyinMap.getOrElse(rimeSchema){double_pinyin}.getOrElse(char) {char.toString()}) }
                }
                append("'")
            }
        }
    }
}