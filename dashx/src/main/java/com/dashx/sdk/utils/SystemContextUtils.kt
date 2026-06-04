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
    // Settings.Secure.getString can return null on certain OEM/edge cases
    // even though the platform docs imply otherwise — fall back to "" so
    // callers (subscribe deviceUid, SystemContext device.id) can do a
    // single `isNotEmpty()` check without risking an NPE on those devices.
    return Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: ""
}

/**
 * Returns the advertising ID captured asynchronously by [getAdvertisingInfo]
 * and persisted in [SharedPreferences]. Empty string when the async fetch
 * hasn't completed yet, when the user has opted out of ad tracking, or when
 * Google Play Services is unavailable.
 */
fun getStoredAdvertisingId(context: Context): String {
    return getDashXSharedPreferences(context).getString(ADVERTISING_ID, "") ?: ""
}

/**
 * Returns the user's ad-tracking consent state captured by
 * [getAdvertisingInfo]. Defaults to `false` (treat unknown as opt-out)
 * which matches what [SystemContext] already sends to analytics.
 */
fun isAdTrackingEnabled(context: Context): Boolean {
    return getDashXSharedPreferences(context).getBoolean(AD_TRACKING_ENABLED, false)
}

/**
 * Whether [getAdvertisingInfo] has completed (successfully or with an
 * exception). Used by subscribe to decide whether to commit
 * `SHARED_PREFERENCES_KEY_SUBSCRIBED_AD_INFO_VERSION` (the ad-info-sync
 * marker, NOT the core subscribed-library-version marker, which is
 * always committed): if the fetch is still in flight, hold off so the
 * next refreshSubscriptionDeviceInfo run picks up the freshly-populated
 * ad-id; if the fetch has finished (regardless of result), it's safe to
 * mark ad-info as synced for this SDK version.
 */
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
            // Typically GooglePlayServicesNotAvailableException — the device has no
            // working Play Services so the advertising ID will never be available.
            // We still mark the fetch as completed below so the backfill trigger
            // converges to a no-op state instead of looping.
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
                // Mark the fetch as resolved (success OR failure). Subsequent calls
                // to DashX.refreshSubscriptionDeviceInfo will see this flag via
                // [hasAdvertisingInfoBeenFetched] and commit the ad-info version
                // on the next subscribe — bounding the retry to one extra round-trip.
                putBoolean(ADVERTISING_INFO_FETCHED, true)
                // If the user reset their advertising ID or flipped ad-tracking
                // consent since the last sync, invalidate the synced ad-info
                // version marker so [DashX.refreshSubscriptionDeviceInfo] sees a
                // gen-mismatch and re-sends the contact's ad-info to the backend.
                // Without this, [SHARED_PREFERENCES_KEY_SUBSCRIBED_AD_INFO_VERSION]
                // already matching the current SDK version short-circuits the
                // refresh, and the new ad-id/consent state is stranded locally
                // until the FCM token rotates or the SDK version bumps.
                if (newAdvertisingId != previousAdvertisingId ||
                    newAdTrackingEnabled != previousAdTrackingEnabled
                ) {
                    remove(SHARED_PREFERENCES_KEY_SUBSCRIBED_AD_INFO_VERSION)
                }
            }.apply()
        }
        // Trigger the optional ad-info backfill if a token is already saved and
        // this SDK version hasn't synced ad info yet. No-op when subscribe hasn't
        // run yet (no saved token) or when ad info is already current.
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
