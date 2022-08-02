@file:JvmName("Constants")

package com.dashx.sdk


const val PACKAGE_NAME = "com.dashx.sdk"
const val DEFAULT_INSTANCE = "default"

const val SHARED_PREFERENCES_PREFIX = PACKAGE_NAME
const val SHARED_PREFERENCES_KEY_BUILD = "$SHARED_PREFERENCES_PREFIX.$DEFAULT_INSTANCE.build"
const val SHARED_PREFERENCES_KEY_DEVICE_TOKEN = "$SHARED_PREFERENCES_PREFIX.$DEFAULT_INSTANCE.device_token"
const val SHARED_PREFERENCES_KEY_ANONYMOUS_UID = "$SHARED_PREFERENCES_PREFIX.$DEFAULT_INSTANCE.anonymous_uid"
const val INTERNAL_EVENT_APP_INSTALLED = "Application Installed"
const val INTERNAL_EVENT_APP_UPDATED = "Application Updated"
const val INTERNAL_EVENT_APP_OPENED = "Application Opened"
const val INTERNAL_EVENT_APP_BACKGROUNDED = "Application Backgrounded"
const val INTERNAL_EVENT_APP_CRASHED = "Application Crashed"
const val INTERNAL_EVENT_APP_SCREEN_VIEWED = "Screen Viewed"

object UserAttributes {
    const val UID = "uid"
    const val ANONYMOUS_UID = "anonymousUid"
    const val EMAIL = "email"
    const val PHONE = "phone"
    const val NAME = "name"
    const val FIRST_NAME = "firstName"
    const val LAST_NAME = "lastName"
}

object RequestType {
    const val PUT = "PUT"
}

object FileConstants {
    const val CONTENT_TYPE = "Content-Type"
    const val IMAGE_CONTENT_TYPE = "image/*"
    const val VIDEO_CONTENT_TYPE = "video/*"
    const val FILE_CONTENT = "*/*"
}

object UploadConstants {
    const val POLL_INTERVAL: Long = 3000
    const val POLL_TIME_OUT = 10

    const val READY = "ready"
    const val WAITING = "waiting"
}
