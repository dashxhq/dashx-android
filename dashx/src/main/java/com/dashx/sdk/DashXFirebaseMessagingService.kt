package com.dashx.sdk

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.webkit.URLUtil
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL


data class DashXPayload(
    @SerializedName("id") val id: String,
    @SerializedName("title") val title: String?,
    @SerializedName("body") val body: String?,
    @SerializedName("image") val image: String?,
    @SerializedName("small_icon") val smallIcon: String?,
    @SerializedName("large_icon") val largeIcon: String?,
    @SerializedName("channel_id") val channelId: String?,
    @SerializedName("sound") val sound: String?,
    @SerializedName("visibility") val visibility: String?,
    @SerializedName("notification_count") val notificationCount: String?,
    @SerializedName("light_settings") val lightSettings: String?,
    @SerializedName("color") val color: String?,
    @SerializedName("click_action") val clickAction: String?,
)

data class LightSettings(
    @SerializedName("color") val color: String,
    @SerializedName("light_on_duration") val on: Int,
    @SerializedName("light_off_duration") val off: Int,
)

class DashXFirebaseMessagingService : FirebaseMessagingService() {
    private val tag = DashXFirebaseMessagingService::class.java.simpleName
    private val notificationReceiverClass: Class<*> = NotificationReceiver::class.java

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

            val gson = Gson()
            var dashXData = gson.fromJson(dashxDataMap, DashXPayload::class.java)

            val id = dashXData.id
            val title = dashXData.title
            val body = dashXData.body

            if ((title != null) || (body != null)) {
                createNotificationChannel(dashXData.channelId)

                NotificationManagerCompat.from(this)
                    .notify(id, 1, createNotification(dashXData))
            }
        }
    }

    private fun createNotificationChannel(channelId: String? = CHANNEL_ID) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            )

            channel.description = CHANNEL_DESCRIPTION

            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun createNotification(
        dashXData: DashXPayload
    ): Notification {
        val id = dashXData.id
        val title = dashXData.title
        val body = dashXData.body
        val channelId = dashXData.channelId ?: CHANNEL_ID

        val pendingIntent = getNewPendingIntent()

        val notificationBuilder = NotificationCompat.Builder(this, channelId)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        dashXData.image?.let { image ->
            try {
                // Check if it's a URL
                if (URLUtil.isValidUrl(image)) {

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
                    val resourceId = resources.getIdentifier(image, "drawable", packageName)

                    if (resourceId != 0) { // Valid resource
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
                DashXLog.e(tag, "Image resource not found for notification $id")
            }
        }

        dashXData.largeIcon?.let { largeIcon ->
            try {
                // Check if it's a URL
                if (URLUtil.isValidUrl(largeIcon)) {

                    val url = URL(largeIcon)
                    val connection = url.openConnection() as HttpURLConnection
                    connection.doInput = true
                    connection.connect()

                    val input: InputStream = connection.inputStream
                    val largeIconBitmap = BitmapFactory.decodeStream(input)

                    notificationBuilder.setLargeIcon(largeIconBitmap)
                } else { // Check if it's a resource
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
            val resourceId = resources.getIdentifier(sound, "raw", packageName)
            if (resourceId != 0) {
                notificationBuilder.setSound(Uri.parse("android.resource://$packageName/resourceId"))
            } else {
                DashXLog.e(tag, "Sound resource not found for notification $id")
            }
        }

        dashXData.visibility?.let { visibility ->
            notificationBuilder.setVisibility(visibility.toInt())
        }

        dashXData.notificationCount?.let { count ->
            notificationBuilder.setNumber(count.toInt())
        }

        dashXData.lightSettings?.let { lightSettings ->
            val gson = Gson()
            var ls = gson.fromJson(lightSettings, LightSettings::class.java)
            val color = Color.parseColor(ls.color)
            notificationBuilder.setLights(color, ls.on, ls.off)
        }

        dashXData.color?.let { color ->
            val color = Color.parseColor(color)
            notificationBuilder.setColor(color)
        }

        return notificationBuilder.build()
    }

    private fun getNewPendingIntent(): PendingIntent {
        val context = applicationContext
        val pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        val intent = getNewBaseIntent()

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
            this,
            notificationReceiverClass
        ).addFlags(
            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
        )
    }

    private fun appInForeground(): Boolean {
        val context = applicationContext
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val runningAppProcesses = activityManager.runningAppProcesses ?: return false
        return runningAppProcesses.any { it.processName == context.packageName && it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND }
    }

    companion object {
        private const val CHANNEL_NAME = "Default Channel"
        private const val CHANNEL_DESCRIPTION = "DashX's default notification channel"
        private const val CHANNEL_ID = "DASHX_NOTIFICATION_CHANNEL"
    }
}
