package me.excuse.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface InterceptEventDao {
    @Insert
    suspend fun insert(event: InterceptEvent): Long

    @Query("""
        SELECT * FROM intercept_event
        WHERE `at` >= COALESCE(:afterTime, -9223372036854775808)
          AND (:afterTime IS NULL OR `at` > :afterTime OR id > :afterId)
        ORDER BY `at`, id LIMIT :limit
    """)
    suspend fun exportPage(afterTime: Long?, afterId: Long, limit: Int): List<InterceptEvent>

    // STARTED 跟着它的会话删；跨边界或未结束的会话必须连同结果一起保留。
    @Query("""
        SELECT COUNT(*) FROM intercept_event
        WHERE (sessionId IS NULL AND `at` < :before)
           OR sessionId IN (SELECT id FROM usage_session WHERE startTime < :before AND endTime < :before)
    """)
    suspend fun cleanupCount(before: Long): Int

    @Query("""
        DELETE FROM intercept_event
        WHERE (sessionId IS NULL AND `at` < :before)
           OR sessionId IN (SELECT id FROM usage_session WHERE startTime < :before AND endTime < :before)
    """)
    suspend fun deleteBefore(before: Long): Int

    @Query("""
        SELECT COUNT(*) FROM intercept_event
        WHERE `at` >= :sinceMillis AND `at` < :untilMillis
    """)
    fun countBetween(sinceMillis: Long, untilMillis: Long): Flow<Int>

    @Query("""
        SELECT COUNT(*) FROM intercept_event
        WHERE outcome IN ('ABANDONED', 'TIMEOUT')
          AND `at` >= :sinceMillis AND `at` < :untilMillis
    """)
    fun abandonedCountBetween(sinceMillis: Long, untilMillis: Long): Flow<Int>
}
