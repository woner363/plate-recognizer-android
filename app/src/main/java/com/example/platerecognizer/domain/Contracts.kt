package com.example.platerecognizer.domain

import android.net.Uri
import com.example.platerecognizer.data.ActiveSession
import com.example.platerecognizer.data.PlateRecord
import com.example.platerecognizer.data.SessionState
import kotlinx.coroutines.flow.Flow

/**
 * 业务层依赖的最小接口集合。ViewModel 不直接依赖具体实现，
 * 测试时可注入 fake 模拟"识别失败 / 保存失败 / 删除失败"等场景。
 *
 * §4.7：接口不暴露 Room Entity transform；状态迁移用 expected-state 语义，
 * 返回 Boolean 表示是否成功（false = 状态冲突 / session 不存在）。
 */

/** 正式车牌记录的读写。 */
interface PlateRecords {
    fun observeAll(): Flow<List<PlateRecord>>
    suspend fun add(plateNo: String, qualityScore: Float, imageUri: String?, note: String? = null): Long
    suspend fun applyCorrection(record: PlateRecord, newPlate: String, note: String?)
    suspend fun delete(record: PlateRecord)

    /**
     * §4.3：事务性确认——插入记录 + 标记 session SAVED，幂等。
     * 返回 PlateRecord id；session 状态冲突时抛异常。
     */
    suspend fun confirmSession(
        sessionId: String,
        plateNo: String,
        qualityScore: Float,
        imageUri: String?,
        note: String?,
    ): Long

    /** §4.3：按 sourceSessionId 查询是否已入库（启动恢复用）。 */
    suspend fun findBySourceSessionId(sessionId: String): PlateRecord?
}

/** 车牌 OCR 引擎。 */
interface RecognitionEngine {
    suspend fun recognize(uri: Uri): com.example.platerecognizer.ocr.Recognition?

    /** §4.7：String 重载，供 ViewModel 内部与 JVM 测试使用（避开 Android Uri）。 */
    suspend fun recognizeString(imageUriString: String): com.example.platerecognizer.ocr.Recognition? =
        recognize(Uri.parse(imageUriString))
}

/** app 私有图片仓库：导入外部 URI、删除自有图片。 */
interface ManagedImageStore {
    suspend fun importToLocal(source: Uri): Uri
    suspend fun deleteOwned(uri: Uri): Boolean

    /** §4.7：String 重载，供 ViewModel 内部与 JVM 测试使用（避开 Android Uri）。 */
    suspend fun importToLocalString(sourceUriString: String): String =
        importToLocal(Uri.parse(sourceUriString)).toString()
    suspend fun deleteOwnedString(imageUriString: String): Boolean =
        deleteOwned(Uri.parse(imageUriString))
}

/**
 * 识别 session 状态机持久化。
 *
 * 所有迁移方法返回 Boolean：true 表示状态匹配并完成迁移；
 * false 表示状态冲突（已被并发改动）或 session 不存在，调用方应放弃当前操作。
 */
interface RecognitionSessions {
    fun observeActive(): Flow<ActiveSession?>
    suspend fun createCapturing(imageUri: String): ActiveSession
    suspend fun beginRecognizing(id: String): Boolean
    suspend fun setRecognized(id: String, candidate: String, qualityScore: Float, error: String?): Boolean
    suspend fun beginSaving(id: String): Boolean
    suspend fun revertToAwaiting(id: String, error: String?): Boolean
    suspend fun markSaved(id: String): Boolean
    suspend fun beginDiscarding(id: String): Boolean
    suspend fun markDiscarded(id: String): Boolean
    suspend fun markFailed(id: String, error: String?): Boolean
    suspend fun beginClearingFailed(id: String): Boolean
    suspend fun delete(id: String)
    suspend fun listActiveImageUris(): List<String>
    suspend fun listAllNonTerminal(): List<ActiveSession>

    /** 当前活跃 session 的同步快照（用于恢复决策）。 */
    suspend fun snapshotActive(): ActiveSession?
}

/** CSV 导出。 */
interface CsvExporter {
    suspend fun exportCsv(): Pair<Int, String>
}
