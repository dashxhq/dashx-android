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

    @Test
    fun internalEventNames_areNotEmpty() {
        assertTrue(INTERNAL_EVENT_APP_INSTALLED.isNotEmpty())
        assertTrue(INTERNAL_EVENT_APP_UPDATED.isNotEmpty())
        assertTrue(INTERNAL_EVENT_APP_OPENED.isNotEmpty())
        assertTrue(INTERNAL_EVENT_APP_BACKGROUNDED.isNotEmpty())
        assertTrue(INTERNAL_EVENT_APP_CRASHED.isNotEmpty())
        assertTrue(INTERNAL_EVENT_APP_SCREEN_VIEWED.isNotEmpty())
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
