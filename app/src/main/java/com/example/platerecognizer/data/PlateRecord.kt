package com.example.platerecognizer.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 车牌识别记录实体。等价于 Python 版 PlateRecord。
 * 使用 data class（值对象）；更新走 copy() 保持不可变。
 */
@Entity(tableName = "plates")
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
) {
    /** 应用一次修正，返回新对象（不变量更新模式）。 */
    fun withCorrection(newPlate: String, newNote: String? = note): PlateRecord =
        copy(plateNo = newPlate, corrected = true, note = newNote)
}
