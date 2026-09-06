package me.excuse.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceStartupPolicyTest {
    @Test
    fun recentSeedWinsOverLongWindowUsageStats() {
        val decision = decideStartupForeground(
            seededPackage = "com.android.launcher",
            historyPackage = "com.example.monitored",
        )

        assertEquals("com.android.launcher", decision.packageName)
        assertFalse(decision.recoveredFromHistory)
    }

    @Test
    fun longWindowHistoryRecoversAnAppThatWasAlreadyOpen() {
        val decision = decideStartupForeground(
            seededPackage = null,
            historyPackage = "com.example.monitored",
        )

        assertEquals("com.example.monitored", decision.packageName)
        assertTrue(decision.recoveredFromHistory)
    }

    @Test
    fun historyRecoveryIsAttemptedOnlyOncePerServiceInstance() {
        assertTrue(
            shouldAttemptStartupHistoryRecovery(
                seededPackage = null,
                isFirstPollForService = true,
            )
        )
        assertFalse(
            shouldAttemptStartupHistoryRecovery(
                seededPackage = null,
                isFirstPollForService = false,
            )
        )
        assertFalse(
            shouldAttemptStartupHistoryRecovery(
                seededPackage = "com.android.launcher",
                isFirstPollForService = true,
            )
        )
    }

    @Test
    fun historyRecoveryNeverReadsEventsFromAPreviousBoot() {
        assertEquals(
            970_000L,
            startupHistoryStartMillis(
                nowMillis = 1_000_000L,
                elapsedRealtimeMillis = 30_000L,
                recoveryWindowMillis = 86_400_000L,
            ),
        )
        assertEquals(
            913_600_000L,
            startupHistoryStartMillis(
                nowMillis = 1_000_000_000L,
                elapsedRealtimeMillis = 172_800_000L,
                recoveryWindowMillis = 86_400_000L,
            ),
        )
    }

    @Test
    fun everyRequiredPermissionMustRemainGranted() {
        assertTrue(MonitoringPermissionState(true, true, true).isReady)
        assertFalse(MonitoringPermissionState(false, true, true).isReady)
        assertFalse(MonitoringPermissionState(true, false, true).isReady)
        assertFalse(MonitoringPermissionState(true, true, false).isReady)
    }
}
