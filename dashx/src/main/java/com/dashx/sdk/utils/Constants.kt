@file:JvmName("Constants")

package com.dashx.sdk.utils

const val PACKAGE_NAME = "com.dashx.sdk"
const val DEFAULT_INSTANCE = "default"

const val SHARED_PREFERENCES_PREFIX = PACKAGE_NAME
const val SHARED_PREFERENCES_KEY_ACCOUNT_UID = "$SHARED_PREFERENCES_PREFIX.$DEFAULT_INSTANCE.account_uid"
const val SHARED_PREFERENCES_KEY_ACCOUNT_ANONYMOUS_UID = "$SHARED_PREFERENCES_PREFIX.$DEFAULT_INSTANCE.account_anonymous_uid"
const val SHARED_PREFERENCES_KEY_IDENTITY_TOKEN = "$SHARED_PREFERENCES_PREFIX.$DEFAULT_INSTANCE.identity_token"
const val SHARED_PREFERENCES_KEY_DEVICE_TOKEN = "$SHARED_PREFERENCES_PREFIX.$DEFAULT_INSTANCE.device_token"
const val SHARED_PREFERENCES_KEY_BUILD = "$SHARED_PREFERENCES_PREFIX.$DEFAULT_INSTANCE.build"
const val INTERNAL_EVENT_APP_INSTALLED = "Application Installed"
const val INTERNAL_EVENT_APP_UPDATED = "Application Updated"
const val INTERNAL_EVENT_APP_OPENED = "Application Opened"
const val INTERNAL_EVENT_APP_BACKGROUNDED = "Application Backgrounded"
const val INTERNAL_EVENT_APP_CRASHED = "Application Crashed"
const val INTERNAL_EVENT_APP_SCREEN_VIEWED = "Screen Viewed"

const val DATA = "data"

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

object SystemContextConstants {
    const val LOCALE = "locale"
    const val TIME_ZONE = "timeZone"
    const val USER_AGENT = "userAgent"
    const val IPV4 = "ipV4"
    const val IPV6 = "ipV6"

    // Network
    const val NETWORK = "network"
    const val WIFI = "wifi"
    const val CELLULAR = "cellular"
    const val CARRIER = "carrier"
    const val BLUETOOTH = "bluetooth"

    // Device
    const val DEVICE = "device"
    const val AD_TRACKING_ENABLED = "adTrackingEnabled"
    const val ADVERTISING_ID = "advertisingId"
    const val ID = "id"
    const val KIND = "kind"
    const val MANUFACTURER = "manufacturer"
    const val MODEL = "model"
    const val NAME = "name"
    const val TOKEN = "token"
}
