package me.excuse.app.ui.today

import me.excuse.app.data.db.UsageSession

private const val MILLIS_PER_MINUTE = 60_000L

internal data class DayFragment(
    val session: UsageSession,
    val displayStart: Long,
    val displayEnd: Long,
)

/** 未结束记录始终截到当前时间；从哪个入口查看日期不能改变时长口径。 */
internal fun daySessionFragments(
    sessions: List<UsageSession>,
    nowMillis: Long,
    dayStart: Long,
    dayEnd: Long,
): List<DayFragment> = sessions.map { session ->
    DayFragment(
        session = session,
        displayStart = session.startTime.coerceAtLeast(dayStart),
        displayEnd = (session.endTime ?: nowMillis).coerceAtMost(dayEnd),
    )
}

/** 每条片段保留毫秒精度，最后只做一次向下取整，避免很多短 session 被逐条吞掉。 */
internal fun wholeMinutes(durationsMillis: Sequence<Long>): Int {
    var totalMillis = 0L
    durationsMillis.forEach { duration ->
        val positiveDuration = duration.coerceAtLeast(0L)
        totalMillis = if (Long.MAX_VALUE - totalMillis < positiveDuration) {
            Long.MAX_VALUE
        } else {
            totalMillis + positiveDuration
        }
    }
    return (totalMillis / MILLIS_PER_MINUTE)
        .coerceAtMost(Int.MAX_VALUE.toLong())
        .toInt()
}

internal fun visibleDurationMillis(
    startMillis: Long,
    endMillis: Long,
    windowStartMillis: Long,
    windowEndMillis: Long,
): Long {
    val visibleStart = startMillis.coerceAtLeast(windowStartMillis)
    val visibleEnd = endMillis.coerceAtMost(windowEndMillis)
    return (visibleEnd - visibleStart).coerceAtLeast(0L)
}

internal fun usageMinutesInWindow(
    sessions: Iterable<UsageSession>,
    openSessionEndMillis: Long,
    windowStartMillis: Long,
    windowEndMillis: Long,
): Int = wholeMinutes(
    sessions.asSequence().map { session ->
        visibleDurationMillis(
            startMillis = session.startTime,
            endMillis = session.endTime ?: openSessionEndMillis,
            windowStartMillis = windowStartMillis,
            windowEndMillis = windowEndMillis,
        )
    }
)
