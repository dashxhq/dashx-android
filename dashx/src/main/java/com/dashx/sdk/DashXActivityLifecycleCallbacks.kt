package com.dashx.android

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

/**
 * Automatic screen tracking: tracks `$screen` each time an Activity resumes. App open/background
 * tracking is process-level; see [enableActivityLifecycleTracking].
 */
class DashXActivityLifecycleCallbacks : Application.ActivityLifecycleCallbacks {

    override fun onActivityResumed(activity: Activity) {
        if (!screenTrackingEnabled) {
            return
        }

        val name = screenName(activity) ?: return
        screenSink(name)
    }

    override fun onActivityPaused(activity: Activity) {}

    override fun onActivityStarted(activity: Activity) {}

    override fun onActivityStopped(activity: Activity) {}

    override fun onActivityDestroyed(activity: Activity) {}

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}

    companion object {
        private const val TAG = "DashXActivityLifecycleCallbacks"

        private val lock = Any()
        @Volatile private var screenTrackingEnabled = false
        private var screenCallbacks: DashXActivityLifecycleCallbacks? = null
        private var processObserverRegistered = false

        internal var screenSink: (String) -> Unit = { DashX.screen(it, null) }
        internal var registerActivityCallbacks: (Application, Application.ActivityLifecycleCallbacks) -> Unit =
            { application, callbacks -> application.registerActivityLifecycleCallbacks(callbacks) }
        internal var registerProcessObserver: (DefaultLifecycleObserver) -> Unit = ::addProcessObserverOnMain
        internal var appLifecycleTracker = defaultAppLifecycleTracker()

        private val processObserver = object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = appLifecycleTracker.onStart()
            override fun onStop(owner: LifecycleOwner) = appLifecycleTracker.onStop()
        }

        /**
         * Tracks `$app_opened` / `$app_installed` / `$app_updated` when the app comes to the
         * foreground and `$app_backgrounded` when it leaves it. Safe to call more than once.
         */
        @Suppress("UNUSED_PARAMETER")
        fun enableActivityLifecycleTracking(context: Context) {
            synchronized(lock) {
                if (processObserverRegistered) {
                    return
                }
                processObserverRegistered = true
            }
            registerProcessObserver(processObserver)
        }

        /**
         * Tracks `$screen` each time an Activity resumes. Single-Activity apps (Fragments, Jetpack
         * Compose navigation) should call [DashX.screen] from their navigation instead.
         */
        fun enableScreenTracking(context: Context) {
            synchronized(lock) {
                screenTrackingEnabled = true

                if (screenCallbacks != null) {
                    return
                }

                // Left unregistered on failure so a later enable call can retry.
                val application = (context as? Application) ?: (context.applicationContext as? Application)
                if (application == null) {
                    DashXLog.e(TAG, "Screen tracking requires an Application context; skipping registration.")
                    return
                }
                val callbacks = DashXActivityLifecycleCallbacks()
                registerActivityCallbacks(application, callbacks)
                screenCallbacks = callbacks
            }
        }

        internal fun screenName(activity: Activity): String? {
            // The SDK's invisible notification-tap trampoline, not a screen.
            if (activity is NotificationReceiver) {
                return null
            }
            return activityLabel(activity) ?: activity.javaClass.simpleName.ifBlank { activity.javaClass.name }
        }

        private fun activityLabel(activity: Activity): String? {
            return try {
                val packageManager = activity.packageManager ?: return null
                val componentName = activity.componentName ?: return null
                val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    packageManager.getActivityInfo(componentName, PackageManager.ComponentInfoFlags.of(0))
                } else {
                    packageManager.getActivityInfo(componentName, 0)
                }
                // loadLabel() falls back to the application label, which would name every unlabeled screen alike.
                if (info.labelRes == 0 && info.nonLocalizedLabel == null) {
                    return null
                }
                info.loadLabel(packageManager).toString().takeIf { it.isNotBlank() }
            } catch (_: Exception) {
                null
            }
        }

        private fun defaultAppLifecycleTracker() = AppLifecycleTracker(
            clock = { SystemClock.elapsedRealtime() },
            onAppStarted = { fromBackground -> DashX.trackAppStarted(fromBackground) },
            onAppBackgrounded = { durationMs -> DashX.trackAppBackgrounded(durationMs) }
        )

        private fun addProcessObserverOnMain(observer: DefaultLifecycleObserver) {
            val mainLooper = Looper.getMainLooper()
            if (Looper.myLooper() == mainLooper) {
                ProcessLifecycleOwner.get().lifecycle.addObserver(observer)
            } else {
                Handler(mainLooper).post { ProcessLifecycleOwner.get().lifecycle.addObserver(observer) }
            }
        }

        internal fun resetForTesting() {
            synchronized(lock) {
                screenTrackingEnabled = false
                screenCallbacks = null
                processObserverRegistered = false
            }
            screenSink = { DashX.screen(it, null) }
            registerActivityCallbacks = { application, callbacks -> application.registerActivityLifecycleCallbacks(callbacks) }
            registerProcessObserver = ::addProcessObserverOnMain
            appLifecycleTracker = defaultAppLifecycleTracker()
        }
    }
}
