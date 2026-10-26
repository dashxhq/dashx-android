package com.dashx.android

import com.dashx.android.utils.*
import org.junit.Assert.*
import org.junit.Test

class ConstantsTest {

    @Test
    fun sharedPreferencesKeys_haveCorrectPrefix() {
        val prefix = "com.dashx.android.default"
        assertTrue(SHARED_PREFERENCES_KEY_ACCOUNT_UID.startsWith(prefix))
        assertTrue(SHARED_PREFERENCES_KEY_ACCOUNT_ANONYMOUS_UID.startsWith(prefix))
        assertTrue(SHARED_PREFERENCES_KEY_IDENTITY_TOKEN.startsWith(prefix))
        assertTrue(SHARED_PREFERENCES_KEY_DEVICE_TOKEN.startsWith(prefix))
        assertTrue(SHARED_PREFERENCES_KEY_BUILD.startsWith(prefix))
    }

    /** Wire contract shared with the browser, iOS and React Native SDKs. */
    @Test
    fun sdkEventNames_matchCrossPlatformConvention() {
        assertEquals("\$app_installed", INTERNAL_EVENT_APP_INSTALLED)
        assertEquals("\$app_updated", INTERNAL_EVENT_APP_UPDATED)
        assertEquals("\$app_opened", INTERNAL_EVENT_APP_OPENED)
        assertEquals("\$app_backgrounded", INTERNAL_EVENT_APP_BACKGROUNDED)
        assertEquals("\$app_crashed", INTERNAL_EVENT_APP_CRASHED)
        assertEquals("\$screen", INTERNAL_EVENT_APP_SCREEN_VIEWED)
        assertEquals("\$deep_link_opened", EVENT_DEEP_LINK_OPENED)
        assertEquals("\$notification_navigated", EVENT_NOTIFICATION_NAVIGATED)
    }

    @Test
    fun userAttributes_haveExpectedKeys() {
        assertEquals("uid", UserAttributes.UID)
        assertEquals("anonymousUid", UserAttributes.ANONYMOUS_UID)
        assertEquals("email", UserAttributes.EMAIL)
        assertEquals("phone", UserAttributes.PHONE)
        assertEquals("name", UserAttributes.NAME)
        assertEquals("firstName", UserAttributes.FIRST_NAME)
        assertEquals("lastName", UserAttributes.LAST_NAME)
    }

    @Test
    fun uploadConstants_pollValues() {
        assertEquals(3000L, UploadConstants.POLL_INTERVAL)
        assertEquals(10, UploadConstants.POLL_TIME_OUT)
    }

    @Test
    fun fileConstants_contentTypes() {
        assertEquals("Content-Type", FileConstants.CONTENT_TYPE)
        assertEquals("image/*", FileConstants.IMAGE_CONTENT_TYPE)
        assertEquals("video/*", FileConstants.VIDEO_CONTENT_TYPE)
        assertEquals("*/*", FileConstants.FILE_CONTENT)
    }
}
