package com.dashx.android

/**
 * Optional hooks for push notification lifecycle. Register with [DashX.registerNotificationListener].
 */
interface DashXNotificationListener {
    fun onNotificationReceived(payload: DashXPayload) {}

    /**
     * Called when the user taps the notification body or an action button.
     * [action] is the resolved [NavigationAction] (deep link, screen, rich landing, click action, or `null`).
     * [actionIdentifier] is the tapped action button's identifier (matches [ActionButton.identifier]),
     * or `null` when the notification body itself was tapped.
     *
     * Return `true` to perform navigation yourself and skip the SDK default handling; return `false` to let
     * the SDK proceed with its default behavior (opening URLs, Custom Tabs, `click_action`, etc.).
     *
     * The default implementation delegates to the 2-arg overload below so that pre-existing
     * implementations that only override [onNotificationClicked] keep working unchanged.
     * Prefer overriding this 3-arg form in new code to receive [actionIdentifier].
     */
    fun onNotificationClicked(
        payload: DashXPayload,
        action: NavigationAction?,
        actionIdentifier: String?
    ): Boolean = onNotificationClicked(payload, action)

    /**
     * Legacy overload — kept for backward compatibility with pre-1.2.7 implementations.
     * New code should override [onNotificationClicked] with the `actionIdentifier` parameter.
     */
    fun onNotificationClicked(payload: DashXPayload, action: NavigationAction?): Boolean = false

    fun onNotificationDismissed(payload: DashXPayload) {}
}
