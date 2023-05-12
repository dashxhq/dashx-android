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

            notificationId?.let { id ->  dashXClient.trackNotification(id, TrackNotificationStatus.OPENED)}

            if (context !is Activity) {
                DashXLog.e(tag, "'context' must be an instance of Activity class")
                return
            }

            val clickAction = extras?.getString(NotificationReceiver.NOTIFICATION_CLICK_ACTION)
            val clickUrl = extras?.getString(NotificationReceiver.NOTIFICATION_URL)

            if (clickUrl != null) {
                val url = Uri.parse(clickUrl.trim { it <= ' ' })
                val urlIntent = urlOpenIntent(url)
                context.startActivity(urlIntent)
                return
            }

            if (clickAction != null) {
                val clickActionActivity = Intent(context, Class.forName(clickAction))
                context.startActivity(clickActionActivity)
            }
        }

        private fun urlOpenIntent(url: Uri): Intent {
            val intent = Intent(Intent.ACTION_VIEW, url)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return intent
        }
    }
}

