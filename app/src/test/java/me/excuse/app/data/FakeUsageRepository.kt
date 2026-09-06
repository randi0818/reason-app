package me.excuse.app.data

import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import me.excuse.app.category.AppCategory
import me.excuse.app.data.db.MonitoredApp
import me.excuse.app.data.db.UsageSession
import me.excuse.app.service.InterceptOutcome

/** 测试按需覆盖行为；默认值让纯 ViewModel/SessionStore 测试不必依赖 Room。 */
open class FakeUsageRepository : UsageRepositoryContract {
    override fun monitoredApps(): Flow<List<MonitoredApp>> = flowOf(emptyList())
    override fun appCategoryOverrides(): Flow<Map<String, AppCategory>> = flowOf(emptyMap())
    override suspend fun setAppCategory(packageName: String, category: AppCategory?) = Unit
    override suspend fun enabledPackages(): Set<String> = emptySet()
    override suspend fun usageExportSnapshot() = UsageExportSnapshot(
        sessions = emptyList(),
        events = emptyList(),
        monitoredApps = emptyList(),
        categoryOverrides = emptyList(),
    )
    override suspend fun setMonitored(packageName: String, appName: String, enabled: Boolean) = Unit
    override suspend fun reasonUseCountsTodayFor(packageName: String): Map<String, Int> = emptyMap()
    override suspend fun reasonUseCountToday(packageName: String, normalized: String) = 0
    override suspend fun startSession(
        packageName: String,
        appName: String,
        reason: String,
        plannedMinutes: Int,
    ): Long = 0L

    override suspend fun recordInterceptOutcome(
        packageName: String,
        appName: String,
        outcome: InterceptOutcome,
        at: Long,
    ) = Unit

    override suspend fun finishSession(id: Long, endedAt: Long, overran: Boolean) = Unit
    override suspend fun finishUnfinishedSessions(nowMillis: Long) = Unit
    override suspend fun extendSession(id: Long, plannedMinutes: Int) = Unit
    override fun sessionsToday(): Flow<List<UsageSession>> = flowOf(emptyList())
    override fun entryCountToday(): Flow<Int> = flowOf(0)
    override fun overrunCountToday(): Flow<Int> = flowOf(0)
    override fun sessionsForDay(date: LocalDate): Flow<List<UsageSession>> = flowOf(emptyList())
    override fun entryCountForDay(date: LocalDate): Flow<Int> = flowOf(0)
    override fun overrunCountForDay(date: LocalDate): Flow<Int> = flowOf(0)
    override fun promptCountForDay(date: LocalDate): Flow<Int> = flowOf(0)
    override fun abandonedCountForDay(date: LocalDate): Flow<Int> = flowOf(0)
    override fun sessionsForRollingDays(n: Int): Flow<List<UsageSession>> = flowOf(emptyList())
    override fun entryCountRollingDays(n: Int): Flow<Int> = flowOf(0)
    override fun overrunCountRollingDays(n: Int): Flow<Int> = flowOf(0)
    override fun promptCountRollingDays(n: Int): Flow<Int> = flowOf(0)
    override fun abandonedCountRollingDays(n: Int): Flow<Int> = flowOf(0)
    override fun reasonWallForRollingDays(n: Int): Flow<List<ReasonWallItem>> = flowOf(emptyList())
}
