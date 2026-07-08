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
import com.example.platerecognizer.util.PlateValidator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/** 一次性 UI 事件——仅 Toast。 */
sealed interface UiEvent {
    data class Toast(val message: String) : UiEvent
}

/**
 * 识别流程 UI 状态。由 [PlatesViewModel.uiState] 单一权威源驱动——
 * Room session 映射 + 一个短暂的 transient 覆盖（仅在 session 创建前的 capturing 窗口）。
 *
 * Ready → Capturing/Recognizing → AwaitingConfirmation → Saving → Ready
 *                                              → Discarding → Ready
 *                                              → Failed → Discarding → Ready
 */
sealed interface RecognitionUiState {
    data object Ready : RecognitionUiState
    data object Capturing : RecognitionUiState
    data object Recognizing : RecognitionUiState
    data class AwaitingConfirmation(val session: ActiveSession) : RecognitionUiState
    data class Saving(val session: ActiveSession) : RecognitionUiState
    data class Discarding(val session: ActiveSession) : RecognitionUiState
    data class Failed(
        val session: ActiveSession,
        val message: String,
        val recoverable: Boolean,
    ) : RecognitionUiState
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
     * §4.1：UI 状态的唯一权威源。
     *
     * - [transientOverride]：仅在"session 创建前的 capturing 窗口"非空，覆盖 Room 映射；
     *   失败时清 null，让 Room 映射（session=null→Ready）接管，保证 UI 不卡死（§4.2）。
     * - [sessions].observeActive()：Room session 经 [mapSessionToUiState] 映射。
     *
     * 两者 combine 后任一变化都会重新计算，不再有"双源互相覆盖"问题。
     */
    private val transientOverride = MutableStateFlow<RecognitionUiState?>(null)

    val uiState: StateFlow<RecognitionUiState> =
        combine(sessions.observeActive(), transientOverride) { session, override ->
            override ?: mapSessionToUiState(session)
        }.stateIn(
            scope = viewModelScope,
            // Eagerly：VM 创建即开始收集，保证 uiState.value 始终是最新状态。
            // 单 ViewModel 开销可忽略；同时让 JVM 测试无需手动订阅。
            started = SharingStarted.Eagerly,
            initialValue = RecognitionUiState.Ready,
        )

    /** 便捷派生：处理中（capturing/recognizing/saving/discarding）。 */
    val isProcessing: StateFlow<Boolean> =
        uiState.let { state ->
            val derived = MutableStateFlow(false)
            viewModelScope.launch {
                state.collect { s ->
                    derived.value = s is RecognitionUiState.Capturing ||
                        s is RecognitionUiState.Recognizing ||
                        s is RecognitionUiState.Saving ||
                        s is RecognitionUiState.Discarding
                }
            }
            derived.asStateFlow()
        }

