package me.excuse.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptReturnPolicyTest {
    @Test
    fun targetStillForegroundPreservesCurrentEntryPoint() {
        assertFalse(
            shouldRestorePromptTarget(
                targetPackage = "com.example.target",
                observedForegroundPackage = "com.example.target",
            )
        )
    }

    @Test
    fun foregroundStealThatLeavesNoForegroundRestoresTarget() {
        assertTrue(
            shouldRestorePromptTarget(
                targetPackage = "com.example.target",
                observedForegroundPackage = null,
            )
        )
    }

    @Test
    fun switchingElsewhereWhilePromptIsOpenRestoresTarget() {
        assertTrue(
            shouldRestorePromptTarget(
                targetPackage = "com.example.target",
                observedForegroundPackage = "com.example.other",
            )
        )
    }

    private val target = "com.example.target"
    private val monitored = setOf(target)
    private val isMonitored: (String) -> Boolean = { it in monitored }
    private val stabilizerPackage = "me.excuse.app"
    private val stabilizerClass = "me.excuse.app.strict.StealForegroundActivity"

    private fun planHomeFor(
        observedPackage: String?,
        observedClass: String? = null,
        pendingPausePackages: Set<String> = emptySet(),
        monitoredPredicate: (String) -> Boolean = isMonitored,
    ): HomeRequestPlan = planHomeRequest(
        targetPackage = target,
        observedForegroundPackage = observedPackage,
        observedForegroundClassName = observedClass,
        hasPendingPauseFor = { it in pendingPausePackages },
        isMonitored = monitoredPredicate,
        stabilizerPackage = stabilizerPackage,
        stabilizerClassName = stabilizerClass,
    )

    @Test
    fun cancellingWhileOnRecentsDoesNotArmTheNoOpHomeGuard() {
        // 拦截窗浮在 recents 上时点「我不用了」：观测前台是 launcher，不是被监控 app。
        // HOME 仍会 best-effort 发出；这里只禁止清 sticky 和开启 UsageStats 静默期。
        assertEquals(HomeRequestPlan(guardPackage = null), planHomeFor("com.example.launcher"))
    }

    @Test
    fun unrelatedForegroundDoesNotArmTheHomeGuard() {
        assertEquals(HomeRequestPlan(guardPackage = null), planHomeFor("com.example.unmonitored"))
    }

    @Test
    fun cancellingInsideTheTargetArmsTheHomeGuardForIt() {
        assertEquals(HomeRequestPlan(guardPackage = target), planHomeFor(target))
    }

    @Test
    fun anotherMonitoredForegroundArmsTheGuardForTheActualForeground() {
        val appB = "com.example.b"
        assertEquals(
            HomeRequestPlan(guardPackage = appB),
            planHomeFor(
                observedPackage = appB,
                monitoredPredicate = { it == target || it == appB },
            ),
        )
    }

    @Test
    fun unknownForegroundDoesNotPretendTheHomeTransitionIsConfirmed() {
        assertEquals(HomeRequestPlan(guardPackage = null), planHomeFor(null))
    }

    @Test
    fun visibleTargetIsStillGuardedAfterItWasRemovedFromTheList() {
        assertEquals(
            HomeRequestPlan(guardPackage = target),
            planHomeFor(target, monitoredPredicate = { false }),
        )
    }

    @Test
    fun foregroundStabilizerMapsTheGuardBackToTheTarget() {
        assertEquals(
            HomeRequestPlan(guardPackage = target),
            planHomeFor(stabilizerPackage, stabilizerClass),
        )
    }

    @Test
    fun exitingForegroundStabilizerStillGuardsTheTargetResumeBehindIt() {
        // 稳定页的 PAUSED 是 immediate finish 的常态，下面的目标可能马上补 RESUMED；
        // 它是普通 pending pause 的刻意例外，否则会在 launcher 上冒出 ghost prompt。
        assertEquals(
            HomeRequestPlan(guardPackage = target),
            planHomeFor(
                observedPackage = stabilizerPackage,
                observedClass = stabilizerClass,
                pendingPausePackages = setOf(stabilizerPackage),
            ),
        )
    }

    @Test
    fun mainActivityDoesNotMasqueradeAsTheForegroundStabilizer() {
        assertEquals(
            HomeRequestPlan(guardPackage = null),
            planHomeFor(stabilizerPackage, "me.excuse.app.MainActivity"),
        )
    }

    @Test
    fun unresolvedTargetPauseDoesNotArmTheHomeGuard() {
        assertEquals(
            HomeRequestPlan(guardPackage = null),
            planHomeFor(target, pendingPausePackages = setOf(target)),
        )
    }

    @Test
    fun unresolvedPauseOnAnotherMonitoredAppDoesNotArmTheWrongGuard() {
        val appB = "com.example.b"
        assertEquals(
            HomeRequestPlan(guardPackage = null),
            planHomeFor(
                observedPackage = appB,
                pendingPausePackages = setOf(appB),
                monitoredPredicate = { it == target || it == appB },
            ),
        )
    }

    @Test
    fun recentsCancelKeepsUsageStatsFallbackAvailableForAMissedResume() {
        val launcher = "com.example.launcher"
        val stateMachine = InterceptStateMachine()
        assertTrue(
            stateMachine.tick(target, monitored, now = 1_000L, entryEdge = true)
                .any { it is InterceptStateMachine.Effect.ShowPrompt },
        )
        assertEquals(emptyList<InterceptStateMachine.Effect>(), stateMachine.tick(launcher, monitored, 1_100L))

        stateMachine.onPromptCancelled(target, now = 1_200L)
        assertEquals(HomeRequestPlan(guardPackage = null), planHomeFor(launcher))

        val missedResume = UsageStatsForeground(packageName = target, lastTimeUsed = 1_300L)
        assertTrue(
            shouldApplyUsageStatsForeground(
                state = stateMachine.state,
                stickyPackage = launcher,
                lastForegroundChangeAt = 1_100L,
                candidate = missedResume,
                monitored = monitored,
            )
        )
        val reentry = stateMachine.tick(
            foreground = missedResume.packageName,
            monitored = monitored,
            now = 1_400L,
            entryEdge = true,
        )
        assertEquals(1, reentry.count { it is InterceptStateMachine.Effect.ShowPrompt })
    }

    @Test
    fun confirmationPlanFinishesInsteadOfLaunchingARecentlyRemovedTarget() {
        assertEquals(
            PromptConfirmationPlan(
                shouldLaunchTarget = false,
                shouldFinishRemovedSession = true,
            ),
            planPromptConfirmation(
                targetPackage = target,
                observedForegroundPackage = "com.example.launcher",
                monitored = emptySet(),
            ),
        )
    }

    @Test
    fun confirmationPlanRestoresAStillMonitoredTargetWhenNeeded() {
        assertEquals(
            PromptConfirmationPlan(
                shouldLaunchTarget = true,
                shouldFinishRemovedSession = false,
            ),
            planPromptConfirmation(
                targetPackage = target,
                observedForegroundPackage = "com.example.launcher",
                monitored = monitored,
            ),
        )
    }
}
