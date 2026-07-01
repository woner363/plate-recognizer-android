package com.example.platerecognizer.domain

import android.net.Uri
import com.example.platerecognizer.data.PlateRecord
import kotlinx.coroutines.flow.Flow

/**
 * §4.8：业务层依赖的最小接口集合。ViewModel 不直接依赖具体实现，
 * 测试时可注入 fake 模拟"识别失败 / 保存失败 / 删除失败"等场景。
 *
 * 接口刻意保持窄，避免泄露 Room / ML Kit / 文件系统细节。
 */

/** 正式车牌记录的读写。 */
interface PlateRecords {
    fun observeAll(): Flow<List<PlateRecord>>
    suspend fun add(plateNo: String, qualityScore: Float, imageUri: String?, note: String? = null): Long
    suspend fun applyCorrection(record: PlateRecord, newPlate: String, note: String?)
    suspend fun delete(record: PlateRecord)
}

/** 车牌 OCR 引擎。 */
interface RecognitionEngine {
    suspend fun recognize(uri: Uri): com.example.platerecognizer.ocr.Recognition?
}

/** app 私有图片仓库：导入外部 URI、删除自有图片。 */
interface ManagedImageStore {
    suspend fun importToLocal(source: Uri): Uri
    suspend fun deleteOwned(uri: Uri): Boolean
}

/** 识别 session 状态机持久化。 */
interface RecognitionSessions {
    fun observeActive(): Flow<com.example.platerecognizer.data.ActiveSession?>
    suspend fun createCapturing(imageUri: String): com.example.platerecognizer.data.ActiveSession
    suspend fun transition(
        id: String,
        state: com.example.platerecognizer.data.SessionState,
        transform: (com.example.platerecognizer.data.RecognitionSessionEntity) -> com.example.platerecognizer.data.RecognitionSessionEntity =
            { it },
    )
    suspend fun setRecognized(id: String, candidate: String, qualityScore: Float, error: String?)
    suspend fun markAwaiting(id: String, error: String?)
    suspend fun markSaved(id: String)
    suspend fun markDiscarded(id: String)
    suspend fun markFailed(id: String, error: String?)
    suspend fun delete(id: String)
    suspend fun listActiveImageUris(): List<String>
}

/** CSV 导出。从 PlateRepository 拆出（§4.8/§4.9）。 */
interface CsvExporter {
    suspend fun exportCsv(): Pair<Int, String>
}
