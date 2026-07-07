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
 *  1. **字符集过滤**：滑窗只接受能通过 [PlateValidator.isValid] 的子串
 *     （31 省白名单、发牌机关白名单、I/O 排除、新能源字母位约束）。
 *  2. **同起点截断剔除**：同一起点既能拼成合法 7 位又能拼成合法 8 位时，
 *     视为新能源 8 位，避免被截断。
 *  3. **评分**：偏向 7 位 + 字母数字混合更常见，作为多候选时的排序依据。
 *  4. **几何加权**：TextBlock 宽高比 ∈ [2.0, 5.0] / 面积 ≥ 1% 时给 qualityScore
 *     加权，但**不硬淘汰**——ML Kit boundingBox 是文字框非车牌外框，硬过滤会误杀。
 *     空间过滤主要交给取景框 ROI 裁剪完成。
 *
 * 调用方应**先把输入裁剪到取景框 ROI 区域**再喂给 [recognize]。
 */
class PlateRecognizer(context: Context) : com.example.platerecognizer.domain.RecognitionEngine {

    private val appContext: Context = context.applicationContext

    private val recognizer: TextRecognizer =
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

    @Volatile private var closed = false

    override suspend fun recognize(uri: Uri): Recognition? {
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
     * 从 [Text] 的 textBlocks 中挑选最像车牌的候选。
     *
     * 设计要点：ML Kit 的 boundingBox 包围的是**识别文字像素**而非车牌物理外框，
     * 因此**不能**把 2.6–3.8 的车牌外框宽高比当作硬过滤——很多合法车牌的文字框
     * 会因字体/边距/透视落到此区间之外。
     *
     * 既然相机输入已经被取景框 ROI 裁剪到约 3.14:1 范围，几何形状的过滤工作主要
     * 由 ROI 完成。本函数只在多候选时使用"几何相似度"作为**加权评分**，而非淘汰条件。
     *
     * 兜底：所有 block 都没有合法候选时，对整段全文做一次匹配；qualityScore 封顶
     * 0.7，确保走人工确认。
     */
    private fun pickBestFromBlocks(text: Text, imgW: Int, imgH: Int): Recognition? {
        if (text.text.isBlank()) return null
        val imageArea = (imgW.toLong() * imgH.toLong()).coerceAtLeast(1L)

        data class Scored(val recog: Recognition, val score: Float)
        val scored = mutableListOf<Scored>()

        for (block in text.textBlocks) {
            val recog = pickBestCandidate(block.text) ?: continue
            val rect = block.boundingBox

            // 几何加权（看像不像车牌文字框）：
            //   - 宽高比 ∈ [2.0, 5.0] +0.05
            //   - 占图面积 ≥ 1% +0.03
            // 不在范围内**不扣分**——我们已经被 ROI 卡过了一次，这里只奖励"长且明显"的块。
            var bonus = 0f
            if (rect != null && rect.width() > 0 && rect.height() > 0) {
                val aspect = rect.width().toFloat() / rect.height().toFloat()
                if (aspect in 2.0f..5.0f) bonus += 0.05f
                val areaRatio = (rect.width().toLong() * rect.height().toLong()).toDouble() /
                    imageArea.toDouble()
                if (areaRatio >= 0.01) bonus += 0.03f
            }
            scored += Scored(recog, recog.qualityScore + bonus)
        }

        if (scored.isNotEmpty()) {
            val best = scored.maxBy { it.score }
            // 加权只用来在多候选间排序，对外仍按 pickBestCandidate 的原 qualityScore。
            return best.recog
        }

        // 全文兜底
        val fallback = pickBestCandidate(text.text) ?: return null
        return fallback.copy(qualityScore = fallback.qualityScore.coerceAtMost(MAX_FALLBACK_QUALITY))
    }

    /** 关闭释放资源。幂等。 */
    fun close() {
        if (closed) return
        closed = true
        recognizer.close()
    }

    companion object {
        /** 几何过滤无果时，从全文捞回的候选 qualityScore 封顶。 */
        private const val MAX_FALLBACK_QUALITY = 0.7f

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
                return Recognition(plateNo = best.plate, qualityScore = conf)
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
                return Recognition(norm, qualityScore = conf)
            }

            val trimmed = PlateValidator.normalize(flat.take(8))
            return if (trimmed.isEmpty()) null else Recognition(trimmed, qualityScore = 0.10f)
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
