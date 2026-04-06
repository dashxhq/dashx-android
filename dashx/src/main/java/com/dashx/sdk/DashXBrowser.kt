package com.dashx.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent

/**
 * In-app browser helpers for notification "rich landing" URLs.
 * Uses Chrome Custom Tabs when a supporting browser is available; falls back to a plain
 * [Intent.ACTION_VIEW] otherwise.
 */
object DashXBrowser {
    /**
     * Opens [url] in a Custom Tabs browser. When no Custom Tabs provider is installed on the
     * device the URL is opened via [Intent.ACTION_VIEW] as a fallback.
     */
    fun openRichLanding(context: Context, url: String) {
        val uri = Uri.parse(url.trim())
        val packageName = CustomTabsClient.getPackageName(context, null)
        if (packageName != null) {
            CustomTabsIntent.Builder()
                .build()
                .also { it.intent.setPackage(packageName) }
                .launchUrl(context, uri)
        } else {
            val fallback = Intent(Intent.ACTION_VIEW, uri)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(fallback)
        }
    }
}
