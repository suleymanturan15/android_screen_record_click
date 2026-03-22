package com.timemacro.scheduler.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.timemacro.scheduler.data.db.dao.LogDao
import com.timemacro.scheduler.data.db.dao.MacroLogDao
import com.timemacro.scheduler.data.db.dao.MacroDao
import com.timemacro.scheduler.data.db.dao.TaskDao
import com.timemacro.scheduler.data.db.entities.LogEntity
import com.timemacro.scheduler.data.db.entities.MacroLogEntity
import com.timemacro.scheduler.data.db.entities.MacroEntity
import com.timemacro.scheduler.data.db.entities.TaskEntity

@Database(
    entities = [
        MacroEntity::class,
        TaskEntity::class,
        LogEntity::class,
        MacroLogEntity::class,
    ],
    version = 5,
    exportSchema = false,
)
abstract class TimeMacroDatabase : RoomDatabase() {
    abstract fun macroDao(): MacroDao
    abstract fun taskDao(): TaskDao
    abstract fun logDao(): LogDao
    abstract fun macroLogDao(): MacroLogDao

    companion object {
        val MIGRATION_1_2: Migration =
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE tasks ADD COLUMN initialDelayMinutes INTEGER NOT NULL DEFAULT 2")
                    db.execSQL("ALTER TABLE tasks ADD COLUMN planEndsAt INTEGER")
                    db.execSQL("ALTER TABLE logs ADD COLUMN source TEXT")
                }
            }

        val MIGRATION_2_3: Migration =
            object : Migration(2, 3) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE tasks ADD COLUMN nextScheduledAt INTEGER")
                    db.execSQL("ALTER TABLE tasks ADD COLUMN lastScheduledAt INTEGER")
                    db.execSQL("ALTER TABLE tasks ADD COLUMN lastRunAt INTEGER")
                }
            }

        val MIGRATION_3_4: Migration =
            object : Migration(3, 4) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    // Macro enable/disable.
                    db.execSQL("ALTER TABLE macros ADD COLUMN is_enabled INTEGER NOT NULL DEFAULT 1")

                    // New macro logs table.
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS macro_logs (
                            id TEXT NOT NULL PRIMARY KEY,
                            timestampMs INTEGER NOT NULL,
                            macroId TEXT,
                            macroNameSnapshot TEXT NOT NULL,
                            source TEXT NOT NULL,
                            status TEXT NOT NULL,
                            message TEXT,
                            durationMs INTEGER,
                            steps INTEGER
                        )
                        """.trimIndent(),
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_macro_logs_timestampMs ON macro_logs(timestampMs)")
                }
            }

        val MIGRATION_4_5: Migration =
            object : Migration(4, 5) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    // New task scheduling fields:
                    // - runsPerDay (replaces old dailyHoursToRun meaning in UI)
                    // - monthlyDay / yearlyMonth / yearlyDay for MONTHLY/YEARLY plan types
                    db.execSQL("ALTER TABLE tasks ADD COLUMN runsPerDay INTEGER NOT NULL DEFAULT 20")
                    db.execSQL("ALTER TABLE tasks ADD COLUMN monthlyDay INTEGER")
                    db.execSQL("ALTER TABLE tasks ADD COLUMN yearlyMonth INTEGER")
                    db.execSQL("ALTER TABLE tasks ADD COLUMN yearlyDay INTEGER")

                    // Backfill runsPerDay from legacy dailyHoursToRun.
                    // (Legacy default was also 20, so this keeps existing behavior unless user changes it.)
                    db.execSQL("UPDATE tasks SET runsPerDay = dailyHoursToRun")
                }
            }

        fun build(context: Context): TimeMacroDatabase =
            Room.databaseBuilder(context, TimeMacroDatabase::class.java, "timemacro.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .build()
    }
}

