package com.example.platerecognizer.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [PlateRecord::class, RecognitionSessionEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun plateDao(): PlateDao
    abstract fun recognitionSessionDao(): RecognitionSessionDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "plates.db",
                )
                    .addMigrations(MIGRATION_1_2)
                    .build().also { instance = it }
            }

        /**
         * §4.5：v1 → v2 新增 recognition_sessions 表。
         * 旧 plates 表无结构变化，仅 CREATE 新表。
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `recognition_sessions` (
                        `id` TEXT NOT NULL,
                        `state` TEXT NOT NULL,
                        `candidate` TEXT,
                        `quality_score` REAL,
                        `image_uri` TEXT NOT NULL,
                        `error` TEXT,
                        `created_at` INTEGER NOT NULL,
                        `updated_at` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
            }
        }
    }
}
