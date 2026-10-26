package com.dashx.android

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DashXActivityLifecycleCallbacksTest {

    class HomeActivity : Activity()
    class CheckoutActivity : Activity()

    private val processOwner = object : LifecycleOwner {
        override val lifecycle: Lifecycle get() = throw UnsupportedOperationException()
    }

    private val application = Application()
    private val events = mutableListOf<String>()
    private val processObservers = mutableListOf<DefaultLifecycleObserver>()
    private val activityCallbacks = mutableListOf<Application.ActivityLifecycleCallbacks>()
    private var now = 0L

    @Before
    fun setUp() {
        DashXActivityLifecycleCallbacks.resetForTesting()
        DashXActivityLifecycleCallbacks.screenSink = { events.add("screen $it") }
        DashXActivityLifecycleCallbacks.registerProcessObserver = { processObservers.add(it) }
        DashXActivityLifecycleCallbacks.registerActivityCallbacks = { _, callbacks -> activityCallbacks.add(callbacks) }
        DashXActivityLifecycleCallbacks.appLifecycleTracker = AppLifecycleTracker(
            clock = { now },
            onAppStarted = { events.add("started fromBackground=$it") },
            onAppBackgrounded = { events.add("backgrounded durationMs=$it") }
        )
    }

    @After
    fun tearDown() {
        DashXActivityLifecycleCallbacks.resetForTesting()
    }

    @Test
    fun enablingLifecycleTrackingTwice_registersOneObserver() {
        DashXActivityLifecycleCallbacks.enableActivityLifecycleTracking(application)
        DashXActivityLifecycleCallbacks.enableActivityLifecycleTracking(application)

        assertEquals(1, processObservers.size)

        processObservers.forEach { it.onStart(processOwner) }
        assertEquals(listOf("started fromBackground=false"), events)
    }

    @Test
    fun enablingScreenTrackingTwice_registersOneCallback() {
        DashXActivityLifecycleCallbacks.enableScreenTracking(application)
        DashXActivityLifecycleCallbacks.enableScreenTracking(application)

        assertEquals(1, activityCallbacks.size)
    }

    @Test
    fun lifecycleTrackingAlone_tracksNoScreens() {
        DashXActivityLifecycleCallbacks.enableActivityLifecycleTracking(application)

        assertTrue(activityCallbacks.isEmpty())
        DashXActivityLifecycleCallbacks().onActivityResumed(HomeActivity())
        assertTrue(events.isEmpty())
    }

    @Test
    fun screenTrackingAlone_tracksNoAppLifecycle() {
        DashXActivityLifecycleCallbacks.enableScreenTracking(application)

        assertTrue(processObservers.isEmpty())
        assertTrue(events.isEmpty())
    }

    @Test
    fun activityTransitions_trackScreensOnResumeAndNoAppLifecycleEvents() {
        DashXActivityLifecycleCallbacks.enableActivityLifecycleTracking(application)
        DashXActivityLifecycleCallbacks.enableScreenTracking(application)
        val process = processObservers.single()
        val callbacks = activityCallbacks.single()
        val home = HomeActivity()
        val checkout = CheckoutActivity()

        process.onStart(processOwner)
        callbacks.onActivityCreated(home, null)
        callbacks.onActivityStarted(home)
        callbacks.onActivityResumed(home)

        callbacks.onActivityPaused(home)
        callbacks.onActivityCreated(checkout, null)
        callbacks.onActivityStarted(checkout)
        callbacks.onActivityResumed(checkout)
        callbacks.onActivitySaveInstanceState(home, Bundle())
        callbacks.onActivityStopped(home)

        callbacks.onActivityPaused(checkout)
        callbacks.onActivityResumed(home)
        callbacks.onActivityStopped(checkout)
        callbacks.onActivityDestroyed(checkout)

        callbacks.onActivityPaused(home)
        callbacks.onActivityStopped(home)
        now += 4_000
        process.onStop(processOwner)
        process.onStart(processOwner)
        callbacks.onActivityStarted(home)
        callbacks.onActivityResumed(home)

        assertEquals(
            listOf(
                "started fromBackground=false",
                "screen HomeActivity",
                "screen CheckoutActivity",
                "screen HomeActivity",
                "backgrounded durationMs=4000",
                "started fromBackground=true",
                "screen HomeActivity"
            ),
            events
        )
    }
}
