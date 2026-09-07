package me.excuse.app.data

import androidx.room.withTransaction
import java.time.LocalDate
import kotlinx.coroutines.flow.map
import me.excuse.app.category.AppCategory
import me.excuse.app.data.db.AppCategoryOverride
import me.excuse.app.data.db.AppDatabase
import me.excuse.app.data.db.InterceptEvent
import me.excuse.app.data.db.MonitoredApp
import me.excuse.app.data.db.UsageSession
import me.excuse.app.service.InterceptOutcome
import me.excuse.app.util.ReasonNormalizer
import me.excuse.app.util.TimeUtil

class UsageRepository(private val db: AppDatabase) : UsageRepositoryContract {

    override fun monitoredApps() = db.monitoredApps().all()

    override fun appCategoryOverrides() = db.appCategories().all().map { overrides ->
        overrides.mapNotNull { override ->
            AppCategory.fromStorageKey(override.category)?.let { category ->
                override.packageName to category
            }
        }.toMap()
    }

    override suspend fun setAppCategory(packageName: String, category: AppCategory?) {
        if (category == null) {
            db.appCategories().delete(packageName)
        } else {
            db.appCategories().upsert(
                AppCategoryOverride(packageName, category.storageKey)
            )
        }
    }

    override suspend fun enabledPackages(): Set<String> =
        db.monitoredApps().enabledPackages().toSet()

    override suspend fun setMonitored(packageName: String, appName: String, enabled: Boolean) {
        if (enabled) {
            db.monitoredApps().upsert(
                MonitoredApp(
                    packageName = packageName,
                    appName = appName,
                    enabled = true,
                    addedAt = System.currentTimeMillis()
                )
            )
        } else {
            db.monitoredApps().delete(packageName)
        }
    }

    override suspend fun reasonUseCountsTodayFor(packageName: String): Map<String, Int> {
        return db.usageSessions()
            .reasonsForAppSince(packageName, TimeUtil.startOfTodayMillis())
            .groupingBy { it }
            .eachCount()
    }

    override suspend fun reasonUseCountToday(packageName: String, normalized: String): Int {
        if (normalized.isEmpty()) return 0
        return db.usageSessions().reasonUseCount(
            pkg = packageName,
            reasonNormalized = normalized,
            sinceMillis = TimeUtil.startOfTodayMillis()
        )
    }

    override suspend fun startSession(
        packageName: String,
        appName: String,
        reason: String,
        plannedMinutes: Int
    ): Long {
        val startedAt = System.currentTimeMillis()
        val session = UsageSession(
            packageName = packageName,
            appName = appName,
            reason = reason.trim(),
            reasonNormalized = ReasonNormalizer.normalize(reason),
            plannedMinutes = plannedMinutes,
            startTime = startedAt
        )
        return db.withTransaction {
            val sessionId = db.usageSessions().insert(session)
            db.interceptEvents().insert(
                InterceptEvent(
                    packageName = packageName,
                    appName = appName,
                    outcome = InterceptOutcome.STARTED.name,
                    at = startedAt,
                    sessionId = sessionId,
                )
            )
            sessionId
        }
    }

    override suspend fun recordInterceptOutcome(
        packageName: String,
        appName: String,
        outcome: InterceptOutcome,
        at: Long,
    ) {
        require(outcome != InterceptOutcome.STARTED) {
            "STARTED outcomes must be recorded atomically by startSession"
        }
        db.interceptEvents().insert(
            InterceptEvent(
                packageName = packageName,
                appName = appName,
                outcome = outcome.name,
                at = at,
            )
        )
    }

    override suspend fun finishSession(id: Long, endedAt: Long, overran: Boolean) {
        // 单条原子 UPDATE：与延期并发时不会把 plannedMinutes、endTime 或 overran 覆盖回旧值。
        db.usageSessions().finish(
            id = id,
            endTime = endedAt,
            overran = overran,
        )
    }

    override suspend fun finishUnfinishedSessions(nowMillis: Long) {
        db.withTransaction {
            db.usageSessions().unfinishedOnce().forEach { session ->
                // 进程消失之后没有继续使用的证据；拿不到准确退出点时，最多只兑现用户
                // 已确认的计划时长，不能把关机到下次启动的整段空白算进去。
                db.usageSessions().finish(
                    id = session.id,
                    endTime = recoveredSessionEndTime(session, nowMillis),
                    overran = session.overran,
                )
            }
        }
    }

    override suspend fun extendSession(id: Long, plannedMinutes: Int) {
        db.usageSessions().extendTo(id, plannedMinutes)
    }

    override fun sessionsToday() = sessionsForDay(TimeUtil.today())
    override fun entryCountToday() = entryCountForDay(TimeUtil.today())
    override fun overrunCountToday() = overrunCountForDay(TimeUtil.today())

    override fun sessionsForDay(date: LocalDate) = db.usageSessions()
        .sessionsBetween(TimeUtil.startOfDayMillis(date), TimeUtil.endOfDayMillis(date))

