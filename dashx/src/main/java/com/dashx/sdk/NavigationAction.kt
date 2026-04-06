package com.dashx.android

/**
 * Resolved navigation intent for a notification tap or action (see [DashXPayload] fields such as
 * `url`, `screenName`, etc.).
 */
sealed class NavigationAction {
    data class DeepLink(val url: String) : NavigationAction()

    data class Screen(val name: String, val data: Map<String, String>?) : NavigationAction()

    data class RichLanding(val url: String) : NavigationAction()
}

/**
 * Derives navigation from [DashXPayload]. For a notification action button tap, pass
 * [actionButtonIdentifier] (same value as [ActionButton.identifier]) so URL/screen are resolved from
 * that button; otherwise the main payload fields are used (`screen_name` / `screen_data` over `url`).
 */
fun DashXPayload.resolveNavigationAction(actionButtonIdentifier: String? = null): NavigationAction? {
    if (!actionButtonIdentifier.isNullOrEmpty()) {
        val button = actionButtons?.firstOrNull { it.identifier == actionButtonIdentifier }
        if (button != null) {
            val bn = button.screenName?.trim()
            if (!bn.isNullOrEmpty()) {
                return NavigationAction.Screen(bn, button.screenData)
            }
            val bu = button.url?.trim()
            if (!bu.isNullOrEmpty()) {
                if (button.richLanding == true) {
                    return NavigationAction.RichLanding(bu)
                }
                return NavigationAction.DeepLink(bu)
            }
            return null
        }
    }

    val name = screenName?.trim()
    if (!name.isNullOrEmpty()) {
        return NavigationAction.Screen(name, screenData)
    }
    val u = url?.trim()
    if (!u.isNullOrEmpty()) {
        if (richLanding == true) {
            return NavigationAction.RichLanding(u)
        }
        return NavigationAction.DeepLink(u)
    }
    return null
}
