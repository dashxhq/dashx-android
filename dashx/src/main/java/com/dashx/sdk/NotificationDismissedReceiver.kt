package com.dashx.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.dashx.android.graphql.generated.type.TrackMessageStatus
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

class NotificationDismissedReceiver : BroadcastReceiver() {
    private val dashXClient = DashX
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private val tag = NotificationDismissedReceiver::class.java.simpleName
        const val ACTION_DISMISS_NOTIFICATION = "com.dashx.android.ACTION_DISMISS_NOTIFICATION"
        const val EXTRA_NOTIFICATION_ID = "com.dashx.android.EXTRA_NOTIFICATION_ID"
        const val EXTRA_DASHX_PAYLOAD_JSON = "com.dashx.android.EXTRA_DASHX_PAYLOAD_JSON"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_DISMISS_NOTIFICATION) {
            val notificationId = intent.getStringExtra(EXTRA_NOTIFICATION_ID)
            val payloadJson = intent.getStringExtra(EXTRA_DASHX_PAYLOAD_JSON)
            val payload: DashXPayload? = when {
                payloadJson != null -> try {
                    json.decodeFromString<DashXPayload>(payloadJson)
                } catch (e: Throwable) {
                    DashXLog.e(tag, "Failed to deserialize DashX payload: ${e.message}")
                    null
                }
                else -> null
            } ?: notificationId?.let { DashXPayload(id = it) }

            notificationId?.let { id ->
                dashXClient.trackMessageOrPersist(context, id, TrackMessageStatus.DISMISSED)
            }

            payload?.let { DashX.dispatchNotificationDismissed(it) }

            DashXLog.d(tag, "Notification dismissed: $notificationId")
        }
    }
}
