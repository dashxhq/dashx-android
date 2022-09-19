package com.dashx.sdk

import android.content.Context
import com.dashx.sdk.SystemContextConstants.IPV4
import com.dashx.sdk.SystemContextConstants.IPV6
import com.dashx.sdk.SystemContextConstants.LOCALE
import com.dashx.sdk.SystemContextConstants.TIME_ZONE
import com.dashx.sdk.SystemContextConstants.USER_AGENT
import com.dashx.sdk.utils.getIpHostAddresses
import com.dashx.sdk.utils.getAppLocale
import com.dashx.sdk.utils.getAppTimeZone
import com.dashx.sdk.utils.getAppUserAgent
import org.json.JSONObject

class SystemContext {

    private var context: Context? = null
    private var systemContextHashMap = hashMapOf<String, Any>()

    companion object {

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

    private fun setLocale() {
        getAppLocale(context!!)?.let { put(LOCALE, it) }
    }

    private fun setTimeZone() {
        put(TIME_ZONE, getAppTimeZone())
    }

    private fun setUserAgent() {
        put(USER_AGENT, getAppUserAgent())
    }

    private fun setIpAddress() {
        val ipAddressHashMap = getIpHostAddresses()
        put(IPV4, ipAddressHashMap[IPV4] ?: "")
        put(IPV6, ipAddressHashMap[IPV6] ?: "")
    }

    fun getLocale(): JSONObject {
        return JSONObject(systemContextHashMap[LOCALE] as Map<String, Any>)
    }

    fun getTimeZone(): JSONObject {
        return JSONObject(systemContextHashMap[TIME_ZONE] as Map<String, Any>)
    }

    fun getUserAgent(): JSONObject {
        return JSONObject(systemContextHashMap[USER_AGENT] as Map<String, Any>)
    }

    fun getIpAddress(): JSONObject {
        return JSONObject(hashMapOf(IPV4 to systemContextHashMap[IPV4], IPV6 to systemContextHashMap[IPV6]))
    }

    fun getSystemContext(): JSONObject {
        return JSONObject(systemContextHashMap as Map<String, Any>)
    }

    private fun put(key: String, value: Any) {
        systemContextHashMap[key] = value
    }
}
