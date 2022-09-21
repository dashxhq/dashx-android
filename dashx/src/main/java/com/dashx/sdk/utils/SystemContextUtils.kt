package com.dashx.sdk.utils

import android.content.Context
import com.dashx.sdk.SystemContextConstants
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.NetworkInterface
import java.util.*
import kotlin.collections.HashMap

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
