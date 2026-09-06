package me.excuse.app.data

import me.excuse.app.data.db.UsageSession
import org.junit.Assert.assertEquals
import org.junit.Test

class UsageRepositoryRecoveryTest {
    @Test
    fun recoveryNeverCountsPastTheConfirmedPlan() {
        val session = session(startedAt = 1_000L, plannedMinutes = 15)

        assertEquals(901_000L, recoveredSessionEndTime(session, recoveredAt = 9_000_000L))
    }

    @Test
    fun recoveryUsesRestartTimeWhenItIsStillInsideThePlan() {
        val session = session(startedAt = 1_000L, plannedMinutes = 15)

        assertEquals(301_000L, recoveredSessionEndTime(session, recoveredAt = 301_000L))
    }

    @Test
    fun clockRollbackCannotCreateANegativeSession() {
        val session = session(startedAt = 10_000L, plannedMinutes = 15)

        assertEquals(10_000L, recoveredSessionEndTime(session, recoveredAt = 5_000L))
    }

    private fun session(startedAt: Long, plannedMinutes: Int) = UsageSession(
        id = 1L,
        packageName = "com.example.a",
        appName = "A",
        reason = "reply",
        reasonNormalized = "reply",
        plannedMinutes = plannedMinutes,
        startTime = startedAt,
    )
}
