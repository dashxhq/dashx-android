package com.dashx.sdk

import android.app.Activity
import android.content.Intent
import android.os.Bundle

class NotificationReceiver : Activity() {
    companion object {
        private val tag = NotificationReceiver::class.java.simpleName
        const val NOTIFICATION_CLICK_ACTION = "com.dashx.sdk.NOTIFICATION_CLICK_ACTION"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val extras = intent.extras
        val clickAction = extras?.getString(NOTIFICATION_CLICK_ACTION)

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
