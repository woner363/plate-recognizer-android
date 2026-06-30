package com.example.platerecognizer.data

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 车牌记录仓储 (Repository Pattern)。只管正式 PlateRecord 与 CSV 导出。
 *
 * 图片所有权交给 [ImageStore]：删除记录时通过 [imageStore] 清理关联文件，
 * 不再在 Repository 内重复实现边界判断（§4.9.4）。
 *
 * 所有阻塞 IO（MediaStore）显式切到 Dispatchers.IO；
 * Room 的 suspend DAO 自身已在 IO 线程。
 */
class PlateRepository(
    private val dao: PlateDao,
    private val context: Context,
    private val imageStore: ImageStore,
) {
    fun observeAll(): Flow<List<PlateRecord>> = dao.observeAll()

    suspend fun add(
        plateNo: String,
        qualityScore: Float,
        imageUri: String?,
        note: String? = null,
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

    suspend fun applyCorrection(record: PlateRecord, newPlate: String, note: String?) {
        require(newPlate.isNotBlank()) { "修正后的车牌不能为空" }
        dao.update(record.withCorrection(newPlate, note))
    }

    /** 删除记录，同时通过 [imageStore] 清理 app 自有关联图片。 */
    suspend fun delete(record: PlateRecord) {
        dao.delete(record)
        record.imageUri?.let { imageStore.deleteOwned(android.net.Uri.parse(it)) }
    }

    /**
     * 导出到「下载」目录。返回 (count, 文件名)。
     *
     * §4.9：
     * - Android 10+ 用 MediaStore IS_PENDING=1 → 写入 → IS_PENDING=0 两阶段，
     *   写入失败时删除已创建的空条目，Downloads 不留空文件；
     * - pre-Q 写文件失败抛异常由上层处理。
     */
    suspend fun exportCsv(): Pair<Int, String> = withContext(Dispatchers.IO) {
        val records = dao.listAll()
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val filename = "plates_$ts.csv"
        val csv = buildCsv(records)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("无法在 Download 目录创建文件")
            try {
                resolver.openOutputStream(uri)?.use { os ->
                    os.write(csv.toByteArray(Charsets.UTF_8))
                } ?: error("无法写入文件")
                // 写入成功，解除 pending
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (e: Throwable) {
                // §4.9：写入失败，删除刚创建的空条目，不留残骸
                runCatching { resolver.delete(uri, null, null) }
                throw e
            }
        } else {
            @Suppress("DEPRECATION")
            val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            downloads.mkdirs()
            val file = java.io.File(downloads, filename)
            file.writeText(csv, Charsets.UTF_8)
        }
        records.size to filename
    }

    private fun buildCsv(records: List<PlateRecord>): String {
        val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val sb = StringBuilder()
        // 带 BOM (U+FEFF) 兼容 Excel；用转义字面量避免 lint 的 ByteOrderMark 警告。
        sb.append('\uFEFF')
        sb.append("ID,车牌号,置信度,拍摄时间,图片URI,已修正,备注\n")
        for (r in records) {
            // confidence 强制 Locale.US：避免在 fr/de 等地区把小数点变成逗号、撕裂列结构
            sb.append(r.id).append(',')
              .append(escape(r.plateNo)).append(',')
              .append(String.format(Locale.US, "%.2f", r.confidence)).append(',')
              .append(escape(df.format(Date(r.capturedAt)))).append(',')
              .append(escape(r.imageUri ?: "")).append(',')
              .append(if (r.corrected) "是" else "否").append(',')
              .append(escape(r.note ?: ""))
              .append('\n')
        }
        return sb.toString()
    }

    /**
     * CSV 字段转义 + CSV-injection 防御：
     * - 包含 , " \r \n 的字段加双引号，内部双引号翻倍；
     * - 以 = + - @ \t \r 开头的字段会被 Excel/Sheets 当作公式执行，前置 ' 中和。
     */
    private fun escape(s: String): String {
        val neutralized = if (s.isNotEmpty() && s[0] in "=+-@\t\r") "'$s" else s
        return if (neutralized.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + neutralized.replace("\"", "\"\"") + "\""
        } else {
            neutralized
        }
    }
}
