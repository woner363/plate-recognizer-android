package com.example.platerecognizer.data

import android.content.Context
import android.net.Uri
import com.example.platerecognizer.domain.PlateRecords
import kotlinx.coroutines.flow.Flow

/**
 * 车牌记录仓储。只管正式 PlateRecord 的 CRUD；CSV 导出已拆到 [CsvExporter]（§4.8）。
 *
 * 图片所有权交给 [ImageStore]：删除记录时通过 [imageStore] 清理关联文件，
 * 不再在 Repository 内重复实现边界判断（§4.9.4）。
 *
 * 实现 [PlateRecords] 接口，让 ViewModel 依赖抽象而非具体类，便于注入 fake 测试。
 */
class PlateRepository(
    private val dao: PlateDao,
    private val context: Context,
    private val imageStore: ImageStore,
) : PlateRecords {

    override fun observeAll(): Flow<List<PlateRecord>> = dao.observeAll()

    override suspend fun add(
        plateNo: String,
        qualityScore: Float,
        imageUri: String?,
        note: String?,
    ): Long {
        require(plateNo.isNotBlank()) { "plateNo 不能为空" }
        val record = PlateRecord(
            plateNo = plateNo,
            confidence = qualityScore,
            capturedAt = System.currentTimeMillis(),
            imageUri = imageUri,
            note = note,
        )
        return dao.insert(record)
    }

    override suspend fun applyCorrection(record: PlateRecord, newPlate: String, note: String?) {
        require(newPlate.isNotBlank()) { "修正后的车牌不能为空" }
        dao.update(record.withCorrection(newPlate, note))
    }

    /** 删除记录，同时通过 [imageStore] 清理 app 自有关联图片。 */
    override suspend fun delete(record: PlateRecord) {
        dao.delete(record)
        record.imageUri?.let { imageStore.deleteOwned(Uri.parse(it)) }
    }
}
