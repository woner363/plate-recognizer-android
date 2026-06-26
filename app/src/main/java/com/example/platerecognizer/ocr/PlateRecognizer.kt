package com.example.platerecognizer.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.example.platerecognizer.util.PlateValidator
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 基于 ML Kit 中文文本识别的车牌 OCR。
 *
 * 策略：对每行 / 整体文本做一遍归一化，挑出合法车牌；
 *      没有合法时取最像车牌的候选并返回低置信度结果，交由 UI 提示修正。
 */
class PlateRecognizer(context: Context) {

    private val recognizer: TextRecognizer =
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

    /**
     * 识别给定 URI 的图像。
     * @return 识别到的车牌；如果完全没有候选返回 null。
     */
    suspend fun recognize(context: Context, uri: Uri): Recognition? {
        val image = InputImage.fromFilePath(context, uri)
        return recognizeInput(image)
    }

    suspend fun recognize(bitmap: Bitmap, rotationDegrees: Int = 0): Recognition? {
        val image = InputImage.fromBitmap(bitmap, rotationDegrees)
        return recognizeInput(image)
    }

    private suspend fun recognizeInput(image: InputImage): Recognition? =
        suspendCancellableCoroutine { cont ->
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    cont.resume(pickBestCandidate(visionText.text))
                }
                .addOnFailureListener { e -> cont.resumeWithException(e) }
        }

    /**
     * 从一段任意文本中找最像车牌的子串。
     */
    private fun pickBestCandidate(rawText: String): Recognition? {
        if (rawText.isBlank()) return null

        // 用行 + 整段一起做候选；先去除空白拼接，避免 "京A" 与 "12345" 被换行分开
        val flat = rawText.replace(Regex("\\s+"), "")
        val candidates = mutableListOf<String>()

        // 1) 整段中按字符滑窗找 7-8 位窗口
        for (len in intArrayOf(8, 7)) {
            if (flat.length < len) continue
            for (i in 0..flat.length - len) {
                val sub = flat.substring(i, i + len)
                val norm = PlateValidator.normalize(sub)
                if (PlateValidator.isValid(norm)) candidates += norm
            }
        }

        if (candidates.isNotEmpty()) {
            // 高置信度：直接命中合法车牌
            return Recognition(plateNo = candidates.first(), confidence = 0.95f)
        }

        // 2) 没有合法窗口 → 退一步：找包含中国省份首字的最长字母数字段
        val fallback = Regex("[京津沪渝冀豫云辽黑湘皖鲁新苏浙赣鄂桂甘晋蒙陕吉闽贵粤青藏川宁琼][A-Za-z0-9]{4,9}")
            .findAll(flat).maxByOrNull { it.value.length }?.value
        if (fallback != null) {
            return Recognition(plateNo = PlateValidator.normalize(fallback), confidence = 0.45f)
        }

        // 3) 最后回退：返回原文（让用户手动改）
        val trimmed = PlateValidator.normalize(flat.take(8))
        return if (trimmed.isEmpty()) null else Recognition(trimmed, 0.10f)
    }

    /** 关闭释放资源。 */
    fun close() {
        recognizer.close()
    }

    companion object {
        /** 便利方法：从文件路径解码为 Bitmap（用于相册导入回退）。 */
        fun decodeBitmap(path: String): Bitmap? = BitmapFactory.decodeFile(path)
    }
}
