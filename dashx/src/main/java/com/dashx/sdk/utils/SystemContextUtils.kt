package com.dashx.sdk.utils

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.AsyncTask
import android.os.Build
import android.provider.Settings
import android.telephony.TelephonyManager
import com.google.android.gms.ads.identifier.AdvertisingIdClient
import com.google.android.gms.common.GooglePlayServicesNotAvailableException
import com.google.android.gms.common.GooglePlayServicesRepairableException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.*

private val coroutineScope = CoroutineScope(Dispatchers.IO)

fun getBluetoothInfo(): Boolean {
    val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
    return bluetoothAdapter.isEnabled
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

@SuppressLint("HardwareIds")
fun getDeviceToken(context: Context): String {
    val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    return telephonyManager.deviceId
}

fun getAdvertisingId(context: Context): String {
    val adInfo = AdvertisingIdClient(context.applicationContext)
    var advertisingId: String ?= ""
    CoroutineScope(Dispatchers.IO).launch {
        adInfo.start()
        val adIdInfo = adInfo.info
        adInfo.finish()
        advertisingId = adIdInfo.id
        return@launch
    }
    return advertisingId ?: ""
}

fun getDeviceKind(): String {
    return Build.TYPE
}

fun getAdTrackingEnabled(): Boolean {
    val adInfo: AdvertisingIdClient.Info? = null
    return adInfo?.isLimitAdTrackingEnabled ?: false
}

