package me.excuse.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface UsageSessionDao {
    @Insert
    suspend fun insert(session: UsageSession): Long

    @Query("SELECT * FROM usage_session ORDER BY startTime")
    suspend fun allOnce(): List<UsageSession>

    @Query("""
        UPDATE usage_session
        SET endTime = :endTime,
            overran = CASE WHEN overran = 1 OR :overran = 1 THEN 1 ELSE 0 END
        WHERE id = :id AND endTime IS NULL
    """)
    suspend fun finish(id: Long, endTime: Long, overran: Boolean): Int

    @Query("SELECT * FROM usage_session WHERE endTime IS NULL ORDER BY startTime")
    suspend fun unfinishedOnce(): List<UsageSession>

    @Query("""
        UPDATE usage_session
        SET plannedMinutes = MAX(plannedMinutes, :plannedMinutes),
            overran = 1
        WHERE id = :id AND endTime IS NULL
    """)
    suspend fun extendTo(id: Long, plannedMinutes: Int): Int

    @Query("""
        SELECT reasonNormalized FROM usage_session
        WHERE packageName = :pkg AND startTime >= :sinceMillis
    """)
    suspend fun reasonsForAppSince(pkg: String, sinceMillis: Long): List<String>

    @Query("""
        SELECT COUNT(*) FROM usage_session
        WHERE packageName = :pkg
          AND reasonNormalized = :reasonNormalized
          AND startTime >= :sinceMillis
    """)
    suspend fun reasonUseCount(
        pkg: String,
        reasonNormalized: String,
        sinceMillis: Long
    ): Int

    /**
     * 任何与 [sinceMillis, untilMillis) 区间有 overlap 的 session 都会被返回。
     * 跨日 session（startTime 在区间外但 endTime 在区间内、或反之）能查到，
     * 由调用方负责把展示时间 clip 到区间边界。
     *
     * 注：entryCount / overrunCount 仍按 startTime 切日（"决策发生在哪天"），
     *     和这条查询语义不同，不要互相替换。
     */
    @Query("""
        SELECT * FROM usage_session
        WHERE startTime < :untilMillis
          AND (endTime IS NULL OR endTime >= :sinceMillis)
        ORDER BY startTime DESC
    """)
    fun sessionsBetween(sinceMillis: Long, untilMillis: Long): Flow<List<UsageSession>>

    @Query("""
        SELECT COUNT(*) FROM usage_session
        WHERE startTime >= :sinceMillis AND startTime < :untilMillis
    """)
    fun countBetween(sinceMillis: Long, untilMillis: Long): Flow<Int>

    @Query("""
        SELECT COUNT(*) FROM usage_session
        WHERE overran = 1 AND startTime >= :sinceMillis AND startTime < :untilMillis
    """)
    fun overrunCountBetween(sinceMillis: Long, untilMillis: Long): Flow<Int>

    @Query("""
        SELECT reason, reasonNormalized, appName, startTime
        FROM usage_session
        WHERE startTime >= :sinceMillis AND startTime < :untilMillis
          AND reasonNormalized != ''
        ORDER BY startTime DESC
    """)
    fun reasonOccurrencesBetween(
        sinceMillis: Long,
        untilMillis: Long,
    ): Flow<List<ReasonOccurrence>>
}
