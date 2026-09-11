package com.dashx.android.push

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.webkit.URLUtil
import androidx.core.app.NotificationCompat
import com.dashx.android.ActionButton
import com.dashx.android.DashX
import com.dashx.android.DashXLog
import com.dashx.android.DashXPayload
import com.dashx.android.NotificationDismissedReceiver
import com.dashx.android.NotificationReceiver
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.serialization.json.Json

/**
 * Builds and channels DashX notifications. Extracted from the Firebase service so the built-in
 * service and a host's own service (via [DashXPush.handleMessage]) share one implementation.
 */
internal object NotificationRenderer {
    private const val TAG = "NotificationRenderer"
    private const val CHANNEL_NAME = "Default Channel"
    private const val CHANNEL_DESCRIPTION = "Default notification channel"
    internal const val CHANNEL_ID = "default_dashx_notification_channel"

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Creates the default DashX channel only when the payload does not specify a channel id. If
     * `channel_id` is present, the host app is responsible for registering that channel. The default
     * channel is created once; if it already exists we leave it unchanged.
     */
    fun ensureDefaultNotificationChannelIfNeeded(context: Context, dashXData: DashXPayload) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (dashXData.channelId != null) return

        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (notificationManager.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT)

        dashXData.sound?.let { sound ->
            buildSoundUri(context, sound)?.let { uri ->
                val audioAttributesBuilder = AudioAttributes.Builder()
                audioAttributesBuilder.setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                audioAttributesBuilder.setUsage(AudioAttributes.USAGE_NOTIFICATION)
                channel.setSound(uri, audioAttributesBuilder.build())
            }
        }

        dashXData.lightSettings?.let { ls ->
            // Remote payload data; an invalid value throws IllegalArgumentException and would abort
            // channel creation before the notification posts.
            try {
                val color = Color.parseColor(ls.color)
                channel.enableLights(true)
                channel.lightColor = color
            } catch (t: Throwable) {
                DashXLog.e(TAG, "Invalid light_settings: ${t.message}")
            }
        }