    override fun entryCountForDay(date: LocalDate) = db.usageSessions()
        .countBetween(TimeUtil.startOfDayMillis(date), TimeUtil.endOfDayMillis(date))

    override fun overrunCountForDay(date: LocalDate) = db.usageSessions()
        .overrunCountBetween(TimeUtil.startOfDayMillis(date), TimeUtil.endOfDayMillis(date))

    override fun promptCountForDay(date: LocalDate) = db.interceptEvents()
        .countBetween(TimeUtil.startOfDayMillis(date), TimeUtil.endOfDayMillis(date))

    override fun abandonedCountForDay(date: LocalDate) = db.interceptEvents()
        .abandonedCountBetween(TimeUtil.startOfDayMillis(date), TimeUtil.endOfDayMillis(date))

    /** 滚动 N 天（含今天）—— 起点为 today-(n-1) 的 00:00，终点为 today 的 23:59:59.999 */
    override fun sessionsForRollingDays(n: Int) = db.usageSessions().sessionsBetween(
        TimeUtil.startOfDayMillis(TimeUtil.today().minusDays((n - 1).toLong())),
        TimeUtil.endOfDayMillis(TimeUtil.today())
    )

    override fun entryCountRollingDays(n: Int) = db.usageSessions().countBetween(
        TimeUtil.startOfDayMillis(TimeUtil.today().minusDays((n - 1).toLong())),
        TimeUtil.endOfDayMillis(TimeUtil.today())
    )

    override fun overrunCountRollingDays(n: Int) = db.usageSessions().overrunCountBetween(
        TimeUtil.startOfDayMillis(TimeUtil.today().minusDays((n - 1).toLong())),
        TimeUtil.endOfDayMillis(TimeUtil.today())
    )

    override fun promptCountRollingDays(n: Int) = db.interceptEvents().countBetween(
        TimeUtil.startOfDayMillis(TimeUtil.today().minusDays((n - 1).toLong())),
        TimeUtil.endOfDayMillis(TimeUtil.today())
    )

    override fun abandonedCountRollingDays(n: Int) = db.interceptEvents().abandonedCountBetween(
        TimeUtil.startOfDayMillis(TimeUtil.today().minusDays((n - 1).toLong())),
        TimeUtil.endOfDayMillis(TimeUtil.today())
    )

    override suspend fun exportUsage(consumer: UsageExportConsumer): Int = db.withTransaction {
        val sessions = consumeExportPages(
            fetch = { time, id -> db.usageSessions().exportPage(time, id, 256) },
            timestamp = { it.startTime },
            id = { it.id },
            consume = consumer::session,
        )
        consumer.beginEvents()
        val events = consumeExportPages(
            fetch = { time, id -> db.interceptEvents().exportPage(time, id, 256) },
            timestamp = { it.at },
            id = { it.id },
            consume = consumer::event,
        )
        consumer.finish(db.monitoredApps().allOnce(), db.appCategories().allOnce())
        sessions + events
    }

    override suspend fun historyCountsBefore(beforeMillis: Long): HistoryRecordCounts = db.withTransaction {
        require(beforeMillis <= TimeUtil.startOfTodayMillis()) { "只能清理今天之前的记录" }
        cleanupCounts(beforeMillis)
    }

    override suspend fun clearHistoryBefore(
        beforeMillis: Long,
        expected: HistoryRecordCounts,
    ): HistoryRecordCounts? = db.withTransaction {
        require(beforeMillis <= TimeUtil.startOfTodayMillis()) { "只能清理今天之前的记录" }
        if (cleanupCounts(beforeMillis) != expected) return@withTransaction null
        // 先删除关联结果，否则删掉 session 后子查询就找不到该删的 STARTED 了。
        val events = db.interceptEvents().deleteBefore(beforeMillis)
        val sessions = db.usageSessions().deleteBefore(beforeMillis)
        HistoryRecordCounts(sessions, events)
    }

    private suspend fun cleanupCounts(beforeMillis: Long) = HistoryRecordCounts(
        db.usageSessions().cleanupCount(beforeMillis),
        db.interceptEvents().cleanupCount(beforeMillis),
    )

    override fun reasonWallForRollingDays(n: Int) = db.usageSessions()
        .reasonOccurrencesBetween(
            TimeUtil.startOfDayMillis(TimeUtil.today().minusDays((n - 1).toLong())),
            TimeUtil.endOfDayMillis(TimeUtil.today()),
        )
        .map(::aggregateReasonWall)
}

internal fun recoveredSessionEndTime(session: UsageSession, recoveredAt: Long): Long {
    val plannedDuration = session.plannedMinutes.coerceAtLeast(0).toLong() * 60_000L
    val plannedEnd = if (session.startTime > Long.MAX_VALUE - plannedDuration) {
        Long.MAX_VALUE
    } else {
        session.startTime + plannedDuration
    }
    return recoveredAt.coerceAtLeast(session.startTime).coerceAtMost(plannedEnd)
}
