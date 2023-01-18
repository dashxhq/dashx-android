package com.dashx.sdk.utils

 import android.content.Context
import android.app.Activity
import android.content.pm.PackageManager
import android.Manifest
import android.os.Build
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.dashx.sdk.DashXLog


class PermissionsUtils {
    companion object {
        private val tag = PermissionsUtils::class.java.simpleName

        const val REQUEST_PERMISSION_MULTIPLE = 0
        const val REQUEST_PERMISSION_POST_NOTIFICATIONS = 1
        const val REQUEST_PERMISSION_CAMERA = 2
        const val REQUEST_PERMISSION_LOCATION = 3
    }

    fun checkAndRequestPermissions(activity: Activity): Boolean {
        val permissionPostNotifications = ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS)
        val permissionCamera = ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA)
        val permissionLocation = ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION)

        val listPermissionsNeeded = ArrayList<String>()

        // Notifications Permission
        if (permissionPostNotifications != PackageManager.PERMISSION_GRANTED) {
            // Show an explanation?
            if (ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.POST_NOTIFICATIONS)) {
                Toast.makeText(activity, "Please allow notifications permission to receive important alerts.", Toast.LENGTH_SHORT).show()
            }

            listPermissionsNeeded.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        // Camera Permission
        if (permissionCamera != PackageManager.PERMISSION_GRANTED) {
            // Show an explanation?
            if (ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)) {
                Toast.makeText(activity, "Camera permission is required for this app to run.", Toast.LENGTH_SHORT).show()
            }

            listPermissionsNeeded.add(Manifest.permission.CAMERA)
        }

        // Location Permission
        if (permissionLocation != PackageManager.PERMISSION_GRANTED) {
            // Show an explanation?
            if (ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.ACCESS_FINE_LOCATION)) {
                Toast.makeText(activity, "Location permission is required for this app to run.", Toast.LENGTH_SHORT).show()
            }

            listPermissionsNeeded.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        if (listPermissionsNeeded.isNotEmpty()) {
            ActivityCompat.requestPermissions(activity, listPermissionsNeeded.toTypedArray(), REQUEST_PERMISSION_MULTIPLE)
            return false
        }

        return true
    }

    fun requestPostNotificationsPermission(activity: Activity) {
        // This is only necessary for API level >= 33 (TIRAMISU)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                if (ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.POST_NOTIFICATIONS)) {
                    ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_PERMISSION_POST_NOTIFICATIONS)
                } else {
                    ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_PERMISSION_POST_NOTIFICATIONS)
                }
            } else {
                DashXLog.d(tag, "requestPostNotificationsPermission(): permission already granted")
            }
        }
    }

    fun requestCameraPermission(activity: Activity) {
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            if (ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)) {
                ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.CAMERA), REQUEST_PERMISSION_CAMERA)
            } else {
                ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.CAMERA), REQUEST_PERMISSION_CAMERA)
            }
        } else {
            DashXLog.d(tag, "requestCameraPermission(): permission already granted")
        }
    }

    fun requestLocationPermission(activity: Activity) {
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            if (ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.ACCESS_FINE_LOCATION)) {
                Toast.makeText(activity, "LOCATION permission is needed to display location info ", Toast.LENGTH_SHORT).show()
                ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), REQUEST_PERMISSION_LOCATION)
                Toast.makeText(activity, "REQUEST LOCATION PERMISSION", Toast.LENGTH_LONG).show()
            } else {
                ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), REQUEST_PERMISSION_LOCATION)
                Toast.makeText(activity, "REQUEST LOCATION PERMISSION", Toast.LENGTH_LONG).show()
            }
        } else {
                DashXLog.d(tag, "requestLocationPermission(): permission already granted")

        }
    }

    fun hasPermissions(context: Context, vararg permissions: String): Boolean {
        if (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && context != null && permissions != null) {
            for (permission in permissions) {
                if (ActivityCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
                    return false
                }
            }
        }

        return true
    }
}
