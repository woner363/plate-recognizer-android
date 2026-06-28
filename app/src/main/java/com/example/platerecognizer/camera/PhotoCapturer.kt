package com.example.platerecognizer.camera

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.RectF
import android.media.ExifInterface
import android.net.Uri
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import android.graphics.Bitmap

/**
 * CameraX 拍照助手。负责把当前 ImageCapture 用例的一次抓拍保存到 app 私有目录，
 * 可选地按 [PreviewRoi] 裁剪到取景框区域以提高 OCR 几何过滤命中率。
 *
 * UI 层负责把 ImageCapture 绑定到生命周期、把取景框相对 PreviewView 的归一化坐标
 * 传给本类，然后调用 [takePicture]。
 */
class PhotoCapturer(
    private val context: Context,
    private val imageCapture: ImageCapture,
) {
    /**
     * 取景框相对 PreviewView 的归一化矩形：x/y/w/h 都在 [0,1]。
     * 与照片自身的"orientation 校正后"坐标系对齐 —— 即假定 PreviewView 在屏幕上看到
     * 什么角度，这里的坐标就是什么角度。本类负责把它转换到 Bitmap 像素坐标。
     */
    data class PreviewRoi(val x: Float, val y: Float, val w: Float, val h: Float)

    /**
     * 抓拍一帧并保存为 JPG，返回文件 Uri。
     * @param roi 可选裁剪区。null 时输出整张原图（与旧行为一致）。
     */
    suspend fun takePicture(roi: PreviewRoi? = null): Uri = suspendCancellableCoroutine { cont ->
        val outputDir = File(context.filesDir, "plates").apply { mkdirs() }
        val name = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault())
            .format(Date()) + ".jpg"
        val rawFile = File(outputDir, name)
        val output = ImageCapture.OutputFileOptions.Builder(rawFile).build()
        val completed = AtomicBoolean(false)
        cont.invokeOnCancellation { completed.set(true) }
        imageCapture.takePicture(
            output,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                    if (!completed.compareAndSet(false, true)) return
                    val finalFile = if (roi != null) {
                        runCatching { cropToFile(rawFile, roi) }
                            .getOrDefault(rawFile)  // 裁剪失败兜底用原图
                    } else {
                        rawFile
                    }
                    cont.resume(result.savedUri ?: Uri.fromFile(finalFile))
                }
                override fun onError(exception: ImageCaptureException) {
                    if (!completed.compareAndSet(false, true)) return
                    cont.resumeWithException(exception)
                }
            },
        )
    }

    /**
     * 解码 [source] → 校正 EXIF 旋转 → 按 [roi] 裁剪 → 保存为新文件，**删除原图**返回新文件。
     *
     * 之所以把 EXIF 校正一并做了：CameraX 写出的 JPEG 通常带 Orientation=6/8 标记，
     * 直接用 BitmapFactory 解出来是横躺的；如果不校正就裁剪，roi 会落在错误区域。
     */
    private fun cropToFile(source: File, roi: PreviewRoi): File {
        val bytes = source.readBytes()
        val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: return source  // 解码失败兜底
        val rotation = readExifRotation(source)
        val upright = if (rotation == 0) raw else rotateBitmap(raw, rotation).also {
            if (it !== raw) raw.recycle()
        }

        // ROI 归一化坐标 → 像素坐标
        val pxRect = RectF(
            roi.x * upright.width,
            roi.y * upright.height,
            (roi.x + roi.w) * upright.width,
            (roi.y + roi.h) * upright.height,
        )
        val x = pxRect.left.toInt().coerceIn(0, upright.width - 1)
        val y = pxRect.top.toInt().coerceIn(0, upright.height - 1)
        val w = (pxRect.width().toInt()).coerceIn(1, upright.width - x)
        val h = (pxRect.height().toInt()).coerceIn(1, upright.height - y)

        val cropped = Bitmap.createBitmap(upright, x, y, w, h)
        if (cropped !== upright) upright.recycle()

        val target = File(source.parentFile, source.nameWithoutExtension + "_roi.jpg")
        FileOutputStream(target).use { os ->
            cropped.compress(Bitmap.CompressFormat.JPEG, 92, os)
        }
        cropped.recycle()
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
}
