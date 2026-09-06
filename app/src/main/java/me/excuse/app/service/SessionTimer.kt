package me.excuse.app.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 每个 packageName 最多挂一个计时协程。到点回调 onElapsed。 */
class SessionTimer(
    private val scope: CoroutineScope,
    private val wallClockMillis: () -> Long = System::currentTimeMillis,
    private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val suspendFor: suspend (Long) -> Unit = { delay(it) },
) {

    data class Active(
        val sessionId: Long,
        val packageName: String,
        val plannedMinutes: Int,
        val startTime: Long,
        val startedAtMonotonic: Long,
        val job: Job,
        val extensionCount: Int = 0,
    )

    fun start(
        sessionId: Long,
        packageName: String,
        plannedMinutes: Int,
        onElapsed: () -> Unit,
    ): Active {
        cancel(packageName)
        val startTime = wallClockMillis()
        val startedAtMonotonic = monotonicMillis()
        val job = scope.launch {
            suspendFor(plannedMinutes * 60_000L)
            onElapsed()
        }
        val active = Active(
            sessionId = sessionId,
            packageName = packageName,
            plannedMinutes = plannedMinutes,
            startTime = startTime,
            startedAtMonotonic = startedAtMonotonic,
            job = job,
        )
        jobs[packageName] = active
        return active
    }

    fun extend(packageName: String, addMinutes: Int, onElapsed: () -> Unit): Active? {
        val existing = jobs[packageName] ?: return null
        existing.job.cancel()
        val newPlanned = existing.plannedMinutes + addMinutes
        val job = scope.launch {
            // 墙钟会被用户改时区、校时或回拨；deadline 只由进程内单调时钟决定，
            // 否则一次墙钟跳变就可能把五分钟延期变成数小时或立刻到点。
            val elapsedSinceStart =
                (monotonicMillis() - existing.startedAtMonotonic).coerceAtLeast(0L)
            val remaining = newPlanned * 60_000L - elapsedSinceStart
            if (remaining > 0L) suspendFor(remaining)
            onElapsed()
        }
        val updated = existing.copy(
            plannedMinutes = newPlanned,
            extensionCount = existing.extensionCount + 1,
            job = job,
        )
        jobs[packageName] = updated
        return updated
    }

    fun cancel(packageName: String) {
        jobs.remove(packageName)?.job?.cancel()
    }

    fun get(packageName: String): Active? = jobs[packageName]

    fun cancelAll() {
        jobs.values.forEach { it.job.cancel() }
        jobs.clear()
    }

    private val jobs = HashMap<String, Active>()
}
