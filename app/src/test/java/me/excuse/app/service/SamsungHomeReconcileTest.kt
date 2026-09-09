package me.excuse.app.service

import me.excuse.app.service.ForegroundEventInterpreter.Event
import me.excuse.app.service.ForegroundEventInterpreter.EventType
import me.excuse.app.service.InterceptStateMachine.Effect
import me.excuse.app.service.InterceptStateMachine.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SamsungHomeReconcileTest {
    private val app = "com.example.calculator"
    private val home = "com.example.launcher"
    private val monitored = setOf(app)

    @Test
    fun gestureHomeWithLatePauseAndStopStillExpiresSession() {
        for (type in listOf(EventType.Paused, EventType.Stopped)) {
            val fg = interpreter()
            val sm = InterceptStateMachine()
            fg.seed(event(1_000L, EventType.Resumed, app))
            sm.tick(app, monitored, 1_000L)
            sm.onPromptConfirmed(app, 5, 1_100L)
            sm.tick(app, monitored, 1_200L, entryEdge = true)
            fg.process(event(2_000L, EventType.Resumed, home), 2_000L).forEach {
                sm.tick(it.packageName, monitored, it.at, it.isEntryEdge)
            }
            assertTrue(sm.state is State.SessionLeaving)

            // Samsung 手势 Home 先 resume launcher，约两秒后才 pause/stop 原 app；
            // 原 app 的 lastTimeUsed 因离开而更新，并不表示用户返回了它。
            fg.process(event(4_000L, type, app), 4_000L)
            assertFalse(canReconcile(fg, sm.state, 4_000L))
            assertTrue(sm.onSessionLeaveExpired(app, 12_001L).any {
                it is Effect.FinishSession && it.endedAt == 2_000L
            })
            val effects = fg.process(event(17_000L, EventType.Resumed, app), 17_000L)
                .flatMap { sm.tick(it.packageName, monitored, it.at, it.isEntryEdge) }
            assertTrue(Effect.ShowPrompt(app) in effects)
        }
    }

    @Test
    fun coldSeedOnHomeDoesNotResurrectTheStoppedTarget() {
        val fg = interpreter()
        fg.seed(event(1_000L, EventType.Resumed, app))
        fg.seed(event(2_000L, EventType.Resumed, home))
        fg.seed(event(4_000L, EventType.Stopped, app))
        assertFalse(canReconcile(fg, State.Background(home), 4_000L))
    }

    @Test
    fun newerMissingResumeCanStillBeRecoveredAfterHomeStopEvidence() {
        val fg = interpreter()
        fg.seed(event(1_000L, EventType.Resumed, app))
        fg.process(event(2_000L, EventType.Resumed, home), 2_000L)
        fg.process(event(4_000L, EventType.Stopped, app), 4_000L)
        assertTrue(canReconcile(fg, State.Background(home), 4_100L))
    }

    @Test
    fun backgroundStopMustNotDiscardALateButValidResume() {
        val fg = interpreter()
        fg.seed(event(2_000L, EventType.Resumed, home))
        fg.process(event(4_000L, EventType.Stopped, app), 4_100L)
        val other = "com.example.other"
        val changes = fg.process(event(3_500L, EventType.Resumed, other), 4_200L)
        assertEquals(other, fg.stickyPackageName)
        assertTrue(changes.any { it.packageName == other && it.isEntryEdge })
        assertFalse(canReconcile(fg, State.Background(other), 4_000L))
    }

    @Test
    fun aggregateUpdatedDuringQueryWaitsForTheNextEventSnapshot() {
        val fg = interpreter()
        fg.seed(event(2_000L, EventType.Resumed, home))
        for (queriedThrough in listOf(3_999L, 4_000L)) {
            assertFalse(canReconcile(fg, State.Background(home), 4_000L, queriedThrough))
        }
        // 下一轮若能看到 STOPPED，拒绝；若确实漏发了进入事件，保留正常兜底。
        assertTrue(canReconcile(fg, State.Background(home), 4_000L, 4_001L))
        fg.process(event(4_000L, EventType.Stopped, app), 4_001L)
        assertFalse(canReconcile(fg, State.Background(home), 4_000L, 4_001L))
    }

    @Test
    fun oldPauseCannotLowerAggregateEvidenceAndClockRollbackCanRebuildIt() {
        val fg = interpreter()
        fg.seed(event(2_000L, EventType.Resumed, home))
        fg.process(event(4_000L, EventType.Stopped, app), 4_000L)
        fg.process(event(3_000L, EventType.Paused, app), 4_100L)
        assertFalse(canReconcile(fg, State.Background(home), 4_000L))
        fg.process(event(1_000L, EventType.Resumed, home), 1_000L)
        assertTrue(canReconcile(fg, State.Background(home), 1_100L))
    }

    @Test
    fun resetAndExplicitHomeBarrierResetAggregateEvidence() {
        val fg = interpreter()
        fg.seed(event(2_000L, EventType.Resumed, home))
        fg.process(event(4_000L, EventType.Stopped, app), 4_000L)
        fg.reset()
        assertTrue(canReconcile(fg, State.Unknown, 1_000L))
        fg.forceForeground(null, observedAt = 5_000L)
        assertFalse(canReconcile(fg, State.Unknown, 4_500L))
        assertTrue(canReconcile(fg, State.Unknown, 5_100L))
    }

    private fun canReconcile(
        fg: ForegroundEventInterpreter,
        state: State,
        lastUsed: Long,
        queriedThrough: Long = 1_000_000L,
    ) =
        shouldApplyUsageStatsForeground(
            state = state,
            stickyPackage = fg.stickyPackageName,
            lastUsageEvidenceAt = fg.lastUsageEvidenceAt,
            eventsQueriedThrough = queriedThrough,
            candidate = UsageStatsForeground(app, lastUsed),
            monitored = monitored,
        )

    private fun interpreter() = ForegroundEventInterpreter(200L, isHomePackage = { it == home })
    private fun event(at: Long, type: EventType, pkg: String) = Event(at, type, pkg, "$pkg.Main")
}
