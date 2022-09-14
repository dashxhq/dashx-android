package com.dashx.sdk

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Resources
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.telephony.TelephonyManager
import androidx.core.app.ActivityCompat
import com.google.android.gms.ads.identifier.AdvertisingIdClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.*


class SystemContext {

    private var context: Context? = null
    private var systemContextHashMap = hashMapOf<String,Any>()

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
            hashMap[RELEASE_MODE] = if (0 != context?.applicationInfo?.flags!! and ApplicationInfo.FLAG_DEBUGGABLE) {
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

    val adInfo = AdvertisingIdClient(context!!)

     suspend fun getAdvertisingId(): String =
        withContext(Dispatchers.IO) {
            //Connect with start(), disconnect with finish()
            adInfo.start()
            val adIdInfo = adInfo.info
            adInfo.finish()
            adIdInfo.id
        }

    fun setUserAgentInfo() {
        val userAgent = HashMap<String, Any>()
        userAgent[UID] = Settings.Secure.getString(context!!.getContentResolver(), Settings.Secure.ANDROID_ID)
        userAgent[ADVERTISING_UID] = runBlocking { getAdvertisingId() }

//        val client = AppSet.getClient(context!!)
//        val task: Task<AppSetIdInfo> = client.appSetIdInfo
//        task.addOnSuccessListener {
//            userAgent[APP_SET_SCOPE] = it.scope
//            userAgent[APP_SET_UID] = it.id
//        }

        val model = Build.MODEL
        val manufacturer = Build.MANUFACTURER

        userAgent[NAME] = if (model.startsWith(manufacturer)) {
            model.capitalize()
        } else {
            "$manufacturer $model".capitalize()
        }

        userAgent[MANUFACTURER] = Build.MANUFACTURER
        userAgent[MODEL] = Build.MODEL

//        val fields = VERSION_CODES::class.java.fields
//        userAgent[OS_NAME] = fields[Build.VERSION.SDK_INT + 1].name

        userAgent[OS_VERSION] = Build.VERSION.SDK_INT

        val displayMetricsInfo = Resources.getSystem().displayMetrics
        userAgent[SCREEN_HEIGHT] = displayMetricsInfo.heightPixels
        userAgent[SCREEN_WIDTH] = displayMetricsInfo.widthPixels
        userAgent[SCREEN_DENSITY] = displayMetricsInfo.densityDpi
        userAgent[SCREEN_ORIENTATION] = context!!.resources.configuration.orientation

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
        val telephonyManager = context!!.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        val bluetoothAdapter =  BluetoothAdapter.getDefaultAdapter()

        if (ActivityCompat.checkSelfPermission(context!!, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            val name = bluetoothAdapter.name
            if (name == null) {
                network[BLUETOOTH] = bluetoothAdapter.address
            }
            return
        }

        network[WIFI] = wifiManager.connectionInfo.ssid
        network[CARRIER] = telephonyManager.networkOperatorName
        put(NETWORK, network)
    }

    fun getAppInfo(): JSONObject {
        return JSONObject(systemContextHashMap[APP] as Map<String, Any>)
    }

    fun getLibraryInfo(): JSONObject {
        return JSONObject(systemContextHashMap[LIBRARY] as Map<String, Any>)
    }

    fun getSystemContext():JSONObject {
        return JSONObject(systemContextHashMap as Map<String, Any>)
    }

    private fun put(key: String, value: Any) {
        systemContextHashMap[key] = value
    }

}
