package com.example.platerecognizer.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.platerecognizer.util.PlateValidator

/**
 * 车牌输入/确认对话框。
 * - 自动归一化为大写
 * - 实时显示格式校验错误
 * - 允许用户在错误时仍然保存（弹出二次确认）
 */
@Composable
fun PlateInputDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (plate: String, note: String?) -> Unit,
) {
    var plate by remember { mutableStateOf(initial) }
    var note by remember { mutableStateOf("") }

    val error = PlateValidator.describeError(plate)
    val normalized = PlateValidator.normalize(plate)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Box(Modifier.fillMaxWidth()) {
                Column {
                    OutlinedTextField(
                        value = plate,
                        onValueChange = { plate = it.uppercase() },
                        label = { Text("车牌号") },
                        isError = error != null && plate.isNotBlank(),
                        supportingText = {
                            Text(if (error != null && plate.isNotBlank()) error else "规范化为：$normalized")
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.padding(4.dp))
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it },
                        label = { Text("备注（可选）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = normalized.isNotEmpty(),
                onClick = { onConfirm(normalized, note.ifBlank { null }) },
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
