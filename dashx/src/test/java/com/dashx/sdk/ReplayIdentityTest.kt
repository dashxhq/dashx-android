package com.dashx.android

import org.junit.Assert.assertEquals
import org.junit.Test

class ReplayIdentityTest {

    private val signedIn = DashX.Companion.AccountSnapshot(
        uid = "user-later",
        anonymousUid = "anon-now",
        identityToken = null,
        sessionGeneration = 0L
    )

    @Test
    fun replayKeepsANullAccountUid_soASignedOutEventIsNotCreditedToALaterUser() {
        assertEquals(null to "anon-then", DashX.replayIdentity(null, "anon-then", signedIn))
    }

    @Test
    fun replayKeepsTheQueuedUserOverTheCurrentOne() {
        assertEquals("user-then" to "anon-then", DashX.replayIdentity("user-then", "anon-then", signedIn))
    }

    @Test
    fun anEventQueuedWithoutIdentity_usesTheCurrentOne() {
        assertEquals("user-later" to "anon-now", DashX.replayIdentity(null, null, signedIn))
    }
}
