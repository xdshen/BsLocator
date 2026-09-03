package com.example.bslocator.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Measurement::class, MeasurementSession::class],
    version = 3,
    exportSchema = false
)
abstract class MeasurementDatabase : RoomDatabase() {
    abstract fun measurementDao(): MeasurementDao
    abstract fun sessionDao(): MeasurementSessionDao

    companion object {
        @Volatile
        private var INSTANCE: MeasurementDatabase? = null

        /** Migration from v1 → v2: add `eci` column (default -1). */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE measurements ADD COLUMN eci INTEGER NOT NULL DEFAULT -1")
            }
        }

        /** Migration from v2 → v3: add `measurement_sessions` table and `session_id` column. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Create measurement_sessions table
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS measurement_sessions (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL DEFAULT '',
                        start_time INTEGER NOT NULL DEFAULT 0,
                        end_time INTEGER NOT NULL DEFAULT -1,
                        notes TEXT NOT NULL DEFAULT '',
                        is_active INTEGER NOT NULL DEFAULT 1
                    )
                    """.trimIndent()
                )
                // Add session_id column to measurements (default 0 = legacy data)
                db.execSQL("ALTER TABLE measurements ADD COLUMN session_id INTEGER NOT NULL DEFAULT 0")
                // Create a default session for existing data
                db.execSQL(
                    """
                    INSERT INTO measurement_sessions (name, start_time, end_time, notes, is_active)
                    VALUES ('历史数据', 0, -1, '升级前导入的历史数据', 0)
                    """.trimIndent()
                )
                // Note: we leave existing measurements with session_id=0 rather than
                // updating them, so the user can still see legacy data.
            }
        }

        fun getDatabase(context: Context): MeasurementDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    MeasurementDatabase::class.java,
                    "bslocator_database"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
