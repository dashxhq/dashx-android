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

    /** Wire contract: existing app versions, customer analytics and the public docs use these names. */
    @Test
    fun sdkEventNames_keepTheirPublishedNames() {
        assertEquals("Application Installed", INTERNAL_EVENT_APP_INSTALLED)
        assertEquals("Application Updated", INTERNAL_EVENT_APP_UPDATED)
        assertEquals("Application Opened", INTERNAL_EVENT_APP_OPENED)
        assertEquals("Application Backgrounded", INTERNAL_EVENT_APP_BACKGROUNDED)
        assertEquals("Application Crashed", INTERNAL_EVENT_APP_CRASHED)
        assertEquals("\$screen", INTERNAL_EVENT_APP_SCREEN_VIEWED)
        assertEquals("Deep Link Opened", EVENT_DEEP_LINK_OPENED)
        assertEquals("dx_notification_navigated", EVENT_NOTIFICATION_NAVIGATED)
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
