package me.excuse.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "usage_session")
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
