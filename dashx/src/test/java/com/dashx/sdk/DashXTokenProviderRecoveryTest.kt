package com.dashx.android

import com.dashx.android.realtime.ConnectionState
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

private fun awaitUntil(timeoutMs: Long = 4000, what: String = "condition", condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (condition()) return
        Thread.sleep(10)
    }
    fail("Timed out waiting for: $what")
}

/**
 * Drives the token-provider machinery through DashX's static surface — no configure(), no Android.
 * Storage calls no-op without a context, and no realtime runtime exists, so identity installs
 * publish [ConnectionState.Idle] directly.
 */
class DashXTokenProviderRecoveryTest {

    private lateinit var uid: String

    @Before
    fun isolate() {
        uid = "user-" + UUID.randomUUID()
        // Cycle through a real identity so the clear is a genuine T2 (setIdentity(null, null) on an
        // already-clean account is a no-op that would leave a previous test's state standing).
        DashX.setIdentity("isolation-$uid", "isolation-token")
        DashX.setIdentity(null, null)
        assertEquals(ConnectionState.Idle, DashX.connectionState.value)
    }

    @After
    fun cleanup() {
        DashX.setIdentity(null, null)
    }

    @Test
    fun providerThrowingSynchronously_clearsTheSingleFlightSlot() {
        DashX.setIdentity(uid, "cached-token")
        val calls = AtomicInteger(0)
        DashX.setIdentityTokenProvider(uid, DashXTokenProvider { _, _ ->
            calls.incrementAndGet()
            throw IllegalStateException("host provider bug")
        })

        // Both refreshes must terminate: a leaked in-flight slot would hang the second await
        // forever on the first load's never-completed deferred.
        assertFalse(runBlocking { DashX.awaitTokenRefresh() })
        assertFalse(runBlocking { DashX.awaitTokenRefresh() })
        assertEquals("each refresh must reach the provider", 2, calls.get())
        awaitUntil(what = "AuthenticationFailed") {
            DashX.connectionState.value is ConnectionState.AuthenticationFailed
        }
    }

    @Test
    fun registeringProviderAfterAuthFailure_forcesARefresh() {
        DashX.setIdentity(uid, "rejected-token")
        DashX.setIdentityTokenProvider(uid, DashXTokenProvider { _, callback ->
            callback.onUnavailable(null)
        })
        assertFalse(runBlocking { DashX.awaitTokenRefresh() })
        awaitUntil(what = "AuthenticationFailed") {
            DashX.connectionState.value is ConnectionState.AuthenticationFailed
        }

        // Re-registering under AuthenticationFailed is the host's retry gesture: it must load
        // immediately, with forceRefresh — the cached token is exactly the rejected one.
        val seenForceRefresh = AtomicReference<Boolean?>(null)
        DashX.setIdentityTokenProvider(uid, DashXTokenProvider { forceRefresh, callback ->
            seenForceRefresh.set(forceRefresh)
            callback.onToken("fresh-token")
        })

        awaitUntil(what = "fresh token installed") { DashX.account.get().identityToken == "fresh-token" }
        assertEquals(true, seenForceRefresh.get())
        awaitUntil(what = "recovered to Idle") { DashX.connectionState.value == ConnectionState.Idle }
    }

    @Test
    fun explicitToken_supersedesAnInFlightLoad() {
        DashX.setIdentity(uid, "t0")
        val providerInvoked = CountDownLatch(1)
        val release = CountDownLatch(1)
        DashX.setIdentityTokenProvider(uid, DashXTokenProvider { _, callback ->
            Thread {
                providerInvoked.countDown()
                release.await(10, TimeUnit.SECONDS)
                callback.onToken("stale-late-token")
            }.start()
        })

        val refresh = runBlocking {
            val deferred = async(Dispatchers.IO) { DashX.awaitTokenRefresh() }
            assertTrue(providerInvoked.await(4, TimeUnit.SECONDS))

            DashX.setIdentity(uid, "explicit-t9") // T1 while the load is in flight

            val result = deferred.await()
            release.countDown()
            result
        }

        // The awaiting retry gets the explicit token, and the late provider result is stale
        // under the bumped token epoch — it must never overwrite t9.
        assertTrue(refresh)
        assertEquals("explicit-t9", DashX.account.get().identityToken)
        Thread.sleep(300)
        assertEquals("explicit-t9", DashX.account.get().identityToken)
    }

    @Test
    fun replacedProvider_invalidatesThePredecessorsInFlightLoad() {
        val firstInvoked = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        DashX.setIdentityTokenProvider(uid, DashXTokenProvider { _, callback -> // T0: load starts
            Thread {
                firstInvoked.countDown()
                releaseFirst.await(10, TimeUnit.SECONDS)
                callback.onToken("first-provider-token")
            }.start()
        })
        assertTrue(firstInvoked.await(4, TimeUnit.SECONDS))

        DashX.setIdentityTokenProvider(uid, DashXTokenProvider { _, callback ->
            callback.onToken("second-provider-token")
        })

        awaitUntil(what = "second provider's token") {
            DashX.account.get().identityToken == "second-provider-token"
        }
        releaseFirst.countDown()
        Thread.sleep(300)
        assertEquals(
            "the replaced provider's late result must not install",
            "second-provider-token",
            DashX.account.get().identityToken
        )
    }
}
