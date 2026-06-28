package com.example.platerecognizer.data

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * App 私有图片仓库：负责把外部 URI（相册、SAF）复制成自家 filesDir/plates 下的本地文件，
 * 保证抓拍/相册导入两条路最终都得到稳定可读、可清理的本地 URI。
 *
 * 抓拍路径已经写在 filesDir/plates 之下（[com.example.platerecognizer.camera.PhotoCapturer]），
 * 不需要再复制；只对外部 URI 走 [importToLocal]。
 */
class ImageStore(context: Context) {

    private val appContext: Context = context.applicationContext

    private val dir: File
        get() = File(appContext.filesDir, "plates").apply { mkdirs() }

    /**
     * 把外部 URI 内容复制到本地 filesDir/plates/imported_*.jpg，返回本地 file:// URI。
     * 失败时（无权限 / 流为空）抛异常由上层处理。
     */
    suspend fun importToLocal(source: Uri): Uri = withContext(Dispatchers.IO) {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault()).format(Date())
        val target = File(dir, "imported_$ts.jpg")
        appContext.contentResolver.openInputStream(source)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: error("无法读取所选图片")
        target.toUri()
    }
}
