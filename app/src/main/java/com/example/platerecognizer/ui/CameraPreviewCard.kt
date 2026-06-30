package com.example.platerecognizer.ui

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import com.example.platerecognizer.camera.PhotoCapturer

@Composable
internal fun CameraPreviewCard(
    imageCapture: ImageCapture,
    isProcessing: Boolean,
    modifier: Modifier = Modifier,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var cameraError by remember { mutableStateOf<String?>(null) }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(28.dp),
        color = Color.Black,
        shadowElevation = 8.dp,
    ) {
        Box(Modifier.fillMaxSize()) {
            AndroidView(
                factory = { context ->
                    PreviewView(context).also { previewView ->
                        previewView.scaleType = PreviewView.ScaleType.FILL_CENTER
                        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
                        cameraProviderFuture.addListener({
                            try {
                                val provider = cameraProviderFuture.get()
                                val preview = Preview.Builder().build().also {
                                    it.setSurfaceProvider(previewView.surfaceProvider)
                                }
                                val selector = CameraSelector.DEFAULT_BACK_CAMERA
                                // §4.7：doOnLayout 确保 ViewPort 已就绪再绑定，不永久降级。
                                previewView.doOnLayout {
                                    try {
                                        provider.unbindAll()
                                        val viewPort = previewView.viewPort
                                        val group = UseCaseGroup.Builder()
                                            .addUseCase(preview)
                                            .addUseCase(imageCapture)
                                            .apply { viewPort?.let { vp -> setViewPort(vp) } }
                                            .build()
                                        provider.bindToLifecycle(lifecycleOwner, selector, group)
                                        cameraError = null
                                    } catch (e: Exception) {
                                        cameraError = "相机绑定失败：${e.message ?: "未知错误"}"
                                    }
                                }
                            } catch (e: Exception) {
                                cameraError = "相机不可用：${e.message ?: "未知错误"}"
                            }
                        }, ContextCompat.getMainExecutor(context))
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )

            // §4.7：Composable 移除时解绑相机，释放硬件资源，避免"相机已被占用"。
            val disposeContext = LocalContext.current
            DisposableEffect(disposeContext) {
                onDispose {
                    val providerFuture = ProcessCameraProvider.getInstance(disposeContext)
                    providerFuture.addListener({
                        runCatching { providerFuture.get().unbindAll() }
                    }, ContextCompat.getMainExecutor(disposeContext))
                }
            }

            PlateViewfinder(modifier = Modifier.fillMaxSize())
            CameraStatus(
                isProcessing = isProcessing,
                modifier = Modifier.align(Alignment.TopStart).padding(14.dp),
            )
            CameraHint(
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp),
            )
            cameraError?.let {
                CameraError(message = it, modifier = Modifier.fillMaxSize())
            }

            if (isProcessing) {
                Box(
                    modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.28f)),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp)
                }
            }
        }
    }
}

/** 取景框相对 PreviewView 的归一化区域，物理比例约为 440:140。 */
internal val PLATE_ROI = PhotoCapturer.PreviewRoi(
    x = 0.10f,
    y = 0.5f - 0.339f / 2f,
    w = 0.80f,
    h = 0.339f,
)

@Composable
private fun PlateViewfinder(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val x = PLATE_ROI.x * size.width
        val y = PLATE_ROI.y * size.height
        val w = PLATE_ROI.w * size.width
        val h = PLATE_ROI.h * size.height
        val mask = Color(0x88000000)

        drawRect(mask, size = Size(size.width, y))
        drawRect(mask, Offset(0f, y + h), Size(size.width, size.height - y - h))
        drawRect(mask, Offset(0f, y), Size(x, h))
        drawRect(mask, Offset(x + w, y), Size(size.width - x - w, h))
        drawRect(
            color = Color.White,
            topLeft = Offset(x, y),
            size = Size(w, h),
            style = Stroke(width = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))),
        )

        val corner = w.coerceAtMost(h) * 0.12f
        val accent = Color(0xFFFFD54F)
        val stroke = 5f
        drawLine(accent, Offset(x, y), Offset(x + corner, y), stroke)
        drawLine(accent, Offset(x, y), Offset(x, y + corner), stroke)
        drawLine(accent, Offset(x + w, y), Offset(x + w - corner, y), stroke)
        drawLine(accent, Offset(x + w, y), Offset(x + w, y + corner), stroke)
        drawLine(accent, Offset(x, y + h), Offset(x + corner, y + h), stroke)
        drawLine(accent, Offset(x, y + h), Offset(x, y + h - corner), stroke)
        drawLine(accent, Offset(x + w, y + h), Offset(x + w - corner, y + h), stroke)
        drawLine(accent, Offset(x + w, y + h), Offset(x + w, y + h - corner), stroke)
    }
}

@Composable
private fun CameraStatus(isProcessing: Boolean, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = Color.Black.copy(alpha = 0.48f),
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(7.dp).clip(CircleShape).background(
                    if (isProcessing) Color(0xFFFFD166) else Color(0xFF73E2BA),
                ),
            )
            Spacer(Modifier.width(7.dp))
            Text(
                text = if (isProcessing) "正在识别" else "相机已就绪",
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun CameraHint(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = Color.Black.copy(alpha = 0.55f),
    ) {
        Text(
            text = "将车牌置于框内并保持稳定",
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            color = Color.White.copy(alpha = 0.92f),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun CameraError(message: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(
            Brush.verticalGradient(
                listOf(Color.Black.copy(alpha = 0.55f), Color.Black.copy(alpha = 0.8f)),
            ),
        ),
        contentAlignment = Alignment.Center,
    ) {
        Surface(shape = RoundedCornerShape(18.dp), color = Color.White.copy(alpha = 0.12f)) {
            Column(
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector = Icons.Default.PhotoCamera,
                    contentDescription = null,
                    tint = Color.White,
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    text = message,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
