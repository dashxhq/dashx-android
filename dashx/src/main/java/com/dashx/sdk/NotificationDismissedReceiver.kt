package com.dashx.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.dashx.graphql.generated.type.TrackMessageStatus

class NotificationDismissedReceiver : BroadcastReceiver() {
    private val dashXClient = DashX

    companion object {
        private val tag = NotificationDismissedReceiver::class.java.simpleName
        const val ACTION_DISMISS_NOTIFICATION = "com.dashx.android.ACTION_DISMISS_NOTIFICATION"
        const val EXTRA_NOTIFICATION_ID = "com.dashx.android.EXTRA_NOTIFICATION_ID"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_DISMISS_NOTIFICATION) {
            val notificationId = intent.getStringExtra(EXTRA_NOTIFICATION_ID)
            notificationId?.let { id ->
                dashXClient.trackMessage(
                    id,
                    TrackMessageStatus.DISMISSED
                )
            }
            DashXLog.d(tag, "Notification dismissed: $notificationId")
        }
    }
}
