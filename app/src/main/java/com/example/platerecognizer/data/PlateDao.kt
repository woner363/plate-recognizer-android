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
