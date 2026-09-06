package me.excuse.app.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.excuse.app.data.FakeUsageRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionStoreTest {
    private val appA = "com.example.a"
    private val appB = "com.example.b"

    @Test
    fun startThenFinishPassesTheInsertedId() = withStore { store, writer ->
        val started = runBlocking {
            store.start(appA, "A", "reply", 15) {}
        }

        assertEquals(1L, started.sessionId)
        assertEquals("A", store.get(appA)?.appName)
        assertEquals("reply", store.get(appA)?.reason)
        assertTrue(started.startedAt > 0L)

        store.finish(appA, endedAt = 5_000L, overran = false)

        assertEquals(listOf(FinishCall(1L, 5_000L, false)), writer.finished)
        assertNull(store.get(appA))
    }

    @Test
    fun extendUpdatesTimerMetadataAndWriter() = withStore { store, writer ->
        runBlocking { store.start(appA, "A", "reply", 15) {} }

        val extended = store.extend(appA, addMinutes = 5) {}

        assertEquals(20, extended?.plannedMinutes)
        assertEquals(1, extended?.extensionCount)
        assertEquals(listOf(ExtendCall(1L, 20)), writer.extended)
    }

    @Test
    fun shutdownFinishesEveryActiveSessionAndClearsTimers() = withStore { store, writer ->
        runBlocking {
            store.start(appA, "A", "reply", 15) {}
            store.start(appB, "B", "watch", 30) {}
        }
        store.extend(appB, addMinutes = 5) {}

        store.stopForShutdown(endedAt = 9_000L)
        runBlocking { store.flushForShutdown() }

        assertEquals(
            setOf(
                FinishCall(1L, 9_000L, false),
                FinishCall(2L, 9_000L, true),
            ),
            writer.finished.toSet(),
        )
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun shutdownFlushesFinishQueuedAfterWriterScopeWasCancelled() {
        val timerScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val writerScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val writer = FakeWriter()
        val store = SessionStore(SessionTimer(timerScope), writer, writerScope)
        try {
            runBlocking { store.start(appA, "A", "reply", 15) {} }
            writerScope.cancel()

            store.finish(appA, endedAt = 5_000L, overran = false)
            assertTrue(writer.finished.isEmpty())

            store.stopForShutdown(endedAt = 9_000L)
            runBlocking { store.flushForShutdown() }

            assertEquals(listOf(FinishCall(1L, 5_000L, false)), writer.finished)
        } finally {
            timerScope.cancel()
        }
    }

    @Test
    fun shutdownFlushesExtensionBeforeFinishWhenWriterScopeWasCancelled() {
        val timerScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val writerScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val writer = FakeWriter()
        val store = SessionStore(SessionTimer(timerScope), writer, writerScope)
        try {
            runBlocking { store.start(appA, "A", "reply", 15) {} }
            writerScope.cancel()

            store.extend(appA, addMinutes = 5) {}
            store.finish(appA, endedAt = 5_000L, overran = true)
            store.stopForShutdown(endedAt = 9_000L)
            runBlocking { store.flushForShutdown() }

            assertEquals(
                listOf("extend:1:20", "finish:1:5000:true"),
                writer.operations,
            )
        } finally {
            timerScope.cancel()
        }
    }

    @Test
    fun failedBackgroundWriteIsReportedAndRetriedDuringShutdown() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val writer = FakeWriter().apply { finishFailuresRemaining = 1 }
        val failures = mutableListOf<String>()
        val store = SessionStore(
            timer = SessionTimer(scope),
            writer = writer,
            writerScope = scope,
            onWriteFailure = { operation, _ -> failures += operation },
        )
        try {
            runBlocking { store.start(appA, "A", "reply", 15) {} }
            store.finish(appA, endedAt = 5_000L, overran = false)

            assertEquals(listOf("finish session=1"), failures)
            assertTrue(writer.finished.isEmpty())

            store.stopForShutdown(endedAt = 9_000L)
            runBlocking { store.flushForShutdown() }

            assertEquals(listOf(FinishCall(1L, 5_000L, false)), writer.finished)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun failedBackgroundWriteRetriesWithoutAnotherWriteOrShutdown() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val writer = FakeWriter().apply { finishFailuresRemaining = 1 }
        val failures = mutableListOf<String>()
        val backoffs = mutableListOf<Long>()
        val store = SessionStore(
            timer = SessionTimer(scope),
            writer = writer,
            writerScope = scope,
            onWriteFailure = { operation, _ -> failures += operation },
            retryBackoffMillis = listOf(25L),
            waitBeforeRetry = { backoffs += it },
        )
        try {
            runBlocking { store.start(appA, "A", "reply", 15) {} }

            store.finish(appA, endedAt = 5_000L, overran = false)

            assertEquals(listOf("finish session=1"), failures)
            assertEquals(listOf(25L), backoffs)
            assertEquals(listOf(FinishCall(1L, 5_000L, false)), writer.finished)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun stopClearsTimersImmediatelyEvenWhenTheShutdownWriteIsSlow() = runBlocking {
        withTimeout(5_000L) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val writeStarted = CompletableDeferred<Unit>()
            val allowWrite = CompletableDeferred<Unit>()
            val finishes = mutableListOf<FinishCall>()
            val writer = object : FakeUsageRepository() {
                override suspend fun startSession(
                    packageName: String, appName: String, reason: String, plannedMinutes: Int,
                ) = 42L

                override suspend fun finishSession(id: Long, endedAt: Long, overran: Boolean) {
                    writeStarted.complete(Unit)
                    allowWrite.await()
                    finishes += FinishCall(id, endedAt, overran)
                }
            }
            val timer = SessionTimer(scope)
            val store = SessionStore(timer, writer, scope)
            try {
                store.start(appA, "A", "reply", 15) {}
                store.stopForShutdown(9_000L)

                assertNull(timer.get(appA))
                assertTrue(store.all().isEmpty())
                val flush = launch { store.flushForShutdown() }
                writeStarted.await()
                assertTrue(finishes.isEmpty())

                // 重复停服不能覆盖原始停止时刻，也不能重复入队。
                store.stopForShutdown(12_000L)
                allowWrite.complete(Unit)
                flush.join()
                assertEquals(listOf(FinishCall(42L, 9_000L, false)), finishes)
            } finally {
                scope.cancel()
            }
        }
    }

    private fun withStore(block: (SessionStore, FakeWriter) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val writer = FakeWriter()
        val store = SessionStore(SessionTimer(scope), writer, scope)
        try {
            block(store, writer)
        } finally {
            scope.cancel()
        }
    }

    private data class FinishCall(val id: Long, val endedAt: Long, val overran: Boolean)
    private data class ExtendCall(val id: Long, val plannedMinutes: Int)

    private class FakeWriter : FakeUsageRepository() {
        private var nextId = 1L
        var finishFailuresRemaining = 0
        val finished = mutableListOf<FinishCall>()
        val extended = mutableListOf<ExtendCall>()
        val operations = mutableListOf<String>()

        override suspend fun startSession(
            packageName: String,
            appName: String,
            reason: String,
            plannedMinutes: Int,
        ): Long = nextId++

        override suspend fun finishSession(id: Long, endedAt: Long, overran: Boolean) {
            if (finishFailuresRemaining > 0) {
                finishFailuresRemaining -= 1
                error("synthetic finish failure")
            }
            finished += FinishCall(id, endedAt, overran)
            operations += "finish:$id:$endedAt:$overran"
        }

        override suspend fun extendSession(id: Long, plannedMinutes: Int) {
            extended += ExtendCall(id, plannedMinutes)
            operations += "extend:$id:$plannedMinutes"
        }
    }
}
