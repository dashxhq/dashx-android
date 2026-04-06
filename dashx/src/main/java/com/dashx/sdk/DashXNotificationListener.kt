package com.dashx.android

/**
 * Optional hooks for push notification lifecycle. Register with [DashX.registerNotificationListener].
 */
interface DashXNotificationListener {
    fun onNotificationReceived(payload: DashXPayload) {}

    /**
     * Called when the user opens a notification (or an action is handled in a later SDK version).
     * Return `true` to perform navigation yourself and skip the SDK default URL / `click_action` handling.
     */
    fun onNotificationClicked(payload: DashXPayload, action: NavigationAction?): Boolean = false

    fun onNotificationDismissed(payload: DashXPayload) {}
}
