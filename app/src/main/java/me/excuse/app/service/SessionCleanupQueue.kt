package me.excuse.app.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** 应用级收尾队列：新 service 的恢复必须等旧 service 写完准确终点，不能抢先补结束。 */
class SessionCleanupQueue(
    private val scope: CoroutineScope,
    private val onFailure: (String, Exception) -> Unit,
) {
    private val lock = Any()
    private var tail: Job? = null

    fun enqueue(operation: String, cleanup: suspend () -> Unit): Job {
        val job = synchronized(lock) {
            val previous = tail
            scope.launch(start = CoroutineStart.LAZY) {
                previous?.join()
                try {
                    cleanup()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    onFailure(operation, error)
                }
            }.also { tail = it }
        }
        job.start()
        return job
    }
}
