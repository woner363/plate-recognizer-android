package com.example.platerecognizer.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.platerecognizer.PlateRecognizerApp
import com.example.platerecognizer.data.ActiveSession
import com.example.platerecognizer.data.SessionState
import com.example.platerecognizer.domain.CsvExporter
import com.example.platerecognizer.domain.ManagedImageStore
import com.example.platerecognizer.domain.PlateRecords
import com.example.platerecognizer.domain.RecognitionEngine
import com.example.platerecognizer.domain.RecognitionSessions
import com.example.platerecognizer.data.PlateRecord
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
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/** 一次性 UI 事件——仅 Toast。 */
sealed interface UiEvent {
    data class Toast(val message: String) : UiEvent
}

/**
 * §4.3：识别流程的单一状态机。任意时刻只处于一个状态，避免多个 Boolean 描述同一流程。
 *
 * Ready → Capturing/Recognizing → AwaitingConfirmation → Saving → Ready
 *                                              → Discarding → Ready
 *                                              → Failed → AwaitingConfirmation（可重试）
 */
sealed interface RecognitionUiState {
    data object Ready : RecognitionUiState
    data object Capturing : RecognitionUiState
    data object Recognizing : RecognitionUiState
    data class AwaitingConfirmation(val session: ActiveSession) : RecognitionUiState
    data class Saving(val session: ActiveSession) : RecognitionUiState
    data class Discarding(val session: ActiveSession) : RecognitionUiState
    data class Failed(val message: String, val recoverable: Boolean) : RecognitionUiState
}

