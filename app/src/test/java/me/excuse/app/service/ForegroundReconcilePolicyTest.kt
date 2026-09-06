package me.excuse.app.service

import me.excuse.app.service.InterceptStateMachine.State
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundReconcilePolicyTest {
    private val appA = "com.example.a"
    private val appB = "com.example.b"
    private val launcher = "com.example.launcher"
    private val monitored = setOf(appA)

    @Test
    fun unknownStartupCanRecoverARecentMonitoredForeground() {
        assertTrue(
            shouldApplyUsageStatsForeground(
                state = State.Unknown,
                stickyPackage = null,
                lastForegroundChangeAt = null,
                candidate = UsageStatsForeground(appA, lastTimeUsed = 1_000L),
                monitored = monitored,
            )
        )
    }

    @Test
    fun staleStatsCannotUndoFreshPauseIntoSessionLeaving() {
        assertFalse(
            shouldApplyUsageStatsForeground(
                state = State.SessionLeaving(
                    pkg = appA,
                    startedAt = 1_000L,
                    plannedMinutes = 30,
                    extensionCount = 0,
                    leftAt = 2_200L,
                ),
                stickyPackage = null,
                lastForegroundChangeAt = 2_000L,
                candidate = UsageStatsForeground(appA, lastTimeUsed = 1_000L),
                monitored = monitored,
            )
        )
    }

    @Test
    fun statsAtTheSameTimestampAsExplicitLeaveIsNotNewEvidence() {
        assertFalse(
            shouldApplyUsageStatsForeground(
                state = State.SessionLeaving(
                    pkg = appA,
                    startedAt = 1_000L,
                    plannedMinutes = 30,
                    extensionCount = 0,
                    leftAt = 2_200L,
                ),
                stickyPackage = null,
                lastForegroundChangeAt = 2_000L,
                candidate = UsageStatsForeground(appA, lastTimeUsed = 2_000L),
                monitored = monitored,
            )
        )
    }

    @Test
    fun newerStatsCanRecoverARealResumeThatEventsMissed() {
        assertTrue(
            shouldApplyUsageStatsForeground(
                state = State.SessionLeaving(
                    pkg = appA,
                    startedAt = 1_000L,
                    plannedMinutes = 30,
                    extensionCount = 0,
                    leftAt = 2_200L,
                ),
                stickyPackage = null,
                lastForegroundChangeAt = 2_000L,
                candidate = UsageStatsForeground(appA, lastTimeUsed = 2_300L),
                monitored = monitored,
            )
        )
    }

    @Test
    fun statsResumeInsidePauseDisambiguationWindowStillCountsAsNewEvidence() {
        assertTrue(
            shouldApplyUsageStatsForeground(
                state = State.SessionLeaving(
                    pkg = appA,
                    startedAt = 1_000L,
                    plannedMinutes = 30,
                    extensionCount = 0,
                    leftAt = 2_200L,
                ),
                stickyPackage = null,
                lastForegroundChangeAt = 2_000L,
                candidate = UsageStatsForeground(appA, lastTimeUsed = 2_100L),
                monitored = monitored,
            )
        )
    }

    @Test
    fun newerStatsCanRecoverFromLauncherStickyWithinLeaveGrace() {
        assertTrue(
            shouldApplyUsageStatsForeground(
                state = State.SessionLeaving(
                    pkg = appA,
                    startedAt = 1_000L,
                    plannedMinutes = 30,
                    extensionCount = 0,
                    leftAt = 2_200L,
                ),
                stickyPackage = launcher,
                lastForegroundChangeAt = 2_300L,
                candidate = UsageStatsForeground(appA, lastTimeUsed = 2_400L),
                monitored = monitored,
            )
        )
    }

    @Test
    fun staleMonitoredStatsCannotOverrideNewerLauncher() {
        assertFalse(
            shouldApplyUsageStatsForeground(
                state = State.Background(launcher),
                stickyPackage = launcher,
                lastForegroundChangeAt = 3_000L,
                candidate = UsageStatsForeground(appA, lastTimeUsed = 2_900L),
                monitored = monitored,
            )
        )
    }

    @Test
    fun newerMonitoredStatsCanOverrideLauncherWhenResumeWasMissed() {
        assertTrue(
            shouldApplyUsageStatsForeground(
                state = State.Background(launcher),
                stickyPackage = launcher,
                lastForegroundChangeAt = 3_000L,
                candidate = UsageStatsForeground(appA, lastTimeUsed = 3_100L),
                monitored = monitored,
            )
        )
    }

    @Test
    fun aggregateEvidenceNeverReplacesAnOutstandingPrompt() {
        assertFalse(
            shouldApplyUsageStatsForeground(
                state = State.Prompting(appA, shownAt = 1_000L),
                stickyPackage = launcher,
                lastForegroundChangeAt = 2_000L,
                candidate = UsageStatsForeground(appA, lastTimeUsed = 2_100L),
                monitored = monitored,
            )
        )
    }

    @Test
    fun activeSessionRejectsNewerEvidenceForAnotherPackage() {
        assertFalse(
            shouldApplyUsageStatsForeground(
                state = State.InSession(
                    pkg = appA,
                    startedAt = 2_000L,
                    plannedMinutes = 30,
                    extensionCount = 0,
                    initialEntryPending = true,
                ),
                stickyPackage = null,
                lastForegroundChangeAt = 2_000L,
                candidate = UsageStatsForeground(appB, lastTimeUsed = 2_100L),
                monitored = setOf(appA, appB),
            )
        )
    }

    @Test
    fun activeSessionCanRecoverItsOwnTargetAfterResumeWasMissed() {
        assertTrue(
            shouldApplyUsageStatsForeground(
                state = State.InSession(
                    pkg = appA,
                    startedAt = 2_000L,
                    plannedMinutes = 30,
                    extensionCount = 0,
                    initialEntryPending = true,
                ),
                stickyPackage = launcher,
                lastForegroundChangeAt = 2_000L,
                candidate = UsageStatsForeground(appA, lastTimeUsed = 2_100L),
                monitored = monitored,
            )
        )
    }

    @Test
    fun establishedSessionCannotTurnAggregateEvidenceIntoAReentryEdge() {
        assertFalse(
            shouldApplyUsageStatsForeground(
                state = State.InSession(
                    pkg = appA,
                    startedAt = 2_000L,
                    plannedMinutes = 30,
                    extensionCount = 0,
                    initialEntryPending = false,
                ),
                stickyPackage = launcher,
                lastForegroundChangeAt = 2_000L,
                candidate = UsageStatsForeground(appA, lastTimeUsed = 2_100L),
                monitored = monitored,
            )
        )
    }

    @Test
    fun knownUnmonitoredForegroundRejectsAnotherUnmonitoredAggregate() {
        assertFalse(
            shouldApplyUsageStatsForeground(
                state = State.Background(launcher),
                stickyPackage = launcher,
                lastForegroundChangeAt = 3_000L,
                candidate = UsageStatsForeground("com.example.other", lastTimeUsed = 3_100L),
                monitored = monitored,
            )
        )
    }

    @Test
    fun timeUpWindowCannotBeReplacedByAggregateEvidence() {
        assertFalse(
            shouldApplyUsageStatsForeground(
                state = State.TimeUp(
                    pkg = appA,
                    startedAt = 1_000L,
                    plannedMinutes = 5,
                    extensionCount = 0,
                ),
                stickyPackage = null,
                lastForegroundChangeAt = 301_000L,
                candidate = UsageStatsForeground(appB, lastTimeUsed = 301_100L),
                monitored = setOf(appA, appB),
            )
        )
    }
}
