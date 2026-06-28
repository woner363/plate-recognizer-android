package com.example.platerecognizer.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.example.platerecognizer.PlateRecognizerApp
import com.example.platerecognizer.data.ImageStore
import com.example.platerecognizer.data.PlateRecord
import com.example.platerecognizer.data.PlateRepository
import com.example.platerecognizer.ocr.PlateRecognizer
import com.example.platerecognizer.util.PlateValidator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/** 一次性 UI 事件（提示、对话框）。 */
sealed interface UiEvent {
    data class Toast(val message: String) : UiEvent
    /** 需要用户确认 / 修正车牌。 */
    data class RequestPlateInput(
        val initial: String,
        val confidence: Float,
        val imageUri: Uri,
        val error: String?,
    ) : UiEvent
}

class PlatesViewModel(
    private val repo: PlateRepository,
    private val recognizer: PlateRecognizer,
    private val imageStore: ImageStore,
) : ViewModel() {

    val records: StateFlow<List<PlateRecord>> =
        repo.observeAll().stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    /**
     * 一次性事件。用 Channel 保证：
     * - 紧挨着发的两条事件不会互相覆盖（StateFlow 的 conflated 行为会丢事件）；
     * - 同一条 Toast 消息重复发送也能各自送达。
     */
    private val _events = Channel<UiEvent>(capacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: Flow<UiEvent> = _events.receiveAsFlow()

    private fun emit(event: UiEvent) {
        _events.trySend(event)
    }

    /**
     * 串行化拍照/导入/OCR/入库整个流程：
     * 用 tryLock 做"门"，第二次点击在第一次没结束前会被拒绝并提示，
     * 避免两个识别结果互相覆盖、isProcessing 半路被清零等问题。
     */
    private val recognitionMutex = Mutex()

    /**
     * 抓拍/相册图像得到本地 URI 后，启动识别。
     * 内部封装成单一闸门——CameraX 写图 + ImageStore 复制都由调用方完成 URI 再调本函数。
     */
    fun onImageCaptured(uri: Uri) {
        launchSerial("识别已在进行中") { processRecognition(uri) }
    }

    /**
     * 抓拍专用入口：把 CameraX 的 takePicture 也拉进同一把锁，
     * 这样"按下抓拍 → 文件落盘 → OCR → 入库"全程都被 isProcessing 覆盖。
     * 失败时上报 Toast 并放弃，不进入识别流程。
     */
    fun capturePhotoThenRecognize(capture: suspend () -> Uri) {
        launchSerial("识别已在进行中") {
            val uri = try {
                capture()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emit(UiEvent.Toast("抓拍失败: ${e.message ?: "未知错误"}"))
                return@launchSerial
            }
            processRecognition(uri)
        }
    }

    /**
     * 从相册选取一张图：先复制到 app 私有目录，再走识别流程。整段在同一把锁下，
     * 这样 CSV 里的 imageUri 长期可读，删除记录时也能被 [PlateRepository.delete] 清理。
     */
    fun onImagePicked(source: Uri) {
        launchSerial("识别已在进行中") {
            val local = try {
                imageStore.importToLocal(source)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emit(UiEvent.Toast("导入失败: ${e.message ?: "未知错误"}"))
                return@launchSerial
            }
            processRecognition(local)
        }
    }

    /** 真正的识别管线：内部不再单独管 isProcessing（由 [launchSerial] 统一管理）。 */
    private suspend fun processRecognition(uri: Uri) {
        val result = try {
            recognizer.recognize(uri)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(UiEvent.Toast("识别异常: ${e.message ?: "未知错误"}"))
            null
        }

        if (result == null) {
            emit(
                UiEvent.RequestPlateInput(
                    initial = "",
                    confidence = 0f,
                    imageUri = uri,
                    error = "未检测到车牌",
                ),
            )
            return
        }

        val err = PlateValidator.describeError(result.plateNo)
        if (err == null && result.confidence >= 0.9f) {
            // 高置信度直接入库；DB 失败时回落到人工确认，避免静默丢数据。
            try {
                repo.add(result.plateNo, result.confidence, uri.toString())
                emit(UiEvent.Toast("识别: ${result.plateNo}"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emit(UiEvent.Toast("保存失败，已转入人工确认: ${e.message ?: "未知错误"}"))
                emit(
                    UiEvent.RequestPlateInput(
                        initial = result.plateNo,
                        confidence = result.confidence,
                        imageUri = uri,
                        error = err,
                    ),
                )
            }
        } else {
            // 低置信度或格式不合规 → 要求确认/修正
            emit(
                UiEvent.RequestPlateInput(
                    initial = result.plateNo,
                    confidence = result.confidence,
                    imageUri = uri,
                    error = err,
                ),
            )
        }
    }

    /**
     * 串行入口：tryLock 失败立即返回；失败后不进入 isProcessing。
     * 与 viewModelScope.launch 绑定，cancel 时自动释放锁。
     */
    private inline fun launchSerial(busyMessage: String, crossinline block: suspend () -> Unit) {
        if (!recognitionMutex.tryLock()) {
            emit(UiEvent.Toast(busyMessage))
            return
        }
        _isProcessing.value = true
        viewModelScope.launch {
            try {
                block()
            } finally {
                _isProcessing.value = false
                recognitionMutex.unlock()
            }
        }
    }

    /**
     * 对话框确认后保存（可能是首次入库，也可能用户决定保留非法格式）。
     * note 在保存时一并写入，避免事后再 update 触发 corrected=true 误标。
     */
    fun saveAfterPrompt(plateNo: String, confidence: Float, imageUri: Uri, note: String?) {
        viewModelScope.launch {
            try {
                repo.add(plateNo, confidence, imageUri.toString(), note)
                emit(UiEvent.Toast("已保存: $plateNo"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emit(UiEvent.Toast("保存失败: ${e.message ?: "未知错误"}"))
            }
        }
    }

    fun applyCorrection(record: PlateRecord, newPlate: String, note: String?) {
        viewModelScope.launch {
            try {
                repo.applyCorrection(record, newPlate, note)
                emit(UiEvent.Toast("已修正: ${record.plateNo} → $newPlate"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emit(UiEvent.Toast("修正失败: ${e.message ?: "未知错误"}"))
            }
        }
    }

    fun delete(record: PlateRecord) {
        viewModelScope.launch {
            try {
                repo.delete(record)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emit(UiEvent.Toast("删除失败: ${e.message ?: "未知错误"}"))
            }
        }
    }

    fun exportCsv() {
        viewModelScope.launch {
            try {
                val (count, filename) = repo.exportCsv()
                emit(UiEvent.Toast("已导出 $count 条到 Download/$filename"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emit(UiEvent.Toast("导出失败: ${e.message ?: "未知错误"}"))
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        // recognizer 现在由 AppContainer 持有（进程级），不在 ViewModel 关闭，
        // 避免下一次进入 Activity 时使用已关闭的 TextRecognizer。
    }

    companion object {
        /** 从 Application 的 [com.example.platerecognizer.AppContainer] 取依赖装配 ViewModel。 */
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PlateRecognizerApp
                val c = app.container
                @Suppress("UNCHECKED_CAST")
                return PlatesViewModel(c.repository, c.recognizer, c.imageStore) as T
            }
        }
    }
}
