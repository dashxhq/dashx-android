package com.dashx.sdk

import android.app.*
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
import androidx.core.app.NotificationManagerCompat
import com.dashx.graphql.generated.enums.TrackNotificationStatus
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL


@Serializable
data class DashXPayload(
    @SerialName("id") val id: String,
    @SerialName("title") val title: String? = null,
    @SerialName("body") val body: String? = null,
    @SerialName("image") val image: String? = null,
    @SerialName("small_icon") val smallIcon: String? = null,
    @SerialName("large_icon") val largeIcon: String? = null,
    @SerialName("channel_id") val channelId: String? = null,
    @SerialName("sound") val sound: String? = null,
    @SerialName("visibility") val visibility: String? = null,
    @SerialName("notification_count") val notificationCount: String? = null,
    @SerialName("light_settings") val lightSettings: String? = null,
    @SerialName("color") val color: String? = null,
    @SerialName("tag") val tag: String? = null,
    @SerialName("click_action") val clickAction: String? = null,
)

@Serializable
data class LightSettings(
    @SerialName("color") val color: String,
    @SerialName("light_on_duration") val on: Int,
    @SerialName("light_off_duration") val off: Int,
)

class DashXFirebaseMessagingService : FirebaseMessagingService() {
    private val dashXClient = DashXClient.getInstance()
    private val tag = DashXFirebaseMessagingService::class.java.simpleName

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)

        DashXLog.d(tag, "Notification received.")

        if (appInForeground()) {
            DashXLog.d(tag, "App in foreground. Skipping...")
            return
        }

        val dashxDataMap = remoteMessage.data["dashx"]

        if (dashxDataMap != null) {
            DashXLog.d(tag, "Generating DashX notification...")

            var dashXData =
                Json { ignoreUnknownKeys = true }.decodeFromString<DashXPayload>(dashxDataMap)

            val id = dashXData.id
            val title = dashXData.title
            val body = dashXData.body

            if ((title != null) || (body != null)) {
                createNotificationChannel(dashXData)

                val tag = dashXData.tag ?: dashXData.id

                NotificationManagerCompat.from(applicationContext)
                    .notify(tag, 1, createNotification(dashXData))

                dashXClient.trackNotification(id, TrackNotificationStatus.DELIVERED)
            }
        }
    }

    private fun createNotificationChannel(dashXData: DashXPayload) {
        val channelId = dashXData.channelId ?: CHANNEL_ID
        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            )

            dashXData.sound?.let { sound ->
                buildSoundUri(sound)?.let { uri ->
                    val audioAttributesBuilder = AudioAttributes.Builder()
                    audioAttributesBuilder.setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    audioAttributesBuilder.setUsage(AudioAttributes.USAGE_NOTIFICATION)

                    channel.setSound(uri, audioAttributesBuilder.build())
                }
            }

            dashXData.lightSettings?.let { lightSettings ->
                var ls =
                    Json { ignoreUnknownKeys = true }.decodeFromString<LightSettings>(lightSettings)

                channel.enableLights(true)
                channel.lightColor = Color.parseColor(ls.color)
            }

            channel.description = CHANNEL_DESCRIPTION

            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(
        dashXData: DashXPayload
    ): Notification {
        val id = dashXData.id
        val title = dashXData.title
        val body = dashXData.body
        val channelId = dashXData.channelId ?: CHANNEL_ID

        val notificationBuilder = NotificationCompat.Builder(applicationContext, channelId)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)

        dashXData.image?.let { image ->
            DashXLog.d(tag, "Trying to attach image")

            try {
                // Check if it's a URL
                if (URLUtil.isValidUrl(image)) {
                    DashXLog.d(tag, "Image URL is valid")

                    val url = URL(image)
                    val connection = url.openConnection() as HttpURLConnection
                    connection.doInput = true
                    connection.connect()

                    val input: InputStream = connection.inputStream
                    val imageBitmap = BitmapFactory.decodeStream(input)

                    notificationBuilder.setStyle(
                        NotificationCompat.BigPictureStyle().bigPicture(imageBitmap)
                    )
                } else { // Check if it's a resource
                    DashXLog.d(tag, "Invalid Image URL, checking if it's a resource")

                    val resourceId = resources.getIdentifier(image, "drawable", packageName)

                    if (resourceId != 0) { // Valid resource
                        DashXLog.d(tag, "Image is a valid resource")

                        val imageBitmap = BitmapFactory.decodeResource(resources, resourceId)
                        notificationBuilder.setStyle(
                            NotificationCompat.BigPictureStyle().bigPicture(imageBitmap)
                        )
                    } else {
                        DashXLog.e(tag, "Image resource not found for notification $id")
                    }
                }
            } catch (e: java.lang.Exception) {
                DashXLog.e(tag, e.toString())
            }
        }

        dashXData.smallIcon?.let { smallIcon ->
            val resourceId = resources.getIdentifier(smallIcon, "drawable", packageName)
            if (resourceId != 0) {
                notificationBuilder.setSmallIcon(resourceId)
            } else {
                DashXLog.e(tag, "Small icon resource not found for notification $id")
                notificationBuilder.setSmallIcon(getDefaultSmallIcon())
            }
        } ?: run {
            notificationBuilder.setSmallIcon(getDefaultSmallIcon())
        }

        dashXData.largeIcon?.let { largeIcon ->
            try {
                // Check if it's a URL
                if (URLUtil.isValidUrl(largeIcon)) {
                    DashXLog.d(tag, "Large icon URL is valid")

                    val url = URL(largeIcon)
                    val connection = url.openConnection() as HttpURLConnection
                    connection.doInput = true
                    connection.connect()

                    val input: InputStream = connection.inputStream
                    val largeIconBitmap = BitmapFactory.decodeStream(input)

                    notificationBuilder.setLargeIcon(largeIconBitmap)
                } else { // Check if it's a resource
                    DashXLog.d(tag, "Invalid large icon URL, checking if it's a resource")

                    val resourceId = resources.getIdentifier(largeIcon, "drawable", packageName)

                    if (resourceId != 0) { // Valid resource
                        val largeIconBitmap = BitmapFactory.decodeResource(resources, resourceId)
                        notificationBuilder.setLargeIcon(largeIconBitmap)
                    } else {
                        DashXLog.e(tag, "Large icon resource not found for notification $id")
                    }
                }
            } catch (e: java.lang.Exception) {
                DashXLog.e(tag, e.toString())
            }
        }

        dashXData.sound?.let { sound ->
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                buildSoundUri(sound)?.let { uri ->
                    notificationBuilder.setSound(uri)
                }
            }
        }

        dashXData.visibility?.let { visibility ->
            notificationBuilder.setVisibility(visibility.toInt())
        }

        dashXData.notificationCount?.let { count ->
            notificationBuilder.setNumber(count.toInt())
        }

        dashXData.lightSettings?.let { lightSettings ->
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                var ls =
                    Json { ignoreUnknownKeys = true }.decodeFromString<LightSettings>(lightSettings)
                val color = Color.parseColor(ls.color)

                notificationBuilder.setLights(color, ls.on, ls.off)
            }
        }

        dashXData.color?.let { color ->
            val color = Color.parseColor(color)
            notificationBuilder.setColor(color)
        }

        val defaultPendingIntent = getDefaultPendingIntent(id, dashXData.clickAction)
        notificationBuilder.setContentIntent(defaultPendingIntent)

        val dismissedPendingIntent = getDismissedPendingIntent(id)
        notificationBuilder.setDeleteIntent(dismissedPendingIntent)

        return notificationBuilder.build()
    }

    private fun getDefaultPendingIntent(id: String, className: String?): PendingIntent {
        val context = applicationContext
        val pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val intent = getNewBaseIntent()

        intent.putExtra(NotificationReceiver.DASHX_NOTIFICATION_ID, id)

        if (className != null) {
            intent.putExtra(NotificationReceiver.NOTIFICATION_CLICK_ACTION, className)
        }

        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: return PendingIntent.getActivity(context, 1, intent, pendingIntentFlags)

        // Mimic launcher behaviour
        launchIntent.setPackage(null)
        launchIntent.flags =
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED

        return PendingIntent.getActivities(
            context,
            1,
            arrayOf(launchIntent, intent),
            pendingIntentFlags
        )
    }

    private fun getNewBaseIntent(): Intent {
        return Intent(
            applicationContext,
            NotificationReceiver::class.java
        ).addFlags(
            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
        )
    }

    private fun getDismissedPendingIntent(notificationId: String): PendingIntent {
        val dismissIntent =
            Intent(applicationContext, NotificationDismissedReceiver::class.java).apply {
                action = NotificationDismissedReceiver.ACTION_DISMISS_NOTIFICATION
                putExtra(NotificationDismissedReceiver.EXTRA_NOTIFICATION_ID, notificationId)
            }

        return PendingIntent.getBroadcast(
            applicationContext,
            1,
            dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun getDefaultSmallIcon(): Int {
        val ai = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getApplicationInfo(
                packageName,
                PackageManager.ApplicationInfoFlags.of(0)
            )
        } else {
            packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
        }

        return ai.icon
    }

    private fun buildSoundUri(sound: String): Uri? {
        DashXLog.d(tag, "Trying to fetch sound resource...")

        val resourceId = resources.getIdentifier(sound, "raw", packageName)
        if (resourceId != 0) {
            DashXLog.d(tag, "Sound resource found...")

            return Uri.parse(ContentResolver.SCHEME_ANDROID_RESOURCE + "://$packageName/$resourceId")
        } else {
            DashXLog.e(tag, "Sound resource not found.")
        }

        return null
    }

    private fun appInForeground(): Boolean {
        val context = applicationContext
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val runningAppProcesses = activityManager.runningAppProcesses ?: return false
        return runningAppProcesses.any { it.processName == context.packageName && it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND }
    }

    companion object {
        private const val CHANNEL_NAME = "Default Channel"
        private const val CHANNEL_DESCRIPTION = "Default notification channel"
        private const val CHANNEL_ID = "DASHX_NOTIFICATION_CHANNEL"
    }
}
