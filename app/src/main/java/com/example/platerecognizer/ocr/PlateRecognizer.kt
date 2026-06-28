package com.example.platerecognizer.ocr

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.example.platerecognizer.util.PlateValidator
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 基于 ML Kit 中文文本识别的车牌 OCR。
 *
 * 识别策略（与 [GA 36-2018] 协同）：
 *  1. **空间过滤**：只接受 boundingBox 形状像号牌的 TextBlock —— 宽高比落在
 *     [2.6, 3.8]（440×140=3.14 与 480×140=3.43 的兼容窗），且面积占比 ≥ 2%。
 *     这一步直接把广告牌、车架号、店招、路标过滤掉。
 *  2. **字符集过滤**：滑窗只接受能通过 [PlateValidator.isValid] 的子串
 *     （即包含 31 省白名单、发牌机关白名单、I/O 排除、新能源字母位约束）。
 *  3. **同起点截断剔除**：同一起点既能拼成合法 7 位又能拼成合法 8 位时，
 *     视为新能源 8 位，避免被截断。
 *  4. **评分**：偏向 7 位 + 字母数字混合更常见，作为多候选时的排序依据。
 *
 * 调用方应**先把输入裁剪到取景框 ROI 区域**再喂给 [recognize]，以最大化几何过滤效果。
 */
class PlateRecognizer(context: Context) {

    private val appContext: Context = context.applicationContext

    private val recognizer: TextRecognizer =
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

    @Volatile private var closed = false

    suspend fun recognize(uri: Uri): Recognition? {
        check(!closed) { "PlateRecognizer 已关闭" }
        val image = InputImage.fromFilePath(appContext, uri)
        return recognizeInput(image)
    }

    suspend fun recognize(bitmap: Bitmap, rotationDegrees: Int = 0): Recognition? {
        check(!closed) { "PlateRecognizer 已关闭" }
        val image = InputImage.fromBitmap(bitmap, rotationDegrees)
        return recognizeInput(image)
    }

    private suspend fun recognizeInput(image: InputImage): Recognition? =
        suspendCancellableCoroutine { cont ->
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    cont.resume(
                        pickBestFromBlocks(visionText, image.width, image.height)
                    )
                }
                .addOnFailureListener { e -> cont.resumeWithException(e) }
        }

    /**
     * 从 [Text] 的 textBlocks 中按空间形状预过滤，再喂给 [pickBestCandidate]。
     * 优先返回通过几何过滤 + 合法字符校验的候选；
     * 全军覆没时退而求其次：把全文拼起来做一次松弛候选（兼容取景框很小、
     * 整张图只有车牌一行文字时 boundingBox 角度信息可能不可靠的场景）。
     */
    private fun pickBestFromBlocks(text: Text, imgW: Int, imgH: Int): Recognition? {
        if (text.text.isBlank()) return null
        val imageArea = (imgW.toLong() * imgH.toLong()).coerceAtLeast(1L)

        // 1) 几何过滤：宽高比 + 面积
        val candidates = mutableListOf<Recognition>()
        for (block in text.textBlocks) {
            val rect = block.boundingBox ?: continue
            val w = rect.width()
            val h = rect.height()
            if (w <= 0 || h <= 0) continue
            val aspect = w.toFloat() / h.toFloat()
            val areaRatio = (w.toLong() * h.toLong()).toDouble() / imageArea.toDouble()

            val shapeOk = aspect in PLATE_ASPECT_MIN..PLATE_ASPECT_MAX
            val sizeOk = areaRatio >= MIN_AREA_RATIO

            if (shapeOk && sizeOk) {
                pickBestCandidate(block.text)?.let { candidates += it }
            }
        }
        if (candidates.isNotEmpty()) {
            return candidates.maxBy { it.confidence }
        }

        // 2) 几何过滤无果 → 退到全文兜底，但置信度封顶，避免高置信度误入库
        val fallback = pickBestCandidate(text.text) ?: return null
        return fallback.copy(confidence = fallback.confidence.coerceAtMost(MAX_FALLBACK_CONFIDENCE))
    }

    /** 关闭释放资源。幂等。 */
    fun close() {
        if (closed) return
        closed = true
        recognizer.close()
    }

    companion object {
        /** 车牌宽高比窗：440×140≈3.14（普通），480×140≈3.43（新能源），±0.3 容差。 */
        private const val PLATE_ASPECT_MIN = 2.6f
        private const val PLATE_ASPECT_MAX = 3.8f

        /** 占图面积下限：太小的文字块（车架号等）剔除。 */
        private const val MIN_AREA_RATIO = 0.02

        /** 几何过滤未命中时，从全文捞回的候选 confidence 封顶。 */
        private const val MAX_FALLBACK_CONFIDENCE = 0.7f

        private const val PROVINCES = "京津沪渝冀豫云辽黑湘皖鲁新苏浙赣鄂桂甘晋蒙陕吉闽贵粤青藏川宁琼"
        private val fallbackRe = Regex("[$PROVINCES][A-Za-z0-9]{4,9}")
        private val whitespaceRe = Regex("\\s+")

        /**
         * 从一段任意文本中找最像车牌的子串。暴露为 internal 以便 JVM 单测直接验证。
         */
        internal fun pickBestCandidate(rawText: String): Recognition? {
            if (rawText.isBlank()) return null
            val flat = rawText.replace(whitespaceRe, "")

            data class Hit(val start: Int, val len: Int, val plate: String)
            val hits = ArrayList<Hit>()
            for (len in intArrayOf(7, 8)) {
                if (flat.length < len) continue
                for (i in 0..flat.length - len) {
                    val sub = flat.substring(i, i + len)
                    val norm = PlateValidator.normalize(sub)
                    if (norm.length == len && PlateValidator.isValid(norm)) {
                        hits += Hit(i, len, norm)
                    }
                }
            }
            if (hits.isNotEmpty()) {
                // 同起点同时命中 7 位与 8 位 → 视为 8 位（新能源）被截断
                val eightStarts = hits.filter { it.len == 8 }.map { it.start }.toHashSet()
                val kept = hits.filterNot { it.len == 7 && it.start in eightStarts }
                val best = kept.distinctBy { it.plate }.maxBy { scoreValid(it.plate) }
                val conf = (0.85f + scoreValid(best.plate) * 0.013f).coerceIn(0.85f, 0.98f)
                return Recognition(plateNo = best.plate, confidence = conf)
            }

            // fallback：含省份首字的最长字母数字段
            val fallback = fallbackRe.findAll(flat).maxByOrNull { it.value.length }?.value
            if (fallback != null) {
                val norm = PlateValidator.normalize(fallback)
                val conf = when {
                    norm.length >= 7 -> 0.60f
                    norm.length == 6 -> 0.45f
                    else -> 0.30f
                }
                return Recognition(norm, conf)
            }

            val trimmed = PlateValidator.normalize(flat.take(8))
            return if (trimmed.isEmpty()) null else Recognition(trimmed, 0.10f)
        }

        /**
         * 对一个合法车牌候选打分。仅在多候选时用于排序，不直接作为 OCR 置信度对外暴露。
         * - 7 位（普通蓝/黄牌）比 8 位（新能源）常见，+10；
         * - 序号位字母数字混合给 +2（全数字也合规但相对少见）。
         */
        private fun scoreValid(plate: String): Int {
            var score = 0
            score += if (plate.length == 7) 10 else 0
            val tail = plate.substring(2)
            val hasDigit = tail.any { it.isDigit() }
            val hasLetter = tail.any { it.isLetter() }
            if (hasDigit && hasLetter) score += 2
            return score
        }
    }
}
