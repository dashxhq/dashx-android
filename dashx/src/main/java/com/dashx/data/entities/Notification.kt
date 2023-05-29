package com.dashx.data.entities

import androidx.room.Entity
import androidx.room.Dao
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo

@Entity(tableName = "notifications")
data class Notification(
    @PrimaryKey(autoGenerate = true) val id: Int,
    @ColumnInfo(name = "dashx_notification_id")  val dashxNotificationId: String,
    @ColumnInfo(name = "group_id")  val groupId: String? = null,
    @ColumnInfo(name = "collapse_id")  val collapseId: String? = null,
    @ColumnInfo(name = "is_summary")  val isSummary: Boolean = false,
    @ColumnInfo(name = "delivered_at")  val createdAt: String? = null,
    @ColumnInfo(name = "opened_at")  val openedAt: String? = null,
    @ColumnInfo(name = "dismissed_at")  val dismissedAt: String? = null,
    @ColumnInfo(name = "expires_at")  val expiresAt: String? = null,
    val title: String? = null,
    val body: String? = null,
    val payload: String,
    val priority: Int
)
