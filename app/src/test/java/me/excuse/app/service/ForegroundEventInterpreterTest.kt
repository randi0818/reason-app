package me.excuse.app.service

import me.excuse.app.service.ForegroundEventInterpreter.Event
import me.excuse.app.service.ForegroundEventInterpreter.EventType
import me.excuse.app.service.ForegroundEventInterpreter.ForegroundChange
import me.excuse.app.service.InterceptStateMachine.Effect
import me.excuse.app.service.InterceptStateMachine.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundEventInterpreterTest {

    private val appA = "com.example.a"
    private val appB = "com.example.b"
    private val monitored = setOf(appA, appB)

    @Test
    fun c3c4QuickSameActivityReentryEmitsLeaveThenReentry() {
        val fg = fresh()
        fg.seed(resumed(1_000L, appA, "MainActivity"))

        assertEquals(
            emptyList<ForegroundChange>(),
            fg.process(paused(2_000L, appA, "MainActivity"), 2_000L),
        )

        val changes = fg.process(resumed(2_050L, appA, "MainActivity"), 2_050L)

        assertEquals(
            listOf(
                ForegroundChange(null, 2_050L),
                entry(appA, 2_050L),
            ),
            changes,
        )
    }

    @Test
    fun c3c4QuickSameActivityReentryWithinGraceResumesSession() {
        val fg = fresh()
        val sm = InterceptStateMachine()
        fg.seed(resumed(1_000L, appA, "MainActivity"))
        sm.tick(appA, monitored, 1_000L)
        sm.onPromptConfirmed(appA, plannedMinutes = 30, now = 1_100L)
        assertEquals(emptyList<Effect>(), sm.tick(appA, monitored, 1_200L, entryEdge = true))

        fg.process(paused(2_000L, appA, "MainActivity"), 2_000L)
        val effects = fg.process(resumed(2_050L, appA, "MainActivity"), 2_050L)
            .flatMap { sm.tick(it.packageName, monitored, it.at, it.isEntryEdge) }

        // 解释器先发 leave 再发 re-entry：状态机走 InSession → SessionLeaving → InSession
        // 总 effects = [ScheduleSessionLeave, RefreshNotification, CancelSessionLeave, RefreshNotification]
        // 关键断言：不重弹、session 续上、宽限计时被取消
        assertTrue(effects.any { it is Effect.ScheduleSessionLeave && it.pkg == appA })
        assertTrue(Effect.CancelSessionLeave in effects)
        assertTrue("re-entry within grace should not re-prompt", effects.none { it is Effect.ShowPrompt })
        assertTrue("session should not finalize within grace", effects.none { it is Effect.FinishSession })
        assertTrue(sm.state is State.InSession)
        assertEquals(appA, (sm.state as State.InSession).pkg)
    }

    @Test
    fun delayedPauseAndReentryBatchPastGraceFinishesAndReprompts() {
        val fg = fresh()
        val sm = InterceptStateMachine()
        fg.seed(resumed(1_000L, appA, "MainActivity"))
        sm.tick(appA, monitored, 1_000L)
        sm.onPromptConfirmed(appA, plannedMinutes = 30, now = 1_100L)
        sm.tick(appA, monitored, 1_200L, entryEdge = true)

        fg.process(paused(2_000L, appA, "MainActivity"), 2_000L)
        // 模拟主线程/UsageEvents 批处理停顿：没有机会先 agePendingPause，12 秒后才同时
        // 解释离开和重入。状态机必须用原 pause 边沿守住 10 秒 deadline。
        val changes = fg.process(resumed(14_000L, appA, "MainActivity"), 14_000L)
        assertEquals(
            listOf(
                ForegroundChange(null, 2_200L),
                entry(appA, 14_000L),
            ),
            changes,
        )

        val effects = changes.flatMap {
            sm.tick(it.packageName, monitored, it.at, it.isEntryEdge)
        }

        assertTrue(
            Effect.FinishSession(appA, endedAt = 2_200L, overran = false) in effects
        )
        assertTrue(Effect.ShowPrompt(appA) in effects)
        assertTrue(Effect.CancelSessionLeave in effects)
        assertEquals(State.Prompting(appA, 14_000L), sm.state)
        assertEquals(emptyList<Effect>(), sm.onSessionLeaveExpired(appA, 14_001L))
    }

    @Test
    fun samePackageResumedWithoutPauseSynthesizesLeaveThenEntryEdge() {
        val fg = fresh()
        fg.seed(resumed(1_000L, appA, "MainActivity"))

        val changes = fg.process(resumed(2_000L, appA, "MainActivity"), 2_000L)

        assertEquals(
            listOf(
                ForegroundChange(null, 2_000L),
                entry(appA, 2_000L),
            ),
            changes,
        )
    }

    @Test
    fun differentActivityResumeWithoutPauseStaysInApp() {
        val fg = fresh()
        fg.seed(resumed(1_000L, appA, "MainActivity"))

        val changes = fg.process(resumed(2_000L, appA, "DetailActivity"), 2_000L)

        assertEquals(emptyList<ForegroundChange>(), changes)
        assertEquals(appA, fg.stickyPackageName)
        assertEquals("DetailActivity", fg.stickyClassName)
    }

    @Test
    fun missingClassAfterPauseIsNotTrustedAsInAppNavigation() {
        val fg = fresh()
        fg.seed(resumed(1_000L, appA, "MainActivity"))
        fg.process(paused(2_000L, appA, "MainActivity"), 2_000L)

        val changes = fg.process(resumed(2_050L, appA, null), 2_050L)

        assertEquals(
            listOf(
                ForegroundChange(null, 2_050L),
                entry(appA, 2_050L),
            ),
            changes,
        )
    }

    @Test
    fun newlyKnownClassAfterUnknownPauseIsNotTrustedAsInAppNavigation() {
        val fg = fresh()
        fg.seed(resumed(1_000L, appA, null))
        fg.process(paused(2_000L, appA, null), 2_000L)

        val changes = fg.process(resumed(2_050L, appA, "MainActivity"), 2_050L)

        assertEquals(
            listOf(
                ForegroundChange(null, 2_050L),
                entry(appA, 2_050L),
            ),
            changes,
        )
    }

    @Test
    fun delayedOlderEventCannotMoveEvidenceWatermarkBackward() {
        val fg = fresh()
        fg.forceForeground(appA, "MainActivity", observedAt = 2_000L)

        val changes = fg.process(resumed(1_900L, appB, "HomeActivity"), now = 2_100L)

        assertEquals(emptyList<ForegroundChange>(), changes)
        assertEquals(appA, fg.stickyPackageName)
        assertEquals(2_000L, fg.lastForegroundChangeAt)
    }

    @Test
    fun acceptedResumeAndStopAdvanceEvidenceWatermark() {
        val fg = fresh()
        fg.seed(resumed(1_000L, appA, "MainActivity"))

        fg.process(resumed(2_000L, appB, "OtherActivity"), now = 2_500L)
        assertEquals(2_000L, fg.lastForegroundChangeAt)
        assertEquals(appB, fg.stickyPackageName)

        fg.process(stopped(3_000L, appB, "OtherActivity"), now = 3_500L)
        assertEquals(3_000L, fg.lastForegroundChangeAt)
        assertEquals(null, fg.stickyPackageName)
    }

    @Test
    fun wallClockRollbackRebuildsEvidenceWatermark() {
        val fg = fresh()
        fg.forceForeground(appA, "MainActivity", observedAt = 5_000L)

        val changes = fg.process(resumed(4_000L, appB, "OtherActivity"), now = 4_000L)

        assertEquals(listOf(entry(appB, 4_000L)), changes)
        assertEquals(4_000L, fg.lastForegroundChangeAt)
        assertEquals(appB, fg.stickyPackageName)
    }

    @Test
    fun delayedStoppedAfterQuickSameActivityReentryIsIgnored() {
        val fg = fresh()
        fg.seed(resumed(1_000L, appA, "MainActivity"))

        fg.process(paused(2_000L, appA, "MainActivity"), 2_000L)
        fg.process(resumed(2_050L, appA, "MainActivity"), 2_050L)
        val changes = fg.process(stopped(2_300L, appA, "MainActivity"), 2_300L)

        assertEquals(emptyList<ForegroundChange>(), changes)
        assertEquals(appA, fg.stickyPackageName)
        assertEquals("MainActivity", fg.stickyClassName)
    }

    @Test
    fun forcedHomeMakesSamePackageRecentsReentryVisibleAgain() {
        val fg = fresh()
        fg.seed(resumed(1_000L, appA, "MainActivity"))

        fg.forceForeground(null)
        val changes = fg.process(resumed(1_500L, appA, "MainActivity"), 1_500L)

        assertEquals(listOf(entry(appA, 1_500L)), changes)
    }

    @Test
    fun packageOnlyStickyStillDetectsHomeLeaveByPausedEvent() {
        val fg = fresh()
        fg.forceForeground(appA)

        assertEquals(
            emptyList<ForegroundChange>(),
            fg.process(paused(2_000L, appA, "MainActivity"), 2_000L),
        )

        assertEquals(listOf(ForegroundChange(null, 2_200L)), fg.agePendingPause(2_201L))
    }

    @Test
    fun packageOnlyStickyDoesNotTreatStoppedAsDefinitiveLeave() {
        val fg = fresh()
        fg.forceForeground(appA)

        val changes = fg.process(stopped(2_000L, appA, "MainActivity"), 2_000L)

        assertEquals(emptyList<ForegroundChange>(), changes)
        assertEquals(appA, fg.stickyPackageName)
    }

    @Test
    fun quickDifferentActivitySamePackageStaysInCurrentSession() {
        val fg = fresh()
        fg.seed(resumed(1_000L, appA, "MainActivity"))

        fg.process(paused(2_000L, appA, "MainActivity"), 2_000L)
        val changes = fg.process(resumed(2_050L, appA, "DetailActivity"), 2_050L)

        assertEquals(emptyList<ForegroundChange>(), changes)
        assertEquals(appA, fg.stickyPackageName)
        assertEquals("DetailActivity", fg.stickyClassName)
    }

    @Test
    fun delayedOldActivityStoppedIsIgnoredAfterInAppNavigation() {
        val fg = fresh()
        fg.seed(resumed(1_000L, appA, "MainActivity"))

        fg.process(paused(2_000L, appA, "MainActivity"), 2_000L)
        fg.process(resumed(2_050L, appA, "DetailActivity"), 2_050L)
        val changes = fg.process(stopped(2_300L, appA, "MainActivity"), 2_300L)

        assertEquals(emptyList<ForegroundChange>(), changes)
        assertEquals(appA, fg.stickyPackageName)
        assertEquals("DetailActivity", fg.stickyClassName)
    }

    @Test
    fun pendingPauseAgesOutWhenNoResumeArrives() {
        val fg = fresh()
        fg.seed(resumed(1_000L, appA, "MainActivity"))

        fg.process(paused(2_000L, appA, "MainActivity"), 2_000L)

        assertTrue(fg.hasPendingPauseFor(appA))
        assertFalse(fg.hasPendingPauseFor(appB))
        assertEquals(emptyList<ForegroundChange>(), fg.agePendingPause(2_200L))
        assertEquals(listOf(ForegroundChange(null, 2_200L)), fg.agePendingPause(2_201L))
        assertFalse(fg.hasPendingPauseFor(appA))
        assertEquals(2_000L, fg.lastForegroundChangeAt)
    }

    private fun fresh() = ForegroundEventInterpreter(pauseReentryGapMs = 200L)

    private fun resumed(at: Long, pkg: String, cls: String?) =
        Event(at, EventType.Resumed, pkg, cls)

    private fun paused(at: Long, pkg: String, cls: String?) =
        Event(at, EventType.Paused, pkg, cls)

    private fun stopped(at: Long, pkg: String, cls: String?) =
        Event(at, EventType.Stopped, pkg, cls)

    private fun entry(pkg: String, at: Long) =
        ForegroundChange(pkg, at, isEntryEdge = true)
}
