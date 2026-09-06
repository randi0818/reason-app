package me.excuse.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        UsageSession::class,
        MonitoredApp::class,
        AppCategoryOverride::class,
        InterceptEvent::class,
    ],
    version = 3,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun usageSessions(): UsageSessionDao
    abstract fun monitoredApps(): MonitoredAppDao
    abstract fun appCategories(): AppCategoryDao
    abstract fun interceptEvents(): InterceptEventDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `app_category` " +
                        "(`packageName` TEXT NOT NULL, `category` TEXT NOT NULL, " +
                        "PRIMARY KEY(`packageName`))"
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `intercept_event` " +
                        "(`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`packageName` TEXT NOT NULL, `appName` TEXT NOT NULL, " +
                        "`outcome` TEXT NOT NULL, `at` INTEGER NOT NULL, `sessionId` INTEGER)"
                )
            }
        }

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "excuse.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { instance = it }
            }
    }
}
