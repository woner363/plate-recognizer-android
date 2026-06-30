package com.example.platerecognizer.ocr

/**
 * 单次识别结果（不可变）。
 *
 * @param plateNo 已归一化的车牌号（可能仍是非法的，由上层决定如何处理）。
 * @param qualityScore 0..1，**候选质量分**，不是 ML Kit 的字符识别概率。
 *   由车牌长度、字符组成等启发式打分，仅用于在多候选间排序和 UI 提示，
 *   **不能**作为"自动入库"的依据——它无法判断 OCR 是否把 8 识别成 B。
 */
data class Recognition(
    val plateNo: String,
    val qualityScore: Float,
)
