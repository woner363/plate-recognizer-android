package com.example.platerecognizer.camera

import android.content.Context
import android.net.Uri
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * CameraX 拍照助手。负责把当前 ImageCapture 用例的一次抓拍保存到 app 私有目录。
 *
 * UI 层（PlateCameraScreen）负责把 ImageCapture 绑定到生命周期，然后调用 [takePicture]。
 */
class PhotoCapturer(
    private val context: Context,
    private val imageCapture: ImageCapture,
) {
    /** 抓拍一帧并保存为 JPG，返回文件 Uri。 */
    suspend fun takePicture(): Uri = suspendCancellableCoroutine { cont ->
        val outputDir = File(context.filesDir, "plates").apply { mkdirs() }
        val name = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault())
            .format(Date()) + ".jpg"
        val file = File(outputDir, name)
        val output = ImageCapture.OutputFileOptions.Builder(file).build()
        imageCapture.takePicture(
            output,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                    val uri = result.savedUri ?: Uri.fromFile(file)
                    cont.resume(uri)
                }
                override fun onError(exception: ImageCaptureException) {
                    cont.resumeWithException(exception)
                }
            },
        )
    }
}
