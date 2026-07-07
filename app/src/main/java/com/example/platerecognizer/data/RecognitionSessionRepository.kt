package com.example.platerecognizer.data

import com.example.platerecognizer.domain.RecognitionSessions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * RecognitionSession 的访问层，封装 [RecognitionSessionDao]。
 *
 * §4.4：所有状态迁移用 expected-state 原子 SQL，返回 Boolean；
 * §4.5：createCapturing 在事务里把现存非终态 session 标记为 DISCARDED，
 * 保证任意时刻最多一个活跃 session；
 * id 用 UUID 避免进程重启后的碰撞。
 */
class RecognitionSessionRepository(
    private val dao: RecognitionSessionDao,
) : RecognitionSessions {

    override fun observeActive(): Flow<ActiveSession?> =
        dao.observeActive().map { it?.toActive() }

    override suspend fun createCapturing(imageUri: String): ActiveSession {
        val now = System.currentTimeMillis()
        val entity = RecognitionSessionEntity(
            id = UUID.randomUUID().toString(),
            state = SessionState.CAPTURING,
            candidate = null,
            qualityScore = null,
            imageUri = imageUri,
            error = null,
            createdAt = now,
            updatedAt = now,
        )
        dao.createCapturingClosingOthers(entity)
        return entity.toActive()
    }

    /** CAPTURING → RECOGNIZING。 */
    override suspend fun beginRecognizing(id: String): Boolean = dao.transitionIf(
        id,
        expectedState = SessionState.CAPTURING,
        nextState = SessionState.RECOGNIZING,
        updatedAt = now(),
    ) == 1

    override suspend fun setRecognized(
        id: String,
        candidate: String,
        qualityScore: Float,
        error: String?,
    ): Boolean = dao.setRecognizedIf(
        id,
        expectedState = SessionState.RECOGNIZING,
        nextState = SessionState.AWAITING_CONFIRMATION,
        candidate = candidate,
        qualityScore = qualityScore,
        error = error,
        updatedAt = now(),
    ) == 1

    /** AWAITING_CONFIRMATION → SAVING。保存入口的 CAS。 */
    override suspend fun beginSaving(id: String): Boolean = dao.transitionIf(
        id,
        expectedState = SessionState.AWAITING_CONFIRMATION,
        nextState = SessionState.SAVING,
        updatedAt = now(),
    ) == 1

    /** SAVING → AWAITING_CONFIRMATION（保存失败回退），保留 candidate/qualityScore。 */
    override suspend fun revertToAwaiting(id: String, error: String?): Boolean = dao.revertToAwaitingIf(
        id,
        expectedState = SessionState.SAVING,
        nextState = SessionState.AWAITING_CONFIRMATION,
        error = error,
        updatedAt = now(),
    ) == 1

    override suspend fun markSaved(id: String): Boolean = dao.transitionIf(
        id,
        expectedState = SessionState.SAVING,
        nextState = SessionState.SAVED,
        updatedAt = now(),
    ) == 1

    /** AWAITING_CONFIRMATION → DISCARDING。放弃入口的 CAS。 */
    override suspend fun beginDiscarding(id: String): Boolean = dao.transitionIf(
        id,
        expectedState = SessionState.AWAITING_CONFIRMATION,
        nextState = SessionState.DISCARDING,
        updatedAt = now(),
    ) == 1

    override suspend fun markDiscarded(id: String): Boolean = dao.transitionIf(
        id,
        expectedState = SessionState.DISCARDING,
        nextState = SessionState.DISCARDED,
        updatedAt = now(),
    ) == 1

    override suspend fun markFailed(id: String, error: String?): Boolean {
        // 任意非终态 → FAILED；用逐个尝试常见 expected state 的方式（简单但够用）
        for (expected in listOf(
            SessionState.CAPTURING,
            SessionState.RECOGNIZING,
            SessionState.AWAITING_CONFIRMATION,
            SessionState.SAVING,
            SessionState.DISCARDING,
        )) {
            if (dao.transitionIf(id, expected, SessionState.FAILED, now()) == 1) return true
        }
        return false
    }

    override suspend fun delete(id: String) = dao.deleteById(id)

    override suspend fun listActiveImageUris(): List<String> = dao.listActiveImageUris()

    override suspend fun listAllNonTerminal(): List<ActiveSession> =
        dao.listAllNonTerminal().map { it.toActive() }

    override suspend fun snapshotActive(): ActiveSession? =
        dao.observeActive().firstOrNull()?.toActive()

    private fun now(): Long = System.currentTimeMillis()

    private fun RecognitionSessionEntity.toActive(): ActiveSession = ActiveSession(
        id = id,
        state = state,
        candidate = candidate,
        qualityScore = qualityScore,
        imageUri = imageUri,
        error = error,
        createdAt = createdAt,
    )
}

/** ViewModel 使用的活跃 session 值对象（脱离 Room Entity）。 */
data class ActiveSession(
    val id: String,
    val state: SessionState,
    val candidate: String?,
    val qualityScore: Float?,
    val imageUri: String,
    val error: String?,
    val createdAt: Long,
) {
    val isAwaitingConfirmation: Boolean get() = state == SessionState.AWAITING_CONFIRMATION
}
