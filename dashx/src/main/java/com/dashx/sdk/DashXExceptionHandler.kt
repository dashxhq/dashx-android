package com.dashx.android

class DashXExceptionHandler(private val mainExceptionHandler: Thread.UncaughtExceptionHandler) :
    Thread.UncaughtExceptionHandler {

    companion object {
        fun enable() {
            val defaultExceptionHandler = Thread.getDefaultUncaughtExceptionHandler()
            if (defaultExceptionHandler is DashXExceptionHandler) {
                return
            }

            if (defaultExceptionHandler != null) {
                val dashXExceptionHandler = DashXExceptionHandler(defaultExceptionHandler)
                Thread.setDefaultUncaughtExceptionHandler(dashXExceptionHandler)
            }
        }
    }

    override fun uncaughtException(thread: Thread, exception: Throwable) {
        DashX.trackAppCrashed(exception)

        mainExceptionHandler.uncaughtException(thread, exception)
    }
}
