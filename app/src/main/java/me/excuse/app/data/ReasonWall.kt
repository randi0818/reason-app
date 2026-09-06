package me.excuse.app.data

import me.excuse.app.data.db.ReasonOccurrence

data class ReasonWallItem(
    val reason: String,
    val count: Int,
    val appNames: List<String>,
    val lastUsedAt: Long,
)

fun aggregateReasonWall(occurrences: List<ReasonOccurrence>): List<ReasonWallItem> =
    occurrences
        .filter { it.reasonNormalized.isNotEmpty() }
        .groupBy { it.reasonNormalized }
        .map { (_, grouped) ->
            val recentFirst = grouped.sortedByDescending { it.startTime }
            ReasonWallItem(
                reason = recentFirst.first().reason,
                count = grouped.size,
                appNames = recentFirst.map { it.appName }.distinct(),
                lastUsedAt = recentFirst.first().startTime,
            )
        }
        .sortedWith(
            compareByDescending<ReasonWallItem> { it.count }
                .thenByDescending { it.lastUsedAt }
                .thenBy { it.reason }
        )
