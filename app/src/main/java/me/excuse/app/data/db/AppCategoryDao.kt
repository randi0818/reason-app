package me.excuse.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AppCategoryDao {
    @Query("SELECT * FROM app_category ORDER BY packageName")
    fun all(): Flow<List<AppCategoryOverride>>

    @Query("SELECT * FROM app_category ORDER BY packageName")
    suspend fun allOnce(): List<AppCategoryOverride>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(override: AppCategoryOverride)

    @Query("DELETE FROM app_category WHERE packageName = :packageName")
    suspend fun delete(packageName: String)
}
