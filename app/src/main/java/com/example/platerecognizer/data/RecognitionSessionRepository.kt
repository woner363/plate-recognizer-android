package com.example.platerecognizer.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * RecognitionSession 的访问层，封装 [RecognitionSessionDao]。
 *
 * 暴露给 ViewModel 的是领域化的 API（创建、迁移状态、清理），不直接暴露 Entity。
 * 活跃 session 通过 [observeActive] 以 [ActiveSession] 值对象形式观察。
 */
class RecognitionSessionRepository(
    private val dao: RecognitionSessionDao,
) {

    fun observeActive(): Flow<ActiveSession?> =
        dao.observeActive().map { it?.toActive() }

    suspend fun createCapturing(imageUri: String): ActiveSession {
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

    suspend fun transition(id: String, state: SessionState, transform: (RecognitionSessionEntity) -> RecognitionSessionEntity = { it }) {
        val current = dao.getById(id) ?: return
        val updated = transform(current).copy(state = state, updatedAt = System.currentTimeMillis())
        dao.upsert(updated)
    }

    suspend fun setRecognized(id: String, candidate: String, qualityScore: Float, error: String?) {
        transition(id, SessionState.AWAITING_CONFIRMATION) {
            it.copy(candidate = candidate, qualityScore = qualityScore, error = error)
        }
    }

    suspend fun markAwaiting(id: String, error: String?) {
        transition(id, SessionState.AWAITING_CONFIRMATION) {
            it.copy(error = error)
        }
    }

    suspend fun markSaved(id: String) {
        transition(id, SessionState.SAVED)
    }

    suspend fun markDiscarded(id: String) {
        transition(id, SessionState.DISCARDED)
    }

    suspend fun markFailed(id: String, error: String?) {
        transition(id, SessionState.FAILED) { it.copy(error = error) }
    }

    suspend fun delete(id: String) = dao.deleteById(id)

    /** 非终态 session 引用的图片 URI（孤儿清理保留）。 */
    suspend fun listActiveImageUris(): List<String> = dao.listActiveImageUris()

    private fun RecognitionSessionEntity.toActive(): ActiveSession = ActiveSession(
        id = id,
        state = state,
        candidate = candidate,
        qualityScore = qualityScore,
        imageUri = imageUri,
        error = error,
        createdAt = createdAt,
    )

    /**
     * 简易 id 生成：时间戳 + 计数器。不用 UUID 是为了保持依赖最小；
     * 单进程内时间戳精度足够区分，冲突时 upsert(REPLACE) 会覆盖旧记录（可接受）。
     */
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
