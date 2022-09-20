package com.dashx.sdk

import android.content.Context
import com.dashx.sdk.SystemContextConstants.ADVERTISING_ID
import com.dashx.sdk.SystemContextConstants.AD_TRACKING_ENABLED
import com.dashx.sdk.SystemContextConstants.BLUETOOTH
import com.dashx.sdk.SystemContextConstants.CARRIER
import com.dashx.sdk.SystemContextConstants.CELLULAR
import com.dashx.sdk.SystemContextConstants.DEVICE
import com.dashx.sdk.SystemContextConstants.ID
import com.dashx.sdk.SystemContextConstants.KIND
import com.dashx.sdk.SystemContextConstants.MANUFACTURER
import com.dashx.sdk.SystemContextConstants.MODEL
import com.dashx.sdk.SystemContextConstants.NAME
import com.dashx.sdk.SystemContextConstants.NETWORK
import com.dashx.sdk.SystemContextConstants.TOKEN
import com.dashx.sdk.SystemContextConstants.WIFI
import com.dashx.sdk.utils.*
import org.json.JSONObject

class SystemContext {

    private var context: Context? = null
    private var systemContextHashMap = hashMapOf<String, Any>()

    companion object {

        private var INSTANCE: SystemContext = SystemContext()

        fun configure(context: Context): SystemContext {
            INSTANCE.init(context)
            return INSTANCE
        }

        @JvmName("getSystemContextInstance")
        fun getInstance(): SystemContext {
            if (INSTANCE.context == null) {
                throw NullPointerException("Configure SystemContext before accessing it.")
            }
            return INSTANCE
        }
    }

    fun init(context: Context) {
        this.context = context.applicationContext
    }

    fun setNetworkInfo() {
        val network = hashMapOf<String, Any>()

        network[BLUETOOTH] = getBluetoothInfo()
        network[CARRIER] = getCarrierInfo(context!!)
        network[CELLULAR] = getCellularInfo(context!!).toString()
        network[WIFI] = getWifiInfo(context!!)

        put(NETWORK, network)
    }

    fun setDeviceInfo() {
        val device = HashMap<String, Any>()

        device[AD_TRACKING_ENABLED] = getAdTrackingEnabled()
        device[ADVERTISING_ID] = getAdvertisingId(context!!)
        device[ID] = getDeviceId(context!!)
        device[KIND] = getDeviceKind()
        device[MANUFACTURER] = getDeviceManufacturer()
        device[MODEL] = getDeviceModel()
        device[NAME] = getDeviceName()
        device[TOKEN] = getDeviceToken(context!!)

        put(DEVICE, device)
    }

    fun getNetworkInfo(): JSONObject {
        return JSONObject(systemContextHashMap[NETWORK] as Map<String, Any>)
    }

    fun getDeviceInfo(): JSONObject {
        return JSONObject(systemContextHashMap[DEVICE] as Map<String, Any>)
    }

    fun getSystemContext(): JSONObject {
        return JSONObject(systemContextHashMap as Map<String, Any>)
    }

    private fun put(key: String, value: Any) {
        systemContextHashMap[key] = value
    }
}
