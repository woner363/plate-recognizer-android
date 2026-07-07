package com.example.platerecognizer.data

import androidx.room.withTransaction
import com.example.platerecognizer.domain.PlateRecords
import com.example.platerecognizer.util.PlateValidator
import kotlinx.coroutines.flow.Flow

/**
 * 车牌记录仓储。只管正式 PlateRecord 的 CRUD；CSV 导出已拆到 [CsvExporter]（§4.8）。
 *
 * 图片所有权交给 [ImageStore]：删除记录时通过 [imageStore] 清理关联文件。
 *
 * §4.3：[confirmSession] 用 Room 事务保证"插入记录 + 标记 session SAVED"原子完成，
 * 配合 [PlateRecord.sourceSessionId] 唯一索引实现幂等——进程在 SAVING 中断后恢复重试
 * 不会产生重复记录。
 *
 * 实现 [PlateRecords] 接口，便于注入 fake 测试。
 */
class PlateRepository(
    private val db: AppDatabase,
    private val plateDao: PlateDao,
    private val sessionDao: RecognitionSessionDao,
    private val imageStore: ImageStore,
) : PlateRecords {

    override fun observeAll(): Flow<List<PlateRecord>> = plateDao.observeAll()

    override suspend fun add(
        plateNo: String,
        qualityScore: Float,
        imageUri: String?,
        note: String?,
    ): Long {
        // §4.11：业务边界强制校验，非 UI 调用也无法写入非法车牌
        require(PlateValidator.isValid(plateNo)) { "plateNo 格式非法: $plateNo" }
        val record = PlateRecord(
            plateNo = plateNo,
            qualityScore = qualityScore,
            capturedAt = System.currentTimeMillis(),
            imageUri = imageUri,
            note = note,
        )
        return plateDao.insert(record)
    }

    /**
     * §4.3：事务性确认——插入正式记录 + 标记 session SAVED，原子完成。
     *
     * 幂等：若 session 已 SAVED（上次 confirm 部分完成），返回既有 PlateRecord id；
     * 若 session 状态不是 SAVING（并发冲突或已终结），抛异常让上层回退。
     */
    override suspend fun confirmSession(
        sessionId: String,
        plateNo: String,
        qualityScore: Float,
        imageUri: String?,
        note: String?,
    ): Long = db.withTransaction {
        // §4.11：业务边界强制校验
        require(PlateValidator.isValid(plateNo)) { "plateNo 格式非法: $plateNo" }
        // 先查幂等：session 已 SAVED → 返回既有记录
        val existingRecord = plateDao.findBySourceSessionId(sessionId)
        if (existingRecord != null) {
            // session 应该已是 SAVED（事务原子保证）；兜底强制标记
            sessionDao.transitionIf(
                sessionId, SessionState.SAVING, SessionState.SAVED, System.currentTimeMillis(),
            )
            return@withTransaction existingRecord.id
        }
        // expected-state CAS：SAVING → SAVED
        val rows = sessionDao.transitionIf(
            sessionId, SessionState.SAVING, SessionState.SAVED, System.currentTimeMillis(),
        )
        if (rows == 0) {
            error("session 状态不是 SAVING，无法确认（并发冲突或已终结）")
        }
        val record = PlateRecord(
            plateNo = plateNo,
            qualityScore = qualityScore,
            capturedAt = System.currentTimeMillis(),
            imageUri = imageUri,
            note = note,
            sourceSessionId = sessionId,
        )
        plateDao.insert(record)
    }

    override suspend fun findBySourceSessionId(sessionId: String): PlateRecord? =
        plateDao.findBySourceSessionId(sessionId)

    override suspend fun applyCorrection(record: PlateRecord, newPlate: String, note: String?) {
        // §4.11：业务边界强制校验
        require(PlateValidator.isValid(newPlate)) { "修正后的车牌格式非法: $newPlate" }
        plateDao.update(record.withCorrection(newPlate, note))
    }

    /** 删除记录，同时通过 [imageStore] 清理 app 自有关联图片。 */
    override suspend fun delete(record: PlateRecord) {
        plateDao.delete(record)
        record.imageUri?.let { imageStore.deleteOwnedString(it) }
    }
}
