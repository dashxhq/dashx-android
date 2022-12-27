package com.dashx.sdk

import android.util.Log

object DashXLog {
    private val tag = DashXLog::class.java.simpleName

    enum class LogLevel(val code: Int) {
        DEBUG(2), INFO(1), ERROR(0), OFF(-1);
    }

    private var logLevel = LogLevel.ERROR

    @JvmStatic
    fun setLogLevel(logLevel: LogLevel) {
        DashXLog.logLevel = logLevel
    }

    @JvmStatic
    fun d(tag: String? = this.tag, logText: String?) {
        if (logLevel.code >= LogLevel.DEBUG.code) {
            Log.d(tag, logText ?: "")
        }
    }

    @JvmStatic
    fun i(tag: String? = this.tag, logText: String?) {
        if (logLevel.code >= LogLevel.INFO.code) {
            Log.i(tag, logText ?: "")
        }
    }

    @JvmStatic
    fun e(tag: String? = this.tag, logText: String?) {
        if (logLevel.code >= LogLevel.ERROR.code) {
            Log.e(tag, logText ?: "")
        }
    }
}
