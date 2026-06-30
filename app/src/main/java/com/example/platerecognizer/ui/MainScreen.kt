package com.example.platerecognizer.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.platerecognizer.camera.PhotoCapturer
import com.example.platerecognizer.data.PlateRecord
import kotlinx.coroutines.flow.collect

@Composable
fun MainScreen(vm: PlatesViewModel = viewModel(factory = PlatesViewModel.Factory)) {
    val context = LocalContext.current

    val records by vm.records.collectAsStateWithLifecycle()
    val isProcessing by vm.isProcessing.collectAsStateWithLifecycle()
    val uiState by vm.uiState.collectAsStateWithLifecycle()

    // 派生：当前是否有待确认对话框（AwaitingConfirmation / Saving / Discarding）
    val pendingSession = (uiState as? RecognitionUiState.AwaitingConfirmation)?.session
        ?: (uiState as? RecognitionUiState.Saving)?.session
        ?: (uiState as? RecognitionUiState.Discarding)?.session
    val isSaving = uiState is RecognitionUiState.Saving

    // 维持单一 ImageCapture 用例引用，供拍照按钮使用
    val imageCapture = remember { ImageCapture.Builder().build() }
    val capturer = remember(imageCapture) { PhotoCapturer(context, imageCapture) }

    // 相册选择器（PickVisualMedia 比 GetContent 更现代，且不需要存储权限）
    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? -> uri?.let(vm::onImagePicked) }

    // 导出 CSV 的权限闸门：Q+ 走 MediaStore 不需要权限；pre-Q 才请求 WRITE_EXTERNAL_STORAGE。
    val exportLauncher = rememberExportLauncher(
        onAuthorized = { vm.exportCsv() },
        onDenied = {
            android.widget.Toast.makeText(
                context,
                "缺少存储权限，无法导出",
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        },
    )

    // 修正对话框的本地状态（仅记录列表打开它，不需要持久化）
    var editing by remember { mutableStateOf<PlateRecord?>(null) }
    // §4.10：删除确认对话框
    var deleting by remember { mutableStateOf<PlateRecord?>(null) }

    // 监听 Toast（仅 Toast，确认对话框已迁到 vm.pending）
    LaunchedEffect(Unit) {
        vm.events.collect { e ->
            when (e) {
                is UiEvent.Toast ->
                    android.widget.Toast.makeText(context, e.message, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 16.dp),
        ) {
            AppHeader(
                isProcessing = isProcessing,
                hasPending = pendingSession != null,
                modifier = Modifier.padding(top = 18.dp, bottom = 16.dp),
            )

            CameraPreviewCard(
                imageCapture = imageCapture,
                isProcessing = isProcessing,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(4f / 3f),
            )

            CaptureActions(
                enabled = !isProcessing && pendingSession == null,
                onCapture = { vm.capturePhotoThenRecognize { capturer.takePicture(PLATE_ROI) } },
                onGallery = {
                    pickImage.launch(
                        androidx.activity.result.PickVisualMediaRequest(
                            ActivityResultContracts.PickVisualMedia.ImageOnly,
                        ),
                    )
                },
                modifier = Modifier.padding(top = 14.dp),
            )

            RecordsHeader(
                count = records.size,
                exportEnabled = !isProcessing,
                onExport = { exportLauncher.launch() },
                modifier = Modifier.padding(top = 20.dp, bottom = 10.dp),
            )
            RecordsList(
                records = records,
                onEdit = { editing = it },
                onDelete = { deleting = it },  // §4.10：删除前确认，防误删
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        }
    }

    // 修正对话框（列表里 ✏️ 入口）
    editing?.let { rec ->
        PlateInputDialog(
            title = "修正车牌",
            initial = rec.plateNo,
            onDismiss = { editing = null },
            onConfirm = { newPlate, note ->
                vm.applyCorrection(rec, newPlate, note)
                editing = null
            },
        )
    }

    // 识别后的人工确认对话框——由 vm.uiState 驱动，session 持久化到 Room，旋转/重建/进程恢复后仍可见
    pendingSession?.let { p ->
        PlateInputDialog(
            title = if (p.error != null) "请确认车牌（${p.error}）" else "请确认识别结果",
            initial = p.candidate ?: "",
            onDismiss = { vm.discardPending() },
            onConfirm = { plate, note -> vm.confirmPending(plate, note) },
            dismissEnabled = !isSaving,
        )
    }

    // §4.10：删除确认，防止误删（删除会同时清掉图片，不可撤销）
    deleting?.let { rec ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { deleting = null },
            title = { androidx.compose.material3.Text("删除记录？") },
            text = { androidx.compose.material3.Text("将删除「${rec.plateNo}」及其关联图片，无法撤销。") },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        vm.delete(rec)
                        deleting = null
                    },
                ) { androidx.compose.material3.Text("删除", color = androidx.compose.ui.graphics.Color(0xFFBA1A1A)) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { deleting = null }) {
                    androidx.compose.material3.Text("取消")
                }
            },
        )
    }
}

/**
 * 导出 CSV 时的存储权限闸门：
 * - Android 10+ (Q)：MediaStore 不需要 WRITE_EXTERNAL_STORAGE，直接执行；
 * - Android 9 及以下：检查/请求 WRITE_EXTERNAL_STORAGE，授予后再执行。
 *
 * 返回的 [ExportLauncher.launch] 在按钮 onClick 中调用即可。
 */
private class ExportLauncher(val launch: () -> Unit)

@Composable
private fun rememberExportLauncher(
    onAuthorized: () -> Unit,
    onDenied: () -> Unit,
): ExportLauncher {
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) onAuthorized() else onDenied() }

    return remember(permissionLauncher) {
        ExportLauncher {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                onAuthorized()
            } else {
                val perm = Manifest.permission.WRITE_EXTERNAL_STORAGE
                if (ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED) {
                    onAuthorized()
                } else {
                    permissionLauncher.launch(perm)
                }
            }
        }
    }
}
