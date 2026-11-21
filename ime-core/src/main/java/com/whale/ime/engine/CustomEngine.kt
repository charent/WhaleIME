package com.whale.ime.engine

import com.whale.ime.keyboard.layout.InputModeSwitcher
import com.whale.ime.phrase.PhraseQuickCodeManager
import com.whale.ime.userword.UserWordManager
import com.whale.ime.util.StringUtils
import com.whale.ime.util.TimeUtils
import com.whale.ime.rime.CandidateListItem
import com.whale.ime.engine.RimeEngine
import com.whale.ime.vendor.expression.ExpressionBuilder
import com.whale.ime.preference.AppPrefs.Companion.getInstance
import kotlin.math.roundToInt

object CustomEngine {
    fun parseExpressionAtEnd(input: String): String? {
        val expressionEndPattern = "((φ|π|pi|sin|cos|tan|cot|asin|acos|atan|sinh|cosh|tanh|abs|log|log1p|ceil|floor|sqrt|cbrt|pow|exp|expm|signum|csc|sec|csch|sech|coth|toradian|todegree|\\(|\\)|^|%|\\+|-|\\*|/|\\.|e|E|\\d)+)$".toRegex()
        return expressionEndPattern.find(input.removeSuffix("="))?.value
    }

    /**
     * 是否值得做等式计算：光标前这段至少要有一个数字和一个运算符。
     * 用于 26 键等非数字盘场景，避免普通英文/中文输入也弹出计算候选。
     */
    fun isComputableExpression(expression: String?): Boolean {
        if (expression.isNullOrBlank()) return false
        return expression.any(Char::isDigit) && expression.any { it in "+-*/%^" }
    }

    fun expressionCalculator(input: String, expression: String?): Array<CandidateListItem> {
        val results = mutableListOf<CandidateListItem>()
        if(!expression.isNullOrBlank() && expression.length < 500 && !StringUtils.isNumber(expression) && !StringUtils.isLetter(expression)){
            try {
                val evaluate = ExpressionBuilder(expression).build().evaluate()
                val  resultFloat = evaluate.toFloat()
                val  resultInt = evaluate.roundToInt()
                if(evaluate.compareTo(resultInt) == 0){
                    if(!input.endsWith("=")) {
                        // 候选文本带上等式原文，点击时整体替换光标前的等式
                        results.add(CandidateListItem(RimeEngine.EXPR_TAG, "$expression=$resultInt"))
                        results.add(CandidateListItem(RimeEngine.EXPR_TAG, resultInt.toString()))
                    }
                    else results.add(CandidateListItem(RimeEngine.CUSTOM_TAG, resultInt.toString()))
                } else {
                    val resultPercentage = "${(evaluate * 100).roundToInt()}%"
                    if(!input.endsWith("=")) {
                        results.add(CandidateListItem(RimeEngine.EXPR_TAG, "$expression=$resultFloat"))
                        results.add(CandidateListItem(RimeEngine.EXPR_TAG, resultFloat.toString()))
                        results.add(CandidateListItem(RimeEngine.EXPR_TAG, "$expression≈$resultInt"))
                        if(evaluate < 10 && evaluate > 0.005) results.add(CandidateListItem(RimeEngine.EXPR_TAG, "$expression=$resultPercentage"))
                    } else {
                        results.add(CandidateListItem(RimeEngine.CUSTOM_TAG, resultFloat.toString()))
                        if(evaluate < 10 && evaluate > 0.005) results.add(CandidateListItem(RimeEngine.CUSTOM_TAG, resultPercentage))
                    }
                }
            } catch (_:Exception){ }
        }
        results.addAll(arrayOf("=", "+", "-", "*", "/", "%", ".", ",", "'", "(", ")")
            .map { CandidateListItem(RimeEngine.CUSTOM_TAG, it) })
        return results.toTypedArray()
    }

    /**
     * 联想附加候选。分成两段给调用方：[leading] 是日期这类「强建议」，必须排在 Rime
     * 联想词**之前**——塞在 5 条续写词之后会被候选栏挡在屏幕外，等于没有（「今天」
     * 的日期曾经就是这么消失的）；[trailing] 是标点，仍然排在续写词之后。
     */
    class AssociationExtras(
        val leading: List<String> = emptyList(),
        val trailing: List<String> = emptyList(),
    )

    fun predictAssociationWordsChinese(text: String): AssociationExtras {
        val trailing = mutableListOf("，", "。")
        val leading = mutableListOf<String>()
        val suffixesExclamation = setOf("啊", "呀", "呐", "啦", "噢", "哇", "吧", "呗", "了")
        val suffixesQuestion = setOf("吗", "啊", "呢", "吧", "谁", "何", "什么", "哪", "几", "多少", "怎", "难道", "岂", "不")
        val days = suffixesDays.firstOrNull{ text.endsWith(it) }
        if(days != null) leading.addAll(TimeUtils.getData(days))
        else if(suffixesExclamation.any{ text.endsWith(it) }){ trailing.add(0, "！") }
        else if(suffixesQuestion.any{ text.contains(it) }){ trailing.add(0, "？") }
        return AssociationExtras(leading, trailing)
    }

    /**
     * 日期词（今天 / 明天 / 昨天…）对应的日期候选，用于在**组合态的候选栏**里直接给出日期，
     * 与 `rq` 的日期候选走同一个 [TimeUtils.getData]；不是日期词返回空。
     */
    fun dayWordDateCandidates(word: String): List<String> =
        if (word in suffixesDays) TimeUtils.getData(word) else emptyList()

    private val suffixesDays = setOf("大前天", "前天", "昨天", "今天", "明天", "大后天", "后天")

    fun processPhrase(text: String):MutableList<String> {
        val phrases = mutableListOf<String>()
        // 常用语快捷码：默认不注入候选（整句模板只在键盘「常用语」面板里上屏，
        // 否则 `zm` 这类常用首字母会被内置短句抢占候选首位）。打开开关后，
        // 编码精确命中快捷码就把整句常用语放进最前，第一候选即该常用语。
        if (!InputModeSwitcher.isEnglish &&
            getInstance().input.phraseQuickCodeCandidate.getValue()
        ) {
            phrases.addAll(PhraseQuickCodeManager.match(text))
        }
        // 用户词库：整串编码命中排最前，自己学习/添加的词优先于自带词库。
        // 英文方案下按键串不是拼音，只认原样编码，不做读音缩写匹配（见 UserWordManager.match）。
        if (getInstance().input.userDictEnable.getValue()) {
            phrases.addAll(UserWordManager.match(text, allowAbbreviation = !InputModeSwitcher.isEnglish))
        }
        val chinesePredictionDate = getInstance().input.chinesePredictionDate.getValue()
        if(chinesePredictionDate) {
            val suffixesDate = setOf("rq", "riqi", "PGPG", "PP")
            if (suffixesDate.any { it == text }) {
                phrases.addAll(TimeUtils.getData())
            }
            val suffixesTime = setOf("sj", "shijian", "PJ", "PGGJGAM")
            if (suffixesTime.any { it == text }) {
                phrases.addAll(TimeUtils.getTime())
            }
        }
        return phrases.distinct().toMutableList()
    }
}