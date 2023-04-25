package com.dashx.sdk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class NotificationDismissedReceiver : BroadcastReceiver() {
    companion object {
        private val tag = NotificationDismissedReceiver::class.java.simpleName
        const val ACTION_DISMISS_NOTIFICATION = "com.dashx.sdk.ACTION_DISMISS_NOTIFICATION"
        const val EXTRA_NOTIFICATION_ID = "com.dashx.sdk.EXTRA_NOTIFICATION_ID"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_DISMISS_NOTIFICATION) {
            val notificationId = intent.getStringExtra(EXTRA_NOTIFICATION_ID)
            // Track the dismissed notification and take appropriate action
            DashXLog.d(tag, "Notification dismissed: $notificationId")
        }
    }
}
