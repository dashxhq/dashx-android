package com.dashx.sdk.utils

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Resources
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.dashx.sdk.utils.SystemContextConstants.AD_TRACKING_ENABLED
import com.dashx.sdk.utils.SystemContextConstants.ADVERTISING_ID
import com.dashx.sdk.utils.SystemContextConstants.LAST_GPS_POINT_X
import com.dashx.sdk.utils.SystemContextConstants.LAST_GPS_POINT_Y
import com.google.android.gms.ads.identifier.AdvertisingIdClient
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.NetworkInterface
import java.util.*
import kotlin.math.pow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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

fun getBluetoothInfo(context: Context?): Boolean {
    val bluetoothManager = context?.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    return bluetoothManager.adapter.isEnabled
}

fun getWifiInfo(context: Context): Boolean {
    val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    return wifiManager.isWifiEnabled
}

fun getCellularInfo(context: Context): Boolean? {
    val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    return connectivityManager.activeNetworkInfo?.isConnected
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

fun getLocationAddress(context: Context) {
    val geocoder = Geocoder(context, Locale.getDefault())
    val location = getLocationCoordinates(context)

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val geocodeListener = Geocoder.GeocodeListener { addresses ->
            // do something with the addresses list
        }

        geocoder.getFromLocation(location?.latitude ?: 0.0, location?.longitude ?: 0.0, 1, geocodeListener)
    } else {
        val addresses = geocoder.getFromLocation(location?.latitude ?: 0.0, location?.longitude ?: 0.0, 1)

    }
}

fun getLocationCoordinates(context: Context): Location? {
    val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager?
    var location: Location? = null

    try {
        if (
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        ) {
            location = locationManager!!.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            context.let {
                getDashXSharedPreferences(it).edit().apply {
                    putFloat(LAST_GPS_POINT_X, (location?.latitude?.toFloat()!!))
                    putFloat(LAST_GPS_POINT_Y, (location.longitude.toFloat()))
                }
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return location
}

fun getSpeed(context: Context): Double {
    val lastGPSPointX = context.let {
        getDashXSharedPreferences(it).getFloat(LAST_GPS_POINT_X, 0F)
    }

    val lastGPSPointY = context.let {
        getDashXSharedPreferences(it).getFloat(LAST_GPS_POINT_Y, 0F)
    }

    getLocationCoordinates(context).let {
        val currentGPSPointX = it?.latitude
        val currentGPSPointY = it?.longitude
        val results = FloatArray(1)
        if (currentGPSPointY != null) {
            if (currentGPSPointX != null) {
                Location.distanceBetween(lastGPSPointX.toDouble(), currentGPSPointX.toDouble(), lastGPSPointY.toDouble(), currentGPSPointY.toDouble(), results)
            }
        }

        if (currentGPSPointX != null) {
            val gpsPointX = currentGPSPointX - lastGPSPointX
            val gpsPointY = currentGPSPointY?.minus(lastGPSPointX)
            return kotlin.math.sqrt((gpsPointX).pow(2) + (gpsPointY)?.pow(2)!!) / results[0]
        }
    }
    return 0.0
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
