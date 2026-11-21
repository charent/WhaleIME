package com.whale.ime.callback

import com.whale.ime.rime.CandidateListItem

fun interface IHandWritingCallBack {
    fun onSucess(item:Array<CandidateListItem>)
}