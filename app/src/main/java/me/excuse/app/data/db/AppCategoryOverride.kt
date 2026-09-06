package me.excuse.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "app_category")
data class AppCategoryOverride(
    @PrimaryKey val packageName: String,
    val category: String
)
