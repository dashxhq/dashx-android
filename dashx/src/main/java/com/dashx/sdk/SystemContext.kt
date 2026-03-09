package com.dashx.android

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import com.dashx.android.data.LibraryInfo
import com.dashx.android.utils.*
import com.dashx.android.utils.SystemContextConstants.ADVERTISING_ID
import com.dashx.android.utils.SystemContextConstants.AD_TRACKING_ENABLED
import com.dashx.android.utils.SystemContextConstants.APP
import com.dashx.android.utils.SystemContextConstants.BLUETOOTH
import com.dashx.android.utils.SystemContextConstants.BUILD
import com.dashx.android.utils.SystemContextConstants.CARRIER
import com.dashx.android.utils.SystemContextConstants.CELLULAR
import com.dashx.android.utils.SystemContextConstants.DEBUG
import com.dashx.android.utils.SystemContextConstants.DENSITY
import com.dashx.android.utils.SystemContextConstants.DEVICE
import com.dashx.android.utils.SystemContextConstants.HEIGHT
import com.dashx.android.utils.SystemContextConstants.ID
import com.dashx.android.utils.SystemContextConstants.IPV4
import com.dashx.android.utils.SystemContextConstants.IPV6
import com.dashx.android.utils.SystemContextConstants.KIND
import com.dashx.android.utils.SystemContextConstants.LATITUDE
import com.dashx.android.utils.SystemContextConstants.LIBRARY
import com.dashx.android.utils.SystemContextConstants.LOCALE
import com.dashx.android.utils.SystemContextConstants.LOCATION
import com.dashx.android.utils.SystemContextConstants.LONGITUDE
import com.dashx.android.utils.SystemContextConstants.MANUFACTURER
import com.dashx.android.utils.SystemContextConstants.MODEL
import com.dashx.android.utils.SystemContextConstants.NAME
import com.dashx.android.utils.SystemContextConstants.NAMESPACE
import com.dashx.android.utils.SystemContextConstants.NETWORK
import com.dashx.android.utils.SystemContextConstants.OS
import com.dashx.android.utils.SystemContextConstants.OS_NAME
import com.dashx.android.utils.SystemContextConstants.OS_VERSION
import com.dashx.android.utils.SystemContextConstants.RELEASE
import com.dashx.android.utils.SystemContextConstants.RELEASE_MODE
import com.dashx.android.utils.SystemContextConstants.SCREEN
import com.dashx.android.utils.SystemContextConstants.SPEED
import com.dashx.android.utils.SystemContextConstants.TIME_ZONE
import com.dashx.android.utils.SystemContextConstants.USER_AGENT
import com.dashx.android.utils.SystemContextConstants.VERSION
import com.dashx.android.utils.SystemContextConstants.VERSION_CODE
import com.dashx.android.utils.SystemContextConstants.VERSION_NUMBER
import com.dashx.android.utils.SystemContextConstants.WIDTH
import com.dashx.android.utils.SystemContextConstants.WIFI
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

class SystemContext {

    private var context: Context? = null
    private val systemContextHashMap = ConcurrentHashMap<String, Any>()

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
        val ctx = context ?: return
        val network = hashMapOf<String, Any>()
        network[BLUETOOTH] = getBluetoothInfo(ctx)
        network[CARRIER] = getCarrierInfo(ctx)
        network[CELLULAR] = getCellularInfo(ctx)
        network[WIFI] = getWifiInfo(ctx)

        put(NETWORK, network)
    }

    private fun setDeviceInfo() {
        val ctx = context ?: return
        val device = HashMap<String, Any>()
        device[AD_TRACKING_ENABLED] =
            getDashXSharedPreferences(ctx).getBoolean(AD_TRACKING_ENABLED, false)
        device[ADVERTISING_ID] =
            getDashXSharedPreferences(ctx).getString(ADVERTISING_ID, "") ?: ""
        device[ID] = getDeviceId(ctx)
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
        val ctx = context ?: return
        getAppLocale(ctx)?.let { put(LOCALE, it) }
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
        val packageInfo = context?.packageName?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager?.getPackageInfo(it, PackageManager.PackageInfoFlags.of(0))
            } else {
                packageManager?.getPackageInfo(it, 0)
            }
        }

        val versionCode =
            packageInfo?.let { PackageInfoCompat.getLongVersionCode(packageInfo).toString() } ?: ""

        val hashMap = HashMap<String, Any>()
        packageInfo?.let {
            if (packageManager != null) {
                it.applicationInfo?.let { ai -> hashMap[NAME] = ai.loadLabel(packageManager) }
            }
            hashMap[NAMESPACE] = it.packageName
            hashMap[VERSION_NUMBER] = it.versionName ?: ""
            hashMap[VERSION_CODE] = versionCode
            hashMap[BUILD] = versionCode
            hashMap[RELEASE_MODE] =
                if ((context?.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
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
        return JSONObject(
            hashMapOf(
                IPV4 to systemContextHashMap[IPV4],
                IPV6 to systemContextHashMap[IPV6]
            )
        )
    }

    private fun setLocationInfo() {
        val ctx = context ?: return
        val locationData = HashMap<String, Any>()
        val location = getLocationCoordinates(ctx)

        if (location != null) {
            locationData[LATITUDE] = location.latitude
            locationData[LONGITUDE] = location.longitude
            locationData[SPEED] = location.speed
        }

        put(LOCATION, locationData)
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
