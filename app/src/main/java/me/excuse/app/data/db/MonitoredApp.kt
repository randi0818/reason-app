package me.excuse.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "monitored_app")
data class MonitoredApp(
    @PrimaryKey val packageName: String,
    val appName: String,
    val enabled: Boolean = true,
    val addedAt: Long
)
