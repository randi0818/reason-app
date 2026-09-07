package me.excuse.app.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "intercept_event", indices = [Index("at")])
data class InterceptEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val appName: String,
    val outcome: String,
    val at: Long,
    val sessionId: Long? = null,
)
