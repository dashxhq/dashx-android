package com.dashx.sdk

import android.app.Activity
import android.content.Intent
import android.os.Bundle

class NotificationReceiver : Activity() {
    companion object {
        const val DASHX_NOTIFICATION_ID = "com.dashx.sdk.DASHX_NOTIFICATION_ID"
        const val NOTIFICATION_CLICK_ACTION = "com.dashx.sdk.NOTIFICATION_CLICK_ACTION"
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
