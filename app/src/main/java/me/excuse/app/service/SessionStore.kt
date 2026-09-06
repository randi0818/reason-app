package me.excuse.app.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.excuse.app.data.UsageRepositoryContract

/**
 * Active session 的单一运行时真相源。计时器只负责 deadline，这里把它和展示元数据、
 * session id 以及持久化写入绑在同一条生命周期上。
 */
class SessionStore(
    private val timer: SessionTimer,
    private val writer: UsageRepositoryContract,
    private val writerScope: CoroutineScope,
    private val onWriteFailure: (operation: String, error: Throwable) -> Unit = { _, error ->
        error.printStackTrace()
    },
    private val retryBackoffMillis: List<Long> = listOf(250L, 1_000L, 5_000L),
    private val waitBeforeRetry: suspend (Long) -> Unit = { delay(it) },
) {
    data class Session(
        val sessionId: Long,
        val packageName: String,
        val appName: String,
        val reason: String,
        val plannedMinutes: Int,
        val startedAt: Long,
        val extensionCount: Int = 0,
    )

    private sealed interface PendingWrite {
        val description: String

        suspend fun persist(writer: UsageRepositoryContract)

        data class Finish(
            val sessionId: Long,
            val endedAt: Long,
            val overran: Boolean,
        ) : PendingWrite {
            override val description = "finish session=$sessionId"

            override suspend fun persist(writer: UsageRepositoryContract) {
                writer.finishSession(sessionId, endedAt, overran)
            }
        }

        data class Extend(
            val sessionId: Long,
            val plannedMinutes: Int,
        ) : PendingWrite {
            override val description = "extend session=$sessionId to=$plannedMinutes"

            override suspend fun persist(writer: UsageRepositoryContract) {
                writer.extendSession(sessionId, plannedMinutes)
            }
        }
    }

    private data class QueuedWrite(val id: Long, val write: PendingWrite)

    private val sessions = HashMap<String, Session>()
    private val writeLock = Any()
    private val pendingWrites = ArrayDeque<QueuedWrite>()
    private var nextWriteId = 1L
    private var writeJob: Job? = null
    private var shuttingDown = false

    suspend fun start(
        packageName: String,
        appName: String,
        reason: String,
        plannedMinutes: Int,
        onElapsed: () -> Unit,
    ): Session {
        check(!shuttingDown) { "Session store has stopped" }
        val sessionId = writer.startSession(packageName, appName, reason, plannedMinutes)
        val active = timer.start(sessionId, packageName, plannedMinutes, onElapsed)
        return Session(
            sessionId = sessionId,
            packageName = packageName,
            appName = appName,
            reason = reason,
            plannedMinutes = plannedMinutes,
            startedAt = active.startTime,
        ).also { sessions[packageName] = it }
    }

    fun finish(packageName: String, endedAt: Long, overran: Boolean) {
        val session = remove(packageName) ?: return
        enqueue(PendingWrite.Finish(session.sessionId, endedAt, overran))
    }

    suspend fun finishNow(packageName: String, endedAt: Long, overran: Boolean) {
        val session = remove(packageName) ?: return
        writer.finishSession(session.sessionId, endedAt, overran)
    }

    fun extend(
        packageName: String,
        addMinutes: Int,
        onElapsed: () -> Unit,
    ): Session? {
        val existing = sessions[packageName] ?: return null
        val active = timer.extend(packageName, addMinutes, onElapsed) ?: return null
        val updated = existing.copy(
            plannedMinutes = active.plannedMinutes,
            extensionCount = active.extensionCount,
        )
        sessions[packageName] = updated
        // 用绝对值写入，让 service 销毁时重放一个已经成功、但尚未来得及从队列移除的
        // 延期操作仍然幂等；增量 UPDATE 在这个窗口里会平白多加五分钟。
        enqueue(PendingWrite.Extend(updated.sessionId, updated.plannedMinutes))
        return updated
    }

    fun get(packageName: String): Session? = sessions[packageName]

    fun all(): Collection<Session> = sessions.values.toList()

    /** 在 service 所在线程冻结元数据和终点；数据库再慢也不能推迟计时器和窗口的停止。 */
    fun stopForShutdown(endedAt: Long) {
        val active = sessions.values.toList()
        synchronized(writeLock) {
            if (shuttingDown) return
            shuttingDown = true
            active.forEach { session ->
                pendingWrites.addLast(
                    queued(
                        PendingWrite.Finish(
                            sessionId = session.sessionId,
                            endedAt = endedAt,
                            // 服务可能正好在到点窗显示期间关闭；墙钟超过 deadline 不代表用户延期。
                            overran = session.extensionCount > 0,
                        )
                    )
                )
            }
            writeJob?.cancel()
        }
        sessions.clear()
        timer.cancelAll()
    }

    /** 只冲刷已冻结的写入队列，不从后台线程访问 session 元数据或计时器。 */
    suspend fun flushForShutdown() {
        val runningWriter = synchronized(writeLock) {
            check(shuttingDown) { "Stop the session store before flushing" }
            writeJob
        }
        // 先等普通 drain 完全退出，再按原顺序重放；已成功但尚未出队的操作必须幂等。
        runningWriter?.cancelAndJoin()
        synchronized(writeLock) { writeJob = null }
        drainPendingWritesForShutdown()
    }

    private fun remove(packageName: String): Session? {
        val session = sessions.remove(packageName) ?: return null
        timer.cancel(packageName)
        return session
    }

    private fun enqueue(write: PendingWrite) {
        synchronized(writeLock) {
            pendingWrites.addLast(queued(write))
            if (!shuttingDown && writeJob?.isActive != true) {
                writeJob = writerScope.launch { drainPendingWritesInBackground() }
            }
        }
    }

    private fun queued(write: PendingWrite): QueuedWrite =
        QueuedWrite(id = nextWriteId++, write = write)

    private suspend fun drainPendingWritesInBackground() {
        var retryingWriteId: Long? = null
        var nextBackoffIndex = 0
        while (true) {
            val current = synchronized(writeLock) {
                pendingWrites.firstOrNull().also {
                    if (it == null) writeJob = null
                }
            } ?: return
            if (retryingWriteId != current.id) {
                retryingWriteId = current.id
                nextBackoffIndex = 0
            }

            try {
                current.write.persist(writer)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                reportWriteFailure(current.write, error)
                val backoff = retryBackoffMillis.getOrNull(nextBackoffIndex)
                if (backoff == null) {
                    // 有限重试耗尽后仍留在队首，shutdown 会再冲刷；不能越过失败的延期
                    // 先写 finish，否则 DAO 会因 endTime 已落盘而拒绝随后补写的延期。
                    synchronized(writeLock) { writeJob = null }
                    return
                }
                nextBackoffIndex += 1
                waitBeforeRetry(backoff)
                continue
            }

            synchronized(writeLock) {
                if (pendingWrites.firstOrNull()?.id == current.id) {
                    pendingWrites.removeFirst()
                }
            }
            retryingWriteId = null
            nextBackoffIndex = 0
        }
    }

    private suspend fun drainPendingWritesForShutdown() {
        while (true) {
            val current = synchronized(writeLock) { pendingWrites.firstOrNull() } ?: return
            try {
                current.write.persist(writer)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                reportWriteFailure(current.write, error)
                throw error
            }
            synchronized(writeLock) {
                if (pendingWrites.firstOrNull()?.id == current.id) {
                    pendingWrites.removeFirst()
                }
            }
        }
    }

    private fun reportWriteFailure(write: PendingWrite, error: Throwable) {
        try {
            onWriteFailure(write.description, error)
        } catch (reportingError: Throwable) {
            error.addSuppressed(reportingError)
            error.printStackTrace()
        }
    }
}
