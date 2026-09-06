package me.excuse.app.service

/** 日期边界和刷新时间各管一件事：本地确认可以延长 TTL，但不能把昨天延长成今天。 */
internal class DailyReasonCountsCache(private val ttlMillis: Long) {
    data class Entry(
        val dayStartMillis: Long,
        val updatedAt: Long,
        val counts: Map<String, Int>,
    )

    private val entries = HashMap<String, Entry>()

    fun get(packageName: String, dayStartMillis: Long): Entry? {
        val entry = entries[packageName] ?: return null
        if (entry.dayStartMillis == dayStartMillis) return entry
        entries.remove(packageName)
        return null
    }

    fun needsRefresh(entry: Entry?, nowMillis: Long): Boolean =
        entry == null || nowMillis < entry.updatedAt || nowMillis - entry.updatedAt >= ttlMillis

    fun completeRefresh(
        packageName: String,
        expected: Entry?,
        queriedDayStart: Long,
        currentDayStart: Long,
        nowMillis: Long,
        counts: Map<String, Int>,
    ): Map<String, Int>? {
        // 跨午夜的旧查询不能覆盖新一天；同日查询也不能覆盖其间确认的本地递增。
        if (queriedDayStart != currentDayStart || get(packageName, currentDayStart) !== expected) {
            return null
        }
        entries[packageName] = Entry(currentDayStart, nowMillis, counts)
        return counts
    }

    fun recordUse(
        packageName: String,
        normalizedReason: String,
        dayStartMillis: Long,
        nowMillis: Long,
    ): Map<String, Int> {
        val previous = get(packageName, dayStartMillis)?.counts.orEmpty()
        val updated = previous + (normalizedReason to ((previous[normalizedReason] ?: 0) + 1))
        entries[packageName] = Entry(dayStartMillis, nowMillis, updated)
        return updated
    }
}
