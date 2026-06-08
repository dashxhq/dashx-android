package com.dashx.android

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle

class DashXActivityLifecycleCallbacks : Application.ActivityLifecycleCallbacks {
    private val dashXClient = DashX
    private var startSession = System.currentTimeMillis().toDouble()

    init {
        dashXClient.trackAppStarted()
    }

    override fun onActivityPaused(activity: Activity) {
        if (!lifecycleTrackingEnabled) {
            return
        }

        dashXClient.trackAppSession(System.currentTimeMillis().toDouble() - startSession)
    }

    override fun onActivityResumed(activity: Activity) {
        if (!lifecycleTrackingEnabled) {
            return
        }

        startSession = System.currentTimeMillis().toDouble()
        dashXClient.trackAppStarted(fromBackground = true)
    }

    override fun onActivityStarted(activity: Activity) {
        if (!screenTrackingEnabled) {
            return
        }

        val packageManager = activity.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getActivityInfo(
                activity.componentName,
                PackageManager.ComponentInfoFlags.of(PackageManager.GET_META_DATA.toLong())
            )
        } else {
            packageManager.getActivityInfo(activity.componentName, PackageManager.GET_META_DATA)
        }
        val activityLabel = info.loadLabel(packageManager)
        dashXClient.screen(activityLabel.toString(), hashMapOf())
    }

    override fun onActivityDestroyed(activity: Activity) {

    }

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {

    }

    override fun onActivityStopped(activity: Activity) {

    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {

    }

    companion object {
        private var dashXActivityLifecycleCallbacks: DashXActivityLifecycleCallbacks? = null
        private var screenTrackingEnabled = false
        private var lifecycleTrackingEnabled = false

        private fun registerCallbacks(context: Context) {
            // Callers may pass an Activity/Service/ContextWrapper; only an
            // Application can register lifecycle callbacks. Resolve via
            // applicationContext and no-op (don't crash) if unavailable —
            // leaving the field null so a later enable call can retry.
            val application = context.applicationContext as? Application
            if (application == null) {
                DashXLog.e(
                    "DashXActivityLifecycleCallbacks",
                    "Lifecycle/screen tracking requires an Application context; skipping registration."
                )
                return
            }
            dashXActivityLifecycleCallbacks = DashXActivityLifecycleCallbacks()
            application.registerActivityLifecycleCallbacks(dashXActivityLifecycleCallbacks)
        }

        fun enableActivityLifecycleTracking(context: Context) {
            lifecycleTrackingEnabled = true

            if (dashXActivityLifecycleCallbacks != null) {
                return
            }

            registerCallbacks(context)
        }

        fun enableScreenTracking(context: Context) {
            screenTrackingEnabled = true

            if (dashXActivityLifecycleCallbacks != null) {
                return
            }

            registerCallbacks(context)
        }
    }
}
