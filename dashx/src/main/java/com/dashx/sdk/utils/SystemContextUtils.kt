package com.dashx.sdk.utils

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Resources
import android.location.Location
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.dashx.sdk.utils.SystemContextConstants.ADVERTISING_ID
import com.dashx.sdk.utils.SystemContextConstants.AD_TRACKING_ENABLED
import com.google.android.gms.ads.identifier.AdvertisingIdClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.NetworkInterface
import java.util.*

private val displayMetricsInfo = Resources.getSystem().displayMetrics

fun getIpHostAddresses(): HashMap<String, String> {
    val ipAddressHashMap = hashMapOf<String, String>()
    NetworkInterface.getNetworkInterfaces()?.toList()?.map { networkInterface ->
        networkInterface.inetAddresses?.toList()?.filter {
            !it.isLoopbackAddress && (it is Inet4Address || it is Inet6Address)
        }?.map {
            when (it) {
                is Inet4Address -> {
                    ipAddressHashMap.put(SystemContextConstants.IPV4, it.hostAddress ?: "")
                }
                is Inet6Address -> {
                    ipAddressHashMap.put(SystemContextConstants.IPV6, it.hostAddress ?: "")
                }
                else -> {

                }
            }
        }
    }
    return ipAddressHashMap
}

fun getAppLocale(context: Context): Locale? {
    return context.resources?.configuration?.locale
}

fun getAppTimeZone(): String {
    return TimeZone.getDefault().id
}

fun getAppUserAgent(): String {
    return System.getProperty("http.agent") ?: ""
}

fun getBluetoothInfo(context: Context): Boolean {
    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R) {
        if (!PermissionUtils.hasPermissions(context, android.Manifest.permission.BLUETOOTH)) {
            return false
        }
    }

    val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    return bluetoothManager.adapter.isEnabled
}

fun getWifiInfo(context: Context): Boolean {
    return if (PermissionUtils.hasPermissions(
            context,
            android.Manifest.permission.ACCESS_WIFI_STATE
        )
    ) {
        val wifiManager =
            context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiManager.isWifiEnabled
    } else false
}

@SuppressLint("MissingPermission")
fun getCellularInfo(context: Context): Boolean {
    if (PermissionUtils.hasPermissions(context, android.Manifest.permission.ACCESS_NETWORK_STATE)) {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        if (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val capabilities =
                connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)

            if (capabilities != null) {
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                    return true
                }
            }
        } else {
            val activeNetworkInfo = connectivityManager.activeNetworkInfo

            if (activeNetworkInfo != null && activeNetworkInfo.isConnected) {
                return true
            }
        }
    }

    return false
}

fun getCarrierInfo(context: Context): String {
    val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    return telephonyManager.networkOperatorName
}

@SuppressLint("HardwareIds")
fun getDeviceId(context: Context): String {
    return Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
}

fun getDeviceManufacturer(): String {
    return Build.MANUFACTURER
}

fun getDeviceModel(): String {
    return Build.MODEL
}

fun getDeviceName(): String {
    val model = Build.MODEL
    val manufacturer = Build.MANUFACTURER
    return if (model.startsWith(manufacturer)) {
        model.capitalize(Locale.ROOT)
    } else {
        "$manufacturer $model".capitalize(Locale.ROOT)
    }
}

fun getDeviceKind(): String {
    return "android"
}

fun getAdvertisingInfo(context: Context?) {
    var adInfo: AdvertisingIdClient.Info? = null
    CoroutineScope(Dispatchers.IO).launch {
        try {
            adInfo = context?.let { AdvertisingIdClient.getAdvertisingIdInfo(it) }
            context?.let {
                getDashXSharedPreferences(it).edit().apply {
                    putString(ADVERTISING_ID, adInfo?.id)
                    putBoolean(AD_TRACKING_ENABLED, !(adInfo?.isLimitAdTrackingEnabled ?: true))
                }.apply()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

@SuppressLint("MissingPermission")
fun getLocationCoordinates(context: Context): Location? {
    if (
        ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_COARSE_LOCATION
        ) != PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        ) != PackageManager.PERMISSION_GRANTED
    ) {
        return null
    }

    val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    return locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
}

fun getOsName(): String {
    var osName = ""
    val fields = Build.VERSION_CODES::class.java.fields
    fields.filter { it.getInt(Build.VERSION_CODES::class) == Build.VERSION.SDK_INT }
        .forEach {
            osName = it.name
        }
    return osName
}

fun getOsVersion(): String {
    return Build.VERSION.RELEASE
}

fun getScreenHeight(): Int {
    return displayMetricsInfo.heightPixels
}

fun getScreenWidth(): Int {
    return displayMetricsInfo.widthPixels
}

fun getScreenDensity(): Int {
    return displayMetricsInfo.densityDpi
}