class PlatesViewModel(
    private val repo: PlateRecords,
    private val recognizer: RecognitionEngine,
    private val imageStore: ManagedImageStore,
    private val sessions: RecognitionSessions,
    private val csvExporter: CsvExporter,
) : ViewModel() {

    val records: StateFlow<List<PlateRecord>> =
        repo.observeAll().stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    /**
     * UI 状态：Ready 之外，capturing/recognizing 等都属于"处理中"。
     * 由 [sessionRepository] 持久化的 session 驱动——进程恢复后非终态 session 仍可见。
     */
    private val _uiState = MutableStateFlow<RecognitionUiState>(RecognitionUiState.Ready)
    val uiState: StateFlow<RecognitionUiState> = _uiState.asStateFlow()

    /** 便捷派生：处理中（capturing/recognizing/saving/discarding）。 */
    val isProcessing: StateFlow<Boolean> = MutableStateFlow(false).also { mut ->
        viewModelScope.launch {
            _uiState.collect { s ->
                mut.value = s is RecognitionUiState.Capturing ||
                    s is RecognitionUiState.Recognizing ||
                    s is RecognitionUiState.Saving ||
                    s is RecognitionUiState.Discarding
            }
        }
    }.asStateFlow()

    private val _events = Channel<UiEvent>(capacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: Flow<UiEvent> = _events.receiveAsFlow()

    private fun emit(event: UiEvent) {
        _events.trySend(event)
    }

    /**
     * 启动时把 DB 里的非终态 session 恢复成 UI 状态（§4.5）。
     */
    init {
        viewModelScope.launch {
            sessions.observeActive().collect { active ->
                if (_uiState.value is RecognitionUiState.AwaitingConfirmation) return@collect
                if (active == null) {
                    if (_uiState.value !is RecognitionUiState.Capturing &&
                        _uiState.value !is RecognitionUiState.Recognizing &&
                        _uiState.value !is RecognitionUiState.Saving &&
                        _uiState.value !is RecognitionUiState.Discarding
                    ) {
                        _uiState.value = RecognitionUiState.Ready
                    }
                } else {
                    // 恢复到待确认
                    _uiState.value = RecognitionUiState.AwaitingConfirmation(active)
                }
            }
        }
    }

    /** 串行闸门：同一时刻只允许一个识别任务。 */
    private val recognitionMutex = Mutex()

    fun onImageCaptured(uri: Uri) {
        launchRecognition { processRecognition(uri.toString()) }
    }

    fun capturePhotoThenRecognize(capture: suspend () -> Uri) {
        launchRecognition {
            val uri = try {
                capture()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emit(UiEvent.Toast("抓拍失败: ${e.message ?: "未知错误"}"))
                return@launchRecognition
            }
            processRecognition(uri.toString())
        }
    }

    fun onImagePicked(source: Uri) {
        launchRecognition {
            val local = try {
                imageStore.importToLocal(source)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emit(UiEvent.Toast("导入失败: ${e.message ?: "未知错误"}"))
                return@launchRecognition
            }
            processRecognition(local.toString())
        }
    }

    private suspend fun processRecognition(imageUri: String) {
        // 创建 session（CAPTURING → RECOGNIZING），持久化图片引用，便于孤儿清理保留
        val session = sessions.createCapturing(imageUri)
        sessions.transition(session.id, SessionState.RECOGNIZING)
        _uiState.value = RecognitionUiState.Recognizing

        val result = try {
            recognizer.recognize(Uri.parse(imageUri))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(UiEvent.Toast("识别异常: ${e.message ?: "未知错误"}"))
            null
        }

        val candidate = result?.plateNo ?: ""
        val quality = result?.qualityScore ?: 0f
        val err = if (result == null) "未检测到车牌" else PlateValidator.describeError(candidate)
        sessions.setRecognized(session.id, candidate, quality, err)
        // 同步 UI 状态到待确认
        val updated = sessions.observeActive().firstOrNull()
        _uiState.value = RecognitionUiState.AwaitingConfirmation(updated ?: session)
    }

    /**
     * 串行入口：状态机非 Ready 时拒绝新识别。
     */
    private inline fun launchRecognition(crossinline block: suspend () -> Unit) {
        // 只有 Ready/AwaitingConfirmation 之外都不接受新任务；Awaiting 也拒绝（要先处理）
        when (val s = _uiState.value) {
            RecognitionUiState.Ready -> Unit
            is RecognitionUiState.Failed -> Unit
            else -> {
                emit(UiEvent.Toast("请先处理待确认的识别结果"))
                return
            }
        }
        if (!recognitionMutex.tryLock()) {
            emit(UiEvent.Toast("识别已在进行中"))
            return
        }
        _uiState.value = RecognitionUiState.Capturing
        viewModelScope.launch {
            try {
                block()
            } finally {
                recognitionMutex.unlock()
            }
        }
    }

    /**
     * §4.3：用户点"保存"。进入 Saving 后禁用保存/取消/返回，防重复保存。
     * 入库成功才迁移到 SAVED 并清 UI；失败回 AwaitingConfirmation 允许重试。
     */
    fun confirmPending(plateNo: String, note: String?) {
        val current = _uiState.value
        val session = (current as? RecognitionUiState.AwaitingConfirmation)?.session ?: return
        val normalized = PlateValidator.normalize(plateNo)
        val err = PlateValidator.describeError(normalized)
        if (err != null) {
            emit(UiEvent.Toast(err))
            return
        }
        // §4.3：只有 AwaitingConfirmation 才能进 Saving；已 Saving 则忽略（防重复）
        if (!_uiState.compareAndSet(current, RecognitionUiState.Saving(session))) {
            emit(UiEvent.Toast("正在保存，请稍候"))
            return
        }
        viewModelScope.launch {
            try {
                sessions.transition(session.id, SessionState.SAVING)
                repo.add(normalized, session.qualityScore ?: 0f, session.imageUri, note)
                sessions.markSaved(session.id)
                sessions.delete(session.id)  // 终态记录清理
                _uiState.value = RecognitionUiState.Ready
                emit(UiEvent.Toast("已保存: $normalized"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                sessions.markAwaiting(session.id, "保存失败: ${e.message ?: "未知错误"}")
                val restored = sessions.observeActive().firstOrNull() ?: session
                _uiState.value = RecognitionUiState.AwaitingConfirmation(restored)
                emit(UiEvent.Toast("保存失败: ${e.message ?: "未知错误"}"))
            }
        }
    }

    /**
     * §4.3：放弃。只有 AwaitingConfirmation 才能进 Discarding。
     * 删除自有图片 → 标记 DISCARDED → 清 session → 回 Ready。
     */
    fun discardPending() {
        val current = _uiState.value
        val session = (current as? RecognitionUiState.AwaitingConfirmation)?.session ?: return
        if (!_uiState.compareAndSet(current, RecognitionUiState.Discarding(session))) {
            return
        }
        viewModelScope.launch {
            try {
                sessions.transition(session.id, SessionState.DISCARDING)
                runCatching { imageStore.deleteOwned(Uri.parse(session.imageUri)) }
                sessions.markDiscarded(session.id)
                sessions.delete(session.id)
                _uiState.value = RecognitionUiState.Ready
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                sessions.markAwaiting(session.id, null)
                val restored = sessions.observeActive().firstOrNull() ?: session
                _uiState.value = RecognitionUiState.AwaitingConfirmation(restored)
                emit(UiEvent.Toast("放弃失败: ${e.message ?: "未知错误"}"))
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
                val (count, filename) = csvExporter.exportCsv()
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
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer<PlatesViewModel> {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PlateRecognizerApp
                val c = app.container
                PlatesViewModel(
                    repo = c.repository,
                    recognizer = c.recognizer,
                    imageStore = c.imageStore,
                    sessions = c.sessionRepository,
                    csvExporter = c.csvExporter,
                )
            }
        }
    }
}
