package com.example.platerecognizer.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.platerecognizer.data.AppDatabase
import com.example.platerecognizer.data.PlateRecord
import com.example.platerecognizer.data.PlateRepository
import com.example.platerecognizer.ocr.PlateRecognizer
import com.example.platerecognizer.util.PlateValidator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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

class PlatesViewModel(app: Application) : AndroidViewModel(app) {

    private val repo: PlateRepository
    private val recognizer = PlateRecognizer(app)

    init {
        val dao = AppDatabase.get(app).plateDao()
        repo = PlateRepository(dao, app)
    }

    val records: StateFlow<List<PlateRecord>> =
        repo.observeAll().stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _events = MutableStateFlow<UiEvent?>(null)
    val events: StateFlow<UiEvent?> = _events.asStateFlow()

    fun consumeEvent() {
        _events.value = null
    }

    /** 抓拍/相册图像得到 URI 后，开始识别。 */
    fun onImageCaptured(uri: Uri) {
        viewModelScope.launch {
            _isProcessing.value = true
            try {
                val result = runCatching { recognizer.recognize(getApplication(), uri) }
                    .getOrElse { e ->
                        _events.value = UiEvent.Toast("识别异常: ${e.message}")
                        null
                    }
                if (result == null) {
                    _events.value = UiEvent.RequestPlateInput(
                        initial = "",
                        confidence = 0f,
                        imageUri = uri,
                        error = "未检测到车牌",
                    )
                } else {
                    val err = PlateValidator.describeError(result.plateNo)
                    if (err == null && result.confidence >= 0.9f) {
                        // 高置信度直接入库
                        repo.add(result.plateNo, result.confidence, uri.toString())
                        _events.value = UiEvent.Toast("识别: ${result.plateNo}")
                    } else {
                        // 低置信度或格式不合规 → 要求确认/修正
                        _events.value = UiEvent.RequestPlateInput(
                            initial = result.plateNo,
                            confidence = result.confidence,
                            imageUri = uri,
                            error = err,
                        )
                    }
                }
            } finally {
                _isProcessing.value = false
            }
        }
    }

    /** 对话框确认后保存（可能是首次入库，也可能用户决定保留非法格式）。 */
    fun saveAfterPrompt(plateNo: String, confidence: Float, imageUri: Uri) {
        viewModelScope.launch {
            try {
                repo.add(plateNo, confidence, imageUri.toString())
                _events.value = UiEvent.Toast("已保存: $plateNo")
            } catch (e: Throwable) {
                _events.value = UiEvent.Toast("保存失败: ${e.message}")
            }
        }
    }

    fun applyCorrection(record: PlateRecord, newPlate: String, note: String?) {
        viewModelScope.launch {
            try {
                repo.applyCorrection(record, newPlate, note)
                _events.value = UiEvent.Toast("已修正: ${record.plateNo} → $newPlate")
            } catch (e: Throwable) {
                _events.value = UiEvent.Toast("修正失败: ${e.message}")
            }
        }
    }

    fun delete(record: PlateRecord) {
        viewModelScope.launch { repo.delete(record) }
    }

    fun exportCsv() {
        viewModelScope.launch {
            try {
                val (count, filename) = repo.exportCsv()
                _events.value = UiEvent.Toast("已导出 $count 条到 Download/$filename")
            } catch (e: Throwable) {
                _events.value = UiEvent.Toast("导出失败: ${e.message}")
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        recognizer.close()
    }

    companion object {
        val Factory = object : ViewModelProvider.AndroidViewModelFactory() {
            // Default Application factory 已能用；显式给个名字方便引用
        }
    }
}
