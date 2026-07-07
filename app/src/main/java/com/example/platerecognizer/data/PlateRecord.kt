package com.example.platerecognizer.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 车牌识别记录实体。
 *
 * §4.3：[sourceSessionId] 指向产生本记录的 RecognitionSession，带唯一索引。
 * 进程在 SAVING 中断后恢复时，用它判断本 session 是否已入库，避免重复保存。
 *
 * 使用 data class（值对象）；更新走 copy() 保持不可变。
 */
@Entity(
    tableName = "plates",
    indices = [Index(value = ["source_session_id"], unique = true)],
)
data class PlateRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "plate_no")
    val plateNo: String,

    @ColumnInfo(name = "confidence")
    val confidence: Float,

    /** 拍摄时间，毫秒时间戳。 */
    @ColumnInfo(name = "captured_at")
    val capturedAt: Long,

    /** 关联图像的本地 URI（可空）。 */
    @ColumnInfo(name = "image_uri")
    val imageUri: String?,

    @ColumnInfo(name = "corrected")
    val corrected: Boolean = false,

    @ColumnInfo(name = "note")
    val note: String? = null,

    /** §4.3：产生本记录的 session id，唯一约束防重复入库。 */
    @ColumnInfo(name = "source_session_id")
    val sourceSessionId: String? = null,
) {
    /** 应用一次修正，返回新对象（不变量更新模式）。 */
    fun withCorrection(newPlate: String, newNote: String? = note): PlateRecord =
        copy(plateNo = newPlate, corrected = true, note = newNote)
}
