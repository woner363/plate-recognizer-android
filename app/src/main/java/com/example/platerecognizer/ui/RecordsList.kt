package com.example.platerecognizer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.platerecognizer.data.PlateRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 识别记录表（卡片列表）。 */
@Composable
fun RecordsList(
    records: List<PlateRecord>,
    onEdit: (PlateRecord) -> Unit,
    onDelete: (PlateRecord) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (records.isEmpty()) {
        Text(
            "暂无记录。点击下方相机按钮抓拍。",
            modifier = modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(records, key = { it.id }) { r ->
            RecordCard(r, onEdit = onEdit, onDelete = onDelete)
        }
    }
}

private val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

@Composable
private fun RecordCard(
    r: PlateRecord,
    onEdit: (PlateRecord) -> Unit,
    onDelete: (PlateRecord) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        r.plateNo,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    if (r.corrected) {
                        Text(
                            "  ✓已修正",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Text(
                    "时间：${df.format(Date(r.capturedAt))}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "置信度：${"%.2f".format(r.confidence)}",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (!r.note.isNullOrBlank()) {
                    Text(
                        "备注：${r.note}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
            IconButton(onClick = { onEdit(r) }) {
                Icon(Icons.Default.Edit, contentDescription = "修正")
            }
            IconButton(onClick = { onDelete(r) }) {
                Icon(Icons.Default.Delete, contentDescription = "删除")
            }
        }
    }
}
