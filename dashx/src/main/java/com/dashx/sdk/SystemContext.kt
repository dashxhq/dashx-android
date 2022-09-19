package com.dashx.sdk

import android.content.Context
import android.content.pm.ApplicationInfo
import org.json.JSONObject

class SystemContext {

    private var context: Context? = null
    private var systemContextHashMap = hashMapOf<String, Any>()

    companion object {
        // App
        private const val APP = "app"
        private const val NAME = "name"
        private const val IDENTIFIER_KEY = "identifier"
        private const val VERSION_NUMBER = "versionNumber"
        private const val VERSION_CODE = "versionCode"
        private const val BUILD = "build"
        private const val RELEASE_MODE = "releaseMode"
        private const val RELEASE = "release"
        private const val DEBUG = "debug"

        // Library
        private const val LIBRARY = "library"
        private const val VERSION = "version"

        private var INSTANCE: SystemContext = SystemContext()

        fun configure(context: Context): SystemContext {
            INSTANCE.init(context)
            return INSTANCE
        }

        @JvmName("getSystemContextInstance")
        fun getInstance(): SystemContext {
            if (INSTANCE.context == null) {
                throw NullPointerException("Configure SystemContext before accessing it.")
            }
            return INSTANCE
        }
    }

    fun init(context: Context) {
        this.context = context.applicationContext
    }

    private fun setAppInfo() {
        val packageManager = context?.packageManager
        val packageInfo = context?.packageName?.let { packageManager?.getPackageInfo(it, 0) }
        val hashMap = HashMap<String, Any>()
        packageInfo?.let {
            if (packageManager != null) {
                hashMap[NAME] = it.applicationInfo.loadLabel(packageManager)
            }
            hashMap[IDENTIFIER_KEY] = it.packageName
            hashMap[VERSION_NUMBER] = it.versionName
            hashMap[VERSION_CODE] = it.versionCode
            hashMap[BUILD] = it.versionCode
            hashMap[RELEASE_MODE] =
                if (0 != context?.applicationInfo?.flags!! and ApplicationInfo.FLAG_DEBUGGABLE) {
                    DEBUG
                } else {
                    RELEASE
                }
        }
        put(APP, hashMap)
    }

    fun setLibraryInfo() {
        val library = HashMap<String, Any>()
        library[NAME] = BuildConfig.LIBRARY_NAME
        library[VERSION] = BuildConfig.VERSION_NAME
        put(LIBRARY, library)
    }

    fun getAppInfo(): JSONObject {
        return JSONObject(systemContextHashMap[APP] as Map<String, Any>)
    }

    fun getLibraryInfo(): JSONObject {
        return JSONObject(systemContextHashMap[LIBRARY] as Map<String, Any>)
    }

    fun getSystemContext(): JSONObject {
        return JSONObject(systemContextHashMap as Map<String, Any>)
    }

    private fun put(key: String, value: Any) {
        systemContextHashMap[key] = value
    }
}
