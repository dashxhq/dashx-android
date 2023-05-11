package com.dashx.sdk

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.dashx.graphql.generated.enums.TrackNotificationStatus

class NotificationReceiver : Activity() {
    private val dashXClient = DashXClient.getInstance()

    companion object {
        private val tag = NotificationReceiver::class.java.simpleName
        const val DASHX_NOTIFICATION_ID = "com.dashx.sdk.DASHX_NOTIFICATION_ID"
        const val NOTIFICATION_CLICK_ACTION = "com.dashx.sdk.NOTIFICATION_CLICK_ACTION"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val extras = intent.extras
        val notificationId = extras?.getString(DASHX_NOTIFICATION_ID)
        val clickAction = extras?.getString(NOTIFICATION_CLICK_ACTION)

        notificationId?.let { id ->  dashXClient.trackNotification(id, TrackNotificationStatus.OPENED)}

        if (clickAction != null) {
            val clickActionActivity = Intent(this, Class.forName(clickAction))
            startActivity(clickActionActivity)
        }

        finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        finish()
    }
}
