package com.example.platerecognizer.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [PlateRecord::class, RecognitionSessionEntity::class],
    version = 3,
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
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build().also { instance = it }
            }

        /** §4.5：v1 → v2 新增 recognition_sessions 表。 */
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

        /**
         * §4.3：v2 → v3 给 plates 表加 source_session_id 列 + 唯一索引。
         * 用于幂等保存：进程在 SAVING 中断后恢复时判断 session 是否已入库。
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE plates ADD COLUMN source_session_id TEXT")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_plates_source_session_id` ON `plates` (`source_session_id`)",
                )
            }
        }
    }
}
