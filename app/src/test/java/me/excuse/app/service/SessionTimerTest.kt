package me.excuse.app.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.suspendCancellableCoroutine
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionTimerTest {
    @Test
    fun extensionUsesMonotonicElapsedTimeWhenWallClockJumps() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var wallNow = 1_000L
        var monotonicNow = 50_000L
        val waits = mutableListOf<Long>()
        val timer = SessionTimer(
            scope = scope,
            wallClockMillis = { wallNow },
            monotonicMillis = { monotonicNow },
            suspendFor = { duration ->
                waits += duration
                suspendCancellableCoroutine { }
            },
        )
        try {
            timer.start(1L, "com.example.a", plannedMinutes = 1) {}

            wallNow += 3_600_000L
            monotonicNow += 30_000L
            timer.extend("com.example.a", addMinutes = 1) {}

            assertEquals(listOf(60_000L, 90_000L), waits)
        } finally {
            scope.cancel()
        }
    }
}