    private val _events = Channel<UiEvent>(capacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: Flow<UiEvent> = _events.receiveAsFlow()

    private fun emit(event: UiEvent) {
        _events.trySend(event)
    }

    /**
     * §4.3：启动恢复补偿。扫描所有非终态 session，按原状态执行幂等补偿：
     *
     * - SAVING：查 sourceSessionId 是否已入库；已入库则完成 session，否则回退 Awaiting 重试
     * - DISCARDING：继续删除图片并完成 session（不恢复保存入口）
     * - CAPTURING/RECOGNIZING：转为 FAILED（无法安全续跑 OCR）
     * - AWAITING_CONFIRMATION：正常恢复，无需处理
     *
     * 配合 §4.5 单活跃 session 保证，这里通常只有 0 或 1 条。
     */
    init {
        viewModelScope.launch {
            val nonTerminal = sessions.listAllNonTerminal()
            for (s in nonTerminal) {
                when (s.state) {
                    SessionState.SAVING -> {
                        // 幂等：sourceSessionId 已入库则完成，否则回退待确认
                        val existing = repo.findBySourceSessionId(s.id)
                        if (existing != null) {
                            sessions.markSaved(s.id)
                            sessions.delete(s.id)
                        } else {
                            sessions.revertToAwaiting(s.id, "上次保存未完成，请重试")
                        }
                    }
                    SessionState.DISCARDING -> {
                        // 继续完成放弃：删图 + 标记 DISCARDED
                        runCatching { imageStore.deleteOwnedString(s.imageUri) }
                        sessions.markDiscarded(s.id)
                        sessions.delete(s.id)
                    }
                    SessionState.CAPTURING, SessionState.RECOGNIZING -> {
                        // 无法安全续跑 OCR（图片可能在但 OCR 结果丢失），标记 FAILED
                        sessions.markFailed(s.id, "上次识别未完成")
                    }
                    SessionState.AWAITING_CONFIRMATION, SessionState.FAILED -> Unit
                    SessionState.SAVED, SessionState.DISCARDED -> Unit
                }
            }
        }
    }

    /** 串行闸门：同一时刻只允许一个识别任务。 */
    private val recognitionMutex = Mutex()

    fun onImageCaptured(uri: Uri) {
        launchRecognition { processRecognition(uri.toString()) }
    }

    /** §4.6 测试入口：直接传 String，避开 JVM 测试里 Uri 未 mock 的问题。 */
    internal fun onImageCapturedUri(uriString: String) {
        launchRecognition { processRecognition(uriString) }
    }

    fun capturePhotoThenRecognize(capture: suspend () -> Uri) {
        launchRecognition {
            val uri = try {
                capture()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emit(UiEvent.Toast("抓拍失败: ${e.message ?: "未知错误"}"))
                // §4.2：失败时清 transientOverride，UI 回到 Room 映射（Ready）
                transientOverride.value = null
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
                transientOverride.value = null
                return@launchRecognition
            }
            processRecognition(local.toString())
        }
    }

    /** §4.6 测试入口：直接传 String。 */
    internal fun onImagePickedUri(sourceUriString: String) {
        launchRecognition {
            val local = try {
                imageStore.importToLocalString(sourceUriString)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emit(UiEvent.Toast("导入失败: ${e.message ?: "未知错误"}"))
                transientOverride.value = null
                return@launchRecognition
            }
            processRecognition(local)
        }
    }

    private suspend fun processRecognition(imageUri: String) {
        val session = try {
            sessions.createCapturing(imageUri)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(UiEvent.Toast("创建识别任务失败: ${e.message ?: "未知错误"}"))
            transientOverride.value = null
            return
        }
        // session 已持久化，后续状态由 Room Flow 驱动；清 transient 覆盖
        transientOverride.value = null

        // CAPTURING → RECOGNIZING（expected-state）
        if (!sessions.beginRecognizing(session.id)) {
            // session 已被并发终结（极少见），放弃
            runCatching { sessions.markFailed(session.id, "任务被并发终止") }
            return
        }

        val result = try {
            recognizer.recognizeString(imageUri)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(UiEvent.Toast("识别异常: ${e.message ?: "未知错误"}"))
            null
        }

        val candidate = result?.plateNo ?: ""
        val quality = result?.qualityScore ?: 0f
        val err = if (result == null) "未检测到车牌" else PlateValidator.describeError(candidate)
        val updated = sessions.setRecognized(session.id, candidate, quality, err)
        if (!updated) {
            runCatching { sessions.markFailed(session.id, "识别结果写入失败，请重试") }
            emit(UiEvent.Toast("识别结果写入失败，请重试"))
        }
        // Room Flow 会自动把 UI 更新到 AwaitingConfirmation
    }

    /** 串行入口：只有 Ready 时允许新识别；失败任务必须先显式清理，避免旧图片泄漏。 */
    private inline fun launchRecognition(crossinline block: suspend () -> Unit) {
        when (uiState.value) {
            RecognitionUiState.Ready -> Unit
            is RecognitionUiState.Failed -> {
                emit(UiEvent.Toast("请先清除上次失败的识别任务"))
                return
            }
            else -> {
                emit(UiEvent.Toast("请先处理待确认的识别结果"))
                return
            }
        }
        if (!recognitionMutex.tryLock()) {
            emit(UiEvent.Toast("识别已在进行中"))
            return
        }
        // §4.2：进入 transient Capturing；失败路径会清掉它
        transientOverride.value = RecognitionUiState.Capturing
        viewModelScope.launch {
            try {
                block()
            } finally {
                recognitionMutex.unlock()
            }
        }
    }

    /**
     * §4.3/§4.4：用户点"保存"。
     *
     * 不再用 ViewModel 层 CAS——改用 DB 层 expected-state 迁移 AWAITING→SAVING：
     * - 返回 true → 本协程独占保存权；
     * - 返回 false → 已有并发保存在进行，忽略（防重复）。
     *
     * UI 状态由 Room Flow 自动驱动到 Saving（mapSessionToUiState 映射 SAVING→Saving），
     * 保存按钮在 Saving 期间自动禁用。
     */
    fun confirmPending(plateNo: String, note: String?) {
        val current = uiState.value
        val session = (current as? RecognitionUiState.AwaitingConfirmation)?.session ?: return
        val normalized = PlateValidator.normalize(plateNo)
        val err = PlateValidator.describeError(normalized)
        if (err != null) {
            emit(UiEvent.Toast(err))
            return
        }
        viewModelScope.launch {
            // §4.4：DB 层 CAS，AWAITING_CONFIRMATION → SAVING
            if (!sessions.beginSaving(session.id)) {
                emit(UiEvent.Toast("正在保存，请稍候"))
                return@launch
            }
            try {
                // §4.3：事务性确认（插入记录 + 标记 SAVED），幂等——中断重试不重复
                repo.confirmSession(
                    sessionId = session.id,
                    plateNo = normalized,
                    qualityScore = session.qualityScore ?: 0f,
                    imageUri = session.imageUri,
                    note = note,
                )
                sessions.delete(session.id)
                emit(UiEvent.Toast("已保存: $normalized"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                sessions.revertToAwaiting(session.id, "保存失败: ${e.message ?: "未知错误"}")
                emit(UiEvent.Toast("保存失败: ${e.message ?: "未知错误"}"))
            }
        }
    }

    /**
     * §4.3/§4.4：放弃。DB 层 CAS AWAITING→DISCARDING，独占放弃权。
     */
    fun discardPending() {
        val current = uiState.value
        val session = (current as? RecognitionUiState.AwaitingConfirmation)?.session ?: return
        viewModelScope.launch {
            if (!sessions.beginDiscarding(session.id)) {
                return@launch
            }
            try {
                runCatching { imageStore.deleteOwned(Uri.parse(session.imageUri)) }
                sessions.markDiscarded(session.id)
                sessions.delete(session.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                sessions.markFailed(session.id, "放弃失败: ${e.message ?: "未知错误"}")
                emit(UiEvent.Toast("放弃失败: ${e.message ?: "未知错误"}"))
            }
        }
    }

    /** 清理 FAILED session：删除失败任务的私有图片，并把 session 终结。 */
    fun clearFailed() {
        val current = uiState.value
        val session = (current as? RecognitionUiState.Failed)?.session ?: return
        viewModelScope.launch {
            if (!sessions.beginClearingFailed(session.id)) {
                emit(UiEvent.Toast("失败任务状态已变化，请稍后再试"))
                return@launch
            }
            try {
                runCatching { imageStore.deleteOwnedString(session.imageUri) }
                if (sessions.markDiscarded(session.id)) {
                    sessions.delete(session.id)
                    emit(UiEvent.Toast("已清除失败任务"))
                } else {
                    sessions.markFailed(session.id, "清除失败任务时状态异常")
                    emit(UiEvent.Toast("清除失败，请重试"))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                sessions.markFailed(session.id, "清除失败: ${e.message ?: "未知错误"}")
                emit(UiEvent.Toast("清除失败: ${e.message ?: "未知错误"}"))
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

    /**
     * §4.1：Room session → UI 状态的纯映射。任意 SessionState 都有明确 UI 对应，
     * 不再把 SAVING/DISCARDING 错误地映射成 AwaitingConfirmation。
     */
    private fun mapSessionToUiState(session: ActiveSession?): RecognitionUiState = when {
        session == null -> RecognitionUiState.Ready
        session.state == SessionState.CAPTURING -> RecognitionUiState.Capturing
        session.state == SessionState.RECOGNIZING -> RecognitionUiState.Recognizing
        session.state == SessionState.AWAITING_CONFIRMATION ->
            RecognitionUiState.AwaitingConfirmation(session)
        session.state == SessionState.SAVING -> RecognitionUiState.Saving(session)
        session.state == SessionState.DISCARDING -> RecognitionUiState.Discarding(session)
        session.state == SessionState.FAILED ->
            RecognitionUiState.Failed(
                session = session,
                message = session.error ?: "识别任务失败",
                recoverable = true,
            )
        // SAVED / DISCARDED 终态：理论上已被 delete，兜底显示 Ready
        else -> RecognitionUiState.Ready
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
