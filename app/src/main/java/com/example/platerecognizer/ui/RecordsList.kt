package com.example.platerecognizer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.platerecognizer.data.PlateRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** 轻量记录流：信息密度足够，但不把每一条记录做成“表格”。 */
@Composable
fun RecordsList(
    records: List<PlateRecord>,
    onEdit: (PlateRecord) -> Unit,
    onDelete: (PlateRecord) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (records.isEmpty()) {
        EmptyRecords(modifier)
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(records, key = { it.id }) { record ->
            RecordCard(record, onEdit = onEdit, onDelete = onDelete)
        }
    }
}

@Composable
private fun EmptyRecords(modifier: Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 32.dp, vertical = 24.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(56.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.PhotoCamera,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Text(
                text = "还没有识别记录",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "对准车牌拍摄，结果会安全保存在本机",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RecordCard(
    record: PlateRecord,
    onEdit: (PlateRecord) -> Unit,
    onDelete: (PlateRecord) -> Unit,
) {
    val locale = Locale.getDefault()
    val note = record.note
    val capturedTime = remember(record.capturedAt, locale) {
        SimpleDateFormat("MM-dd  HH:mm", locale).format(Date(record.capturedAt))
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Column(Modifier.padding(start = 16.dp, top = 14.dp, end = 8.dp, bottom = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = record.plateNo,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(5.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = capturedTime,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (record.corrected) {
                            Spacer(Modifier.width(8.dp))
                            StatusBadge("已修正")
                        }
                    }
                }

                QualityBadge(record.confidence)
                IconButton(onClick = { onEdit(record) }) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "修正 ${record.plateNo}",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { onDelete(record) }) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = "删除 ${record.plateNo}",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }

            if (!note.isNullOrBlank()) {
                HorizontalDivider(
                    modifier = Modifier.padding(top = 10.dp, end = 8.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                Text(
                    text = note,
                    modifier = Modifier.padding(top = 10.dp, end = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 候选质量徽标。§4.2：这不是 OCR 字符识别概率，而是格式合法性 + 长度常见度的
 * 启发式评分，仅用于提示"这条记录当时质量如何"。用"高/中/低"档位而非精确百分比，
 * 避免误导用户以为是模型置信度。
 */
@Composable
private fun QualityBadge(qualityScore: Float) {
    val level = when {
        qualityScore >= 0.9f -> QualityLevel.HIGH
        qualityScore >= 0.5f -> QualityLevel.MID
        else -> QualityLevel.LOW
    }
    Surface(
        shape = CircleShape,
        color = when (level) {
            QualityLevel.HIGH -> MaterialTheme.colorScheme.tertiaryContainer
            QualityLevel.MID -> MaterialTheme.colorScheme.secondaryContainer
            QualityLevel.LOW -> MaterialTheme.colorScheme.errorContainer
        },
    ) {
        Text(
            text = level.label,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = when (level) {
                QualityLevel.HIGH -> MaterialTheme.colorScheme.onTertiaryContainer
                QualityLevel.MID -> MaterialTheme.colorScheme.onSecondaryContainer
                QualityLevel.LOW -> MaterialTheme.colorScheme.onErrorContainer
            },
        )
    }
}

private enum class QualityLevel(val label: String) {
    HIGH("质量高"), MID("质量中"), LOW("质量低")
}

@Composable
private fun StatusBadge(text: String) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}
