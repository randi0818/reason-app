package me.excuse.app.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import me.excuse.app.data.FakeUsageRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `requestTimeUpExtension` 把「状态机是否允许延期」和「计时器里还有没有这个 session」
 * 两个独立事实合成一个结论。三个分支各自对应一种真实处境，而最重要的一条是 Rejected：
 * 状态机拒绝时**不能**碰计时器和数据库，否则连点「+5 分钟」就会重复加时。
 */
class TimeUpExtensionPolicyTest {

    private val pkg = "com.example.a"

    @Test
    fun appliedWhenMachineAllowsAndSessionIsStillTracked() = withStore { machine, store, writer ->
        reachTimeUp(machine, store)

        val attempt = requestTimeUpExtension(
            stateMachine = machine,
            sessionStore = store,
            pkg = pkg,
            addMinutes = 5,
            now = 2_000L,
            onElapsed = {},
        )

        assertTrue(attempt is TimeUpExtensionAttempt.Applied)
        val applied = attempt as TimeUpExtensionAttempt.Applied
        assertTrue(applied.effects.isNotEmpty())
        assertEquals(20, applied.session.plannedMinutes)
        assertEquals(1, applied.session.extensionCount)
        assertEquals(listOf(1L to 20), writer.extended)
    }

    @Test
    fun timerMissingWhenMachineAllowsButSessionIsGone() = withStore { machine, store, writer ->
        reachTimeUp(machine, store)
        // 服务被回收、session 已经结算等情况下，状态机还停在 TimeUp，但 store 里已经没有它。
        store.finish(pkg, endedAt = 1_500L, overran = false)

        val attempt = requestTimeUpExtension(
            stateMachine = machine,
            sessionStore = store,
            pkg = pkg,
            addMinutes = 5,
            now = 2_000L,
            onElapsed = {},
        )

        assertTrue(attempt is TimeUpExtensionAttempt.TimerMissing)
        // 状态机那一侧仍然产出了 effect，调用方需要靠它把状态收干净。
        assertTrue((attempt as TimeUpExtensionAttempt.TimerMissing).effects.isNotEmpty())
        assertTrue(writer.extended.isEmpty())
    }

    @Test
    fun rejectedLeavesTimerAndWriterUntouched() = withStore { machine, store, writer ->
        reachTimeUp(machine, store)

        val first = requestTimeUpExtension(
            stateMachine = machine, sessionStore = store, pkg = pkg,
            addMinutes = 5, now = 2_000L, onElapsed = {},
        )
        assertTrue(first is TimeUpExtensionAttempt.Applied)

        // 第二次延期：maxExtensions = 1，状态机拒绝。这里必须完全没有副作用 ——
        // 用户连点「+5 分钟」不能变成加了 10 分钟。
        val second = requestTimeUpExtension(
            stateMachine = machine, sessionStore = store, pkg = pkg,
            addMinutes = 5, now = 2_100L, onElapsed = {},
        )

        assertEquals(TimeUpExtensionAttempt.Rejected, second)
        assertEquals(listOf(1L to 20), writer.extended)
        assertEquals(20, store.get(pkg)?.plannedMinutes)
        assertEquals(1, store.get(pkg)?.extensionCount)
    }

    @Test
    fun rejectedForAPackageThatIsNotTheOneAtTimeUp() = withStore { machine, store, writer ->
        reachTimeUp(machine, store)

        val attempt = requestTimeUpExtension(
            stateMachine = machine, sessionStore = store, pkg = "com.example.other",
            addMinutes = 5, now = 2_000L, onElapsed = {},
        )

        assertEquals(TimeUpExtensionAttempt.Rejected, attempt)
        assertTrue(writer.extended.isEmpty())
        assertEquals(15, store.get(pkg)?.plannedMinutes)
    }

    /** 走完 Prompting → InSession → TimeUp，并让 store 里存在同一个 session。 */
    private fun reachTimeUp(machine: InterceptStateMachine, store: SessionStore) {
        machine.tick(foreground = pkg, monitored = setOf(pkg), now = 0L)
        machine.onPromptConfirmed(pkg, plannedMinutes = 15, now = 100L)
        runBlocking { store.start(pkg, "A", "reply", 15) {} }
        machine.onSessionTimeElapsed(pkg, now = 1_000L)
        assertTrue(machine.state is InterceptStateMachine.State.TimeUp)
    }

    private fun withStore(block: (InterceptStateMachine, SessionStore, FakeWriter) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val writer = FakeWriter()
        val store = SessionStore(SessionTimer(scope), writer, scope)
        try {
            block(InterceptStateMachine(), store, writer)
        } finally {
            scope.cancel()
        }
    }

    private class FakeWriter : FakeUsageRepository() {
        private var nextId = 1L
        val extended = mutableListOf<Pair<Long, Int>>()

        override suspend fun startSession(
            packageName: String,
            appName: String,
            reason: String,
            plannedMinutes: Int,
        ): Long = nextId++

        override suspend fun extendSession(id: Long, plannedMinutes: Int) {
            extended += id to plannedMinutes
        }
    }
}
