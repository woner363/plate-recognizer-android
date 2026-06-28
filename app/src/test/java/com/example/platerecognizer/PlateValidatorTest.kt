package com.example.platerecognizer

import com.example.platerecognizer.util.PlateValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlateValidatorTest {

    // ===== 归一化 =====

    @Test fun normalize_strips_punctuation_and_uppercases() {
        assertEquals("京A12345", PlateValidator.normalize(" 京 A·1 2-3 4 5 "))
        assertEquals("YUA12345", PlateValidator.normalize("yu a 12345"))
    }

    @Test fun normalize_converts_fullwidth_to_halfwidth() {
        // OCR 偶尔会输出全角数字/字母
        assertEquals("京A12345", PlateValidator.normalize("京Ａ１２３４５"))
        assertTrue(PlateValidator.isValid("京Ａ１２３４５"))
    }

    @Test fun normalize_handles_fullwidth_space() {
        // U+3000 全角空格应被剔除
        assertEquals("京A12345", PlateValidator.normalize("京A　12345"))
    }

    // ===== 普通蓝/黄牌（7 位） =====

    @Test fun valid_normal_plates() {
        // 京A=北京A、粤B=深圳、沪A、闽A=福州、鲁B=青岛、川A=成都
        listOf("京A12345", "粤B88888", "沪A99999", "闽A12345", "鲁B12345", "川A12345").forEach {
            assertTrue("应合法: $it", PlateValidator.isValid(it))
        }
    }

    @Test fun valid_with_letters_in_serial() {
        // 表 4 第 2/3 种启用规则：序号含 1 或 2 位字母
        // 京A1B345 = 序号 1B345（1 位字母 B）
        // 粤B12C45 = 序号 12C45（1 位字母 C）
        // 沪A AB345 = 序号 AB345（2 位字母 A B，其余数字）
        listOf("京A1B345", "粤B12C45", "沪AAB345").forEach {
            assertTrue("应合法: $it", PlateValidator.isValid(it))
        }
    }

    @Test fun rejects_more_than_two_letters_in_normal_serial() {
        // 标准 5.9 表 4：普通号牌 5 位序号最多 2 位字母
        // 京AAAAAA = 省份 京 + 发牌代号 A + 5 位序号 AAAAA（5 字母）
        // 沪AABC23 = 序号 ABC23（3 字母）
        // 粤BABCD1 = 序号 ABCD1（4 字母）
        listOf("京AAAAAA", "沪AABC23", "粤BABCD1").forEach {
            assertFalse("应拒绝（超过 2 位字母）: $it", PlateValidator.isValid(it))
        }
        val err = PlateValidator.describeError("京AAAAAA")
        assertNotNull(err)
        assertTrue("错误信息应提及字母位数: $err", err!!.contains("字母"))
    }

    // ===== 新能源（8 位） =====

    @Test fun valid_new_energy_small() {
        // 表 6 启用顺序 1/3/5/7/9：首位是 D/A/B/C/E，其余数字
        listOf("京AD12345", "粤BF12345", "沪AA12345").forEach {
            assertTrue("应合法（小型新能源首位字母）: $it", PlateValidator.isValid(it))
        }
    }

    @Test fun valid_new_energy_large() {
        // 表 5：末位是 D/A/B/C/E/F/G/H/J/K，其余数字
        listOf("京A12345D", "粤B12345F").forEach {
            assertTrue("应合法（大型新能源末位字母）: $it", PlateValidator.isValid(it))
        }
    }

    @Test fun invalid_new_energy_letters() {
        // 新能源字母位不在 D/A/B/C/E/F/G/H/J/K 集合
        assertFalse(PlateValidator.isValid("京AZ12345"))    // Z 不在新能源字母集
        assertFalse(PlateValidator.isValid("京A12345Z"))    // Z 不在新能源字母集
    }

    // ===== I / O 字符严格禁止 =====

    @Test fun rejects_letter_I_in_serial() {
        assertFalse("序号位不可含 I", PlateValidator.isValid("京A1I345"))
        val err = PlateValidator.describeError("京A1I345")
        assertNotNull(err)
        assertTrue("错误信息应提及 I/O: $err", err!!.contains("I") || err.contains("O"))
    }

    @Test fun rejects_letter_O_in_serial() {
        assertFalse("序号位不可含 O", PlateValidator.isValid("京AO0000"))
    }

    // ===== 发牌机关白名单（附录 A） =====

    @Test fun rejects_unknown_district_code() {
        // 沪Z 不在上海的实际启用代号（虽然 5.8.2 说 A-Z 可备案启用，
        // 但本工程白名单按"上海 A-Z 全开 + O"配置，Z 落在其中。
        // 这里挑一个明确不在附录 A 的：「冀I」—— 冀只到 T，连 I 也不在序号集；
        // 「闽X」—— 福建附录里只到 K，X 不允许。
        assertFalse("冀I 是非法发牌代号", PlateValidator.isValid("冀I12345"))
        assertFalse("闽X 福建不存在", PlateValidator.isValid("闽X12345"))
    }

    @Test fun accepts_special_double_letter_districts() {
        // 哈尔滨 = 黑A 或 黑L
        assertTrue("哈尔滨 黑L", PlateValidator.isValid("黑L12345"))
        // 福州 = 闽A 或 闽K
        assertTrue("福州 闽K", PlateValidator.isValid("闽K12345"))
        // 佛山 = 粤E/X/Y
        assertTrue("佛山 粤X", PlateValidator.isValid("粤X12345"))
        assertTrue("佛山 粤Y", PlateValidator.isValid("粤Y12345"))
    }

    @Test fun rejects_unknown_province() {
        // 长得像省份但实际不存在的字符
        listOf("中A12345", "国B12345").forEach {
            assertFalse("非省份首字: $it", PlateValidator.isValid(it))
        }
    }

    // ===== 长度 =====

    @Test fun invalid_lengths() {
        listOf("", "A12345", "京A1234", "京A123456789").forEach {
            assertFalse("应不合法: $it", PlateValidator.isValid(it))
        }
    }

    @Test fun describe_error_returns_null_for_valid() {
        assertNull(PlateValidator.describeError("京A12345"))
    }

    @Test fun describe_error_reports_length() {
        val err = PlateValidator.describeError("京A1234")
        assertNotNull(err)
        assertTrue(err!!.contains("位数"))
    }
}
