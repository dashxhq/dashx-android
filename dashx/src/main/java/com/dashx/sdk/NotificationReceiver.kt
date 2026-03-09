package com.dashx.android

import android.app.Activity
import android.content.Intent
import android.os.Bundle

class NotificationReceiver : Activity() {
    companion object {
        const val DASHX_NOTIFICATION_ID = "com.dashx.android.DASHX_NOTIFICATION_ID"
        const val NOTIFICATION_CLICK_ACTION = "com.dashx.android.NOTIFICATION_CLICK_ACTION"
        const val NOTIFICATION_URL = "com.dashx.android.NOTIFICATION_URL"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NotificationProcessor.handleClick(this, intent)
        finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        finish()
    }
}
