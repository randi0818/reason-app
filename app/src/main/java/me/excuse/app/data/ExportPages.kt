package me.excuse.app.data

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** 用时间和 id 共同续读，避免同毫秒记录漏页，也避免 OFFSET 越翻越慢。 */
internal suspend fun <T> consumeExportPages(
    fetch: suspend (Long?, Long) -> List<T>,
    timestamp: (T) -> Long,
    id: (T) -> Long,
    consume: (T) -> Unit,
): Int {
    var afterTime: Long? = null
    var afterId = 0L
    var count = 0
    while (true) {
        currentCoroutineContext().ensureActive()
        val page = fetch(afterTime, afterId)
        if (page.isEmpty()) return count
        page.forEach(consume)
        count += page.size
        afterTime = timestamp(page.last())
        afterId = id(page.last())
    }
}
