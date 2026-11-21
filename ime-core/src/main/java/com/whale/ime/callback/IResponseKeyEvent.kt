package com.whale.ime.callback

import com.whale.ime.keyboard.model.SoftKey
import com.whale.ime.preference.model.PopupMenuMode
import com.whale.ime.rime.CandidateListItem

interface IResponseKeyEvent {
    fun responseKeyEvent(sKey: SoftKey)
    fun responseLongKeyEvent(result:Pair<PopupMenuMode, String>)
    fun responseHandwritingResultEvent(words: Array<CandidateListItem>)
}
