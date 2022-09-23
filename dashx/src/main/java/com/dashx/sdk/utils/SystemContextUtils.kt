package com.dashx.sdk.utils

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.location.*
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.telephony.TelephonyManager
import android.telephony.gsm.GsmCellLocation
import android.util.Log
import androidx.core.content.ContextCompat.getSystemService
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


fun getIpHostAddresses(): HashMap<String, String> {
    val ipAddressHashMap = hashMapOf<String, String>()
    NetworkInterface.getNetworkInterfaces()?.toList()?.map { networkInterface ->
        networkInterface.inetAddresses?.toList()?.find {
            !it.isLoopbackAddress && (it is Inet4Address || it is Inet6Address)
        }?.let {
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
    val wifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    return wifiManager.isWifiEnabled
}

fun getCellularInfo(context: Context): Boolean? {
    val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
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

fun getLocationAddress(context: Context): List<Address> {
    val geocoder = Geocoder(context, Locale.getDefault())
    val location = getLocationCoordinates(context)
    return geocoder.getFromLocation(location?.latitude ?: 0.0, location?.longitude ?: 0.0, 1)
}

fun getLocationCoordinates(context: Context): Location? {
    val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager?
    var location: Location? = null

    val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager?
    if (telephony!!.phoneType == TelephonyManager.PHONE_TYPE_GSM) {
        val location: GsmCellLocation = telephony.cellLocation as GsmCellLocation
        if (location != null) {
            val LAC: String = location.getLac().toString()
            val CID: String = location.getCid().toString()
        }
    }

//    val locationManager = this.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    // Define a listener that responds to location updates

    // Define a listener that responds to location updates
    val locationListener: LocationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) { // Called when a new location is found by the network location provider.
//            makeUseOfNewLocation(location)
            Log.d("csfsgfdg",location.toString())
        }

        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    // Register the listener with the Location Manager to receive location updates

    // Register the listener with the Location Manager to receive location updates


//    if(PermissionChecker.PERMISSION_GRANTED == ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION )
//        || PermissionChecker.PERMISSION_GRANTED == ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION)) {
        try {
//            location = locationManager!!.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            locationManager?.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, Long.MIN_VALUE,0F,locationListener)
        }
        catch (e:Exception) {
        }
//    }
    return location
}

