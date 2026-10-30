package com.dashx.android

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationListenerDispatchTest {

    private val payload = DashXPayload(id = "notification-1")
    private val registered = mutableListOf<DashXNotificationListener>()

    private fun register(listener: DashXNotificationListener) {
        DashX.registerNotificationListener(listener)
        registered += listener
    }

    @After
    fun tearDown() {
        registered.forEach(DashX::unregisterNotificationListener)
    }

    private val throwing = object : DashXNotificationListener {
        override fun onNotificationClicked(
            payload: DashXPayload,
            action: NavigationAction?,
            actionIdentifier: String?
        ): Boolean = throw IllegalStateException("host listener failed")

        override fun onNotificationDismissed(payload: DashXPayload) =
            throw IllegalStateException("host listener failed")
    }

    @Test
    fun click_aThrowingListener_doesNotEscape_andLaterListenersStillRun() {
        val clicked = mutableListOf<String?>()
        register(throwing)
        register(object : DashXNotificationListener {
            override fun onNotificationClicked(
                payload: DashXPayload,
                action: NavigationAction?,
                actionIdentifier: String?
            ): Boolean {
                clicked += actionIdentifier
                return true
            }
        })

        val suppressDefault = DashX.dispatchNotificationClicked(payload, null, "reply")

        assertEquals(listOf<String?>("reply"), clicked)
        assertTrue(suppressDefault)
    }

    @Test
    fun click_aThrowingListener_leavesTheDefaultHandlingOn() {
        register(throwing)

        assertFalse(DashX.dispatchNotificationClicked(payload, null))
    }

    @Test
    fun dismiss_aThrowingListener_doesNotEscape_andLaterListenersStillRun() {
        val dismissed = mutableListOf<String>()
        register(throwing)
        register(object : DashXNotificationListener {
            override fun onNotificationDismissed(payload: DashXPayload) {
                dismissed += payload.id
            }
        })

        DashX.dispatchNotificationDismissed(payload)

        assertEquals(listOf("notification-1"), dismissed)
    }
}
