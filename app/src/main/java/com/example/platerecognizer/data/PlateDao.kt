package com.example.platerecognizer.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
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

    /** 列出所有非终态 session，供启动恢复扫描（§4.5 单活跃修复）。 */
    @Query(
        """
        SELECT * FROM recognition_sessions
        WHERE state NOT IN ('SAVED', 'DISCARDED')
        ORDER BY updated_at ASC
        """,
    )
    suspend fun listAllNonTerminal(): List<RecognitionSessionEntity>

    /**
     * §4.4：带 expected state 的原子状态迁移。返回受影响行数：
     * - 1 = 迁移成功；
     * - 0 = session 不存在或当前状态不匹配（冲突 / 已被并发改动）。
     */
    @Query(
        """
        UPDATE recognition_sessions
        SET state = :nextState, updated_at = :updatedAt
        WHERE id = :id AND state = :expectedState
        """,
    )
    suspend fun transitionIf(
        id: String,
        expectedState: SessionState,
        nextState: SessionState,
        updatedAt: Long,
    ): Int

    /**
     * §4.4：带 expected state 的原子状态迁移 + 字段更新（candidate/quality/error）。
     * 用于 setRecognized 等。
     */
    @Query(
        """
        UPDATE recognition_sessions
        SET state = :nextState,
            candidate = :candidate,
            quality_score = :qualityScore,
            error = :error,
            updated_at = :updatedAt
        WHERE id = :id AND state = :expectedState
        """,
    )
    suspend fun setRecognizedIf(
        id: String,
        expectedState: SessionState,
        nextState: SessionState,
        candidate: String?,
        qualityScore: Float?,
        error: String?,
        updatedAt: Long,
    ): Int

    /**
     * §4.4：SAVING → AWAITING_CONFIRMATION 回退，只更新 state + error，
     * 保留 candidate/qualityScore（保存失败时用户重试不应丢失识别结果）。
     */
    @Query(
        """
        UPDATE recognition_sessions
        SET state = :nextState,
            error = :error,
            updated_at = :updatedAt
        WHERE id = :id AND state = :expectedState
        """,
    )
    suspend fun revertToAwaitingIf(
        id: String,
        expectedState: SessionState,
        nextState: SessionState,
        error: String?,
        updatedAt: Long,
    ): Int

    /**
     * §4.5：创建新 session 前，把所有现存非终态 session 标记为 DISCARDED，
     * 保证任意时刻最多一个活跃 session。事务保证原子性。
     */
    @Transaction
    suspend fun createCapturingClosingOthers(entity: RecognitionSessionEntity) {
        val now = entity.updatedAt
        for (s in listAllNonTerminal()) {
            transitionIf(s.id, s.state, SessionState.DISCARDED, now)
        }
        upsert(entity)
    }
}
