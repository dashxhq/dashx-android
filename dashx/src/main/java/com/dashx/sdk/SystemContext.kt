package com.dashx.sdk

import android.content.Context
import android.content.pm.ApplicationInfo
import com.dashx.sdk.data.LibraryInfo
import com.dashx.sdk.utils.*
import com.dashx.sdk.utils.SystemContextConstants.ADVERTISING_ID
import com.dashx.sdk.utils.SystemContextConstants.AD_TRACKING_ENABLED
import com.dashx.sdk.utils.SystemContextConstants.APP
import com.dashx.sdk.utils.SystemContextConstants.BLUETOOTH
import com.dashx.sdk.utils.SystemContextConstants.BUILD
import com.dashx.sdk.utils.SystemContextConstants.CARRIER
import com.dashx.sdk.utils.SystemContextConstants.CELLULAR
import com.dashx.sdk.utils.SystemContextConstants.CITY
import com.dashx.sdk.utils.SystemContextConstants.COUNTRY
import com.dashx.sdk.utils.SystemContextConstants.DEBUG
import com.dashx.sdk.utils.SystemContextConstants.DENSITY
import com.dashx.sdk.utils.SystemContextConstants.DEVICE
import com.dashx.sdk.utils.SystemContextConstants.HEIGHT
import com.dashx.sdk.utils.SystemContextConstants.ID
import com.dashx.sdk.utils.SystemContextConstants.IPV4
import com.dashx.sdk.utils.SystemContextConstants.IPV6
import com.dashx.sdk.utils.SystemContextConstants.KIND
import com.dashx.sdk.utils.SystemContextConstants.LATITUDE
import com.dashx.sdk.utils.SystemContextConstants.LIBRARY
import com.dashx.sdk.utils.SystemContextConstants.LOCALE
import com.dashx.sdk.utils.SystemContextConstants.LOCATION
import com.dashx.sdk.utils.SystemContextConstants.LONGITUDE
import com.dashx.sdk.utils.SystemContextConstants.MANUFACTURER
import com.dashx.sdk.utils.SystemContextConstants.MODEL
import com.dashx.sdk.utils.SystemContextConstants.NAME
import com.dashx.sdk.utils.SystemContextConstants.NAMESPACE
import com.dashx.sdk.utils.SystemContextConstants.NETWORK
import com.dashx.sdk.utils.SystemContextConstants.OS
import com.dashx.sdk.utils.SystemContextConstants.OS_NAME
import com.dashx.sdk.utils.SystemContextConstants.OS_VERSION
import com.dashx.sdk.utils.SystemContextConstants.RELEASE
import com.dashx.sdk.utils.SystemContextConstants.RELEASE_MODE
import com.dashx.sdk.utils.SystemContextConstants.SCREEN
import com.dashx.sdk.utils.SystemContextConstants.SPEED
import com.dashx.sdk.utils.SystemContextConstants.TIME_ZONE
import com.dashx.sdk.utils.SystemContextConstants.USER_AGENT
import com.dashx.sdk.utils.SystemContextConstants.VERSION
import com.dashx.sdk.utils.SystemContextConstants.VERSION_CODE
import com.dashx.sdk.utils.SystemContextConstants.VERSION_NUMBER
import com.dashx.sdk.utils.SystemContextConstants.WIDTH
import com.dashx.sdk.utils.SystemContextConstants.WIFI
import org.json.JSONObject

class SystemContext {

    private var context: Context? = null
    private var systemContextHashMap = hashMapOf<String, Any>()

    companion object {

        private var INSTANCE: SystemContext = SystemContext()

        private var libraryName = BuildConfig.LIBRARY_NAME
        private var libraryVersion = BuildConfig.VERSION_NAME

        fun configure(context: Context): SystemContext {
            INSTANCE.init(context)
            getAdvertisingInfo(context)
            return INSTANCE
        }

        fun setLibraryInfo(libraryInfo: LibraryInfo?) {
            if (libraryInfo != null) {
                libraryName = libraryInfo.name
                libraryVersion = libraryInfo.version
            }
        }

        @JvmName("getSystemContextInstance")
        fun getInstance(): SystemContext {
            if (INSTANCE.context == null) {
                throw NullPointerException("Configure SystemContext before accessing it.")
            }
            getAdvertisingInfo(INSTANCE.context)
            return INSTANCE
        }
    }

