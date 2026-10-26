package com.dashx.android

import com.dashx.android.utils.INTERNAL_EVENT_APP_INSTALLED
import com.dashx.android.utils.INTERNAL_EVENT_APP_OPENED
import com.dashx.android.utils.INTERNAL_EVENT_APP_UPDATED
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AppLifecycleTrackerTest {

    private var now = 1_000L
    private val events = mutableListOf<String>()
    private val tracker = AppLifecycleTracker(
        clock = { now },
        onAppStarted = { fromBackground -> events.add("started fromBackground=$fromBackground") },
        onAppBackgrounded = { durationMs -> events.add("backgrounded durationMs=$durationMs") }
    )

    @Test
    fun coldStartOpensOnce_thenBackgroundAndForegroundAlternate() {
        tracker.onStart()
        now += 1_500
        tracker.onStop()
        now += 60_000
        tracker.onStart()
        now += 250
        tracker.onStop()

        assertEquals(
            listOf(
                "started fromBackground=false",
                "backgrounded durationMs=1500",
                "started fromBackground=true",
                "backgrounded durationMs=250"
            ),
            events
        )
    }

    @Test
    fun repeatedStartOrStopWithoutTheOtherIsIgnored() {
        tracker.onStop()
        tracker.onStart()
        tracker.onStart()
        now += 10
        tracker.onStop()
        tracker.onStop()

        assertEquals(listOf("started fromBackground=false", "backgrounded durationMs=10"), events)
    }

    @Test
    fun launchEvent_firstInstall() {
        val event = appLaunchEvent("2.0.0", 20, previousBuild = null, fromBackground = false)

        assertEquals(INTERNAL_EVENT_APP_INSTALLED, event.name)
        assertEquals(buildJsonObject { put("version", "2.0.0"); put("build", 20) }, event.data)
    }

    @Test
    fun launchEvent_updateCarriesPreviousBuild() {
        val event = appLaunchEvent("2.0.0", 20, previousBuild = 19, fromBackground = false)

        assertEquals(INTERNAL_EVENT_APP_UPDATED, event.name)
        assertEquals(
            buildJsonObject { put("version", "2.0.0"); put("build", 20); put("previousBuild", 19) },
            event.data
        )
    }

    @Test
    fun launchEvent_coldStartOnSameBuildIsOpenNotFromBackground() {
        val event = appLaunchEvent("2.0.0", 20, previousBuild = 20, fromBackground = false)

        assertEquals(INTERNAL_EVENT_APP_OPENED, event.name)
        assertEquals(JsonPrimitive(false), event.data["fromBackground"])
    }

    @Test
    fun launchEvent_returnFromBackgroundIsAlwaysOpen() {
        val event = appLaunchEvent("2.0.0", 20, previousBuild = null, fromBackground = true)

        assertEquals(INTERNAL_EVENT_APP_OPENED, event.name)
        assertEquals(
            buildJsonObject { put("version", "2.0.0"); put("build", 20); put("fromBackground", true) },
            event.data
        )
    }

    @Test
    fun lifecycleData_usesNativeJsonTypes() {
        val opened = appLaunchEvent("1.0", 7, previousBuild = 7, fromBackground = false).data
        val backgrounded = appBackgroundedData(1500)

        assertFalse((opened["fromBackground"] as JsonPrimitive).isString)
        assertFalse((opened["build"] as JsonPrimitive).isString)
        assertEquals("{\"durationMs\":1500}", backgrounded.toString())
    }

    @Test
    fun screenData_alwaysIncludesName() {
        assertEquals(hashMapOf("name" to "Checkout"), screenEventData("Checkout", null))
    }

    @Test
    fun screenData_keepsCallerPropertiesWithoutMutatingThem() {
        val properties = hashMapOf("plan" to "pro", "name" to "stale")

        val data = screenEventData("Checkout", properties)

        assertEquals(hashMapOf("plan" to "pro", "name" to "Checkout"), data)
        assertEquals("stale", properties["name"])
    }
}
