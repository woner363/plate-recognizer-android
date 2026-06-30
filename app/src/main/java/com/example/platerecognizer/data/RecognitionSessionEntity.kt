package com.example.platerecognizer.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 一次识别任务的持久化状态机记录（§4.5）。
 *
 * 把"待确认"等非终态从 SavedStateHandle 迁到 Room：
 * - SavedStateHandle 只适合短期 UI 状态恢复，不适合持有文件所有权；
 * - 超 24h 的 pending 图片之前会被孤儿清理误删；现在孤儿清理也读本表，
 *   非终态 session 引用的图片不会被清。
 *
 * 生命周期：CAPTURING → RECOGNIZING → AWAITING_CONFIRMATION → SAVING → SAVED（终态）
 *                                                              → DISCARDING → DISCARDED（终态）
 *                                                              → FAILED（可重试回 AWAITING）
 */
@Entity(tableName = "recognition_sessions")
data class RecognitionSessionEntity(
    @PrimaryKey
    val id: String,

    @ColumnInfo(name = "state")
    val state: SessionState,

    @ColumnInfo(name = "candidate")
    val candidate: String?,

    @ColumnInfo(name = "quality_score")
    val qualityScore: Float?,

    @ColumnInfo(name = "image_uri")
    val imageUri: String,

    @ColumnInfo(name = "error")
    val error: String?,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)

/** Session 状态。存储为字符串，避免 Room enum schema 演化复杂度。 */
enum class SessionState {
    CAPTURING,
    RECOGNIZING,
    AWAITING_CONFIRMATION,
    SAVING,
    SAVED,
    DISCARDING,
    DISCARDED,
    FAILED;

    val isTerminal: Boolean get() = this == SAVED || this == DISCARDED
}
