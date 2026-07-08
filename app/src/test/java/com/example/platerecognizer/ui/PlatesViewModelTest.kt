package com.example.platerecognizer.ui

import android.net.Uri
import com.example.platerecognizer.data.ActiveSession
import com.example.platerecognizer.data.PlateRecord
import com.example.platerecognizer.data.SessionState
import com.example.platerecognizer.domain.CsvExporter
import com.example.platerecognizer.domain.ManagedImageStore
import com.example.platerecognizer.domain.PlateRecords
import com.example.platerecognizer.domain.RecognitionEngine
import com.example.platerecognizer.domain.RecognitionSessions
import com.example.platerecognizer.ocr.Recognition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

/**
 * §4.6：PlatesViewModel 状态机核心路径 JVM 测试。
 *
 * 用 fake 实现 4 个 domain 接口 + CsvExporter，无需 Android Context / Robolectric。
 * 验证 P0（单一状态源）与 P1（失败恢复、防重复保存）回归。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlatesViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before fun setup() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun failure_in_capture_resets_to_Ready_not_stuck_in_Capturing() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.capturePhotoThenRecognize { throw RuntimeException("相机被占用") }
        assertTrue(
            "期望 Ready，实际 ${vm.uiState.value}",
            vm.uiState.value is RecognitionUiState.Ready,
        )
    }

    @Test fun failure_in_import_resets_to_Ready() = runTest(dispatcher) {
        val imageStore = FakeImageStore(shouldFailImport = true)
        val vm = newViewModel(imageStore = imageStore)
        vm.onImagePickedUri("content://media/external/123")
        assertTrue(
            "导入失败后应回 Ready，实际 ${vm.uiState.value}",
            vm.uiState.value is RecognitionUiState.Ready,
        )
    }

    @Test fun successful_recognition_reaches_AwaitingConfirmation() = runTest(dispatcher) {
        val recognizer = FakeRecognizer(Recognition("京A12345", 0.9f))
        val sessions = FakeSessions()
        val vm = newViewModel(recognizer = recognizer, sessions = sessions)
        vm.onImageCapturedUri("file:///tmp/test.jpg")

        val state = vm.uiState.value
        assertTrue("期望 AwaitingConfirmation，实际 $state", state is RecognitionUiState.AwaitingConfirmation)
        assertEquals("京A12345", (state as RecognitionUiState.AwaitingConfirmation).session.candidate)
    }

    @Test fun rapid_double_confirm_produces_only_one_record() = runTest(dispatcher) {
        val recognizer = FakeRecognizer(Recognition("京A12345", 0.9f))
        val records = FakeRecords()
        val vm = newViewModel(recognizer = recognizer, records = records)
        vm.onImageCapturedUri("file:///tmp/test.jpg")

        // 连续两次确认
        vm.confirmPending("京A12345", null)
        vm.confirmPending("京A12345", null)

        assertEquals("§4.3/§4.4：连点保存只产生一条记录", 1, records.added.size)
    }

    @Test fun illegal_plate_rejected_at_confirm() = runTest(dispatcher) {
        val recognizer = FakeRecognizer(Recognition("京A12345", 0.9f))
        val records = FakeRecords()
        val vm = newViewModel(recognizer = recognizer, records = records)
        vm.onImageCapturedUri("file:///tmp/test.jpg")

        vm.confirmPending("京AI2345", null)  // 序号位含 I

        assertEquals("非法车牌不应入库", 0, records.added.size)
    }

    @Test fun startup_recognizing_session_becomes_Failed_with_error() = runTest(dispatcher) {
        val sessions = FakeSessions(
            initial = activeSession(SessionState.RECOGNIZING, error = null),
        )
        val vm = newViewModel(sessions = sessions)

        val state = vm.uiState.value
        assertTrue("期望 Failed，实际 $state", state is RecognitionUiState.Failed)
        assertEquals("上次识别未完成", (state as RecognitionUiState.Failed).message)
    }

    @Test fun failed_setRecognized_marks_Failed_not_stuck_in_Recognizing() = runTest(dispatcher) {
        val recognizer = FakeRecognizer(Recognition("京A12345", 0.9f))
        val sessions = FakeSessions(failSetRecognized = true)
        val vm = newViewModel(recognizer = recognizer, sessions = sessions)

        vm.onImageCapturedUri("file:///tmp/test.jpg")

        val state = vm.uiState.value
        assertTrue("期望 Failed，实际 $state", state is RecognitionUiState.Failed)
        assertEquals("识别结果写入失败，请重试", (state as RecognitionUiState.Failed).message)
    }

    @Test fun clear_failed_deletes_image_and_returns_Ready() = runTest(dispatcher) {
        val imageStore = FakeImageStore()
        val sessions = FakeSessions(
            initial = activeSession(
                state = SessionState.FAILED,
                imageUri = "file:///tmp/failed.jpg",
                error = "上次识别未完成",
            ),
        )
        val vm = newViewModel(imageStore = imageStore, sessions = sessions)

        vm.clearFailed()

        assertTrue("清理失败任务后应回 Ready，实际 ${vm.uiState.value}", vm.uiState.value is RecognitionUiState.Ready)
        assertEquals(listOf("file:///tmp/failed.jpg"), imageStore.deleted)
    }

    // ===== fakes =====

    private fun newViewModel(
        recognizer: RecognitionEngine = FakeRecognizer(),
        imageStore: ManagedImageStore = FakeImageStore(),
        sessions: RecognitionSessions = FakeSessions(),
        records: PlateRecords = FakeRecords(),
        csvExporter: CsvExporter = FakeCsvExporter(),
    ) = PlatesViewModel(records, recognizer, imageStore, sessions, csvExporter)

    private class FakeRecognizer(val result: Recognition? = null) : RecognitionEngine {
        override suspend fun recognize(uri: Uri): Recognition? = result
        override suspend fun recognizeString(imageUriString: String): Recognition? = result
    }

    private class FakeImageStore(val shouldFailImport: Boolean = false) : ManagedImageStore {
        val deleted = mutableListOf<String>()
        override suspend fun importToLocal(source: Uri): Uri = Uri.parse("file:///tmp/imported.jpg")
        override suspend fun deleteOwned(uri: Uri): Boolean = true
        override suspend fun importToLocalString(sourceUriString: String): String {
            if (shouldFailImport) error("导入失败")
            return "file:///tmp/imported.jpg"
        }
        override suspend fun deleteOwnedString(imageUriString: String): Boolean {
            deleted += imageUriString
            return true
        }
    }

    private class FakeRecords : PlateRecords {
        val added = mutableListOf<String>()
        private val flow = MutableSharedFlow<List<PlateRecord>>(replay = 1)
        private val bySession = mutableMapOf<String, PlateRecord>()
        init { flow.tryEmit(emptyList()) }
        override fun observeAll(): Flow<List<PlateRecord>> = flow.asSharedFlow()
        override suspend fun add(plateNo: String, qualityScore: Float, imageUri: String?, note: String?): Long {
            added += plateNo
            return added.size.toLong()
        }
        override suspend fun confirmSession(
            sessionId: String, plateNo: String, qualityScore: Float,
            imageUri: String?, note: String?,
        ): Long {
            // 模拟真实事务：若已入库则幂等返回
            bySession[sessionId]?.let { return it.id }
            added += plateNo
            val rec = PlateRecord(id = added.size.toLong(), plateNo = plateNo,
                qualityScore = qualityScore, capturedAt = 0L, imageUri = imageUri,
                note = note, sourceSessionId = sessionId)
            bySession[sessionId] = rec
            return rec.id
        }
        override suspend fun findBySourceSessionId(sessionId: String): PlateRecord? = bySession[sessionId]
        override suspend fun applyCorrection(record: PlateRecord, newPlate: String, note: String?) {}
        override suspend fun delete(record: PlateRecord) {}
    }

    private class FakeCsvExporter : CsvExporter {
        override suspend fun exportCsv(): Pair<Int, String> = 0 to "fake.csv"
    }

    private fun activeSession(
        state: SessionState,
        imageUri: String = "file:///tmp/test.jpg",
        candidate: String? = null,
        qualityScore: Float? = null,
        error: String? = null,
    ) = ActiveSession(
        id = UUID.randomUUID().toString(),
        state = state,
        candidate = candidate,
        qualityScore = qualityScore,
        imageUri = imageUri,
        error = error,
        createdAt = System.currentTimeMillis(),
    )

    /** 内存 session 状态机 fake，模拟 expected-state 语义。 */
    private class FakeSessions(
        initial: ActiveSession? = null,
        private val failSetRecognized: Boolean = false,
    ) : RecognitionSessions {
        private var active: ActiveSession? = initial
        private val flow = MutableStateFlow<ActiveSession?>(initial)

        override fun observeActive(): Flow<ActiveSession?> = flow
        override suspend fun createCapturing(imageUri: String): ActiveSession {
            val s = ActiveSession(
                id = UUID.randomUUID().toString(),
                state = SessionState.CAPTURING,
                candidate = null,
                qualityScore = null,
                imageUri = imageUri,
                error = null,
                createdAt = System.currentTimeMillis(),
            )
            active = s
            flow.value = s
            return s
        }
        override suspend fun beginRecognizing(id: String): Boolean = transition(id, SessionState.CAPTURING, SessionState.RECOGNIZING)
        override suspend fun setRecognized(id: String, candidate: String, qualityScore: Float, error: String?): Boolean {
            if (failSetRecognized) return false
            val cur = active ?: return false
            if (cur.state != SessionState.RECOGNIZING) return false
            active = cur.copy(state = SessionState.AWAITING_CONFIRMATION, candidate = candidate, qualityScore = qualityScore, error = error)
            flow.value = active
            return true
        }
        override suspend fun beginSaving(id: String): Boolean = transition(id, SessionState.AWAITING_CONFIRMATION, SessionState.SAVING)
        override suspend fun revertToAwaiting(id: String, error: String?): Boolean {
            val cur = active ?: return false
            if (cur.id != id || cur.state != SessionState.SAVING) return false
            active = cur.copy(state = SessionState.AWAITING_CONFIRMATION, error = error)
            flow.value = active
            return true
        }
        override suspend fun markSaved(id: String): Boolean {
            if (active?.id != id) return false
            active = null
            flow.value = null
            return true
        }
        override suspend fun beginDiscarding(id: String): Boolean = transition(id, SessionState.AWAITING_CONFIRMATION, SessionState.DISCARDING)
        override suspend fun markDiscarded(id: String): Boolean {
            if (active?.id != id || active?.state != SessionState.DISCARDING) return false
            active = null
            flow.value = null
            return true
        }
        override suspend fun markFailed(id: String, error: String?): Boolean {
            val cur = active ?: return false
            if (cur.id != id || cur.state.isTerminal) return false
            active = cur.copy(state = SessionState.FAILED, error = error)
            flow.value = active
            return true
        }
        override suspend fun beginClearingFailed(id: String): Boolean = transition(id, SessionState.FAILED, SessionState.DISCARDING)
        override suspend fun delete(id: String) { active = null; flow.value = null }
        override suspend fun listActiveImageUris(): List<String> = active?.let { listOf(it.imageUri) } ?: emptyList()
        override suspend fun listAllNonTerminal(): List<ActiveSession> = active?.let { listOf(it) } ?: emptyList()
        override suspend fun snapshotActive(): ActiveSession? = active

        private fun transition(id: String, expected: SessionState, next: SessionState): Boolean {
            val cur = active ?: return false
            if (cur.id != id || cur.state != expected) return false
            active = cur.copy(state = next)
            flow.value = active
            return true
        }
    }
}
