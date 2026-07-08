package com.example.platerecognizer.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.RectF
import androidx.exifinterface.media.ExifInterface
import android.net.Uri
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * CameraX 拍照助手。负责把当前 ImageCapture 用例的一次抓拍保存到 app 私有目录，
 * 可选地按 [PreviewRoi] 裁剪到取景框区域以提高 OCR 几何过滤命中率。
 *
 * UI 层负责把 ImageCapture 绑定到生命周期、把取景框相对 PreviewView 的归一化坐标
 * 传给本类，然后调用 [takePicture]。
 *
 * 流程拆成两个 suspend 阶段：
 *  1. [captureRaw]：CameraX 把原图存到 filesDir/plates，仅相机 IO；
 *  2. [cropToFile]：在 [Dispatchers.IO] 上解码 / 旋转 / 裁剪 / 重编码 / 删原图。
 *
 * 这样主线程不再承担 JPEG 解码与重编码（v0.2.0 之前的写法）。
 */
class PhotoCapturer(
    private val context: Context,
    private val imageCapture: ImageCapture,
) {
    /**
     * 取景框相对 PreviewView 的归一化矩形：x/y/w/h 都在 [0,1]。
     * 当前实现假定 PreviewView 与 ImageCapture 输出按相同 ViewPort 对齐
     * （由 UseCaseGroup 保证），归一化坐标可直接映射到照片像素坐标。
     */
    data class PreviewRoi(val x: Float, val y: Float, val w: Float, val h: Float)

    /**
     * 抓拍一帧并保存为 JPG，返回**实际存在的**文件 Uri。
     * @param roi 可选裁剪区。null 时输出整张原图。
     */
    suspend fun takePicture(roi: PreviewRoi? = null): Uri {
        val rawFile = captureRaw()
        if (roi == null) return Uri.fromFile(rawFile)
        // 失败兜底：保留原图，让 OCR 至少能跑（虽然不裁剪）
        val finalFile = runCatching {
            withContext(Dispatchers.IO) { cropToFile(rawFile, roi) }
        }.getOrElse { rawFile }
        return Uri.fromFile(finalFile)
    }

    /**
     * CameraX 写出原图，仅承担相机回调。返回一个**存在**的 File。
     * 内部仍用 main executor 接收 CameraX 回调（CameraX 文档推荐），
     * 但回调里只做 cont.resume，没有 IO。
     */
    private suspend fun captureRaw(): File = suspendCancellableCoroutine { cont ->
        val outputDir = File(context.filesDir, "plates").apply { mkdirs() }
        // §P3-3：UUID 命名，避免毫秒时间戳在批量拍摄/多窗口场景碰撞
        val uid = java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        val rawFile = File(outputDir, "captured_$uid.jpg")
        val output = ImageCapture.OutputFileOptions.Builder(rawFile).build()
        val completed = AtomicBoolean(false)
        cont.invokeOnCancellation {
            // 协程被取消后 CameraX 仍可能完成保存：在 onImageSaved 里看到 completed=true
            // 时主动删除生成文件，避免孤儿。
            completed.set(true)
        }
        imageCapture.takePicture(
            output,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                    if (!completed.compareAndSet(false, true)) {
                        rawFile.delete()  // 协程已取消，清理迟到产物
                        return
                    }
                    cont.resume(rawFile)
                }
                override fun onError(exception: ImageCaptureException) {
                    if (!completed.compareAndSet(false, true)) return
                    cont.resumeWithException(exception)
                }
            },
        )
    }

    /**
     * 解码 [source] → 校正 EXIF 旋转 → 按 [roi] 裁剪 → 保存为新 [roiFile] → 删除原图，
     * 返回新文件。**全部在 IO 线程执行。**
     *
     * 之所以把 EXIF 校正一并做：CameraX 写出的 JPEG 通常带 Orientation=6/8 标记，
     * BitmapFactory 默认不应用 EXIF；不校正就裁剪，roi 会落在错误区域。
     *
     * 解码使用 inSampleSize：OCR 不需要 4000×3000 全分辨率，按 ROI 缩到目标
     * 像素后正常足够（一张 440×140 物理车牌、在 1080p 截取后大概也就 100×30~200×60，
     * 但 ML Kit 偏好稍高分辨率以保字符锐度，我们留 ~1200px 长边）。
     */
    private fun cropToFile(source: File, roi: PreviewRoi): File {
        // 1) 先读尺寸，不解码像素
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return source

        val rotation = readExifRotation(source)
        // 旋转后的逻辑尺寸（用户看到的尺寸）
        val logicalW = if (rotation % 180 == 0) bounds.outWidth else bounds.outHeight
        val logicalH = if (rotation % 180 == 0) bounds.outHeight else bounds.outWidth

        // 期望 ROI 像素 ≈ logical * roi.w/h；为了让 OCR 字符锐度够，长边目标 ~1200
        val roiPxW = (logicalW * roi.w).toInt().coerceAtLeast(1)
        val roiPxH = (logicalH * roi.h).toInt().coerceAtLeast(1)
        val targetLong = TARGET_ROI_LONG_EDGE
        val needed = maxOf(roiPxW, roiPxH).toFloat()
        val sample = generateSequence(1) { it * 2 }
            .takeWhile { needed / it > targetLong }
            .lastOrNull() ?: 1

        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val raw = BitmapFactory.decodeFile(source.absolutePath, decodeOpts) ?: return source

        val upright = if (rotation == 0) raw else {
            val rotated = rotateBitmap(raw, rotation)
            if (rotated !== raw) raw.recycle()
            rotated
        }

        // ROI 归一化坐标 → 像素坐标（在已校正的 upright 上）
        val pxRect = RectF(
            roi.x * upright.width,
            roi.y * upright.height,
            (roi.x + roi.w) * upright.width,
            (roi.y + roi.h) * upright.height,
        )
        val x = pxRect.left.toInt().coerceIn(0, upright.width - 1)
        val y = pxRect.top.toInt().coerceIn(0, upright.height - 1)
        val w = pxRect.width().toInt().coerceIn(1, upright.width - x)
        val h = pxRect.height().toInt().coerceIn(1, upright.height - y)

        val cropped = Bitmap.createBitmap(upright, x, y, w, h)
        if (cropped !== upright) upright.recycle()

        val target = File(source.parentFile, source.nameWithoutExtension + "_roi.jpg")
        try {
            val ok = FileOutputStream(target).use { os ->
                cropped.compress(Bitmap.CompressFormat.JPEG, 92, os)
            }
            // 关键：压缩失败时**保留原图**，让 OCR 仍能跑
            if (!ok) {
                target.delete()
                return source
            }
        } finally {
            cropped.recycle()
        }
        source.delete()
        return target
    }

    private fun readExifRotation(file: File): Int {
        return try {
            when (ExifInterface(file.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL,
            )) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } catch (_: Exception) {
            0
        }
    }

    private fun rotateBitmap(src: Bitmap, degrees: Int): Bitmap {
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    companion object {
        /** ROI 长边的目标像素：兼顾 ML Kit 字符锐度与内存峰值。 */
        private const val TARGET_ROI_LONG_EDGE = 1200f
    }
}
