package com.dashx.sdk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.dashx.graphql.generated.enums.TrackNotificationStatus

class NotificationDismissedReceiver : BroadcastReceiver() {
    private val dashXClient = DashXClient.getInstance()

    companion object {
        private val tag = NotificationDismissedReceiver::class.java.simpleName
        const val ACTION_DISMISS_NOTIFICATION = "com.dashx.sdk.ACTION_DISMISS_NOTIFICATION"
        const val EXTRA_NOTIFICATION_ID = "com.dashx.sdk.EXTRA_NOTIFICATION_ID"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_DISMISS_NOTIFICATION) {
            val notificationId = intent.getStringExtra(EXTRA_NOTIFICATION_ID)
            notificationId?.let { id ->  dashXClient.trackNotification(id, TrackNotificationStatus.DISMISSED)}
            DashXLog.d(tag, "Notification dismissed: $notificationId")
        }
    }
}
