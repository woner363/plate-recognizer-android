package com.example.platerecognizer

import com.example.platerecognizer.util.PlateValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlateValidatorTest {

    @Test fun normalize_strips_punctuation_and_uppercases() {
        assertEquals("京A12345", PlateValidator.normalize(" 京 A·1 2-3 4 5 "))
        assertEquals("YUA12345", PlateValidator.normalize("yu a 12345"))
    }

    @Test fun valid_normal_plates() {
        listOf("京A12345", "粤B88888", "沪AZ999A").forEach {
            assertTrue("应合法: $it", PlateValidator.isValid(it))
        }
    }

    @Test fun valid_new_energy_plates() {
        listOf("京AD12345", "粤AF12345").forEach {
            assertTrue("应合法: $it", PlateValidator.isValid(it))
        }
    }

    @Test fun invalid_plates() {
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
