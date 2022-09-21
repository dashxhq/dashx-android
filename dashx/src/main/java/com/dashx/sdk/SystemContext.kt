package com.dashx.sdk

import android.content.Context
import com.dashx.sdk.utils.*
import com.dashx.sdk.utils.SystemContextConstants.ADVERTISING_ID
import com.dashx.sdk.utils.SystemContextConstants.AD_TRACKING_ENABLED
import com.dashx.sdk.utils.SystemContextConstants.BLUETOOTH
import com.dashx.sdk.utils.SystemContextConstants.CARRIER
import com.dashx.sdk.utils.SystemContextConstants.CELLULAR
import com.dashx.sdk.utils.SystemContextConstants.DEVICE
import com.dashx.sdk.utils.SystemContextConstants.ID
import com.dashx.sdk.utils.SystemContextConstants.KIND
import com.dashx.sdk.utils.SystemContextConstants.MANUFACTURER
import com.dashx.sdk.utils.SystemContextConstants.MODEL
import com.dashx.sdk.utils.SystemContextConstants.NAME
import com.dashx.sdk.utils.SystemContextConstants.NETWORK
import com.dashx.sdk.utils.SystemContextConstants.WIFI
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

        @JvmName("getSystemContextInstance") fun getInstance(): SystemContext {
            if (INSTANCE.context == null) {
                throw NullPointerException("Configure SystemContext before accessing it.")
            }
            getAdvertisingInfo(INSTANCE.context)
            return INSTANCE
        }
    }

    fun init(context: Context) {
        this.context = context.applicationContext
        getAdvertisingInfo(this.context)
    }

    fun setNetworkInfo() {
        val network = hashMapOf<String, Any>()

        network[BLUETOOTH] = getBluetoothInfo(context)
        network[CARRIER] = getCarrierInfo(context!!)
        network[CELLULAR] = getCellularInfo(context!!).toString()
        network[WIFI] = getWifiInfo(context!!)

        put(NETWORK, network)
    }

    fun setDeviceInfo() {
        val device = HashMap<String, Any>()
        device[AD_TRACKING_ENABLED] = context?.let { getDashXSharedPreferences(it).getBoolean(AD_TRACKING_ENABLED, false) } ?: false
        device[ADVERTISING_ID] = context?.let { getDashXSharedPreferences(it).getString(ADVERTISING_ID, "") } ?: ""
        device[ID] = getDeviceId(context!!)
        device[KIND] = getDeviceKind()
        device[MANUFACTURER] = getDeviceManufacturer()
        device[MODEL] = getDeviceModel()
        device[NAME] = getDeviceName()

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

    fun fetchSystemContext(): JSONObject {
        setNetworkInfo()
        setDeviceInfo()
        return getSystemContext()
    }

    private fun put(key: String, value: Any) {
        systemContextHashMap[key] = value
    }
}
