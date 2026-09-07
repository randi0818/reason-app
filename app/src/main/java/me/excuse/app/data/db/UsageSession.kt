package me.excuse.app.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "usage_session",
    indices = [Index("startTime"), Index("endTime"), Index(value = ["packageName", "startTime"])],
)
data class UsageSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val appName: String,
    val reason: String,
    val reasonNormalized: String,
    val plannedMinutes: Int,
    val startTime: Long,
    val endTime: Long? = null,
    val overran: Boolean = false
)
