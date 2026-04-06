package com.dashx.android

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.dashx.android.graphql.generated.type.TrackMessageStatus
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

class NotificationProcessor {
    companion object {
        private val tag = NotificationProcessor::class.java.simpleName
        private val dashXClient = DashX
        private val json = Json { ignoreUnknownKeys = true }

        fun handleClick(context: Context, intent: Intent) {
            val extras = intent.extras
            val payload = resolvePayload(extras)
            if (payload == null) {
                DashXLog.e(tag, "Missing DashX notification payload")
                return
            }

            dashXClient.trackMessage(
                payload.id,
                TrackMessageStatus.OPENED
            )

            if (context !is Activity) {
                DashXLog.e(tag, "'context' must be an instance of Activity class")
                return
            }

            val actionButtonId = extras?.getString(NotificationReceiver.NOTIFICATION_ACTION_BUTTON_ID)
            val navigationAction = payload.resolveNavigationAction(actionButtonId)

            DashX.trackNotificationNavigation(navigationAction, payload.id)

            if (DashX.dispatchNotificationClicked(payload, navigationAction)) {
                return
            }

            when (navigationAction) {
                is NavigationAction.DeepLink -> {
                    DashX.processDeepLink(Uri.parse(navigationAction.url), "notification")
                    try {
                        context.startActivity(urlOpenIntent(navigationAction.url))
                    } catch (e: Throwable) {
                        DashXLog.e(tag, "No Activity found for URL: ${navigationAction.url} – ${e.message}")
                    }
                    return
                }
                is NavigationAction.RichLanding -> {
                    DashX.processDeepLink(Uri.parse(navigationAction.url), "notification")
                    DashXBrowser.openRichLanding(context, navigationAction.url)
                    return
                }
                is NavigationAction.ClickAction -> {
                    launchClickAction(context, navigationAction.action)
                    return
                }
                is NavigationAction.Screen -> {
                    return
                }
                null -> {
                    val clickUrl = resolveClickUrl(payload, extras, actionButtonId)
                    if (clickUrl != null) {
                        DashX.processDeepLink(Uri.parse(clickUrl), "notification")
                        try {
                            context.startActivity(urlOpenIntent(clickUrl))
                        } catch (e: Throwable) {
                            DashXLog.e(tag, "No Activity found for URL: $clickUrl – ${e.message}")
                        }
                        return
                    }
                    val clickAction = resolveClickAction(payload, extras, actionButtonId)
                    if (clickAction != null) {
                        launchClickAction(context, clickAction)
                    }
                }
            }
        }

        private fun resolveClickAction(
            payload: DashXPayload,
            extras: Bundle?,
            actionButtonId: String?
        ): String? {
            if (!actionButtonId.isNullOrEmpty()) {
                val fromButton = payload.actionButtons
                    ?.firstOrNull { it.identifier == actionButtonId }
                    ?.clickAction
                if (fromButton != null) return fromButton
            }
            return payload.clickAction ?: extras?.getString(NotificationReceiver.NOTIFICATION_CLICK_ACTION)
        }

        private fun resolveClickUrl(
            payload: DashXPayload,
            extras: Bundle?,
            actionButtonId: String?
        ): String? {
            if (!actionButtonId.isNullOrEmpty()) {
                return null
            }
            return payload.url ?: extras?.getString(NotificationReceiver.NOTIFICATION_URL)
        }

        private fun resolvePayload(extras: Bundle?): DashXPayload? {
            val jsonStr = extras?.getString(NotificationReceiver.DASHX_PAYLOAD_JSON)
            if (jsonStr != null) {
                return try {
                    json.decodeFromString<DashXPayload>(jsonStr)
                } catch (e: Throwable) {
                    DashXLog.e(tag, "Failed to deserialize DashX payload: ${e.message}")
                    null
                }
            }
            val id = extras?.getString(NotificationReceiver.DASHX_NOTIFICATION_ID) ?: return null
            return DashXPayload(
                id = id,
                url = extras.getString(NotificationReceiver.NOTIFICATION_URL),
                clickAction = extras.getString(NotificationReceiver.NOTIFICATION_CLICK_ACTION),
            )
        }

        private fun launchClickAction(context: Activity, clickAction: String) {
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
