package me.excuse.app.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SessionCleanupQueueTest {
    @Test
    fun slowShutdownReturnsImmediatelyAndRecoveryWaitsForItsExactEndTime() = runBlocking {
        withTimeout(5_000L) {
            val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val allowWrite = CompletableDeferred<Unit>()
            val operations = mutableListOf<String>()
            var storedEnd: Long? = null
            val queue = SessionCleanupQueue(appScope) { _, error -> throw error }
            try {
                val shutdown = queue.enqueue("shutdown") {
                    operations += "shutdown started"
                    allowWrite.await()
                    storedEnd = 9_000L
                    operations += "shutdown finished"
                }
                val recovery = queue.enqueue("recovery") {
                    if (storedEnd == null) storedEnd = 60_000L
                    operations += "recovery finished"
                }

                assertFalse(shutdown.isCompleted)
                assertFalse(recovery.isCompleted)
                assertEquals(listOf("shutdown started"), operations)
                allowWrite.complete(Unit)
                recovery.join()
                assertEquals(9_000L, storedEnd)
                assertEquals(
                    listOf("shutdown started", "shutdown finished", "recovery finished"),
                    operations,
                )
            } finally {
                appScope.cancel()
            }
        }
    }

    @Test
    fun cancellingTheServiceDoesNotCancelAnAlreadyEnqueuedCleanup() = runBlocking {
        withTimeout(5_000L) {
            val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val allowWrite = CompletableDeferred<Unit>()
            val completed = CompletableDeferred<Unit>()
            val queue = SessionCleanupQueue(appScope) { _, error -> throw error }
            try {
                serviceScope.launch {
                    queue.enqueue("shutdown") {
                        allowWrite.await()
                        completed.complete(Unit)
                    }
                }.join()
                serviceScope.cancel()
                allowWrite.complete(Unit)
                completed.await()
            } finally {
                serviceScope.cancel()
                appScope.cancel()
            }
        }
    }

    @Test
    fun aFailedFlushIsReportedButDoesNotPermanentlyBlockRecovery() = runBlocking {
        withTimeout(5_000L) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val operations = mutableListOf<String>()
            val queue = SessionCleanupQueue(scope) { operation, _ -> operations += "$operation failed" }
            try {
                queue.enqueue("shutdown") { error("disk unavailable") }
                queue.enqueue("recovery") { operations += "recovered" }.join()
                assertEquals(listOf("shutdown failed", "recovered"), operations)
            } finally {
                scope.cancel()
            }
        }
    }
}
