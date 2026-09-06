package me.excuse.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MonitoredAppDao {
    @Query("SELECT * FROM monitored_app ORDER BY addedAt DESC")
    fun all(): Flow<List<MonitoredApp>>

    @Query("SELECT * FROM monitored_app ORDER BY addedAt")
    suspend fun allOnce(): List<MonitoredApp>

    @Query("SELECT packageName FROM monitored_app WHERE enabled = 1")
    suspend fun enabledPackages(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(app: MonitoredApp)

    @Query("DELETE FROM monitored_app WHERE packageName = :pkg")
    suspend fun delete(pkg: String)
}
