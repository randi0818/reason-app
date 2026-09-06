package me.excuse.app.data.db

data class ReasonOccurrence(
    val reason: String,
    val reasonNormalized: String,
    val appName: String,
    val startTime: Long,
)
