package com.dashx.sdk

class DashXExceptionHandler(private val mainExceptionHandler: Thread.UncaughtExceptionHandler)
    : Thread.UncaughtExceptionHandler {
    private val dashXClient: DashXClient = DashXClient.instance

    companion object {
        fun enable() {
            val defaultExceptionHandler = Thread.getDefaultUncaughtExceptionHandler()
            if (defaultExceptionHandler is DashXExceptionHandler) {
                return
            }

            val dashXExceptionHandler = DashXExceptionHandler(defaultExceptionHandler)
            Thread.setDefaultUncaughtExceptionHandler(dashXExceptionHandler)
        }
    }

    override fun uncaughtException(thread: Thread?, exception: Throwable?) {
        dashXClient.trackAppCrashed(exception)
        mainExceptionHandler.uncaughtException(thread, exception)
    }
}
