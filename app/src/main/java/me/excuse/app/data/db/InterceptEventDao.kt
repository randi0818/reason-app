package me.excuse.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface InterceptEventDao {
    @Insert
    suspend fun insert(event: InterceptEvent): Long

    @Query("SELECT * FROM intercept_event ORDER BY `at`")
    suspend fun allOnce(): List<InterceptEvent>

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