    fun init(context: Context) {
        this.context = context.applicationContext
        getAdvertisingInfo(this.context)
    }

    private fun setNetworkInfo() {
        val network = hashMapOf<String, Any>()
        network[BLUETOOTH] = getBluetoothInfo(context!!)
        network[CARRIER] = getCarrierInfo(context!!)
        network[CELLULAR] = getCellularInfo(context!!).toString()
        network[WIFI] = getWifiInfo(context!!)

        put(NETWORK, network)
    }

    private fun setDeviceInfo() {
        val device = HashMap<String, Any>()
        device[AD_TRACKING_ENABLED] = context?.let { getDashXSharedPreferences(it).getBoolean(AD_TRACKING_ENABLED, false) } ?: false
        device[ADVERTISING_ID] = context?.let { getDashXSharedPreferences(it).getString(ADVERTISING_ID, "") } ?: ""
        device[ID] = getDeviceId(context!!)
        device[KIND] = getDeviceKind()
        device[MANUFACTURER] = getDeviceManufacturer()
        device[MODEL] = getDeviceModel()
        device[NAME] = getDeviceName()

        put(DEVICE, device)
    }

    private fun setOsInfo() {
        val os = HashMap<String, Any>()
        os[OS_NAME] = getOsName()
        os[OS_VERSION] = getOsVersion()

        put(OS, os)
    }

    private fun setScreenInfo() {
        val screen = HashMap<String, Any>()
        screen[HEIGHT] = getScreenHeight()
        screen[WIDTH] = getScreenWidth()
        screen[DENSITY] = getScreenDensity()

        put(SCREEN, screen)
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

    private fun setAppInfo() {
        val packageManager = context?.packageManager
        val packageInfo = context?.packageName?.let { packageManager?.getPackageInfo(it, 0) }
        val hashMap = HashMap<String, Any>()
        packageInfo?.let {
            if (packageManager != null) {
                hashMap[NAME] = it.applicationInfo.loadLabel(packageManager)
            }
            hashMap[NAMESPACE] = it.packageName
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

    private fun setLibraryInfo() {
        val library = HashMap<String, Any>()
        library[NAME] = libraryName
        library[VERSION] = libraryVersion

        put(LIBRARY, library)
    }

    fun getNetworkInfo(): JSONObject {
        return JSONObject(systemContextHashMap[NETWORK] as Map<String, Any>)
    }

    fun getDeviceInfo(): JSONObject {
        return JSONObject(systemContextHashMap[DEVICE] as Map<String, Any>)
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

    private fun setLocationInfo() {
        val location = HashMap<String, Any>()
        getLocationCoordinates(context!!).let {
            location[LONGITUDE] = it?.longitude ?: 0.0
            location[LATITUDE] = it?.latitude ?: 0.0
        }
        location[SPEED] = getSpeed(context!!)

        put(LOCATION, location)
    }

    fun getAppInfo(): JSONObject {
        return JSONObject(systemContextHashMap[APP] as Map<String, Any>)
    }

    fun getLibraryInfo(): JSONObject {
        return JSONObject(systemContextHashMap[LIBRARY] as Map<String, Any>)
    }

    fun getOsInfo(): JSONObject {
        return JSONObject(systemContextHashMap[OS] as Map<String, Any>)
    }

    fun getScreenInfo(): JSONObject {
        return JSONObject(systemContextHashMap[SCREEN] as Map<String, Any>)
    }

    fun getLocationInfo(): JSONObject {
        return JSONObject(systemContextHashMap[LOCATION] as Map<String, Any>)
    }

    fun getSystemContext(): JSONObject {
        return JSONObject(systemContextHashMap as Map<String, Any>)
    }

    fun fetchSystemContext(): JSONObject {
        setNetworkInfo()
        setDeviceInfo()
        setLocale()
        setTimeZone()
        setUserAgent()
        setIpAddress()
        setAppInfo()
        setOsInfo()
        setScreenInfo()
        setLibraryInfo()
        setLocationInfo()
        return getSystemContext()
    }

    private fun put(key: String, value: Any) {
        systemContextHashMap[key] = value
    }
}
