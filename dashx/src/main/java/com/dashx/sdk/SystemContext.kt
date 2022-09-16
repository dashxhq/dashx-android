package com.dashx.sdk

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Context.CONNECTIVITY_SERVICE
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Resources
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.telephony.TelephonyManager
import androidx.core.app.ActivityCompat
import com.google.android.gms.ads.identifier.AdvertisingIdClient
import com.google.android.gms.appset.AppSet
import com.google.android.gms.appset.AppSetIdInfo
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.*


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

        //User Agent
        private const val USER_AGENT = "user_agent"
        private const val UID = "uid"
        private const val ADVERTISING_UID = "advertising_uid"
        private const val APP_SET_UID = "app_set_uid"
        private const val APP_SET_SCOPE = "app_set_scope"
        private const val AD_TRACKING = "ad_tracking"
        private const val MANUFACTURER = "manufacturer"
        private const val MODEL = "model"
        private const val OS_NAME = "os_name"
        private const val OS_VERSION = "os_version"
        private const val SCREEN_WIDTH = "width"
        private const val SCREEN_HEIGHT = "height"
        private const val SCREEN_DENSITY = "density"
        private const val SCREEN_ORIENTATION = "orientation"
        private const val PORTRAIT = "portrait"
        private const val LANDSCAPE = "landscape"
        private const val SQUARE = "square"

        //Local
        private const val LOCAL = "local"
        private const val LOCALE = "locale"
        private const val CURRENCY = "currency"
        private const val TIME_ZONE = "time_zone"

        //Network
        private const val NETWORK = "network"
        private const val WIFI = "wifi"
        private const val CELLULAR = "cellular"
        private const val CARRIER = "carrier"
        private const val BLUETOOTH = "bluetooth"

        private var INSTANCE: SystemContext = SystemContext()

        fun configure(context: Context): SystemContext {
            INSTANCE.init(context)
            return INSTANCE
        }

        @JvmName("getSystemContextInstance")
        fun getInstance(): SystemContext {
            try {
                return INSTANCE
            } catch (exception: Exception) {
                throw NullPointerException("Create DashXClient before accessing it.")
            }
        }
    }

    private fun init(context: Context) {
        this.context = context.applicationContext
        setAppInfo()
        setLibraryInfo()
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

    private fun setLibraryInfo() {
        val library = HashMap<String, Any>()
        library[NAME] = BuildConfig.LIBRARY_PACKAGE_NAME
        library[VERSION] = BuildConfig.VERSION_NAME
        put(LIBRARY, library)
    }

    fun setUserAgentInfo() {
        val userAgent = HashMap<String, Any>()

        val model = Build.MODEL
        val manufacturer = Build.MANUFACTURER
        val displayMetricsInfo = Resources.getSystem().displayMetrics
        val fields = Build.VERSION_CODES::class.java.fields
        val orientation = context!!.resources.configuration.orientation

        val client = AppSet.getClient(context!!)
        val task: Task<AppSetIdInfo> = client.appSetIdInfo

        userAgent[UID] =
            Settings.Secure.getString(context!!.getContentResolver(), Settings.Secure.ANDROID_ID)

        GlobalScope.launch {
            val adInfo = AdvertisingIdClient.getAdvertisingIdInfo(context!!)
            userAgent[ADVERTISING_UID] = adInfo?.id.toString()
        }

        task.addOnSuccessListener {
            userAgent[APP_SET_UID] = it.id
            userAgent[APP_SET_SCOPE] = it.scope
        }

        userAgent[NAME] = if (model.startsWith(manufacturer)) {
            model.capitalize()
        } else {
            "$manufacturer $model".capitalize()
        }

        userAgent[MANUFACTURER] = manufacturer
        userAgent[MODEL] = model

        fields.filter { it.getInt(Build.VERSION_CODES::class) == Build.VERSION.SDK_INT }
            .forEach {
                userAgent[OS_NAME] = it.name
            }

        userAgent[OS_VERSION] = Build.VERSION.SDK
        userAgent[SCREEN_HEIGHT] = displayMetricsInfo.heightPixels
        userAgent[SCREEN_WIDTH] = displayMetricsInfo.widthPixels
        userAgent[SCREEN_DENSITY] = displayMetricsInfo.densityDpi

        userAgent[SCREEN_ORIENTATION] = when (orientation) {
            1 -> PORTRAIT
            2 -> LANDSCAPE
            else -> {
                SQUARE
            }
        }
        put(USER_AGENT, userAgent)
    }

    fun setLocalInfo() {
        val local = HashMap<String, Any>()

        val locale = context?.resources?.configuration?.locale

        local[LOCALE] = locale.toString()
        local[CURRENCY] = Currency.getInstance(locale).currencyCode
        local[TIME_ZONE] = TimeZone.getDefault().id
        put(LOCAL, local)
    }

    fun setNetworkInfo() {
        val network = HashMap<String, Any>()

        val wifiManager = context!!.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val mConnectivityManager =
            context!!.getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val mInfo = mConnectivityManager.activeNetworkInfo
        val telephonyManager =
            context!!.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()

        network[BLUETOOTH] = if (bluetoothAdapter != null) {
            if (ActivityCompat.checkSelfPermission(
                    context!!,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                bluetoothAdapter.address
                return
            } else {
                ""
            }
        } else {
            ""
        }

        network[WIFI] = wifiManager.connectionInfo.bssid
        network[CARRIER] = telephonyManager.networkOperatorName

        network[CELLULAR] = if (mInfo?.type == ConnectivityManager.TYPE_MOBILE) {
            when (mInfo.subtype) {
                TelephonyManager.NETWORK_TYPE_GPRS, TelephonyManager.NETWORK_TYPE_EDGE, TelephonyManager.NETWORK_TYPE_CDMA, TelephonyManager.NETWORK_TYPE_1xRTT, TelephonyManager.NETWORK_TYPE_IDEN, TelephonyManager.NETWORK_TYPE_GSM -> "2G"
                TelephonyManager.NETWORK_TYPE_UMTS, TelephonyManager.NETWORK_TYPE_EVDO_0, TelephonyManager.NETWORK_TYPE_EVDO_A, TelephonyManager.NETWORK_TYPE_HSDPA, TelephonyManager.NETWORK_TYPE_HSUPA, TelephonyManager.NETWORK_TYPE_HSPA, TelephonyManager.NETWORK_TYPE_EVDO_B, TelephonyManager.NETWORK_TYPE_EHRPD, TelephonyManager.NETWORK_TYPE_HSPAP, TelephonyManager.NETWORK_TYPE_TD_SCDMA -> "3G"
                TelephonyManager.NETWORK_TYPE_LTE, TelephonyManager.NETWORK_TYPE_IWLAN, 19 -> "4G"
                TelephonyManager.NETWORK_TYPE_NR -> "5G"
                else -> ""
            }
        } else {
            ""
        }

        put(NETWORK, network)
    }

    fun getAppInfo(): JSONObject {
        return JSONObject(systemContextHashMap[APP] as Map<String, Any>)
    }

    fun getLibraryInfo(): JSONObject {
        return JSONObject(systemContextHashMap[LIBRARY] as Map<String, Any>)
    }

    fun getUserAgentInfo(): JSONObject {
        return JSONObject(systemContextHashMap[USER_AGENT] as Map<String, Any>)
    }

    fun getLocalInfo(): JSONObject {
        return JSONObject(systemContextHashMap[LOCAL] as Map<String, Any>)
    }

    fun getNetworkInfo(): JSONObject {
        return JSONObject(systemContextHashMap[NETWORK] as Map<String, Any>)
    }

    fun getSystemContext(): JSONObject {
        return JSONObject(systemContextHashMap as Map<String, Any>)
    }

    private fun put(key: String, value: Any) {
        systemContextHashMap[key] = value
    }

}
