package com.example.platerecognizer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §4.8：CsvExporter.buildCsvInternal 纯函数单测——隔离 MediaStore/IO，
 * 只验编码、转义与公式注入防御。不实例化 CsvExporter（避免 DAO/Context 依赖）。
 */
class CsvExporterTest {

    @Test fun header_has_bom_and_columns() {
        val csv = CsvExporter.buildCsvInternal(emptyList())
        assertEquals('\uFEFF', csv.first())
        assertTrue(csv.contains("ID,车牌号,置信度,拍摄时间,图片URI,已修正,备注"))
    }

    @Test fun normal_record_formats_correctly() {
        val r = PlateRecord(
            id = 1, plateNo = "京A12345", confidence = 0.95f,
            capturedAt = 1700000000000L, imageUri = "file:///x/1.jpg",
            corrected = false, note = null,
        )
        val dataLine = CsvExporter.buildCsvInternal(listOf(r)).lineSequence().drop(1).first()
        assertTrue("应含车牌号", dataLine.contains("京A12345"))
        assertTrue("置信度用 Locale.US 点号: $dataLine", dataLine.contains("0.95"))
        assertTrue("未修正标 否", dataLine.contains(",否,"))
    }

    @Test fun comma_in_note_is_quoted() {
        val r = PlateRecord(
            id = 2, plateNo = "京A12345", confidence = 0.5f,
            capturedAt = 0L, imageUri = null, corrected = true,
            note = "A,B",
        )
        val dataLine = CsvExporter.buildCsvInternal(listOf(r)).lineSequence().drop(1).first()
        assertTrue("含逗号的字段应加双引号: $dataLine", dataLine.contains("\"A,B\""))
    }

    @Test fun formula_injection_is_neutralized() {
        val r = PlateRecord(
            id = 3, plateNo = "京A12345", confidence = 0.5f,
            capturedAt = 0L, imageUri = null, corrected = false,
            note = "=1+1",
        )
        val dataLine = CsvExporter.buildCsvInternal(listOf(r)).lineSequence().drop(1).first()
        assertTrue("公式前缀应被中和: $dataLine", dataLine.contains("'=1+1"))
    }

    @Test fun corrected_flag_shows_yes() {
        val r = PlateRecord(
            id = 4, plateNo = "京A12345", confidence = 0.5f,
            capturedAt = 0L, imageUri = null, corrected = true, note = null,
        )
        val dataLine = CsvExporter.buildCsvInternal(listOf(r)).lineSequence().drop(1).first()
        assertTrue("已修正标 是", dataLine.contains(",是,"))
    }

    @Test fun newline_in_note_is_quoted() {
        val r = PlateRecord(
            id = 5, plateNo = "京A12345", confidence = 0.5f,
            capturedAt = 0L, imageUri = null, corrected = false,
            note = "line1\nline2",
        )
        val dataLine = CsvExporter.buildCsvInternal(listOf(r)).lineSequence().drop(1).first()
        assertTrue("含换行的字段应加双引号: $dataLine", dataLine.contains("\"line1"))
    }
}
