package com.dashx.sdk

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class DashXMessagingService : FirebaseMessagingService() {
    private val tag = DashXMessagingService::class.java.simpleName
    private val dashXClient: DashXClient = DashXClient.instance

    override fun onNewToken(token: String) {
        super.onNewToken(token)
//        dashXClient.setDeviceToken(token)
        DashXLog.d(tag, "onNewToken: $token")
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
//        dashXClient.handleMessage(remoteMessage)
    }
}
