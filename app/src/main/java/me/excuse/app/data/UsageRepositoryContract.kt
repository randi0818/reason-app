package me.excuse.app.data

import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import me.excuse.app.category.AppCategory
import me.excuse.app.data.db.MonitoredApp
import me.excuse.app.data.db.UsageSession
import me.excuse.app.service.InterceptOutcome

interface UsageRepositoryContract {
    fun monitoredApps(): Flow<List<MonitoredApp>>
    fun appCategoryOverrides(): Flow<Map<String, AppCategory>>
    suspend fun setAppCategory(packageName: String, category: AppCategory?)
    suspend fun enabledPackages(): Set<String>
    suspend fun setMonitored(packageName: String, appName: String, enabled: Boolean)
    suspend fun reasonUseCountsTodayFor(packageName: String): Map<String, Int>
    suspend fun reasonUseCountToday(packageName: String, normalized: String): Int

    suspend fun startSession(
        packageName: String,
        appName: String,
        reason: String,
        plannedMinutes: Int,
    ): Long

    suspend fun recordInterceptOutcome(
        packageName: String,
        appName: String,
        outcome: InterceptOutcome,
        at: Long = System.currentTimeMillis(),
    )

    suspend fun finishSession(id: Long, endedAt: Long, overran: Boolean)
    suspend fun finishUnfinishedSessions(nowMillis: Long = System.currentTimeMillis())
    /** 以绝对目标值持久化，重试同一次延期不会重复累加。 */
    suspend fun extendSession(id: Long, plannedMinutes: Int)

    fun sessionsToday(): Flow<List<UsageSession>>
    fun entryCountToday(): Flow<Int>
    fun overrunCountToday(): Flow<Int>
    fun sessionsForDay(date: LocalDate): Flow<List<UsageSession>>
    fun entryCountForDay(date: LocalDate): Flow<Int>
    fun overrunCountForDay(date: LocalDate): Flow<Int>
    fun promptCountForDay(date: LocalDate): Flow<Int>
    fun abandonedCountForDay(date: LocalDate): Flow<Int>
    fun sessionsForRollingDays(n: Int): Flow<List<UsageSession>>
    fun entryCountRollingDays(n: Int): Flow<Int>
    fun overrunCountRollingDays(n: Int): Flow<Int>
    fun promptCountRollingDays(n: Int): Flow<Int>
    fun abandonedCountRollingDays(n: Int): Flow<Int>
    fun reasonWallForRollingDays(n: Int): Flow<List<ReasonWallItem>>

    /** 四张表在同一个数据库快照中读取，避免后台服务恰好写入时导出前后对不上。 */
    suspend fun usageExportSnapshot(): UsageExportSnapshot
}
