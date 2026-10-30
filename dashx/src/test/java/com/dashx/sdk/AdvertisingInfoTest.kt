package com.dashx.android

import android.content.ContextWrapper
import android.content.SharedPreferences
import com.dashx.android.utils.SystemContextConstants.ADVERTISING_ID
import com.dashx.android.utils.SystemContextConstants.ADVERTISING_INFO_FETCHED
import com.dashx.android.utils.SystemContextConstants.AD_TRACKING_ENABLED
import com.dashx.android.utils.getAdvertisingInfo
import com.dashx.android.utils.getStoredAdvertisingId
import com.dashx.android.utils.hasAdvertisingInfoBeenFetched
import com.dashx.android.utils.isAdTrackingEnabled
import com.dashx.android.utils.resetDashXSharedPreferencesForTesting
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class AdvertisingInfoTest {

    private val uncaught = CopyOnWriteArrayList<Throwable>()
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    @Before
    fun setUp() {
        resetDashXSharedPreferencesForTesting()
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught.add(e) }
    }

    @After
    fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        resetDashXSharedPreferencesForTesting()
    }

    /** The JVM's stubbed Build.VERSION.SDK_INT skips EncryptedSharedPreferences, so the SDK reads [prefs]. */
    private fun contextWith(prefs: SharedPreferences) = object : ContextWrapper(null) {
        override fun getSharedPreferences(name: String?, mode: Int) = prefs
    }

    private fun unreadableAdInfo() = InMemorySharedPreferences(
        mutableMapOf(ADVERTISING_ID to "sealed", AD_TRACKING_ENABLED to true, ADVERTISING_INFO_FETCHED to true),
        undecryptable = setOf(ADVERTISING_ID, AD_TRACKING_ENABLED, ADVERTISING_INFO_FETCHED),
    )

    @Test
    fun getAdvertisingInfo_withUnreadableStoredValues_rewritesThemInsteadOfCrashing() {
        val prefs = unreadableAdInfo()

        getAdvertisingInfo(contextWith(prefs))

        val deadline = System.currentTimeMillis() + 5000
        while (prefs.values[ADVERTISING_ID] != "" && uncaught.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        assertEquals(emptyList<Throwable>(), uncaught)
        assertEquals("", prefs.values[ADVERTISING_ID])
        assertEquals(false, prefs.values[AD_TRACKING_ENABLED])
        assertEquals(true, prefs.values[ADVERTISING_INFO_FETCHED])
    }

    @Test
    fun adInfoReaders_fallBackToDefaults_forUnreadableValues() {
        val context = contextWith(unreadableAdInfo())

        assertEquals("", getStoredAdvertisingId(context))
        assertFalse(isAdTrackingEnabled(context))
        assertFalse(hasAdvertisingInfoBeenFetched(context))
    }
}
