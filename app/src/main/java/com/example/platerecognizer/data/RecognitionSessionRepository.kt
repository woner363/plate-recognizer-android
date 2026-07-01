package com.example.platerecognizer.data

import com.example.platerecognizer.domain.RecognitionSessions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * RecognitionSession 的访问层，封装 [RecognitionSessionDao]。
 *
 * 暴露给 ViewModel 的是领域化的 API（创建、迁移状态、清理），不直接暴露 Entity。
 * 活跃 session 通过 [observeActive] 以 [ActiveSession] 值对象形式观察。
 *
 * 实现 [RecognitionSessions] 接口（§4.8），便于注入 fake 测试。
 */
class RecognitionSessionRepository(
    private val dao: RecognitionSessionDao,
) : RecognitionSessions {

    override fun observeActive(): Flow<ActiveSession?> =
        dao.observeActive().map { it?.toActive() }

    override suspend fun createCapturing(imageUri: String): ActiveSession {
        val now = System.currentTimeMillis()
        val entity = RecognitionSessionEntity(
            id = generateId(),
            state = SessionState.CAPTURING,
            candidate = null,
            qualityScore = null,
            imageUri = imageUri,
            error = null,
            createdAt = now,
            updatedAt = now,
        )
        dao.upsert(entity)
        return entity.toActive()
    }

    override suspend fun transition(id: String, state: SessionState, transform: (RecognitionSessionEntity) -> RecognitionSessionEntity) {
        val current = dao.getById(id) ?: return
        val updated = transform(current).copy(state = state, updatedAt = System.currentTimeMillis())
        dao.upsert(updated)
    }

    override suspend fun setRecognized(id: String, candidate: String, qualityScore: Float, error: String?) {
        transition(id, SessionState.AWAITING_CONFIRMATION) {
            it.copy(candidate = candidate, qualityScore = qualityScore, error = error)
        }
    }

    override suspend fun markAwaiting(id: String, error: String?) {
        transition(id, SessionState.AWAITING_CONFIRMATION) {
            it.copy(error = error)
        }
    }

    override suspend fun markSaved(id: String) {
        transition(id, SessionState.SAVED)
    }

    override suspend fun markDiscarded(id: String) {
        transition(id, SessionState.DISCARDED)
    }

    override suspend fun markFailed(id: String, error: String?) {
        transition(id, SessionState.FAILED) { it.copy(error = error) }
    }

    override suspend fun delete(id: String) = dao.deleteById(id)

    /** 非终态 session 引用的图片 URI（孤儿清理保留）。 */
    override suspend fun listActiveImageUris(): List<String> = dao.listActiveImageUris()

    private fun RecognitionSessionEntity.toActive(): ActiveSession = ActiveSession(
        id = id,
        state = state,
        candidate = candidate,
        qualityScore = qualityScore,
        imageUri = imageUri,
        error = error,
        createdAt = createdAt,
    )

    @Synchronized
    private fun generateId(): String {
        counter = (counter + 1) and 0xFFFF
        return "${System.currentTimeMillis()}-${counter.toString(16)}"
    }

    private var counter = 0

    companion object
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
    /** UI 判断是否处于人工确认阶段。 */
    val isAwaitingConfirmation: Boolean get() = state == SessionState.AWAITING_CONFIRMATION
}
