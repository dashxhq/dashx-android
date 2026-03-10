package com.dashx.android

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.dashx.android.graphql.generated.type.TrackMessageStatus

class NotificationProcessor {
    companion object {
        private val tag = NotificationProcessor::class.java.simpleName
        private val dashXClient = DashX

        fun handleClick(context: Context, intent: Intent) {
            val extras = intent.extras
            val notificationId = extras?.getString(NotificationReceiver.DASHX_NOTIFICATION_ID)

            notificationId?.let { id ->
                dashXClient.trackMessage(
                    id,
                    TrackMessageStatus.OPENED
                )
            }

            if (context !is Activity) {
                DashXLog.e(tag, "'context' must be an instance of Activity class")
                return
            }

            val clickAction = extras?.getString(NotificationReceiver.NOTIFICATION_CLICK_ACTION)
            val clickUrl = extras?.getString(NotificationReceiver.NOTIFICATION_URL)

            if (clickUrl != null) {
                val urlIntent = urlOpenIntent(clickUrl)
                context.startActivity(urlIntent)
                return
            }

            if (clickAction != null) {
                // Support both fully-qualified Activity class names and Intent action strings.
                try {
                    val clickActionActivity = Intent(context, Class.forName(clickAction))
                    context.startActivity(clickActionActivity)
                    return
                } catch (_: Throwable) {
                    // Fall through to action-based intent
                }

                val actionIntent = Intent(clickAction).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val resolved = actionIntent.resolveActivity(context.packageManager)
                if (resolved != null) {
                    context.startActivity(actionIntent)
                } else {
                    DashXLog.e(tag, "No Activity found for click_action: $clickAction")
                }
            }
        }

        private fun urlOpenIntent(clickUrl: String): Intent {
            val trimmedStr = clickUrl.trim()

            val intent = if (trimmedStr.startsWith("tel:")) {
                Intent(Intent.ACTION_DIAL, Uri.parse(trimmedStr))
            } else if (trimmedStr.startsWith("mailto:")) {
                Intent(Intent.ACTION_SENDTO, Uri.parse(trimmedStr))
            } else {
                Intent(Intent.ACTION_VIEW, Uri.parse(trimmedStr))
            }

            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return intent
        }
    }
}
