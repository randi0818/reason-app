package me.excuse.app.ui.today

import java.time.LocalDate
import me.excuse.app.data.db.UsageSession
import org.junit.Assert.assertEquals
import org.junit.Test

class UsageDurationTest {
    @Test
    fun todaysOpenDetailFragmentStopsAtNowInsteadOfMidnight() {
        val tenAm = 10 * 3_600_000L
        val fragments = daySessionFragments(
            sessions = listOf(session(start = tenAm, end = null)),
            nowMillis = tenAm + 120_000L,
            dayStart = 0L,
            dayEnd = 86_400_000L,
        )
        assertEquals(tenAm + 120_000L, fragments.single().displayEnd)
        assertEquals(2, wholeMinutes(fragments.asSequence().map { it.displayEnd - it.displayStart }))
    }

    @Test
    fun historicalOpenFragmentIsStillClippedAtThatDaysEnd() {
        val fragments = daySessionFragments(
            sessions = listOf(session(start = -60_000L, end = null)),
            nowMillis = 86_400_000L + 120_000L,
            dayStart = 0L,
            dayEnd = 86_400_000L,
        )
        assertEquals(0L, fragments.single().displayStart)
        assertEquals(86_400_000L, fragments.single().displayEnd)
    }

    @Test
    fun shortSessionsAreSummedBeforeConvertingToMinutes() {
        assertEquals(1, wholeMinutes(sequenceOf(25_000L, 35_000L)))
    }

    @Test
    fun windowAggregationClipsSessionsBeforeSummingMillis() {
        val sessions = listOf(
            session(start = -30_000L, end = 30_000L),
            session(start = 30_000L, end = 60_000L),
            session(start = 120_000L, end = 180_000L),
        )

        assertEquals(
            1,
            usageMinutesInWindow(
                sessions = sessions,
                openSessionEndMillis = 60_000L,
                windowStartMillis = 0L,
                windowEndMillis = 60_000L,
            ),
        )
    }

    @Test
    fun fullExclusiveDayDoesNotLoseTheLastMinute() {
        assertEquals(
            1_440,
            usageMinutesInWindow(
                sessions = listOf(session(start = 0L, end = 86_400_000L)),
                openSessionEndMillis = 86_400_000L,
                windowStartMillis = 0L,
                windowEndMillis = 86_400_000L,
            ),
        )
    }

    @Test
    fun monthTileAccessibilityIncludesDateTodayAndMinutes() {
        assertEquals(
            "2026年9月3日，今天，使用 12 分钟",
            monthDayAccessibilityLabel(LocalDate.of(2026, 9, 3), minutes = 12, isToday = true),
        )
        assertEquals(
            "2025年12月31日，没有使用记录",
            monthDayAccessibilityLabel(LocalDate.of(2025, 12, 31), minutes = 0, isToday = false),
        )
    }

    private fun session(start: Long, end: Long?) = UsageSession(
        packageName = "example.app",
        appName = "Example",
        reason = "test",
        reasonNormalized = "test",
        plannedMinutes = 1,
        startTime = start,
        endTime = end,
    )
}
