package com.dashx.android

import com.dashx.android.utils.INTERNAL_EVENT_APP_INSTALLED
import com.dashx.android.utils.INTERNAL_EVENT_APP_OPENED
import com.dashx.android.utils.INTERNAL_EVENT_APP_UPDATED
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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

internal data class AppLaunchEvent(val name: String, val data: JsonObject)

/**
 * Picks the event for an app start. A cold start compares the running build against the one
 * stored on the previous launch ([previousBuild] is null on first install).
 */
internal fun appLaunchEvent(
    version: String?,
    build: Long,
    previousBuild: Long?,
    fromBackground: Boolean
): AppLaunchEvent = when {
    fromBackground -> AppLaunchEvent(INTERNAL_EVENT_APP_OPENED, buildJsonObject {
        put("version", version)
        put("build", build)
        put("fromBackground", true)
    })
    previousBuild == null -> AppLaunchEvent(INTERNAL_EVENT_APP_INSTALLED, buildJsonObject {
        put("version", version)
        put("build", build)
    })
    previousBuild < build -> AppLaunchEvent(INTERNAL_EVENT_APP_UPDATED, buildJsonObject {
        put("version", version)
        put("build", build)
        put("previousBuild", previousBuild)
    })
    else -> AppLaunchEvent(INTERNAL_EVENT_APP_OPENED, buildJsonObject {
        put("version", version)
        put("build", build)
        put("fromBackground", false)
    })
}

internal fun appBackgroundedData(durationMs: Long): JsonObject =
    buildJsonObject { put("durationMs", durationMs) }

internal fun screenEventData(screenName: String, properties: Map<String, String>?): HashMap<String, String> {
    val data = HashMap(properties ?: emptyMap())
    data["name"] = screenName
    return data
}
