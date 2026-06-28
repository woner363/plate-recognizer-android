package com.example.platerecognizer.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 车牌记录仓储 (Repository Pattern)。
 * 业务层只依赖此接口，便于测试替换。
 *
 * 所有阻塞 IO（文件、MediaStore）显式切到 Dispatchers.IO；
 * Room 的 suspend DAO 自身已经在 IO 线程，这里不再额外包。
 */
class PlateRepository(
    private val dao: PlateDao,
    private val context: Context,
) {
    fun observeAll(): Flow<List<PlateRecord>> = dao.observeAll()

    suspend fun add(
        plateNo: String,
        confidence: Float,
        imageUri: String?,
        note: String? = null,
    ): Long {
        require(plateNo.isNotBlank()) { "plateNo 不能为空" }
        val record = PlateRecord(
            plateNo = plateNo,
            confidence = confidence,
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

    /** 删除记录，同时清理 app 私有目录下关联的抓拍 JPG（相册导入的 URI 不动）。 */
    suspend fun delete(record: PlateRecord) {
        dao.delete(record)
        record.imageUri?.let { deleteOwnedImage(it) }
    }

    /**
     * 导出到「下载」目录。返回 (count, 文件名)。
     * Android 10+ 使用 MediaStore，旧版本写入公共下载目录。
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
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("无法在 Download 目录创建文件")
            resolver.openOutputStream(uri)?.use { os ->
                OutputStreamWriter(os, Charsets.UTF_8).use { it.write(csv) }
            } ?: error("无法写入文件")
        } else {
            @Suppress("DEPRECATION")
            val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            downloads.mkdirs()
            val file = File(downloads, filename)
            file.writeText(csv, Charsets.UTF_8)
        }
        records.size to filename
    }

    /**
     * 仅删除 file:// 协议且位于 app filesDir 之下的图片，避免误删相册原图或外部内容。
     */
    private suspend fun deleteOwnedImage(imageUri: String) = withContext(Dispatchers.IO) {
        runCatching {
            val uri = Uri.parse(imageUri)
            if (uri.scheme != "file") return@runCatching
            val path = uri.path ?: return@runCatching
            val file = File(path)
            val ownedRoot = context.filesDir.canonicalPath
            if (file.canonicalPath.startsWith(ownedRoot) && file.isFile) {
                file.delete()
            }
        }
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
