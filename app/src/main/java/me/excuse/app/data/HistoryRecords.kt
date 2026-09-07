package me.excuse.app.data

data class HistoryRecordCounts(val sessions: Int, val events: Int) {
    val isEmpty: Boolean get() = sessions == 0 && events == 0
}
