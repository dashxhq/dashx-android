package com.dashx.sdk

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

data class DashXPayload(
    @SerializedName("id") val id: String,
    @SerializedName("title") val title: String?,
    @SerializedName("body") val body: String?,
    @SerializedName("image") val image: String?,
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
                createNotificationChannel()

                NotificationManagerCompat.from(this)
                    .notify(id, 1, createNotification(dashXData))
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
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
        val title = dashXData.title
        val body = dashXData.body
        val image = dashXData.image

        val pendingIntent = getNewPendingIntent()

        val notificationBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(getSmallIcon())
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        if (image != null) {
            try {
                val url = URL(image)
                val connection = url.openConnection() as HttpURLConnection
                connection.doInput = true
                connection.connect()
                val input: InputStream = connection.inputStream
                val imageBitmap = BitmapFactory.decodeStream(input)

                notificationBuilder.setStyle(
                    NotificationCompat.BigPictureStyle().bigPicture(imageBitmap)
                )
            } catch (e: java.lang.Exception) {
                DashXLog.e(tag, e.toString())
            }
        }

        return notificationBuilder.build()
    }

    private fun getNewPendingIntent(): PendingIntent {
        val context = getApplicationContext()
        val pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        val intent = getNewBaseIntent()

        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)

        if (launchIntent == null) {
            return PendingIntent.getActivity(context, 1, intent, pendingIntentFlags)
        }

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

    private fun getSmallIcon(): Int {
        val context = getApplicationContext()
        val ai = context.getPackageManager()
            .getApplicationInfo(context.getPackageName(), PackageManager.GET_META_DATA)

        var smallIconInt: Int

        try {
            val smallIcon = ai.metaData.get(DX_NOTIFICATION_ICON)

            if (smallIcon == null) {
                throw IllegalArgumentException()
            }

            smallIconInt = context.getResources()
                .getIdentifier(smallIcon as String, "drawable", context.getPackageName())

            if (smallIconInt == 0) {
                throw IllegalArgumentException()
            }
        } catch (e: IllegalArgumentException) {
            smallIconInt = ai.icon
        }

        return smallIconInt
    }

    fun appInForeground(): Boolean {
        val context = getApplicationContext()
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val runningAppProcesses = activityManager.runningAppProcesses ?: return false
        return runningAppProcesses.any { it.processName == context.packageName && it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND }
    }

    companion object {
        private const val CHANNEL_NAME = "DashX Notification"
        private const val CHANNEL_DESCRIPTION = "DashX's default notification channel"
        private const val CHANNEL_ID = "DASHX_NOTIFICATION_CHANNEL"
        private const val DX_NOTIFICATION_ICON = "DX_NOTIFICATION_ICON"
    }
}
