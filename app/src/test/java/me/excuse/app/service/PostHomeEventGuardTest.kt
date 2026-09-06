package me.excuse.app.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PostHomeEventGuardTest {

    private val appA = "com.example.a"
    private val monitored = setOf(appA)

    @Test
    fun dropsMonitoredResumeFromBeforeForcedHome() {
        val guard = PostHomeEventGuard(usageStatsSuppressMs = 2_000L)

        guard.markHome(targetPackage = appA, now = 1_000L)

        assertTrue(
            guard.shouldDropStaleResume(
                isResume = true,
                eventTimestamp = 900L,
                packageName = appA,
                monitored = monitored,
                now = 1_001L,
            )
        )
    }

    @Test
    fun allowsRealReentryAfterForcedHome() {
        val guard = PostHomeEventGuard(usageStatsSuppressMs = 2_000L)

        guard.markHome(targetPackage = appA, now = 1_000L)

        // launcher 在 Recents 中也会 RESUMED，必须等退出页完成窗口交接。
        assertFalse(
            guard.shouldDropStaleResume(
                isResume = true,
                // launcher 可在 startActivity(HOME) 的 binder 返回前、与请求同毫秒记时。
                eventTimestamp = 1_000L,
                packageName = "com.example.launcher",
                monitored = monitored,
                now = 1_001L,
            )
        )
        assertTrue(guard.shouldSkipUsageStatsReconcile(now = 1_001L))
        guard.completeHome(now = 1_000L)
        assertFalse(guard.shouldSkipUsageStatsReconcile(now = 1_001L))

        assertFalse(
            guard.shouldDropStaleResume(
                isResume = true,
                eventTimestamp = 1_001L,
                packageName = appA,
                monitored = monitored,
                now = 1_002L,
            )
        )
    }

    @Test
    fun suppressesUsageStatsReconcileOnlyTemporarily() {
        val guard = PostHomeEventGuard(usageStatsSuppressMs = 2_000L)

        guard.markHome(targetPackage = appA, now = 1_000L)

        assertTrue(guard.shouldSkipUsageStatsReconcile(now = 2_999L))
        assertFalse(guard.shouldSkipUsageStatsReconcile(now = 3_000L))
    }

    @Test
    fun canSuppressUsageStatsPastFreshnessWindow() {
        val guard = PostHomeEventGuard(usageStatsSuppressMs = 6_000L)

        guard.markHome(targetPackage = appA, now = 1_000L)

        assertTrue(guard.shouldSkipUsageStatsReconcile(now = 6_999L))
        assertFalse(guard.shouldSkipUsageStatsReconcile(now = 7_000L))
    }

    @Test
    fun dropsPostMarkTargetResumeUntilHomeWindowHandoffCompletes() {
        val guard = PostHomeEventGuard(usageStatsSuppressMs = 6_000L)
        guard.markHome(targetPackage = appA, now = 1_000L)

        assertTrue(
            guard.shouldDropStaleResume(
                isResume = true,
                eventTimestamp = 1_001L,
                packageName = appA,
                monitored = monitored,
                now = 1_001L,
            )
        )
        assertTrue(
            guard.shouldDropStaleResume(
                isResume = true,
                eventTimestamp = 1_002L,
                packageName = appA,
                monitored = monitored,
                now = 1_002L,
            )
        )
        assertFalse(
            guard.shouldDropStaleResume(
                isResume = true,
                eventTimestamp = 1_003L,
                packageName = "com.example.launcher",
                monitored = monitored,
                now = 1_003L,
            )
        )
        assertTrue(guard.shouldSkipUsageStatsReconcile(now = 1_003L))
        guard.completeHome(now = 1_003L)
        assertFalse(
            guard.shouldDropStaleResume(
                isResume = true,
                eventTimestamp = 1_004L,
                packageName = appA,
                monitored = monitored,
                now = 1_004L,
            )
        )
    }

    @Test
    fun resetClearsPendingHomeTarget() {
        val guard = PostHomeEventGuard(usageStatsSuppressMs = 6_000L)
        guard.markHome(targetPackage = appA, now = 1_000L)
        guard.reset()

        assertFalse(
            guard.shouldDropStaleResume(
                isResume = true,
                eventTimestamp = 1_001L,
                packageName = appA,
                monitored = monitored,
                now = 1_001L,
            )
        )
        assertFalse(guard.shouldSkipUsageStatsReconcile(now = 1_001L))
    }

    @Test
    fun oldLauncherResumeDoesNotReleasePendingTarget() {
        val guard = PostHomeEventGuard(usageStatsSuppressMs = 6_000L)
        guard.markHome(targetPackage = appA, now = 1_000L)

        assertFalse(
            guard.shouldDropStaleResume(
                isResume = true,
                eventTimestamp = 900L,
                packageName = "com.example.launcher",
                monitored = monitored,
                now = 1_001L,
            )
        )
        assertTrue(guard.shouldSkipUsageStatsReconcile(now = 1_001L))
        assertTrue(
            guard.shouldDropStaleResume(
                isResume = true,
                eventTimestamp = 1_002L,
                packageName = appA,
                monitored = monitored,
                now = 1_002L,
            )
        )
    }

    @Test
    fun anotherMonitoredAppEndsPendingHomeTransitionWithoutBeingDropped() {
        val appB = "com.example.b"
        val guard = PostHomeEventGuard(usageStatsSuppressMs = 6_000L)
        guard.markHome(targetPackage = appA, now = 1_000L)

        assertFalse(
            guard.shouldDropStaleResume(
                isResume = true,
                eventTimestamp = 1_001L,
                packageName = appB,
                monitored = setOf(appA, appB),
                now = 1_001L,
            )
        )
        assertFalse(guard.shouldSkipUsageStatsReconcile(now = 1_001L))
        assertFalse(
            guard.shouldDropStaleResume(
                isResume = true,
                eventTimestamp = 1_002L,
                packageName = appA,
                monitored = setOf(appA, appB),
                now = 1_002L,
            )
        )
    }

    @Test
    fun completedHomeTransitionKeepsThePreHomeResumeBarrier() {
        val guard = PostHomeEventGuard(usageStatsSuppressMs = 6_000L)
        guard.markHome(targetPackage = appA, now = 1_000L)

        assertFalse(
            guard.shouldDropStaleResume(
                isResume = true,
                eventTimestamp = 1_001L,
                packageName = "com.example.launcher",
                monitored = monitored,
                now = 1_001L,
            )
        )
        guard.completeHome(now = 1_001L)
        assertFalse(guard.shouldSkipUsageStatsReconcile(now = 1_002L))
        assertTrue(
            guard.shouldDropStaleResume(
                isResume = true,
                eventTimestamp = 999L,
                packageName = appA,
                monitored = monitored,
                now = 1_002L,
            )
        )
    }

    @Test
    fun pendingTargetExpiresAtTheExistingUsageStatsHorizon() {
        val guard = PostHomeEventGuard(usageStatsSuppressMs = 6_000L)
        guard.markHome(targetPackage = appA, now = 1_000L)

        assertFalse(
            guard.shouldDropStaleResume(
                isResume = true,
                eventTimestamp = 7_000L,
                packageName = appA,
                monitored = monitored,
                now = 7_000L,
            )
        )
    }

    @Test
    fun wallClockRollbackClearsOldHomeBarrier() {
        val guard = PostHomeEventGuard(usageStatsSuppressMs = 6_000L)
        guard.markHome(targetPackage = appA, now = 5_000L)

        assertFalse(
            guard.shouldDropStaleResume(
                isResume = true,
                eventTimestamp = 4_000L,
                packageName = appA,
                monitored = monitored,
                now = 4_000L,
            )
        )
        assertFalse(guard.shouldSkipUsageStatsReconcile(now = 4_000L))
    }
}
