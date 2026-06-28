package com.example.platerecognizer.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.platerecognizer.camera.PhotoCapturer
import com.example.platerecognizer.data.PlateRecord

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: PlatesViewModel = viewModel(factory = PlatesViewModel.Factory)) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val records by vm.records.collectAsState()
    val isProcessing by vm.isProcessing.collectAsState()

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

    // 对话框状态
    var editing by remember { mutableStateOf<PlateRecord?>(null) }
    var promptInfo by remember { mutableStateOf<UiEvent.RequestPlateInput?>(null) }

    // 监听一次性事件（Channel → 不会丢、不会因相同值被去重）
    LaunchedEffect(Unit) {
        vm.events.collect { e ->
            when (e) {
                is UiEvent.Toast ->
                    android.widget.Toast.makeText(context, e.message, android.widget.Toast.LENGTH_SHORT).show()
                is UiEvent.RequestPlateInput -> promptInfo = e
            }
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
            // 摄像头预览 + 车牌取景框
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(4f / 3f),
            ) {
                var cameraError by remember { mutableStateOf<String?>(null) }

                AndroidView(
                    factory = { ctx ->
                        PreviewView(ctx).also { previewView ->
                            previewView.scaleType = PreviewView.ScaleType.FILL_CENTER
                            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                            cameraProviderFuture.addListener({
                                try {
                                    val provider = cameraProviderFuture.get()
                                    val preview = Preview.Builder().build().also {
                                        it.setSurfaceProvider(previewView.surfaceProvider)
                                    }
                                    val selector = CameraSelector.DEFAULT_BACK_CAMERA

                                    // PreviewView 第一次进 onPreDraw 时 viewPort 才非空——
                                    // 用 post 把绑定推到布局完成之后，确保 ImageCapture
                                    // 与 Preview 共用同一个 viewPort（所见即所得裁剪）。
                                    previewView.post post@{
                                        try {
                                            provider.unbindAll()
                                            val viewPort = previewView.viewPort
                                            if (viewPort != null) {
                                                val group = UseCaseGroup.Builder()
                                                    .addUseCase(preview)
                                                    .addUseCase(imageCapture)
                                                    .setViewPort(viewPort)
                                                    .build()
                                                provider.bindToLifecycle(lifecycleOwner, selector, group)
                                            } else {
                                                // 极少数情况下 viewPort 仍为空（PreviewView 未完成首帧），
                                                // 退化绑定，至少不崩。
                                                provider.bindToLifecycle(
                                                    lifecycleOwner, selector, preview, imageCapture,
                                                )
                                            }
                                        } catch (e: Exception) {
                                            cameraError = "相机绑定失败：${e.message ?: "未知错误"}"
                                            e.printStackTrace()
                                        }
                                    }
                                } catch (e: Exception) {
                                    cameraError = "相机不可用：${e.message ?: "未知错误"}"
                                    e.printStackTrace()
                                }
                            }, ContextCompat.getMainExecutor(ctx))
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                // 取景框遮罩——固定比例 [PLATE_ROI]，让用户对齐再拍
                PlateViewfinder(modifier = Modifier.fillMaxSize())
                cameraError?.let { msg ->
                    Box(
                        Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            msg,
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
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
                    onClick = { vm.capturePhotoThenRecognize { capturer.takePicture(PLATE_ROI) } },
                    enabled = !isProcessing,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(),
                ) {
                    Icon(Icons.Default.PhotoCamera, null)
                    Spacer(Modifier.padding(2.dp))
                    Text("抓拍识别")
                }
                OutlinedButton(
                    onClick = {
                        pickImage.launch(
                            androidx.activity.result.PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly,
                            ),
                        )
                    },
                    enabled = !isProcessing,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.PhotoLibrary, null)
                    Spacer(Modifier.padding(2.dp))
                    Text("从相册")
                }
                OutlinedButton(
                    onClick = { exportLauncher.launch() },
                    enabled = !isProcessing,
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
                // note 一并传给 saveAfterPrompt，避免事后再 update 触发 corrected=true
                vm.saveAfterPrompt(plate, info.confidence, info.imageUri, note)
                promptInfo = null
            },
        )
    }
}

/**
 * 取景框相对 PreviewView 的归一化区域。
 *
 * 取宽 80%、按 GA 36-2018 的 440×140 (3.143:1) 算高度，垂直居中。
 * 这个比例同时兼容新能源 480×140 (3.43:1)，用户略微远拍即可对齐。
 *
 * PreviewView 自身是 4:3，所以 ROI 物理高度 = 0.80 * width / 3.143 / (4/3) * 1
 * 简化后：高度归一化值 ≈ 0.191；y 从中央偏一点（约 0.4）开始。
 */
internal val PLATE_ROI: PhotoCapturer.PreviewRoi = PhotoCapturer.PreviewRoi(
    x = 0.10f,
    // PreviewView 高 = 容器宽 / (4/3) ；ROI 物理高 = 容器宽 * 0.80 / 3.143
    // 故 ROI 占容器高度比例 = (0.80/3.143) / (1/(4/3)) = 0.80 * (4/3) / 3.143 ≈ 0.339
    // 实际 ROI 高度归一化 = 物理 ROI 高 / PreviewView 高
    // PreviewView 物理高 = 容器宽 * 3/4 ；ROI 物理高 = 容器宽 * 0.80/3.143
    // ROI 归一化 h = (0.80/3.143) / (3/4) = 0.80 * 4 / (3.143 * 3) ≈ 0.339
    y = 0.5f - 0.339f / 2f,
    w = 0.80f,
    h = 0.339f,
)

/** 在预览上画车牌取景框：半透明遮罩 + 实线边框 + 四角强调线 + 提示文字。 */
@Composable
private fun PlateViewfinder(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val full = Size(size.width, size.height)
        val x = PLATE_ROI.x * full.width
        val y = PLATE_ROI.y * full.height
        val w = PLATE_ROI.w * full.width
        val h = PLATE_ROI.h * full.height

        // 1) 取景框外画 50% 黑色遮罩——用 4 个矩形把 ROI 之外涂掉
        val mask = Color(0x88000000)
        drawRect(mask, topLeft = Offset(0f, 0f), size = Size(full.width, y))                              // 顶
        drawRect(mask, topLeft = Offset(0f, y + h), size = Size(full.width, full.height - y - h))         // 底
        drawRect(mask, topLeft = Offset(0f, y), size = Size(x, h))                                        // 左
        drawRect(mask, topLeft = Offset(x + w, y), size = Size(full.width - x - w, h))                    // 右

        // 2) 实线边框
        drawRect(
            color = Color.White,
            topLeft = Offset(x, y),
            size = Size(w, h),
            style = Stroke(width = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))),
        )

        // 3) 四角强调线
        val corner = w.coerceAtMost(h) * 0.12f
        val cornerStroke = Stroke(width = 5f)
        val accent = Color(0xFFFFD54F)
        // 左上
        drawLine(accent, Offset(x, y), Offset(x + corner, y), strokeWidth = cornerStroke.width)
        drawLine(accent, Offset(x, y), Offset(x, y + corner), strokeWidth = cornerStroke.width)
        // 右上
        drawLine(accent, Offset(x + w, y), Offset(x + w - corner, y), strokeWidth = cornerStroke.width)
        drawLine(accent, Offset(x + w, y), Offset(x + w, y + corner), strokeWidth = cornerStroke.width)
        // 左下
        drawLine(accent, Offset(x, y + h), Offset(x + corner, y + h), strokeWidth = cornerStroke.width)
        drawLine(accent, Offset(x, y + h), Offset(x, y + h - corner), strokeWidth = cornerStroke.width)
        // 右下
        drawLine(accent, Offset(x + w, y + h), Offset(x + w - corner, y + h), strokeWidth = cornerStroke.width)
        drawLine(accent, Offset(x + w, y + h), Offset(x + w, y + h - corner), strokeWidth = cornerStroke.width)
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
