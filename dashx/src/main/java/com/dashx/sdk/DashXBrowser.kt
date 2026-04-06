package com.dashx.android

import android.app.Activity
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent

internal object DashXBrowser {
    fun openRichLanding(activity: Activity, url: String) {
        val uri = Uri.parse(url.trim())
        CustomTabsIntent.Builder()
            .build()
            .launchUrl(activity, uri)
    }
}
