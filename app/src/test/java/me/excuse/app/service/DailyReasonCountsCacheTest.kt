package me.excuse.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyReasonCountsCacheTest {
    private val day = 86_400_000L
    private val pkg = "example.app"

    @Test
    fun crossingMidnightInvalidatesCountsEvenInsideTheTtl() {
        val cache = DailyReasonCountsCache(ttlMillis = 60_000L)
        cache.completeRefresh(pkg, null, 0L, 0L, day - 10_000L, mapOf("reply" to 4))
        assertFalse(cache.needsRefresh(cache.get(pkg, 0L), day - 5_000L))

        assertNull(cache.get(pkg, day))
        assertTrue(cache.needsRefresh(cache.get(pkg, day), day + 1_000L))
        assertEquals(mapOf("reply" to 1), cache.recordUse(pkg, "reply", day, day + 2_000L))
    }

    @Test
    fun repeatedLocalConfirmationsCannotCarryYesterdaysCountForward() {
        val cache = DailyReasonCountsCache(ttlMillis = 60_000L)
        cache.recordUse(pkg, "reply", 0L, day - 20_000L)
        cache.recordUse(pkg, "reply", 0L, day - 1_000L)
        assertEquals(mapOf("reply" to 1), cache.recordUse(pkg, "reply", day, day + 1_000L))
        assertEquals(mapOf("reply" to 2), cache.recordUse(pkg, "reply", day, day + 2_000L))
    }

    @Test
    fun aQueryFromYesterdayCannotOverwriteTodaysLocalCount() {
        val cache = DailyReasonCountsCache(ttlMillis = 60_000L)
        cache.recordUse(pkg, "reply", day, day + 1_000L)
        assertNull(cache.completeRefresh(pkg, null, 0L, day, day + 2_000L, mapOf("reply" to 8)))
        assertEquals(mapOf("reply" to 1), cache.get(pkg, day)?.counts)
    }

    @Test
    fun aSlowSameDayQueryCannotUndoAConfirmation() {
        val cache = DailyReasonCountsCache(ttlMillis = 60_000L)
        cache.completeRefresh(pkg, null, day, day, day, mapOf("reply" to 3))
        val queried = cache.get(pkg, day)
        cache.recordUse(pkg, "reply", day, day + 1_000L)
        assertNull(cache.completeRefresh(pkg, queried, day, day, day + 2_000L, mapOf("reply" to 3)))
        assertEquals(mapOf("reply" to 4), cache.get(pkg, day)?.counts)
    }

    @Test
    fun aDifferentPackageAndAChangedLocalDayRemainIndependent() {
        val cache = DailyReasonCountsCache(ttlMillis = 60_000L)
        cache.recordUse(pkg, "reply", day, day + 1_000L)
        cache.recordUse("other.app", "watch", day, day + 1_000L)
        assertNull(cache.get(pkg, day - 3_600_000L))
        assertEquals(mapOf("watch" to 1), cache.get("other.app", day)?.counts)
        assertTrue(cache.needsRefresh(cache.get("other.app", day), day))
    }
}
