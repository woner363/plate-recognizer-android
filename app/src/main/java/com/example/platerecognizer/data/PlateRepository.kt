package com.example.platerecognizer.data

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.flow.Flow
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 车牌记录仓储 (Repository Pattern)。
 * 业务层只依赖此接口，便于测试替换。
 */
class PlateRepository(
    private val dao: PlateDao,
    private val context: Context,
) {
    fun observeAll(): Flow<List<PlateRecord>> = dao.observeAll()

    suspend fun add(plateNo: String, confidence: Float, imageUri: String?): Long {
        require(plateNo.isNotBlank()) { "plateNo 不能为空" }
        val record = PlateRecord(
            plateNo = plateNo,
            confidence = confidence,
            capturedAt = System.currentTimeMillis(),
            imageUri = imageUri,
        )
        return dao.insert(record)
    }

    suspend fun applyCorrection(record: PlateRecord, newPlate: String, note: String?) {
        require(newPlate.isNotBlank()) { "修正后的车牌不能为空" }
        dao.update(record.withCorrection(newPlate, note))
    }

    suspend fun delete(record: PlateRecord) = dao.delete(record)

    /**
     * 导出到「下载」目录。返回 (count, 文件名)。
     * Android 10+ 使用 MediaStore，旧版本写入公共下载目录。
     */
    suspend fun exportCsv(): Pair<Int, String> {
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
            val file = java.io.File(downloads, filename)
            file.writeText(csv, Charsets.UTF_8)
        }
        return records.size to filename
    }

    private fun buildCsv(records: List<PlateRecord>): String {
        val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val sb = StringBuilder()
        // 带 BOM 兼容 Excel
        sb.append('﻿')
        sb.append("ID,车牌号,置信度,拍摄时间,图片URI,已修正,备注\n")
        for (r in records) {
            sb.append(r.id).append(',')
              .append(escape(r.plateNo)).append(',')
              .append("%.2f".format(r.confidence)).append(',')
              .append(escape(df.format(Date(r.capturedAt)))).append(',')
              .append(escape(r.imageUri ?: "")).append(',')
              .append(if (r.corrected) "是" else "否").append(',')
              .append(escape(r.note ?: ""))
              .append('\n')
        }
        return sb.toString()
    }

    private fun escape(s: String): String =
        if (s.contains(',') || s.contains('"') || s.contains('\n'))
            "\"" + s.replace("\"", "\"\"") + "\""
        else s
}
