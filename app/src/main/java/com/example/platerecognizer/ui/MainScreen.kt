package com.example.platerecognizer.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.platerecognizer.camera.PhotoCapturer
import com.example.platerecognizer.data.PlateRecord
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: PlatesViewModel = viewModel()) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    val records by vm.records.collectAsState()
    val isProcessing by vm.isProcessing.collectAsState()
    val event by vm.events.collectAsState()

    // 维持单一 ImageCapture 用例引用，供拍照按钮使用
    val imageCapture = remember { ImageCapture.Builder().build() }
    val capturer = remember(imageCapture) { PhotoCapturer(context, imageCapture) }

    // 相册选择器
    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? -> uri?.let(vm::onImageCaptured) }

    // 对话框状态
    var editing by remember { mutableStateOf<PlateRecord?>(null) }
    var promptInfo by remember { mutableStateOf<UiEvent.RequestPlateInput?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }

    // 监听一次性事件
    LaunchedEffect(event) {
        when (val e = event) {
            is UiEvent.Toast -> {
                toast = e.message
            }
            is UiEvent.RequestPlateInput -> promptInfo = e
            null -> Unit
        }
        if (event != null) vm.consumeEvent()
    }

    LaunchedEffect(toast) {
        toast?.let {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_SHORT).show()
            toast = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("车牌识别") })
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize(),
        ) {
            // 摄像头预览
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(4f / 3f),
            ) {
                AndroidView(
                    factory = { ctx ->
                        PreviewView(ctx).also { previewView ->
                            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                            cameraProviderFuture.addListener({
                                val provider = cameraProviderFuture.get()
                                val preview = Preview.Builder().build().also {
                                    it.setSurfaceProvider(previewView.surfaceProvider)
                                }
                                val selector = CameraSelector.DEFAULT_BACK_CAMERA
                                try {
                                    provider.unbindAll()
                                    provider.bindToLifecycle(lifecycleOwner, selector, preview, imageCapture)
                                } catch (e: Exception) {
                                    // 真机/模拟器无相机时不致崩溃
                                    e.printStackTrace()
                                }
                            }, ContextCompat.getMainExecutor(ctx))
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                if (isProcessing) {
                    Box(
                        Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }

            // 操作按钮
            Row(
                Modifier.fillMaxWidth().padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {
                        scope.launch {
                            try {
                                val uri = capturer.takePicture()
                                vm.onImageCaptured(uri)
                            } catch (e: Throwable) {
                                toast = "抓拍失败: ${e.message}"
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(),
                ) {
                    Icon(Icons.Default.PhotoCamera, null)
                    Spacer(Modifier.padding(2.dp))
                    Text("抓拍识别")
                }
                OutlinedButton(
                    onClick = { pickImage.launch("image/*") },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.PhotoLibrary, null)
                    Spacer(Modifier.padding(2.dp))
                    Text("从相册")
                }
                OutlinedButton(
                    onClick = { vm.exportCsv() },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.SaveAlt, null)
                    Spacer(Modifier.padding(2.dp))
                    Text("导出CSV")
                }
            }

            Text(
                "识别记录（共 ${records.size} 条）",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )

            RecordsList(
                records = records,
                onEdit = { editing = it },
                onDelete = { vm.delete(it) },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        }
    }

    // 修正对话框
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

    // 识别后的人工确认对话框
    promptInfo?.let { info ->
        PlateInputDialog(
            title = if (info.error != null) "请确认车牌（${info.error}）" else "请确认识别结果",
            initial = info.initial,
            onDismiss = { promptInfo = null },
            onConfirm = { plate, note ->
                vm.saveAfterPrompt(plate, info.confidence, info.imageUri)
                if (!note.isNullOrBlank()) {
                    // 备注通过随后的更正写入；这里简化处理：保存后立刻取最新记录并打上备注
                    scope.launch {
                        val latest = vm.records.value.firstOrNull()
                        if (latest != null && latest.plateNo == plate) {
                            vm.applyCorrection(latest, plate, note)
                        }
                    }
                }
                promptInfo = null
            },
        )
    }

    DisposableEffect(Unit) {
        onDispose { /* CameraX 由 lifecycleOwner 管理生命周期 */ }
    }
}
