package me.excuse.app.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import me.excuse.app.data.FakeUsageRepository
import me.excuse.app.service.InterceptStateMachine.Effect
import me.excuse.app.service.InterceptStateMachine.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InterceptStateMachineTest {

    private val appA = "com.example.a"
    private val appB = "com.example.b"
    private val launcher = "com.android.launcher3"
    private val monitored = setOf(appA, appB)

    private fun fresh() = InterceptStateMachine(
        initialPromptTimeoutMs = 10_000L,
        interactionPromptTimeoutMs = 20_000L,
        maxExtensions = 1,
        sessionLeaveGraceMs = 10_000L,
    )

    private fun primePrompting(
        sm: InterceptStateMachine,
        pkg: String,
        now: Long = 1_000L,
    ): Long {
        val effects = sm.tick(pkg, monitored, now)
        assertTrue("expected ShowPrompt($pkg)", Effect.ShowPrompt(pkg) in effects)
        assertFalse("entering Prompting should not send Home", effects.any { it is Effect.SendHome })
        return now
    }

    private fun primeSession(
        sm: InterceptStateMachine,
        pkg: String,
        startedAt: Long = 1_000L,
        planned: Int = 30,
    ) {
        primePrompting(sm, pkg, startedAt - 1)
        sm.onPromptConfirmed(pkg, planned, startedAt)
        assertEquals(emptyList<Effect>(), sm.tick(pkg, monitored, startedAt + 1, entryEdge = true))
        assertTrue(sm.state is State.InSession)
    }

    @Test
    fun c3_homeThenQuickReentryWithinGraceResumesSession() {
        val sm = fresh()
        primeSession(sm, appA, startedAt = 1_000L, planned = 30)

        assertEquals(emptyList<Effect>(), sm.tick(appA, monitored, 2_000L))
        assertTrue(sm.state is State.InSession)

        val leaveEffects = sm.tick(launcher, monitored, 5_000L)
        assertEquals(
            listOf<Effect>(
                Effect.ScheduleSessionLeave(appA, 10_000L),
                Effect.RefreshNotification,
            ),
            leaveEffects,
        )
        assertTrue(sm.state is State.SessionLeaving)
        assertEquals(appA, (sm.state as State.SessionLeaving).pkg)

        val reentryEffects = sm.tick(appA, monitored, 5_100L, entryEdge = true)
        assertEquals(
            listOf(Effect.CancelSessionLeave, Effect.RefreshNotification),
            reentryEffects,
        )
        assertFalse("re-entry within grace should not re-prompt", Effect.ShowPrompt(appA) in reentryEffects)
        assertTrue(sm.state is State.InSession)
        val resumed = sm.state as State.InSession
        assertEquals(appA, resumed.pkg)
        assertEquals(1_000L, resumed.startedAt)
        assertEquals(30, resumed.plannedMinutes)
        assertFalse(resumed.initialEntryPending)
    }

    @Test
    fun c3_stoppedAndResumedInSameTickResumesWithoutReprompt() {
        val sm = fresh()
        primeSession(sm, appA, startedAt = 1_000L, planned = 30)

        val stopEffects = sm.tick(null, monitored, 5_000L)
        assertEquals(
            listOf<Effect>(
                Effect.ScheduleSessionLeave(appA, 10_000L),
                Effect.RefreshNotification,
            ),
            stopEffects,
        )
        assertTrue(sm.state is State.SessionLeaving)

        val resumeEffects = sm.tick(appA, monitored, 5_001L, entryEdge = true)
        assertEquals(
            listOf(Effect.CancelSessionLeave, Effect.RefreshNotification),
            resumeEffects,
        )
        assertFalse(Effect.ShowPrompt(appA) in resumeEffects)
        assertTrue(sm.state is State.InSession)
    }

    @Test
    fun c4_recentTasksQuickReentryWithinGraceResumesSession() {
        val sm = fresh()
        primeSession(sm, appA, startedAt = 1_000L, planned = 30)

        val tick1 = sm.tick(null, monitored, 4_000L)
        assertEquals(
            listOf<Effect>(
                Effect.ScheduleSessionLeave(appA, 10_000L),
                Effect.RefreshNotification,
            ),
            tick1,
        )
        val tick2 = sm.tick(null, monitored, 4_100L)
        assertEquals(emptyList<Effect>(), tick2)
        assertTrue(sm.state is State.SessionLeaving)

        val reentry = sm.tick(appA, monitored, 4_200L, entryEdge = true)
        assertEquals(
            listOf(Effect.CancelSessionLeave, Effect.RefreshNotification),
            reentry,
        )
        assertTrue(sm.state is State.InSession)
    }

    @Test
    fun c3b_homeBeyondGraceWindowFinalizesAndNextEntryReprompts() {
        val sm = fresh()
        primeSession(sm, appA, startedAt = 1_000L, planned = 30)

        val leaveEffects = sm.tick(launcher, monitored, 5_000L)
        assertTrue(Effect.ScheduleSessionLeave(appA, 10_000L) in leaveEffects)
        assertTrue(sm.state is State.SessionLeaving)

        // 模拟 10s 后宽限期到点：Service 应该调 onSessionLeaveExpired
        val expireEffects = sm.onSessionLeaveExpired(appA, 15_001L)
        assertEquals(
            listOf(
                Effect.FinishSession(appA, endedAt = 5_000L, overran = false),
                Effect.RefreshNotification,
            ),
            expireEffects,
        )
        assertEquals(State.Background(null), sm.state)

        // 12s 处重进 → 必须重弹
        val reentryEffects = sm.tick(appA, monitored, 17_000L, entryEdge = true)
        assertTrue("expected ShowPrompt after grace window", Effect.ShowPrompt(appA) in reentryEffects)
        assertTrue(
            "expected SchedulePromptTimeout",
            reentryEffects.any { it is Effect.SchedulePromptTimeout && it.pkg == appA },
        )
        assertFalse(reentryEffects.any { it is Effect.SendHome })
        assertTrue(sm.state is State.Prompting)
    }

    @Test
    fun sessionLeavingReentryAtDeadlineRepromptsWhenTimerCallbackIsLate() {
        val sm = fresh()
        primeSession(sm, appA, startedAt = 1_000L, planned = 30)
        sm.tick(launcher, monitored, 5_000L)

        val effects = sm.tick(appA, monitored, 15_000L, entryEdge = true)

        assertEquals(
            listOf(
                Effect.CancelSessionLeave,
                Effect.FinishSession(appA, endedAt = 5_000L, overran = false),
                Effect.RefreshNotification,
                Effect.ShowPrompt(appA),
                Effect.SchedulePromptTimeout(appA, 10_000L),
            ),
            effects,
        )
        assertEquals(State.Prompting(appA, 15_000L), sm.state)
        assertEquals(
            "late leave callback must not finish the replacement prompt/session",
            emptyList<Effect>(),
            sm.onSessionLeaveExpired(appA, 15_001L),
        )
    }

    @Test
    fun sessionLeavingReentryOneMillisecondBeforeDeadlineResumes() {
        val sm = fresh()
        primeSession(sm, appA, startedAt = 1_000L, planned = 30)
        sm.tick(launcher, monitored, 5_000L)

        val effects = sm.tick(appA, monitored, 14_999L, entryEdge = true)

        assertEquals(listOf(Effect.CancelSessionLeave, Effect.RefreshNotification), effects)
        assertEquals(
            State.InSession(
                pkg = appA,
                startedAt = 1_000L,
                plannedMinutes = 30,
                extensionCount = 0,
                initialEntryPending = false,
            ),
            sm.state,
        )
    }

    @Test
    fun removingPackageDuringLeaveFinishesAtActualLeaveWithoutReprompting() {
        val sm = fresh()
        primeSession(sm, appA, startedAt = 1_000L, planned = 30)
        sm.tick(launcher, monitored, 5_000L)

        val effects = sm.tick(null, setOf(appB), 6_000L)

        assertEquals(
            listOf(
                Effect.CancelSessionLeave,
                Effect.FinishSession(appA, endedAt = 5_000L, overran = false),
                Effect.RefreshNotification,
            ),
            effects,
        )
        assertEquals(State.Background(null), sm.state)
    }

    @Test
    fun sessionLeavingSwitchToOtherMonitoredAppFinalizesAndReprompts() {
        val sm = fresh()
        primeSession(sm, appA, startedAt = 1_000L, planned = 30)

        val leaveEffects = sm.tick(launcher, monitored, 5_000L)
        assertTrue(sm.state is State.SessionLeaving)
        assertTrue(Effect.ScheduleSessionLeave(appA, 10_000L) in leaveEffects)

        // 宽限期内切到 B：A 立刻 finalize，B 直接弹
        val switchEffects = sm.tick(appB, monitored, 6_000L, entryEdge = true)
        val cancelIdx = switchEffects.indexOf(Effect.CancelSessionLeave)
        val finishIdx = switchEffects.indexOfFirst { it is Effect.FinishSession && it.pkg == appA }
        val showIdx = switchEffects.indexOfFirst { it is Effect.ShowPrompt && it.pkg == appB }
        assertTrue("CancelSessionLeave before FinishSession", cancelIdx >= 0 && cancelIdx < finishIdx)
        assertTrue("FinishSession before ShowPrompt", finishIdx >= 0 && finishIdx < showIdx)
        assertTrue(switchEffects.any { it is Effect.SchedulePromptTimeout && it.pkg == appB })
        assertTrue(sm.state is State.Prompting)
        assertEquals(appB, (sm.state as State.Prompting).pkg)
        assertTrue(switchEffects.none { it is Effect.Dismiss })
    }

    @Test
    fun sessionLeavingTimeElapsedFinalizesWithoutTimeUpOverlay() {
        val sm = fresh()
        primeSession(sm, appA, startedAt = 1_000L, planned = 1)

        val leaveEffects = sm.tick(launcher, monitored, 30_000L)
        assertTrue(sm.state is State.SessionLeaving)
        assertTrue(Effect.ScheduleSessionLeave(appA, 10_000L) in leaveEffects)

        // 宽限期内到点：人已经离开了，别把到点窗弹到桌面上。按正常结束 finalize，不算超时。
        val elapsed = sm.onSessionTimeElapsed(appA, 65_000L)
        assertEquals(
            listOf(
                Effect.CancelSessionLeave,
                Effect.FinishSession(appA, endedAt = 30_000L, overran = false),
                Effect.RefreshNotification,
            ),
            elapsed,
        )
        assertTrue(elapsed.none { it is Effect.ShowTimeUp })
        assertTrue(elapsed.none { it is Effect.SendHome })
        assertEquals(State.Background(null), sm.state)
    }

    @Test
    fun returningAfterSessionLeavingTimeElapsedRepromptsInsteadOfTimeUp() {
        val sm = fresh()
        primeSession(sm, appA, startedAt = 1_000L, planned = 1)
        sm.tick(launcher, monitored, 30_000L)
        sm.onSessionTimeElapsed(appA, 65_000L)

        // 时间已经用完了，再回到同一个 app 就是一次新的进入：重新要理由，而不是「+5 分钟」。
        val back = sm.tick(appA, monitored, 66_000L, entryEdge = true)
        assertTrue(back.any { it is Effect.ShowPrompt && it.pkg == appA })
        assertTrue(back.none { it is Effect.ShowTimeUp })
        assertTrue(sm.state is State.Prompting)
    }

    @Test
    fun sessionLeavingExpireOnDifferentPackageIsIgnored() {
        val sm = fresh()
        primeSession(sm, appA, startedAt = 1_000L, planned = 30)
        sm.tick(launcher, monitored, 5_000L)
        assertTrue(sm.state is State.SessionLeaving)

        // 旧 leave job 携带了不同 pkg 时被忽略
        val effects = sm.onSessionLeaveExpired(appB, 15_001L)
        assertEquals(emptyList<Effect>(), effects)
        assertTrue(sm.state is State.SessionLeaving)
    }

    @Test
    fun resetWhileSessionLeavingFinalizes() {
        val sm = fresh()
        primeSession(sm, appA, startedAt = 1_000L, planned = 30)
        sm.tick(launcher, monitored, 5_000L)
        assertTrue(sm.state is State.SessionLeaving)

        val effects = sm.reset(now = 6_000L)
        assertEquals(
            listOf(
                Effect.CancelSessionLeave,
                Effect.FinishSession(appA, endedAt = 6_000L, overran = false),
                Effect.RefreshNotification,
            ),
            effects,
        )
        assertEquals(State.Unknown, sm.state)
    }

    @Test
    fun d1_switchBetweenMonitoredAppsShowsCorrectAppPrompt() {
        val sm = fresh()
        primeSession(sm, appA, startedAt = 1_000L, planned = 30)

        val effects = sm.tick(appB, monitored, 5_000L, entryEdge = true)

        val finishIdx = effects.indexOfFirst { it is Effect.FinishSession }
        val showIdx = effects.indexOfFirst { it is Effect.ShowPrompt }
        assertTrue("FinishSession should come before ShowPrompt", finishIdx >= 0 && finishIdx < showIdx)
        assertEquals(
            Effect.FinishSession(appA, endedAt = 5_000L, overran = false),
            effects[finishIdx],
        )
        assertEquals(Effect.ShowPrompt(appB), effects[showIdx])
        assertTrue(effects.any { it is Effect.SchedulePromptTimeout && it.pkg == appB })
        assertFalse("entering Prompting should not send Home", effects.any { it is Effect.SendHome })
        assertTrue(effects.none { it is Effect.Dismiss })
        assertTrue(sm.state is State.Prompting)
        assertEquals(appB, (sm.state as State.Prompting).pkg)
    }

    @Test
    fun d2_promptingSwitchesContentWhenDifferentMonitoredAppOpens() {
        val sm = fresh()
        primePrompting(sm, appA, now = 1_000L)

        val effects = sm.tick(appB, monitored, 2_000L, entryEdge = true)

        assertTrue(Effect.CancelPromptTimeout in effects)
        // 切换监控 app 时不发 Dismiss —— 复用同一 overlay，仅 setContent 替换内容，避免
        // visibility 翻转造成上一帧残留。详见 InterceptStateMachine.handlePromptingTick 注释。
        assertFalse("switching monitored apps should not Dismiss the overlay", Effect.Dismiss in effects)
        assertTrue(Effect.ShowPrompt(appB) in effects)
        assertEquals(appB, (sm.state as State.Prompting).pkg)

        val cancelIdx = effects.indexOf(Effect.CancelPromptTimeout)
        val showIdx = effects.indexOfFirst { it is Effect.ShowPrompt }
        assertTrue(cancelIdx >= 0 && cancelIdx < showIdx)
    }

    @Test
    fun b1_cancelThenReentryReprompts() {
        val sm = fresh()
        primePrompting(sm, appA, now = 1_000L)

        val cancelEffects = sm.onPromptCancelled(appA, now = 1_200L)
        assertEquals(
            listOf(
                Effect.CancelPromptTimeout,
                Effect.RecordInterceptOutcome(appA, InterceptOutcome.ABANDONED),
                Effect.SendHome(appA),
            ),
            cancelEffects,
        )
        assertEquals(1, cancelEffects.count { it is Effect.RecordInterceptOutcome })
        assertEquals(State.Background(null), sm.state)

        val reentry = sm.tick(appA, monitored, now = 1_500L, entryEdge = true)
        assertTrue(Effect.ShowPrompt(appA) in reentry)
        assertTrue(sm.state is State.Prompting)
    }

    @Test
    fun b1_cancelAfterRecentsDetourRepromptsOnNextForegroundObservation() {
        val sm = fresh()
        primePrompting(sm, appA, now = 1_000L)
        assertEquals(emptyList<Effect>(), sm.tick(launcher, monitored, now = 1_100L))

        sm.onPromptCancelled(appA, now = 1_200L)
        val reentry = sm.tick(appA, monitored, now = 1_300L, entryEdge = false)

        assertTrue(Effect.ShowPrompt(appA) in reentry)
        assertEquals(appA, (sm.state as State.Prompting).pkg)
    }

    @Test
    fun lateConfirmationAfterCancelIsRejected() {
        val sm = fresh()
        primePrompting(sm, appA, now = 1_000L)
        sm.onPromptCancelled(appA, now = 1_100L)

        val lateEffects = sm.onPromptConfirmed(appA, plannedMinutes = 30, now = 1_200L)

        assertEquals(emptyList<Effect>(), lateEffects)
        assertEquals(State.Background(null), sm.state)
    }

    @Test
    fun lateConfirmationFromPreviousPromptGenerationIsRejected() {
        val sm = fresh()
        primePrompting(sm, appA, now = 1_000L)
        sm.onPromptCancelled(appA, now = 1_100L)
        sm.tick(appB, monitored, now = 1_200L, entryEdge = true)

        val lateEffects = sm.onPromptConfirmed(appA, plannedMinutes = 30, now = 1_300L)

        assertEquals(emptyList<Effect>(), lateEffects)
        assertEquals(State.Prompting(appB, 1_200L), sm.state)
    }

    @Test
    fun b4_confirmTransitionsToSessionAndDismisses() {
        val sm = fresh()
        primePrompting(sm, appA, now = 1_000L)

        val effects = sm.onPromptConfirmed(appA, plannedMinutes = 30, now = 1_500L)
        assertEquals(
            listOf(
                Effect.CancelPromptTimeout,
                Effect.Dismiss,
                Effect.RefreshNotification,
            ),
            effects,
        )
        val s = sm.state as State.InSession
        assertEquals(appA, s.pkg)
        assertEquals(30, s.plannedMinutes)
        assertEquals(0, s.extensionCount)
        assertTrue(s.initialEntryPending)
    }

    @Test
    fun firstEntryEdgeAfterConfirmIsExempted() {
        val sm = fresh()
        primePrompting(sm, appA, now = 1_000L)
        sm.onPromptConfirmed(appA, plannedMinutes = 30, now = 1_500L)

        val effects = sm.tick(appA, monitored, now = 1_600L, entryEdge = true)

        assertEquals(emptyList<Effect>(), effects)
        val s = sm.state as State.InSession
        assertFalse(s.initialEntryPending)
    }

    @Test
    fun inSessionSamePackageEntryEdgeReprompts() {
        val sm = fresh()
        primeSession(sm, appA, startedAt = 1_000L, planned = 30)

        val effects = sm.tick(appA, monitored, now = 2_000L, entryEdge = true)

        assertEquals(
            Effect.FinishSession(appA, endedAt = 2_000L, overran = false),
            effects.first(),
        )
        assertTrue(Effect.ShowPrompt(appA) in effects)
        assertTrue(sm.state is State.Prompting)
    }

    @Test
    fun promptingSamePackageEntryEdgeDoesNotReshowPrompt() {
        val sm = fresh()
        primePrompting(sm, appA, now = 1_000L)

        val effects = sm.tick(appA, monitored, now = 1_200L, entryEdge = true)

        assertEquals(emptyList<Effect>(), effects)
        assertEquals(appA, (sm.state as State.Prompting).pkg)
    }

    @Test
    fun b5_promptTimeoutKeepsCoverUntilHomeCompletes() {
        val sm = fresh()
        primePrompting(sm, appA, now = 1_000L)

        val effects = sm.onPromptTimedOut(appA, now = 11_000L)
        assertEquals(
            listOf(
                Effect.RecordInterceptOutcome(appA, InterceptOutcome.TIMEOUT),
                Effect.SendHome(appA),
            ),
            effects,
        )
        assertEquals(1, effects.count { it is Effect.RecordInterceptOutcome })
        assertEquals(State.Background(null), sm.state)
    }

    @Test
    fun e_navigationBetweenNonMonitoredAppsProducesNoEffects() {
        val sm = fresh()
        val wechat = "com.tencent.mm"
        val browser = "com.android.chrome"

        assertEquals(emptyList<Effect>(), sm.tick(wechat, monitored, 1_000L))
        assertEquals(emptyList<Effect>(), sm.tick(wechat, monitored, 1_100L))
        assertEquals(emptyList<Effect>(), sm.tick(browser, monitored, 1_200L))
        assertEquals(emptyList<Effect>(), sm.tick(wechat, monitored, 1_300L))
        assertEquals(emptyList<Effect>(), sm.tick(null, monitored, 1_400L))
        assertTrue(sm.state is State.Background)
    }

    @Test
    fun f_timeUpExtendOnceThenSecondAttemptIsIgnored_andExitFinishesSession() {
        val sm = fresh()
        primeSession(sm, appA, startedAt = 1_000L, planned = 5)

        val timeUpEffects = sm.onSessionTimeElapsed(appA, now = 6_000L)
        assertEquals(listOf(Effect.ShowTimeUp(appA)), timeUpEffects)
        assertTrue(sm.state is State.TimeUp)

        val extend1 = sm.onTimeUpExtended(appA, addMinutes = 5, now = 6_100L)
        assertEquals(listOf(Effect.Dismiss, Effect.RefreshNotification), extend1)
        val sAfter = sm.state as State.InSession
        assertEquals(10, sAfter.plannedMinutes)
        assertEquals(1, sAfter.extensionCount)
        assertFalse(sAfter.initialEntryPending)

        sm.onSessionTimeElapsed(appA, now = 12_000L)
        assertTrue(sm.state is State.TimeUp)

        val extend2 = sm.onTimeUpExtended(appA, addMinutes = 5, now = 12_100L)
        assertEquals(emptyList<Effect>(), extend2)
        assertTrue(sm.state is State.TimeUp)

        val exitEffects = sm.onTimeUpExit(appA, now = 12_200L)
        assertEquals(
            Effect.FinishSession(appA, endedAt = 12_200L, overran = true),
            exitEffects.first(),
        )
        assertFalse(Effect.Dismiss in exitEffects)
        assertTrue(Effect.SendHome(appA) in exitEffects)
        assertEquals(State.Background(null), sm.state)
    }

    @Test
    fun timeUpExitWithoutExtensionDoesNotCountAsOverrun() {
        val sm = fresh()
        primeSession(sm, appA, startedAt = 1_000L, planned = 5)
        sm.onSessionTimeElapsed(appA, now = 301_000L)

        // 到点窗出现和点击都必然稍晚于 deadline；这段 UI 响应时间不应算超时。
        val exitEffects = sm.onTimeUpExit(appA, now = 303_000L)

        assertEquals(
            Effect.FinishSession(appA, endedAt = 303_000L, overran = false),
            exitEffects.first(),
        )
        assertFalse(Effect.Dismiss in exitEffects)
        assertTrue(Effect.SendHome(appA) in exitEffects)
    }

    @Test
    fun leavingWhileTimeUpIsVisibleDoesNotCountAsOverrunWithoutExtension() {
        val sm = fresh()
        primeSession(sm, appA, startedAt = 1_000L, planned = 5)
        sm.onSessionTimeElapsed(appA, now = 301_000L)

        val leaveEffects = sm.tick(launcher, monitored, now = 320_000L)

        assertEquals(
            Effect.FinishSession(appA, endedAt = 320_000L, overran = false),
            leaveEffects.first(),
        )
        assertTrue(Effect.Dismiss in leaveEffects)
        assertEquals(State.Background(launcher), sm.state)
    }

    @Test
    fun timeUpSwitchToOtherMonitoredAppReusesOverlayWithoutDismiss() {
        val sm = fresh()
        primeSession(sm, appA, startedAt = 1_000L, planned = 5)
        sm.onSessionTimeElapsed(appA, now = 301_000L)

        val effects = sm.tick(appB, monitored, now = 302_000L)

        assertEquals(
            listOf(
                Effect.FinishSession(appA, endedAt = 302_000L, overran = false),
                Effect.RefreshNotification,
                Effect.ShowPrompt(appB),
                Effect.SchedulePromptTimeout(appB, 10_000L),
            ),
            effects,
        )
        assertTrue(effects.none { it is Effect.Dismiss })
        assertTrue(effects.none { it is Effect.SendHome })
        assertEquals(State.Prompting(appB, 302_000L), sm.state)
    }

    @Test
    fun removingPackageWhileTimeUpDismissesWithoutSendingHome() {
        val sm = fresh()
        primeSession(sm, appA, startedAt = 1_000L, planned = 5)
        sm.onSessionTimeElapsed(appA, now = 301_000L)

        val effects = sm.tick(null, setOf(appB), now = 302_000L)

        assertEquals(
            listOf(
                Effect.FinishSession(appA, endedAt = 302_000L, overran = false),
                Effect.Dismiss,
                Effect.RefreshNotification,
            ),
            effects,
        )
        assertTrue(effects.none { it is Effect.SendHome })
        assertEquals(State.Background(null), sm.state)
    }

    @Test
    fun resetWhileTimeUpPreservesExtensionBasedOverrun() {
        val withoutExtension = fresh()
        primeSession(withoutExtension, appA, startedAt = 1_000L, planned = 5)
        withoutExtension.onSessionTimeElapsed(appA, now = 301_000L)

        assertEquals(
            Effect.FinishSession(appA, endedAt = 310_000L, overran = false),
            withoutExtension.reset(now = 310_000L).first(),
        )

        val afterExtension = fresh()
        primeSession(afterExtension, appA, startedAt = 1_000L, planned = 5)
        afterExtension.onSessionTimeElapsed(appA, now = 301_000L)
        afterExtension.onTimeUpExtended(appA, addMinutes = 5, now = 302_000L)
        afterExtension.onSessionTimeElapsed(appA, now = 601_000L)

        assertEquals(
            Effect.FinishSession(appA, endedAt = 610_000L, overran = true),
            afterExtension.reset(now = 610_000L).first(),
        )
    }

    @Test
    fun leavingAfterExtensionCountsAsOverrunEvenBeforeSecondDeadline() {
        val sm = fresh()
        primeSession(sm, appA, startedAt = 1_000L, planned = 5)
        sm.onSessionTimeElapsed(appA, now = 301_000L)
        sm.onTimeUpExtended(appA, addMinutes = 5, now = 302_000L)

        val switchEffects = sm.tick(appB, monitored, now = 303_000L, entryEdge = true)

        assertEquals(
            Effect.FinishSession(appA, endedAt = 303_000L, overran = true),
            switchEffects.first(),
        )
        assertTrue(Effect.ShowPrompt(appB) in switchEffects)
    }

    @Test
    fun timeUpExtensionRequestDoesNotUpdateTimerWhenStateRejects() {
        val sm = fresh()
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.Unconfined)
        val timer = SessionTimer(scope)
        val store = SessionStore(
            timer = timer,
            writer = object : FakeUsageRepository() {
                override suspend fun startSession(
                    packageName: String,
                    appName: String,
                    reason: String,
                    plannedMinutes: Int,
                ) = 42L

                override suspend fun finishSession(id: Long, endedAt: Long, overran: Boolean) = Unit

                override suspend fun extendSession(id: Long, plannedMinutes: Int) = Unit
            },
            writerScope = scope,
        )
        try {
            primeSession(sm, appA, startedAt = 1_000L, planned = 5)
            runBlocking { store.start(appA, "A", "reply", 5) {} }
            sm.onSessionTimeElapsed(appA, now = 6_000L)

            val first = requestTimeUpExtension(
                stateMachine = sm,
                sessionStore = store,
                pkg = appA,
                addMinutes = 5,
                now = 6_100L,
                onElapsed = {},
            )

            assertTrue(first is TimeUpExtensionAttempt.Applied)
            assertEquals(10, store.get(appA)?.plannedMinutes)
            assertEquals(1, store.get(appA)?.extensionCount)

            sm.onSessionTimeElapsed(appA, now = 12_000L)
            val second = requestTimeUpExtension(
                stateMachine = sm,
                sessionStore = store,
                pkg = appA,
                addMinutes = 5,
                now = 12_100L,
                onElapsed = {},
            )

            assertEquals(TimeUpExtensionAttempt.Rejected, second)
            assertEquals(10, store.get(appA)?.plannedMinutes)
            assertEquals(1, store.get(appA)?.extensionCount)
        } finally {
            timer.cancelAll()
            job.cancel()
        }
    }

    @Test
    fun emptyMonitoredSetProducesNoEffects() {
        val sm = fresh()
        val effects = sm.tick(appA, emptySet(), 1_000L)
        assertEquals(emptyList<Effect>(), effects)
        assertEquals(State.Background(appA), sm.state)
    }

    @Test
    fun removingPackageWhileInSessionFinishesWithoutSendingHome() {
        val sm = fresh()
        primeSession(sm, appA, startedAt = 1_000L, planned = 30)

        val effects = sm.tick(null, setOf(appB), now = 2_000L)

        assertEquals(
            listOf(
                Effect.FinishSession(appA, endedAt = 2_000L, overran = false),
                Effect.RefreshNotification,
            ),
            effects,
        )
        assertTrue(effects.none { it is Effect.ShowPrompt || it is Effect.SendHome })
        assertEquals(State.Background(null), sm.state)
    }

    @Test
    fun promptingSurvivesTargetBeingRemovedUntilExplicitOutcome() {
        val sm = fresh()
        primePrompting(sm, appA, now = 1_000L)

        assertEquals(emptyList<Effect>(), sm.tick(appA, setOf(appB), 1_100L))
        assertEquals(emptyList<Effect>(), sm.tick(launcher, setOf(appB), 1_200L))
        assertEquals(State.Prompting(appA, 1_000L), sm.state)

        val cancelled = sm.onPromptCancelled(appA, 1_300L)
        assertTrue(Effect.SendHome(appA) in cancelled)
        assertEquals(State.Background(null), sm.state)
    }

    @Test
    fun confirmationThatCompletesAfterRemovalImmediatelyFinishesTheNewSession() {
        val sm = fresh()
        primePrompting(sm, appA, now = 1_000L)

        val confirmed = sm.onPromptConfirmed(appA, plannedMinutes = 30, now = 2_000L)
        val removal = sm.tick(null, setOf(appB), now = 2_001L)

        assertEquals(
            listOf(
                Effect.CancelPromptTimeout,
                Effect.Dismiss,
                Effect.RefreshNotification,
            ),
            confirmed,
        )
        assertEquals(
            listOf(
                Effect.FinishSession(appA, endedAt = 2_001L, overran = false),
                Effect.RefreshNotification,
            ),
            removal,
        )
        assertEquals(State.Background(null), sm.state)
    }

    @Test
    fun resetWhilePromptingDismisses() {
        val sm = fresh()
        primePrompting(sm, appA, now = 1_000L)

        val effects = sm.reset(now = 2_000L)
        assertEquals(listOf(Effect.CancelPromptTimeout, Effect.Dismiss), effects)
        assertEquals(State.Unknown, sm.state)
    }

    @Test
    fun resetWhileInSessionFinishesSession() {
        val sm = fresh()
        primeSession(sm, appA, startedAt = 1_000L, planned = 30)

        val effects = sm.reset(now = 2_000L)
        assertEquals(
            listOf(
                Effect.FinishSession(appA, endedAt = 2_000L, overran = false),
                Effect.RefreshNotification,
            ),
            effects,
        )
        assertEquals(State.Unknown, sm.state)
    }

    @Test
    fun promptingSurvivesHomeOrRecentsDetour() {
        val sm = fresh()
        primePrompting(sm, appA, now = 1_000L)

        val effects = sm.tick(launcher, monitored, 1_100L)

        assertEquals(emptyList<Effect>(), effects)
        assertEquals(appA, (sm.state as State.Prompting).pkg)
    }

    @Test
    fun promptingSurvivesFastHomeThenReentryBeforeUserResponds() {
        val sm = fresh()
        primePrompting(sm, appA, now = 1_000L)

        assertEquals(emptyList<Effect>(), sm.tick(launcher, monitored, 1_050L))
        val reentry = sm.tick(appA, monitored, 1_100L, entryEdge = true)

        assertEquals(emptyList<Effect>(), reentry)
        assertEquals(appA, (sm.state as State.Prompting).pkg)
    }

    @Test
    fun interactionResetsTimeoutWithLongerWindow() {
        val sm = fresh()
        primePrompting(sm, appA, now = 1_000L)

        val effects = sm.onPromptInteraction(appA, now = 1_500L)
        assertEquals(1, effects.size)
        val sched = effects.single() as Effect.SchedulePromptTimeout
        assertEquals(appA, sched.pkg)
        assertEquals(20_000L, sched.durationMs)
    }
}
