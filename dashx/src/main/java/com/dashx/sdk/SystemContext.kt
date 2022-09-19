package com.dashx.sdk

import android.content.Context
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

    fun getSystemContext(): JSONObject {
        return JSONObject(systemContextHashMap as Map<String, Any>)
    }

    private fun put(key: String, value: Any) {
        systemContextHashMap[key] = value
    }
}
