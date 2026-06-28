package com.example.platerecognizer.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.platerecognizer.util.PlateValidator

/** 车牌确认与修正：把结果、校验和备注放在一个清晰的决策面板里。 */
@Composable
fun PlateInputDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (plate: String, note: String?) -> Unit,
) {
    var plate by remember(initial) { mutableStateOf(initial) }
    var note by remember { mutableStateOf("") }

    val error = PlateValidator.describeError(plate)
    val normalized = PlateValidator.normalize(plate)

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Column {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "确认后将保存到本机记录",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Normal,
                )
            }
        },
        text = {
            Column(Modifier.fillMaxWidth()) {
                PlatePreview(normalized.ifBlank { "等待输入" }, isValid = error == null)
                Spacer(Modifier.height(18.dp))
                OutlinedTextField(
                    value = plate,
                    onValueChange = { plate = it.uppercase() },
                    label = { Text("车牌号") },
                    placeholder = { Text("例如：京A12345") },
                    isError = error != null && plate.isNotBlank(),
                    supportingText = {
                        Text(
                            text = when {
                                plate.isBlank() -> "输入 7 或 8 位车牌号"
                                error != null -> error
                                else -> "格式校验通过"
                            },
                        )
                    },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("备注") },
                    placeholder = { Text("可选，例如停车位置") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                enabled = normalized.isNotEmpty(),
                shape = RoundedCornerShape(14.dp),
                onClick = { onConfirm(normalized, note.ifBlank { null }) },
            ) {
                Text("保存记录")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun PlatePreview(plate: String, isValid: Boolean) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = if (isValid) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
    ) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 13.dp)) {
            Text(
                text = plate,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.4.sp,
                color = if (isValid) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
