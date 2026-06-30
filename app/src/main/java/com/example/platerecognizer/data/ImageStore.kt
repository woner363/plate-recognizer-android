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
     *
     * §4.9：先写 .tmp，完整复制成功后 fsync 再原子重命名；
     * 复制中途失败会删除 .tmp，绝不留下半截文件被误认为完整图片。
     * 失败时（无权限 / 流为空）抛异常由上层处理。
     */
    suspend fun importToLocal(source: Uri): Uri = withContext(Dispatchers.IO) {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault()).format(Date())
        val target = File(dir, "imported_$ts.jpg")
        val tmp = File(dir, "imported_$ts.jpg.tmp")
        try {
            val input = appContext.contentResolver.openInputStream(source)
                ?: error("无法读取所选图片")
            input.use { ins ->
                java.io.FileOutputStream(tmp).use { os ->
                    ins.copyTo(os)
                    os.fd.sync()  // 确保数据落盘
                }
            }
            if (!tmp.renameTo(target)) {
                // rename 失败（极少见），兜底用 copy+delete
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            target.toUri()
        } catch (e: Throwable) {
            tmp.delete()  // §4.9：失败清理临时文件
            throw e
        }
    }

    /**
     * 删除一个 app 自有图片（仅 file:// 协议且位于 filesDir/plates 之下）。
     * 用 canonical path + File.separator 拼接判断包含关系，
     * 避免 ".." / 符号链接绕过；同时防 "/foo" 误判前缀 "/foobar"。
     * 任何不属于私有目录的 URI 静默忽略，绝不误删相册原图或 SAF 来源。
     */
    suspend fun deleteOwned(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            if (uri.scheme != "file") return@runCatching false
            val path = uri.path ?: return@runCatching false
            val target = File(path).canonicalFile
            val ownedRoot = dir.canonicalFile.absolutePath + File.separator
            if (!target.absolutePath.startsWith(ownedRoot)) return@runCatching false
            if (!target.isFile) return@runCatching false
            target.delete()
        }.getOrDefault(false)
    }

    /**
     * 启动时扫描 [dir] 下的所有文件，删除既不被数据库引用、也不在保留期内的文件。
     *
     * 保留逻辑（任一满足则保留）：
     * 1. 文件 file:// URI 出现在 [referencedUris] 集合中（被某条 DB 记录引用）；
     * 2. 修改时间在 [retentionMillis] 之内（默认 24h）—— 用户可能刚拍但还在
     *    人工确认对话框里没保存，不应被清掉。
     *
     * 返回被删除的文件数，用于日志。失败不抛异常，按"尽力而为"清理。
     */
    suspend fun cleanupOrphans(
        referencedUris: Collection<String>,
        retentionMillis: Long = DEFAULT_RETENTION_MS,
    ): Int = withContext(Dispatchers.IO) {
        cleanupOrphansIn(dir, referencedUris, retentionMillis, System.currentTimeMillis())
    }

    companion object {
        /** 孤儿清理保留期：24 小时。pending 图片不会被误清。 */
        const val DEFAULT_RETENTION_MS: Long = 24L * 60 * 60 * 1000

        /**
         * 纯 Kotlin 实现，便于单测注入假目录/假时间。不依赖 Android Uri 解析——
         * 用字符串前缀 `file://` 提取本地路径即可。
         */
        internal fun cleanupOrphansIn(
            root: File,
            referencedUris: Collection<String>,
            retentionMillis: Long,
            now: Long,
        ): Int {
            val files = root.listFiles() ?: return 0
            val referenced = referencedUris.mapNotNullTo(HashSet()) { extractCanonicalPath(it) }
            var deleted = 0
            for (f in files) {
                if (!f.isFile) continue
                val canonical = runCatching { f.canonicalPath }.getOrNull() ?: continue
                if (canonical in referenced) continue
                if (now - f.lastModified() < retentionMillis) continue
                if (runCatching { f.delete() }.getOrDefault(false)) deleted++
            }
            return deleted
        }

        /** 把 "file:///path/to/foo.jpg" 提取为 canonical 路径；非法/非 file 返回 null。 */
        private fun extractCanonicalPath(raw: String): String? {
            if (!raw.startsWith("file://")) return null
            val path = raw.removePrefix("file://").substringBefore('?').substringBefore('#')
            if (path.isEmpty()) return null
            return runCatching { File(path).canonicalPath }.getOrNull()
        }
    }
}
