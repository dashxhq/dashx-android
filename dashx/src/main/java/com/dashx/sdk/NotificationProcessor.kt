package com.dashx.sdk

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.dashx.graphql.generated.enums.TrackNotificationStatus

class NotificationProcessor {
    companion object {
        private val tag = NotificationProcessor::class.java.simpleName
        private val dashXClient = DashXClient.getInstance()

        fun handleClick(context: Context, intent: Intent) {
            val extras = intent.extras
            val notificationId = extras?.getString(NotificationReceiver.DASHX_NOTIFICATION_ID)
            val clickAction = extras?.getString(NotificationReceiver.NOTIFICATION_CLICK_ACTION)

            notificationId?.let { id ->  dashXClient.trackNotification(id, TrackNotificationStatus.OPENED)}

            if (clickAction != null) {
                if (context !is Activity) {
                    DashXLog.e(tag, "'context' must be an instance of Activity class")
                } else {
                    val clickActionActivity = Intent(context, Class.forName(clickAction))
                    context.startActivity(clickActionActivity)
                }
            }
        }
    }

    private fun getOpenURLIntent(url: Uri): Intent {
        val intent = Intent(Intent.ACTION_VIEW, url)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return intent
    }
}

