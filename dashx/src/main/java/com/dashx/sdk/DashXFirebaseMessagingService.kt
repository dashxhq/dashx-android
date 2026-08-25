package com.dashx.android

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ActionButton(
    @SerialName("identifier") val identifier: String,
    @SerialName("label") val label: String,
    @SerialName("icon") val icon: String? = null,
    @SerialName("url") val url: String? = null,
    @SerialName("clickAction") val clickAction: String? = null,
    @SerialName("screenName") val screenName: String? = null,
    @Serializable(with = FlexibleStringMapSerializer::class)
    @SerialName("screenData") val screenData: Map<String, String>? = null,
    @Serializable(with = FlexibleBoolSerializer::class)
    @SerialName("richLanding") val richLanding: Boolean? = null,
)

@Serializable
data class DashXPayload(
    @SerialName("id") val id: String,
    @SerialName("title") val title: String? = null,
    @SerialName("body") val body: String? = null,
    @SerialName("image") val image: String? = null,
    @SerialName("url") val url: String? = null,
    @SerialName("small_icon") val smallIcon: String? = null,
    @SerialName("large_icon") val largeIcon: String? = null,
    @SerialName("channel_id") val channelId: String? = null,
    @SerialName("sound") val sound: String? = null,
    @Serializable(with = FlexibleIntSerializer::class)
    @SerialName("visibility") val visibility: Int? = null,
    @Serializable(with = FlexibleIntSerializer::class)
    @SerialName("notification_count") val notificationCount: Int? = null,
    @SerialName("light_settings") val lightSettings: LightSettings? = null,
    @SerialName("color") val color: String? = null,
    @SerialName("tag") val tag: String? = null,
    @SerialName("click_action") val clickAction: String? = null,
    @SerialName("screen_name") val screenName: String? = null,
    @Serializable(with = FlexibleStringMapSerializer::class)
    @SerialName("screen_data") val screenData: Map<String, String>? = null,
    @SerialName("action_buttons") val actionButtons: List<ActionButton>? = null,
    @Serializable(with = FlexibleBoolSerializer::class)
    @SerialName("rich_landing") val richLanding: Boolean? = null,
)

@Serializable
data class LightSettings(
    @SerialName("color") val color: String,
    @SerialName("light_on_duration") val on: Int,
    @SerialName("light_off_duration") val off: Int,
)

/**
 * Zero-configuration push integration: register nothing and this service handles DashX messages.
 * An app with its own FirebaseMessagingService removes this one from its merged manifest
 * (`tools:node="remove"`) and delegates to [com.dashx.android.push.DashXPush] instead — both paths
 * run the same implementation.
 */
open class DashXFirebaseMessagingService : FirebaseMessagingService() {
    private val tag = DashXFirebaseMessagingService::class.java.simpleName

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        DashXLog.d(tag, "FCM token updated.")
        com.dashx.android.push.DashXPush.onNewToken(token)
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        DashXLog.d(tag, "Notification received.")
        com.dashx.android.push.DashXPush.handleMessage(applicationContext, remoteMessage)
    }
}
