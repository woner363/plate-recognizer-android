package com.example.platerecognizer.data

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * §4.8/§4.9：从 PlateRepository 拆出的 CSV 导出器。
 * 隔离 Android MediaStore 与 CSV 编码，便于单测编码、失败回滚与 Android 版本分支。
 */
class CsvExporter(
    private val dao: PlateDao,
    private val context: Context,
) : com.example.platerecognizer.domain.CsvExporter {

    override suspend fun exportCsv(): Pair<Int, String> = withContext(Dispatchers.IO) {
        val records = dao.listAll()
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val filename = "plates_$ts.csv"
        val csv = buildCsvInternal(records)

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
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (e: Throwable) {
                runCatching { resolver.delete(uri, null, null) }
                throw e
            }
        } else {
            @Suppress("DEPRECATION")
            val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            downloads.mkdirs()
            java.io.File(downloads, filename).writeText(csv, Charsets.UTF_8)
        }
        records.size to filename
    }

    companion object {
        /**
         * 纯函数：CSV 编码 + 转义 + 公式注入防御。不依赖 DAO/Context，便于 JVM 单测。
         * - BOM (U+FEFF) 兼容 Excel（用转义字面量避免 lint ByteOrderMark 警告）；
         * - confidence 用 Locale.US，避免地区差异撕裂列；
         * - 含 , " \r \n 的字段加双引号、内部双引号翻倍；
         * - 以 = + - @ \t \r 开头的字段前置 ' 中和公式注入。
         */
        internal fun buildCsvInternal(records: List<PlateRecord>): String {
            val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            val sb = StringBuilder()
            sb.append('\uFEFF')
            sb.append("ID,车牌号,置信度,拍摄时间,图片URI,已修正,备注\n")
            for (r in records) {
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

        private fun escape(s: String): String {
            val neutralized = if (s.isNotEmpty() && s[0] in "=+-@\t\r") "'$s" else s
            return if (neutralized.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
                "\"" + neutralized.replace("\"", "\"\"") + "\""
            } else {
                neutralized
            }
        }
    }
}
