package com.whale.ime.dispatch

import android.view.KeyEvent
import com.whale.ime.preference.AppPrefs
import com.whale.ime.engine.RimeEngine

object InputDispatcher {

    // 初始化输入法
    @Synchronized
    fun initImeSchema(schema: String) {
        // 换方案（中/英、123、手写）会让引擎丢弃未上屏的组合串，候选栏里的快照必须跟着作废，
        // 否则切完方案会看到上一轮的拼音留在候选区、候选列表却是空的。
        val hadComposition = RimeEngine.hasComposition || RimeEngine.showComposition.isNotEmpty()
        RimeEngine.selectSchema(schema)
        nativeUpdateImeOption()
        if (hadComposition) {
            RimeEngine.reset()
            DictDecoder.reset()
        }
    }

    fun getCurrentRimeSchema(): String {
        return RimeEngine.getCurrentRimeSchema()
    }

    // 传入一个键码
    fun inputKeyCode(event: KeyEvent) {
        DictDecoder.inputAction()
        RimeEngine.onNormalKey(event)
    }

    // 是否输入完毕，等待上屏。
    val isFinish: Boolean
        get() = RimeEngine.isFinish()

    // 选择某个候选拼音
    fun selectPrefix(index: Int) {
        DictDecoder.selectPrefix(index)
        RimeEngine.selectPinyin(index)
    }

    // 执行选择动作，选择了index指向的词语
    fun getWordSelectedWord(index: Int) {
        if (DictDecoder.isAssociate) {
            // 联想：后缀计算必须基于被点击的那条候选文本；不能在 selectAssociation 里
            // 用下标反查 showCandidates（customPhraseSize 在联想态是脏值，会错位/取空，
            // 取空时 DictDecoder 的兜底会把整词再上屏一遍，出现"天气天气好"）
            RimeEngine.selectAssociation(index, DictDecoder.getCandidate(index)?.text ?: "")
        }
        else if(!isFinish)RimeEngine.selectCandidate(index)
    }

    // 删除操作
    fun deleteAction() {
        DictDecoder.deleteAction()
        RimeEngine.onDeleteKey()
    }

    // 重置输入状态
    fun reset() {
        // 顺序不能反：showComposition 是引擎组合串的加工产物，先发快照会把上一次的拼音写进 UI，
        // 而随后清空引擎的人不会再重发状态，候选栏就永久停在「只有编码胶囊」的空态。
        RimeEngine.reset()
        DictDecoder.reset()
    }

    // 释放内存
    fun resetIme() {
        RimeEngine.destroy()
        initImeSchema(AppPrefs.getInstance().internal.pinyinModeRime.getValue())
    }

    // 根据输入的字符查询候选词
    fun predictAssociationWords(words: String) {
        DictDecoder.getAssociateWord(words)
        RimeEngine.predictAssociationWords(words)
    }

    // 刷新引擎配置
    fun nativeUpdateImeOption() {
        val chineseFanTi = AppPrefs.getInstance().input.chineseFanTi.getValue()
        RimeEngine.setImeOption("traditionalization", chineseFanTi)
        val emojiInput = AppPrefs.getInstance().input.emojiInput.getValue()
        RimeEngine.setImeOption("emoji", emojiInput)
    }

    fun setCharCase(charCase: Int) {
        RimeEngine.setCharCase(charCase)
    }
}