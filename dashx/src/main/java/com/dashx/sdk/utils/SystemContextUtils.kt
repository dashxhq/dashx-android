package com.dashx.android.utils

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
import com.dashx.android.DashX
import com.dashx.android.utils.SystemContextConstants.ADVERTISING_ID
import com.dashx.android.utils.SystemContextConstants.ADVERTISING_INFO_FETCHED
import com.dashx.android.utils.SystemContextConstants.AD_TRACKING_ENABLED
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
    val configuration = context.resources.configuration
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        configuration.locales[0]
    } else {
        configuration.locale
    }
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
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
    // ANDROID_ID can be null on certain OEM builds despite the docs;
    // coalesce to "" so callers can `isNotEmpty()` instead of null-checking.
    return Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: ""
}

/** Empty when [getAdvertisingInfo] hasn't resolved, the user opted out, or Play Services is unavailable. */
fun getStoredAdvertisingId(context: Context): String {
    return getDashXSharedPreferences(context).getString(ADVERTISING_ID, "") ?: ""
}

/** Defaults to `false` — treat unknown as opt-out, matching SystemContext. */
fun isAdTrackingEnabled(context: Context): Boolean {
    return getDashXSharedPreferences(context).getBoolean(AD_TRACKING_ENABLED, false)
}

/** `true` once [getAdvertisingInfo] has resolved (success or failure). */
fun hasAdvertisingInfoBeenFetched(context: Context): Boolean {
    return getDashXSharedPreferences(context).getBoolean(ADVERTISING_INFO_FETCHED, false)
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
        model.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
    } else {
        "$manufacturer $model".replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
    }
}

fun getDeviceKind(): String {
    return "android"
}

fun getAdvertisingInfo(context: Context?) {
    CoroutineScope(Dispatchers.IO).launch {
        var adInfo: AdvertisingIdClient.Info? = null
        try {
            adInfo = context?.let { AdvertisingIdClient.getAdvertisingIdInfo(it) }
        } catch (e: Exception) {
            // Typically GooglePlayServicesNotAvailableException. We still
            // mark the fetch as completed below so the backfill trigger
            // converges instead of looping.
            e.printStackTrace()
        }
        context?.let {
            val prefs = getDashXSharedPreferences(it)
            val previousAdvertisingId = prefs.getString(ADVERTISING_ID, "") ?: ""
            val previousAdTrackingEnabled = prefs.getBoolean(AD_TRACKING_ENABLED, false)
            val newAdvertisingId = adInfo?.id ?: ""
            val newAdTrackingEnabled = !(adInfo?.isLimitAdTrackingEnabled ?: true)
            prefs.edit().apply {
                putString(ADVERTISING_ID, newAdvertisingId)
                putBoolean(AD_TRACKING_ENABLED, newAdTrackingEnabled)
                putBoolean(ADVERTISING_INFO_FETCHED, true)
                // Invalidate the ad-info version when the ID or consent
                // changes — otherwise the next refresh short-circuits on
                // the version match and the new state is stranded.
                if (newAdvertisingId != previousAdvertisingId ||
                    newAdTrackingEnabled != previousAdTrackingEnabled
                ) {
                    remove(SHARED_PREFERENCES_KEY_SUBSCRIBED_AD_INFO_VERSION)
                }
            }.apply()
        }
        DashX.refreshSubscriptionDeviceInfo()
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
