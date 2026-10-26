package com.dashx.android

import com.dashx.android.utils.INTERNAL_EVENT_APP_INSTALLED
import com.dashx.android.utils.INTERNAL_EVENT_APP_OPENED
import com.dashx.android.utils.INTERNAL_EVENT_APP_UPDATED

/**
 * App-level foreground/background sequencing, fed by process lifecycle start/stop (not by
 * individual Activities). The first start of the process is the cold start; later starts are
 * returns from the background.
 */
internal class AppLifecycleTracker(
    private val clock: () -> Long,
    private val onAppStarted: (fromBackground: Boolean) -> Unit,
    private val onAppBackgrounded: (durationMs: Long) -> Unit
) {
    private val lock = Any()
    private var hasStarted = false
    private var foregroundSinceMs: Long? = null

    fun onStart() {
        val fromBackground = synchronized(lock) {
            if (foregroundSinceMs != null) return
            foregroundSinceMs = clock()
            val wasStarted = hasStarted
            hasStarted = true
            wasStarted
        }
        onAppStarted(fromBackground)
    }

    fun onStop() {
        val durationMs = synchronized(lock) {
            val since = foregroundSinceMs ?: return
            foregroundSinceMs = null
            (clock() - since).coerceAtLeast(0)
        }
        onAppBackgrounded(durationMs)
    }
}

internal data class AppLaunchEvent(val name: String, val data: HashMap<String, String>)

/**
 * Picks the event for an app start. A cold start compares the running build against the one
 * stored on the previous launch ([previousBuild] is null on first install).
 */
internal fun appLaunchEvent(
    version: String?,
    build: Long,
    previousBuild: Long?,
    fromBackground: Boolean
): AppLaunchEvent {
    val data = hashMapOf("version" to version.toString(), "build" to build.toString())
    return when {
        fromBackground -> {
            data["from_background"] = "true"
            AppLaunchEvent(INTERNAL_EVENT_APP_OPENED, data)
        }
        previousBuild == null -> AppLaunchEvent(INTERNAL_EVENT_APP_INSTALLED, data)
        previousBuild < build -> {
            data["previous_build"] = previousBuild.toString()
            AppLaunchEvent(INTERNAL_EVENT_APP_UPDATED, data)
        }
        else -> AppLaunchEvent(INTERNAL_EVENT_APP_OPENED, data)
    }
}

internal fun appBackgroundedData(elapsedMs: Double): HashMap<String, String> =
    hashMapOf("session_length" to (elapsedMs / 1000).toString())

internal fun screenEventData(screenName: String, properties: Map<String, String>?): HashMap<String, String> {
    val data = HashMap(properties ?: emptyMap())
    data["name"] = screenName
    return data
}
