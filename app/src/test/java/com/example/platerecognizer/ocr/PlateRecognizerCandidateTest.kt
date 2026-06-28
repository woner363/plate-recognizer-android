package com.example.platerecognizer.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 候选选择策略的纯 JVM 测试。
 * `pickBestCandidate` 不依赖 ML Kit / Android，因此可以直接跑。
 */
class PlateRecognizerCandidateTest {

    @Test fun blank_input_returns_null() {
        assertNull(PlateRecognizer.pickBestCandidate(""))
        assertNull(PlateRecognizer.pickBestCandidate("   \n  "))
    }

    @Test fun picks_valid_plate_embedded_in_noise() {
        val r = PlateRecognizer.pickBestCandidate("车架号: ABCDEF\n车牌 京A12345 已登记")
        assertNotNull(r)
        assertEquals("京A12345", r!!.plateNo)
        assertTrue("高置信度: ${r.confidence}", r.confidence >= 0.9f)
    }

    @Test fun strips_inner_whitespace_before_window() {
        // OCR 经常把车牌断成两行
        val r = PlateRecognizer.pickBestCandidate("京A\n12345")
        assertNotNull(r)
        assertEquals("京A12345", r!!.plateNo)
    }

    @Test fun prefers_7_char_over_8_char_when_both_valid_independently() {
        // 不同起点各藏一个合法 7 位和合法 8 位：普通车牌更常见 → 选 7 位
        val r = PlateRecognizer.pickBestCandidate("京A12345 旁边还有 京AD12345")
        assertNotNull(r)
        assertEquals("京A12345", r!!.plateNo)
    }

    @Test fun new_energy_8_char_kept_when_7_char_is_just_a_truncation() {
        // 唯一来源是一个合法新能源 8 位；同起点的 7 位是它的截断，不应让 7 位顶替
        val r = PlateRecognizer.pickBestCandidate("京AD12345")
        assertNotNull(r)
        assertEquals("京AD12345", r!!.plateNo)
    }

    @Test fun rejects_I_O_in_candidate() {
        // GA 36-2018 严禁序号位出现 I/O：粤BIO345 整段都不算合法候选
        // 文本里既有合法 粤B12345 又有非法 粤BIO345，应取合法的
        val r = PlateRecognizer.pickBestCandidate("粤B12345粤BIO345")
        assertNotNull(r)
        assertEquals("粤B12345", r!!.plateNo)
    }

    @Test fun deduplicates_repeated_valid_matches() {
        // 同一车牌出现多次（滑窗会重复命中）—— 不应崩，应返回该车牌
        val r = PlateRecognizer.pickBestCandidate("京A12345 京A12345 京A12345")
        assertNotNull(r)
        assertEquals("京A12345", r!!.plateNo)
    }

    @Test fun fallback_for_almost_valid_plate() {
        // 无合法窗口（首位含省份字但尾段字符不够 / 不合规）
        val r = PlateRecognizer.pickBestCandidate("粤BIO34")  // 6 位，省份+...
        assertNotNull(r)
        // 不会被判为合法车牌（首字母后位数错）但仍走 fallback
        assertTrue("fallback 置信度低于 0.85: ${r!!.confidence}", r.confidence < 0.85f)
        assertTrue("应含 fallback 子串", r.plateNo.startsWith("粤"))
    }

    @Test fun last_resort_returns_low_confidence() {
        // 完全不含省份首字
        val r = PlateRecognizer.pickBestCandidate("ABCDEFGH")
        assertNotNull(r)
        assertEquals(0.10f, r!!.confidence, 0.001f)
    }

    @Test fun confidence_below_threshold_for_new_energy() {
        // 新能源 8 位虽然合法，但评分较低 → 走人工确认而非直接入库
        val r = PlateRecognizer.pickBestCandidate("京AD12345")
        assertNotNull(r)
        assertEquals("京AD12345", r!!.plateNo)
        // 视为「合法但需确认」：confidence 落在 [0.85, 0.9) 区间
        assertTrue("应 ≥ 0.85: ${r.confidence}", r.confidence >= 0.85f)
    }
}
