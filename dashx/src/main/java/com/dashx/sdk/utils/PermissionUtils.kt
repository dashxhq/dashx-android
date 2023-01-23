package com.dashx.sdk.utils

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.Manifest
import android.net.Uri
import android.os.Build
import android.preference.PreferenceManager
import android.provider.Settings
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.dashx.sdk.DashXLog

object PermissionUtils {
    const val tag = "PermissionUtils"

    fun requireRuntimePermissions(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
    }

    fun hasPermissions(context: Context, vararg permissions: String): Boolean {
        if (requireRuntimePermissions()) {
            for (permission in permissions) {
                if (ActivityCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
                    return false
                }
            }
        }

        return true
    }

    fun requestPermission(activity: Activity, permission: String, requestCode: Int) {
        if (!requireRuntimePermissions()) {
            DashXLog.d(tag, "no permissions required")
        }

        val permissionName = permission.substringAfterLast(".").lowercase().replaceFirstChar { it.titlecase() }

        if (ContextCompat.checkSelfPermission(activity, permission) != PackageManager.PERMISSION_GRANTED) {
            if (ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)) {
                val builder = buildAlertDialog(activity)

                with(builder)
                {
                    setMessage("$permissionName permission is needed for this app to function properly.")
                    setPositiveButton("Proceed", DialogInterface.OnClickListener { dialog, id ->
                        markPermissionAsAskedTwice(activity, permission)
                        ActivityCompat.requestPermissions(activity, arrayOf(permission), requestCode)
                    })
                    .show()
                }
            } else {
                if (hasAskedForPermissionTwice(activity, permission)) {
                    val builder = buildAlertDialog(activity)

                    with(builder)
                    {
                        setMessage("$permissionName permission is needed for this app to function properly. Go to settings to enable this permission?")
                        setPositiveButton("Proceed", DialogInterface.OnClickListener { dialog, id ->
                            goToAppSettings(activity)
                        })
                        .show()
                    }
                } else {
                    ActivityCompat.requestPermissions(activity, arrayOf(permission), requestCode)
                }
            }
        } else {
            DashXLog.d(tag, "requestPermission(): already granted")
        }
    }

    fun goToAppSettings(activity: Activity) {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", activity.getPackageName(), null)
        )
        intent.addCategory(Intent.CATEGORY_DEFAULT)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        activity.startActivity(intent)
    }

    fun hasAskedForPermissionTwice(activity: Activity, permission: String): Boolean {
        return PreferenceManager
            .getDefaultSharedPreferences(activity)
            .getBoolean(permission, false)
    }

    fun markPermissionAsAskedTwice(activity: Activity, permission: String) {
        PreferenceManager
            .getDefaultSharedPreferences(activity)
            .edit()
            .putBoolean(permission, true)
            .apply()
    }

    fun buildAlertDialog(activity: Activity): AlertDialog.Builder {
        val builder = AlertDialog.Builder(activity)

        with(builder)
        {
            setTitle("Permission needed")
            setCancelable(false)
            setNegativeButton("Cancel", DialogInterface.OnClickListener { dialog, id ->
                dialog.cancel()
            })
        }

        return builder
    }
}
