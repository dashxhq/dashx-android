package com.dashx.android.push

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.dashx.android.DashX
import com.dashx.android.DashXLog
import com.dashx.android.DashXPayload
import com.dashx.android.graphql.generated.type.TrackMessageStatus
import com.google.firebase.messaging.RemoteMessage
import kotlinx.serialization.json.Json

/** Host-supplied final say on whether a DashX notification is displayed. Defaults to display. */
fun interface DashXNotificationDisplayDecider {
    fun shouldDisplay(payload: DashXPayload): Boolean
}

/**
 * Composition-based push handling for apps with their own [com.google.firebase.messaging.FirebaseMessagingService].
 * The built-in [com.dashx.android.DashXFirebaseMessagingService] delegates here too, so both
 * integrations run one implementation. A custom-service host removes the built-in service from its
 * merged manifest:
 *
 * ```xml
 * <service android:name="com.dashx.android.DashXFirebaseMessagingService" tools:node="remove" />
 * ```
 */
object DashXPush {
    private const val TAG = "DashXPush"
    private val json = Json { ignoreUnknownKeys = true }

    /** Chat pushes are identified by screen name; the tag is tray identity only. */
    private const val IN_APP_CHAT_SCREEN_NAME = "in_app_chat_conversation"
    private const val CHAT_TAG_PREFIX = "in_app_chat:"

    @Volatile internal var displayDecider: DashXNotificationDisplayDecider? = null

    fun isDashXMessage(remoteMessage: RemoteMessage): Boolean =
        remoteMessage.data.containsKey("dashx")

    fun onNewToken(token: String) {
        if (DashX.isIdentified) {
            DashX.subscribe(token)
        }
    }

    /**
     * Handles a DashX push end to end: parse, listener dispatch, suppression, display, delivery
     * tracking.
     *
     * Returns `true` when the message was recognized as DashX's AND its handling decision was
     * consumed — whether a notification was displayed or deliberately suppressed. Returns `false`
     * only for messages that are not DashX's, so a host writes
     * `if (!DashXPush.handleMessage(context, message)) { /* own handling */ }` with no risk of
     * double-handling a suppressed chat push.
     */
    fun handleMessage(context: Context, remoteMessage: RemoteMessage): Boolean {
        val dashxDataMap = remoteMessage.data["dashx"] ?: return false

        val dashXData = try {
            json.decodeFromString<DashXPayload>(dashxDataMap)
        } catch (t: Throwable) {
            // Malformed but carrying the dashx key: logged and CONSUMED, never passed to
            // unrelated host handling.
            DashXLog.e(TAG, "Failed to parse DashX payload: ${t.message}")
            return true
        }

        DashX.dispatchNotificationReceived(dashXData)

        val id = dashXData.id
        val title = dashXData.title
        val body = dashXData.body

        if (title == null && body == null) return true

        if (shouldDisplay(dashXData)) {
            NotificationRenderer.ensureDefaultNotificationChannelIfNeeded(context, dashXData)

            // Android's tray identity is (tag, id): the int derives from the tag as well, so
            // same-tag notifications REPLACE instead of stacking — chat pushes tag per
            // conversation, so a burst of replies stays one tray entry. Untagged notifications
            // are unaffected: the tag defaults to the message id, which is what the int was
            // computed from before.
            val notificationTag = dashXData.tag ?: dashXData.id
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                try {
                    NotificationManagerCompat.from(context)
                        .notify(notificationTag, notificationTag.hashCode(),
                            NotificationRenderer.createNotification(context, dashXData))
                } catch (e: SecurityException) {
                    DashXLog.e(TAG, "Cannot post notification: ${e.message}")
                }
            }
        }

        // DELIVERED regardless of display: suppression is a presentation decision, delivery
        // happened either way — and this preserves the pre-refactor tracking semantics.
        DashX.trackMessageOrPersist(context, id, TrackMessageStatus.DELIVERED)
        return true
    }

    /**
     * Client-side defence in depth over the server's read-cursor suppression: a chat push for a
     * conversation the visitor is looking at RIGHT NOW is not displayed. Decided synchronously from
     * one published snapshot — never from mutable chat state. The host decider gets the final say.
     */
    private fun shouldDisplay(payload: DashXPayload): Boolean {
        val conversationId = chatConversationId(payload)
        if (conversationId != null) {
            val snapshot = DashX.pushRuntime.get()
            if (snapshot.isForeground && conversationId in snapshot.visibleConversationIds) {
                DashXLog.d(TAG, "Suppressing chat push: conversation $conversationId is visible")
                return false
            }
        }
        return displayDecider?.let { runCatching { it.shouldDisplay(payload) }.getOrDefault(true) } ?: true
    }

    private fun chatConversationId(payload: DashXPayload): String? {
        if (payload.screenName != IN_APP_CHAT_SCREEN_NAME) return null
        return payload.screenData?.get("conversationId")
    }

    /** Removes a conversation's tray entry — called when its screen opens. */
    internal fun dismissConversation(conversationId: String) {
        val context = DashX.applicationContext() ?: return
        val tag = CHAT_TAG_PREFIX + conversationId
        NotificationManagerCompat.from(context).cancel(tag, tag.hashCode())
    }
}
