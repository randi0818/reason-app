package me.excuse.app.service

import me.excuse.app.service.ForegroundEventInterpreter.Event
import me.excuse.app.service.ForegroundEventInterpreter.EventType
import me.excuse.app.service.InterceptStateMachine.Effect
import me.excuse.app.service.InterceptStateMachine.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentsCancelFlowTest {
    private val target = "com.example.target"
    private val launcher = "com.example.launcher"
    private val ownPackage = "me.excuse.app"
    private val monitored = setOf(target)

    @Test
    fun screenOffDuringCancelOrTimeoutHandoffStillDismissesTheRemainingCover() {
        listOf(false, true).forEach { timedOut ->
            val machine = InterceptStateMachine()
            machine.tick(target, monitored, 1_000L)
            val exit = if (timedOut) machine.onPromptTimedOut(target, 11_000L)
            else machine.onPromptCancelled(target, 1_100L)
            assertTrue(Effect.SendHome(target) in exit)
            assertFalse(Effect.Dismiss in exit)
            assertTrue(machine.state is State.Background)

            assertTrue(Effect.Dismiss in machine.reset(11_100L))
            assertTrue(machine.state is State.Unknown)
            assertFalse(machine.tick(launcher, monitored, 12_000L).any { it is Effect.ShowPrompt })
            assertTrue(machine.tick(target, monitored, 13_000L).any { it is Effect.ShowPrompt })
        }
    }

    @Test
    fun screenOffDuringTimeUpExitAlsoDismissesTheRemainingCover() {
        val machine = InterceptStateMachine()
        machine.tick(target, monitored, 1_000L)
        machine.onPromptConfirmed(target, 5, 2_000L)
        machine.onSessionTimeElapsed(target, 302_000L)
        val exit = machine.onTimeUpExit(target, 303_000L)
        assertTrue(Effect.SendHome(target) in exit)
        assertFalse(Effect.Dismiss in exit)
        assertTrue(Effect.Dismiss in machine.reset(303_100L))
    }

    @Test
    fun recentsCancellationIgnoresHandoffNoiseButReentryImmediatelyPrompts() {
        val machine = InterceptStateMachine()
        val foreground = ForegroundEventInterpreter(pauseReentryGapMs = 200L)
        val guard = PostHomeEventGuard(usageStatsSuppressMs = 6_000L)
        val effects = mutableListOf<Effect>()
        fun resume(pkg: String, at: Long, observedAt: Long = at) {
            if (guard.shouldDropStaleResume(true, at, pkg, monitored, observedAt)) return
            foreground.process(Event(at, EventType.Resumed, pkg, "Main"), observedAt)
                .forEach { effects += machine.tick(it.packageName, monitored, it.at, it.isEntryEdge) }
        }

        resume(target, 1_000L)
        // Nothing OS 的 Recents 可以先 RESUMED launcher，而目标仍没有 PAUSED。
        resume(launcher, 1_100L)
        assertTrue(machine.state is State.Prompting)
        val cancelled = machine.onPromptCancelled(target, 1_200L)
        assertFalse(Effect.Dismiss in cancelled)
        assertTrue(Effect.SendHome(target) in cancelled)
        effects.clear()
        guard.markHome(target, 1_200L)
        foreground.forceForeground(null, observedAt = 1_200L)

        resume(ownPackage, 1_210L)
        resume(launcher, 1_220L)
        resume(target, 1_230L)
        assertFalse(effects.any { it is Effect.ShowPrompt })
        assertTrue(guard.shouldSkipUsageStatsReconcile(1_240L))

        // service 的真实窗口交接回调先设证据边界，再移除剩余遮挡。
        guard.completeHome(1_250L)
        foreground.forceForeground(null, observedAt = 1_250L)
        resume(target, 1_230L, observedAt = 1_251L)
        assertFalse(effects.any { it is Effect.ShowPrompt })
        assertFalse(guard.shouldSkipUsageStatsReconcile(1_251L))
        assertFalse(shouldApplyUsageStatsForeground(
            state = machine.state,
            stickyPackage = foreground.stickyPackageName,
            lastUsageEvidenceAt = foreground.lastUsageEvidenceAt,
            eventsQueriedThrough = 1_251L,
            candidate = UsageStatsForeground(target, 1_240L),
            monitored = monitored,
        ))

        resume(target, 1_260L)
        assertEquals(1, effects.count { it == Effect.ShowPrompt(target) })
        assertEquals(State.Prompting(target, 1_260L), machine.state)
    }

    @Test
    fun completionWithoutALauncherResumeStillRestoresStatsFallback() {
        val guard = PostHomeEventGuard(usageStatsSuppressMs = 6_000L)
        guard.markHome(target, 1_000L)
        guard.completeHome(1_500L)

        assertFalse(guard.shouldSkipUsageStatsReconcile(1_501L))
        assertTrue(shouldApplyUsageStatsForeground(
            state = State.Background(null),
            stickyPackage = null,
            lastUsageEvidenceAt = 1_500L,
            eventsQueriedThrough = 1_502L,
            candidate = UsageStatsForeground(target, 1_501L),
            monitored = monitored,
        ))
    }
}
