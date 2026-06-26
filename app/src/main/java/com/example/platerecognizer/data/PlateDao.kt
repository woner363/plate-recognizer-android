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
}