        channel.description = CHANNEL_DESCRIPTION
        notificationManager.createNotificationChannel(channel)
    }

    fun createNotification(context: Context, dashXData: DashXPayload): Notification {
        val id = dashXData.id
        val channelId = dashXData.channelId ?: CHANNEL_ID

        val notificationBuilder = NotificationCompat.Builder(context, channelId)
            .setContentTitle(dashXData.title)
            .setContentText(dashXData.body)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)

        dashXData.image?.let { image ->
            try {
                if (URLUtil.isValidUrl(image)) {
                    val url = URL(image)
                    val connection = url.openConnection() as HttpURLConnection
                    try {
                        connection.doInput = true
                        connection.connectTimeout = DashX.imageDownloadTimeoutMs
                        connection.readTimeout = DashX.imageDownloadTimeoutMs
                        connection.connect()
                        connection.inputStream.use { input ->
                            val imageBitmap = BitmapFactory.decodeStream(input)
                            notificationBuilder.setStyle(
                                NotificationCompat.BigPictureStyle().bigPicture(imageBitmap)
                            )
                        }
                    } finally {
                        connection.disconnect()
                    }
                } else {
                    val resourceId = context.resources.getIdentifier(image, "drawable", context.packageName)
                    if (resourceId != 0) {
                        val imageBitmap = BitmapFactory.decodeResource(context.resources, resourceId)
                        notificationBuilder.setStyle(
                            NotificationCompat.BigPictureStyle().bigPicture(imageBitmap)
                        )
                    } else {
                        DashXLog.e(TAG, "Image resource not found for notification $id")
                    }
                }
            } catch (e: Exception) {
                DashXLog.e(TAG, e.toString())
            }
        }

        dashXData.smallIcon?.let { smallIcon ->
            val resourceId = context.resources.getIdentifier(smallIcon, "drawable", context.packageName)
            if (resourceId != 0) {
                notificationBuilder.setSmallIcon(resourceId)
            } else {
                DashXLog.e(TAG, "Small icon resource not found for notification $id")
                notificationBuilder.setSmallIcon(getDefaultSmallIcon(context))
            }
        } ?: run {
            notificationBuilder.setSmallIcon(getDefaultSmallIcon(context))
        }

        dashXData.largeIcon?.let { largeIcon ->
            try {
                if (URLUtil.isValidUrl(largeIcon)) {
                    val url = URL(largeIcon)
                    val connection = url.openConnection() as HttpURLConnection
                    try {
                        connection.doInput = true
                        connection.connectTimeout = DashX.imageDownloadTimeoutMs
                        connection.readTimeout = DashX.imageDownloadTimeoutMs
                        connection.connect()
                        connection.inputStream.use { input ->
                            val largeIconBitmap = BitmapFactory.decodeStream(input)
                            notificationBuilder.setLargeIcon(largeIconBitmap)
                        }
                    } finally {
                        connection.disconnect()
                    }
                } else {
                    val resourceId = context.resources.getIdentifier(largeIcon, "drawable", context.packageName)
                    if (resourceId != 0) {
                        val largeIconBitmap = BitmapFactory.decodeResource(context.resources, resourceId)
                        notificationBuilder.setLargeIcon(largeIconBitmap)
                    } else {
                        DashXLog.e(TAG, "Large icon resource not found for notification $id")
                    }
                }
            } catch (e: Exception) {
                DashXLog.e(TAG, e.toString())
            }
        }

        dashXData.sound?.let { sound ->
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                buildSoundUri(context, sound)?.let { uri -> notificationBuilder.setSound(uri) }
            }
        }

        dashXData.visibility?.let { notificationBuilder.setVisibility(it) }
        dashXData.notificationCount?.let { notificationBuilder.setNumber(it) }

        dashXData.lightSettings?.let { ls ->
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                try {
                    val color = Color.parseColor(ls.color)
                    notificationBuilder.setLights(color, ls.on, ls.off)
                } catch (t: Throwable) {
                    DashXLog.e(TAG, "Invalid light_settings: ${t.message}")
                }
            }
        }

        dashXData.color?.let { color ->
            try {
                notificationBuilder.setColor(Color.parseColor(color))
            } catch (_: Throwable) {
                // ignore invalid color
            }
        }

        notificationBuilder.setContentIntent(getDefaultPendingIntent(context, id, dashXData))
        notificationBuilder.setDeleteIntent(getDismissedPendingIntent(context, dashXData))

        dashXData.actionButtons?.forEach { button ->
            val actionPendingIntent = getActionButtonPendingIntent(context, dashXData, button)
            val iconResId = button.icon?.let {
                context.resources.getIdentifier(it, "drawable", context.packageName)
            } ?: 0
            notificationBuilder.addAction(
                NotificationCompat.Action(iconResId, button.label, actionPendingIntent)
            )
        }

        return notificationBuilder.build()
    }

    private fun getDefaultPendingIntent(context: Context, id: String, payload: DashXPayload): PendingIntent {
        val pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val requestCode = id.hashCode()
        val intent = getNewBaseIntent(context)

        intent.putExtra(NotificationReceiver.DASHX_NOTIFICATION_ID, id)
        intent.putExtra(
            NotificationReceiver.DASHX_PAYLOAD_JSON,
            json.encodeToString(DashXPayload.serializer(), payload)
        )
        if (payload.clickAction != null) {
            intent.putExtra(NotificationReceiver.NOTIFICATION_CLICK_ACTION, payload.clickAction)
        }
        if (payload.url != null) {
            intent.putExtra(NotificationReceiver.NOTIFICATION_URL, payload.url)
        }

        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: return PendingIntent.getActivity(context, requestCode, intent, pendingIntentFlags)

        launchIntent.setPackage(null)
        launchIntent.flags =
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED

        return PendingIntent.getActivities(
            context, requestCode, arrayOf(launchIntent, intent), pendingIntentFlags
        )
    }

    private fun getActionButtonPendingIntent(
        context: Context,
        payload: DashXPayload,
        button: ActionButton
    ): PendingIntent {
        val pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val requestCode = payload.id.hashCode() xor button.identifier.hashCode()
        val intent = getNewBaseIntent(context)

        intent.putExtra(NotificationReceiver.DASHX_NOTIFICATION_ID, payload.id)
        intent.putExtra(
            NotificationReceiver.DASHX_PAYLOAD_JSON,
            json.encodeToString(DashXPayload.serializer(), payload)
        )
        intent.putExtra(NotificationReceiver.NOTIFICATION_ACTION_BUTTON_ID, button.identifier)

        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: return PendingIntent.getActivity(context, requestCode, intent, pendingIntentFlags)

        launchIntent.setPackage(null)
        launchIntent.flags =
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED

        return PendingIntent.getActivities(
            context, requestCode, arrayOf(launchIntent, intent), pendingIntentFlags
        )
    }

    private fun getNewBaseIntent(context: Context): Intent {
        return Intent(context, NotificationReceiver::class.java).addFlags(
            Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        )
    }

    private fun getDismissedPendingIntent(context: Context, payload: DashXPayload): PendingIntent {
        val dismissIntent = Intent(context, NotificationDismissedReceiver::class.java).apply {
            action = NotificationDismissedReceiver.ACTION_DISMISS_NOTIFICATION
            putExtra(NotificationDismissedReceiver.EXTRA_NOTIFICATION_ID, payload.id)
            putExtra(
                NotificationDismissedReceiver.EXTRA_DASHX_PAYLOAD_JSON,
                json.encodeToString(DashXPayload.serializer(), payload)
            )
        }
        return PendingIntent.getBroadcast(
            context,
            payload.id.hashCode(),
            dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun getDefaultSmallIcon(context: Context): Int {
        val ai = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getApplicationInfo(
                context.packageName,
                PackageManager.ApplicationInfoFlags.of(0)
            )
        } else {
            context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
        }
        return ai.icon
    }

    private fun buildSoundUri(context: Context, sound: String): Uri? {
        val resourceId = context.resources.getIdentifier(sound, "raw", context.packageName)
        if (resourceId != 0) {
            return Uri.parse(ContentResolver.SCHEME_ANDROID_RESOURCE + "://${context.packageName}/$resourceId")
        }
        DashXLog.e(TAG, "Sound resource not found.")
        return null
    }
}
