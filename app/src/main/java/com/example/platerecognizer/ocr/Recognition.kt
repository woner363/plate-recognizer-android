package com.example.platerecognizer.ocr

/**
 * 单次识别结果（不可变）。
 * @param plateNo 已归一化的车牌号（可能仍是非法的，由上层决定如何处理）。
 * @param confidence 0..1
 */
data class Recognition(
    val plateNo: String,
    val confidence: Float,
)
