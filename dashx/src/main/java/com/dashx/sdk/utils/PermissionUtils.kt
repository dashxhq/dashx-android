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
    private val tag = PermissionUtils::class.java.simpleName

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

        if (hasPermissions(activity, permission)) {
            DashXLog.d(tag, "requestPermission(): already granted")
            return
        }

        val permissionName = permission.substringAfterLast(".").lowercase().replaceFirstChar { it.titlecase() }

        if (ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)) {
            val builder = buildAlertDialog(activity)

            with(builder)
            {
                setMessage("$permissionName permission is needed for this app to function properly.")
                setPositiveButton("Proceed", DialogInterface.OnClickListener { dialog, id ->
                    markPermissionAsAskedTwice(activity, permission)
                    ActivityCompat.requestPermissions(activity, arrayOf(permission), requestCode)
                })
                show()
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
                    show()
                }
            } else {
                ActivityCompat.requestPermissions(activity, arrayOf(permission), requestCode)
            }
        }
    }

    private fun requireRuntimePermissions(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
    }

    private fun goToAppSettings(activity: Activity) {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", activity.getPackageName(), null)
        )
        intent.addCategory(Intent.CATEGORY_DEFAULT)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        activity.startActivity(intent)
    }

    private fun hasAskedForPermissionTwice(activity: Activity, permission: String): Boolean {
        return PreferenceManager
            .getDefaultSharedPreferences(activity)
            .getBoolean(permission, false)
    }

    private fun markPermissionAsAskedTwice(activity: Activity, permission: String) {
        PreferenceManager
            .getDefaultSharedPreferences(activity)
            .edit()
            .putBoolean(permission, true)
            .apply()
    }

    private fun buildAlertDialog(activity: Activity): AlertDialog.Builder {
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
