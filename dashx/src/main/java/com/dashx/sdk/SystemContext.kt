package com.dashx.sdk

import android.content.Context
import java.util.HashMap

class SystemContext {

    companion object {

        // App
        private const val APP_KEY = "app"
        private const val APP_NAME_KEY = "name"
        private const val APP_IDENTIFIER_KEY = "identifier"
        private const val APP_VERSION_NUMBER_KEY = "versionNumber"
        private const val APP_VERSION_CODE_KEY = "versionCode"
        private const val APP_BUILD_KEY = "build"
        private const val APP_RELEASE_KEY = "release"

        // Library
        private const val LIBRARY_KEY = "library"
        private const val LIBRARY_NAME_KEY = "name"
        private const val LIBRARY_VERSION_KEY = "version"

    }

    fun putApp(context: Context) {

        val packageManager = context.packageManager
        val packageInfo = packageManager.getPackageInfo(context.packageName, 0)
        val app = HashMap<String, Any>()
        putUndefinedIfNull(
            app, APP_NAME_KEY, packageInfo.applicationInfo.loadLabel(packageManager))
        putUndefinedIfNull(app, APP_IDENTIFIER_KEY, packageInfo.packageName)
        putUndefinedIfNull(app, APP_VERSION_NUMBER_KEY, packageInfo.versionName)
        putUndefinedIfNull(app, APP_VERSION_CODE_KEY, packageInfo.versionCode.toString())
        putUndefinedIfNull(app, APP_BUILD_KEY, packageInfo.versionCode.toString())

        put(APP_KEY, app)

    }

    private fun putUndefinedIfNull(app: HashMap<String, Any>, key: String, value: CharSequence) {
        if (value.isNullOrEmpty()) {
            app[key] = "undefined"
        } else {
            app[key] = value
        }
    }

    fun library(context: Context) {

        val library = HashMap<String, Any>()
        library[LIBRARY_NAME_KEY] = BuildConfig.LIBRARY_PACKAGE_NAME
        library[LIBRARY_VERSION_KEY] = BuildConfig.VERSION_NAME

        put(LIBRARY_KEY, library)

    }

    private fun put(key: String, value: Any) {
        val map = HashMap<String, Any>()
        map[key] = value
    }

}
