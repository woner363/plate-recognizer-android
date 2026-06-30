package com.example.platerecognizer.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PlateDao {

    @Query("SELECT * FROM plates ORDER BY captured_at DESC")
    fun observeAll(): Flow<List<PlateRecord>>

    @Query("SELECT * FROM plates ORDER BY captured_at DESC")
    suspend fun listAll(): List<PlateRecord>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(record: PlateRecord): Long

    @Update
    suspend fun update(record: PlateRecord)

    @Delete
    suspend fun delete(record: PlateRecord)

    @Query("DELETE FROM plates WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** 列出所有非空 image_uri，供启动时孤儿文件扫描使用。 */
    @Query("SELECT image_uri FROM plates WHERE image_uri IS NOT NULL")
    suspend fun listAllImageUris(): List<String>
}

@Dao
interface RecognitionSessionDao {

    /** 观察唯一的活跃（非终态）session；UI 据此渲染确认对话框。 */
    @Query(
        """
        SELECT * FROM recognition_sessions
        WHERE state NOT IN ('SAVED', 'DISCARDED')
        ORDER BY updated_at DESC
        LIMIT 1
        """,
    )
    fun observeActive(): Flow<RecognitionSessionEntity?>

    @Query("SELECT * FROM recognition_sessions WHERE id = :id")
    suspend fun getById(id: String): RecognitionSessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: RecognitionSessionEntity)

    @Query("DELETE FROM recognition_sessions WHERE id = :id")
    suspend fun deleteById(id: String)

    /** 列出非终态 session 引用的图片 URI，供孤儿清理保留。 */
    @Query(
        """
        SELECT image_uri FROM recognition_sessions
        WHERE state NOT IN ('SAVED', 'DISCARDED')
        """,
    )
    suspend fun listActiveImageUris(): List<String>
}
