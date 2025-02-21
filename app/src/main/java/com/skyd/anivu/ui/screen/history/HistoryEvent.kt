package com.skyd.anivu.ui.screen.history

import com.skyd.anivu.base.mvi.MviSingleEvent

sealed interface HistoryEvent : MviSingleEvent {
    sealed interface DeleteReadHistory : HistoryEvent {
        data class Failed(val msg: String) : DeleteReadHistory
    }

    sealed interface DeleteMediaPlayHistory : HistoryEvent {
        data class Failed(val msg: String) : DeleteMediaPlayHistory
    }
}