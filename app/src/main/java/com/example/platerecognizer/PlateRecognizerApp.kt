package com.example.platerecognizer

import android.app.Application
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Application 入口：装配 [AppContainer]，并触发后台一次性清理。 */
class PlateRecognizerApp : Application() {

    lateinit var container: AppContainer
        private set

    /**
     * Application 作用域：与进程同生命周期，用于不绑定 UI 的后台任务。
     * 用 SupervisorJob 避免任一子任务异常拖垮整组。
     */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        scheduleOrphanCleanup()
    }

    /**
     * §3.9 / §4.5：扫描 filesDir/plates 下未被数据库引用且超过保留期的孤儿文件。
     * "被引用"包含两类：
     *   - PlateRecord.image_uri（已确认记录）；
     *   - 非终态 RecognitionSession.image_uri（待确认 / 识别中）。
     * 在 IO 线程异步执行，不阻塞 onCreate；失败仅记录日志。
     */
    private fun scheduleOrphanCleanup() {
        appScope.launch {
            runCatching {
                val plateUris = container.database.plateDao().listAllImageUris()
                val sessionUris = container.sessionRepository.listActiveImageUris()
                val referenced = plateUris + sessionUris
                val deleted = container.imageStore.cleanupOrphans(referenced)
                if (deleted > 0) {
                    Log.i(TAG, "cleaned up $deleted orphan image(s) in plates/")
                }
            }.onFailure { e ->
                Log.w(TAG, "orphan cleanup failed", e)
            }
        }
    }

    companion object {
        private const val TAG = "PlateRecognizerApp"
    }
}
