package com.example.platerecognizer.ui

import android.net.Uri
import android.os.Parcelable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
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
import kotlinx.parcelize.Parcelize

/** 一次性 UI 事件——仅 Toast。需对话框的请求改走 [PlatesViewModel.pending]。 */
sealed interface UiEvent {
    data class Toast(val message: String) : UiEvent
}

/**
 * 待用户确认的识别结果。Parcelable + 存进 SavedStateHandle，
 * 旋转屏幕 / Activity 重建 / 进程被回收后恢复时仍可见。
 *
 * @param qualityScore 候选质量分（不是 OCR 概率），仅用于 UI 提示。
 */
@Parcelize
data class PendingRecognition(
    val initial: String,
    val qualityScore: Float,
    val imageUri: Uri,
    val error: String?,
) : Parcelable

class PlatesViewModel(
    private val savedState: SavedStateHandle,
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
     * 待确认状态。由 [SavedStateHandle] 持久化：
     * - 配置变化（旋转）：进程内 StateFlow 自身保留；
     * - Activity 重建 / 进程被回收：SavedStateHandle 会把 Parcelable 写入 bundle，
     *   恢复时 getStateFlow 直接给回上次的值。
     */
    val pending: StateFlow<PendingRecognition?> =
        savedState.getStateFlow(KEY_PENDING, null)

    /**
     * 一次性 Toast 通道（与 pending 互不相干，独立保留 Channel 形态）。
     */
    private val _events = Channel<UiEvent>(capacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: Flow<UiEvent> = _events.receiveAsFlow()

    private fun emit(event: UiEvent) {
        _events.trySend(event)
    }

    private fun setPending(value: PendingRecognition?) {
        savedState[KEY_PENDING] = value
    }

    /**
     * 串行化拍照/导入/OCR/入库整个流程：
     * tryLock 失败立即拒绝并提示，避免两次识别互相覆盖。
     * 此外，pending 存在时直接拒绝新识别——人工还没确认完，不允许覆盖。
     */
    private val recognitionMutex = Mutex()

    fun onImageCaptured(uri: Uri) {
        launchSerial { processRecognition(uri) }
    }

    /**
     * 抓拍专用入口：把 CameraX 的 takePicture 也拉进同一把锁，
     * 这样"按下抓拍 → 文件落盘 → OCR → 入库"全程都被 isProcessing 覆盖。
     */
    fun capturePhotoThenRecognize(capture: suspend () -> Uri) {
        launchSerial {
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
     * 这样 CSV 里 imageUri 长期可读，删除记录时也能被清理。
     */
    fun onImagePicked(source: Uri) {
        launchSerial {
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
            setPending(
                PendingRecognition(
                    initial = "",
                    qualityScore = 0f,
                    imageUri = uri,
                    error = "未检测到车牌",
                )
            )
            return
        }

        // §4.2：不再用启发式 qualityScore 自动入库。
        // qualityScore 只反映"格式是否合法、长度是否常见"，无法判断 OCR 是否把
        // 8 识别成 B。所有识别结果一律走人工确认，由 PlateValidator 给出格式错误提示。
        val err = PlateValidator.describeError(result.plateNo)
        setPending(
            PendingRecognition(
                initial = result.plateNo,
                qualityScore = result.qualityScore,
                imageUri = uri,
                error = err,
            )
        )
    }

    /**
     * 串行入口：
     * - pending 还在 → 拒绝新识别（提示用户先处理上次结果）；
     * - mutex tryLock 失败 → 已有任务在跑，提示并返回；
     * - 否则进入 isProcessing。
     */
    private inline fun launchSerial(crossinline block: suspend () -> Unit) {
        if (pending.value != null) {
            emit(UiEvent.Toast("请先处理待确认的识别结果"))
            return
        }
        if (!recognitionMutex.tryLock()) {
            emit(UiEvent.Toast("识别已在进行中"))
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
     * 用户在确认对话框点"保存"后调用。
     * - §4.4：业务层强制校验车牌格式，非法车牌拒绝保存（UI 也已 disable 按钮，
     *   但防 UI 漏洞或未来其他调用方绕过）；
     * - 入库成功才清空 pending；
     * - 入库失败保留 pending 让用户重试（仅弹 Toast 提示）。
     */
    fun confirmPending(plateNo: String, note: String?) {
        val current = pending.value ?: return
        val normalized = PlateValidator.normalize(plateNo)
        val err = PlateValidator.describeError(normalized)
        if (err != null) {
            emit(UiEvent.Toast(err))
            return
        }
        viewModelScope.launch {
            try {
                repo.add(normalized, current.qualityScore, current.imageUri.toString(), note)
                setPending(null)
                emit(UiEvent.Toast("已保存: $normalized"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emit(UiEvent.Toast("保存失败: ${e.message ?: "未知错误"}"))
            }
        }
    }

    /**
     * 用户关闭确认对话框。删除已落盘的自有图片，再清 pending。
     * 即使图片删除失败也清 pending —— 避免 UI 卡死，孤儿图片由后续启动扫描清理。
     */
    fun discardPending() {
        val current = pending.value ?: return
        // 立即清状态以解锁 UI；删图异步进行
        setPending(null)
        viewModelScope.launch {
            try {
                imageStore.deleteOwned(current.imageUri)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 静默：清理失败不影响用户主流程
            }
        }
    }

    fun applyCorrection(record: PlateRecord, newPlate: String, note: String?) {
        val normalized = PlateValidator.normalize(newPlate)
        val err = PlateValidator.describeError(normalized)
        if (err != null) {
            emit(UiEvent.Toast(err))
            return
        }
        viewModelScope.launch {
            try {
                repo.applyCorrection(record, normalized, note)
                emit(UiEvent.Toast("已修正: ${record.plateNo} → $normalized"))
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
        // recognizer 由 AppContainer 持有（进程级），不在此关闭。
    }

    companion object {
        private const val KEY_PENDING = "pending_recognition"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer<PlatesViewModel> {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PlateRecognizerApp
                val c = app.container
                PlatesViewModel(
                    savedState = createSavedStateHandle(),
                    repo = c.repository,
                    recognizer = c.recognizer,
                    imageStore = c.imageStore,
                )
            }
        }
    }
}
