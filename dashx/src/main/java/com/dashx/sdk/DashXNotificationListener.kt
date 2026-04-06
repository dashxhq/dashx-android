package com.dashx.android

/**
 * Optional hooks for push notification lifecycle. Register with [DashX.registerNotificationListener].
 */
interface DashXNotificationListener {
    fun onNotificationReceived(payload: DashXPayload) {}

    /**
     * Called when the user taps the notification body or an action button.
     * [action] is the resolved [NavigationAction] (deep link, screen, rich landing, click action, or `null`).
     * Return `true` to perform navigation yourself and skip the SDK default handling; return `false` to let
     * the SDK proceed with its default behavior (opening URLs, Custom Tabs, `click_action`, etc.).
     */
    fun onNotificationClicked(payload: DashXPayload, action: NavigationAction?): Boolean = false

    fun onNotificationDismissed(payload: DashXPayload) {}
}
