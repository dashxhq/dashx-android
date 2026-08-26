package com.dashx.android.realtime

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private class FakeWebSocket(private val originalRequest: Request) : WebSocket {
    val sent = CopyOnWriteArrayList<String>()
    @Volatile var closedCode: Int? = null
    @Volatile var cancelled = false

    override fun request(): Request = originalRequest
    override fun queueSize(): Long = 0L
    override fun send(text: String): Boolean { sent.add(text); return true }
    override fun send(bytes: ByteString): Boolean = true
    override fun close(code: Int, reason: String?): Boolean { closedCode = code; return true }
    override fun cancel() { cancelled = true }
}

/** Drives the actor through the socket-factory seam; no network, no Android runtime. */
private class Harness(
    initialForeground: Boolean = true,
    var url: String? = "wss://realtime.test/socket",
    ackTimeoutMs: Long = 60_000
) {
    val states = CopyOnWriteArrayList<ConnectionState>()
    val authRejections = AtomicInteger(0)
    val sockets = CopyOnWriteArrayList<FakeWebSocket>()
    val listeners = CopyOnWriteArrayList<WebSocketListener>()
    val established = CopyOnWriteArrayList<Boolean>()
    val subscribeErrors = CopyOnWriteArrayList<com.dashx.android.DashXError>()

    val runtime = RealtimeRuntime(
        urlProvider = { url },
        onAnyFrame = { },
        onAuthRejected = { authRejections.incrementAndGet() },
        publishState = { states.add(it) },
        initialForeground = initialForeground,
        socketFactory = { request, listener ->
            FakeWebSocket(request).also { sockets.add(it); listeners.add(listener) }
        },
        ackTimeoutMs = ackTimeoutMs
    )

    fun subscribe(conversationId: String = "c1"): DashXRealtimeSubscription =
        runtime.subscribe(SubscriberHandle(
            channelName = RealtimeRuntime.chatChannelName(conversationId),
            onFrame = { },
            onEstablished = { established.add(it) },
            onSubscribeError = { subscribeErrors.add(it) }
        ))

    fun open(index: Int = sockets.size - 1) {
        val socket = sockets[index]
        listeners[index].onOpen(socket, okResponse(socket.request()))
    }

    fun ack(channel: String, index: Int = listeners.size - 1) {
        listeners[index].onMessage(
            sockets[index],
            """{"type":"SUBSCRIPTION_SUCCEEDED","data":{"channel":"$channel"}}"""
        )
    }

    private fun okResponse(request: Request): Response = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(101).message("switching").build()
}

private fun awaitUntil(timeoutMs: Long = 4000, what: String = "condition", condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (condition()) return
        Thread.sleep(10)
    }
    fail("Timed out waiting for: $what")
}

class RealtimeRuntimeTest {

    @Test
    fun subscribeWithoutIdentity_opensNoSocket_staysIdle() {
        val harness = Harness(url = null)
        harness.subscribe()
        Thread.sleep(300)
        assertTrue("no speculative connect without an identity token", harness.sockets.isEmpty())
        assertEquals(ConnectionState.Idle, harness.states.lastOrNull() ?: ConnectionState.Idle)
    }

    @Test
    fun subscribeConnects_sendsChannelSubscribeOnOpen() {
        val harness = Harness()
        harness.subscribe("c1")
        awaitUntil(what = "socket created") { harness.sockets.size == 1 }
        harness.open()
        awaitUntil(what = "Connected") { harness.states.lastOrNull() == ConnectionState.Connected }
        awaitUntil(what = "SUBSCRIBE frame") {
            harness.sockets[0].sent.any { it.contains("SUBSCRIBE") && it.contains("in_app_chat:conversation:c1") }
        }
    }

    @Test
    fun reconnectAck_reportsResubscribe_firstAckDoesNot() {
        val harness = Harness()
        harness.subscribe("c1")
        awaitUntil(what = "socket 1") { harness.sockets.size == 1 }
        harness.open(0)
        harness.ack("in_app_chat:conversation:c1", 0)
        awaitUntil(what = "first ack") { harness.established.size == 1 }
        assertFalse("first acknowledgement is not a resubscribe", harness.established[0])

        // Connection failure → backoff → a NEW socket under a new generation.
        harness.listeners[0].onFailure(harness.sockets[0], RuntimeException("boom"), null)
        awaitUntil(what = "reconnect socket") { harness.sockets.size == 2 }
        harness.open(1)
        harness.ack("in_app_chat:conversation:c1", 1)
        awaitUntil(what = "second ack") { harness.established.size == 2 }
        assertTrue("an ack under a later generation IS a resubscribe", harness.established[1])
    }

    @Test
    fun lastUnsubscribe_closesSocket_andStaleCloseDoesNotReconnect() {
        val harness = Harness()
        val subscription = harness.subscribe("c1")
        awaitUntil(what = "socket created") { harness.sockets.size == 1 }
        harness.open()
        awaitUntil(what = "Connected") { harness.states.lastOrNull() == ConnectionState.Connected }

        subscription.unsubscribe()
        awaitUntil(what = "deliberate close") { harness.sockets[0].closedCode == 1000 }
        // The socket's own onClosed arrives late, stamped with the departed generation.
        harness.listeners[0].onClosed(harness.sockets[0], 1000, "")
        Thread.sleep(1600) // longer than the first backoff step
        assertEquals("a deliberate disconnect must not reconnect", 1, harness.sockets.size)
    }

    @Test
    fun backgroundCancelsConnectingSocket_andStaleOpenIsNotInstalled() {
        val harness = Harness()
        harness.subscribe("c1")
        awaitUntil(what = "connecting socket") { harness.sockets.size == 1 }

        harness.runtime.onBackground()
        awaitUntil(what = "connecting socket cancelled") { harness.sockets[0].cancelled }

        // The connection completes anyway — after the generation moved on.
        harness.open(0)
        awaitUntil(what = "stale socket closed") { harness.sockets[0].closedCode != null }
        assertNotEquals(ConnectionState.Connected, harness.states.lastOrNull())
        awaitUntil(what = "Suspended") { harness.states.lastOrNull() == ConnectionState.Suspended }
    }

    @Test
    fun terminalClose_publishesAuthFailed_firesHook_noRetry_identityChangeRevives() {
        val harness = Harness()
        harness.subscribe("c1")
        awaitUntil(what = "socket 1") { harness.sockets.size == 1 }
        harness.open(0)
        awaitUntil(what = "Connected") { harness.states.lastOrNull() == ConnectionState.Connected }

        harness.listeners[0].onClosed(harness.sockets[0], 4401, "UNAUTHORIZED")
        awaitUntil(what = "AuthenticationFailed") {
            harness.states.lastOrNull() is ConnectionState.AuthenticationFailed
        }
        awaitUntil(what = "auth hook") { harness.authRejections.get() == 1 }
        Thread.sleep(1600)
        assertEquals("terminal close must not retry", 1, harness.sockets.size)

        // Defect-5 regression: a fresh identity revives even a terminally-closed runtime.
        harness.runtime.onIdentityChanged()
        awaitUntil(what = "revived socket") { harness.sockets.size == 2 }
    }

    @Test
    fun terminal4401_refreshesOnce_secondRejectionStaysTerminal() {
        val harness = Harness()
        harness.subscribe("c1")
        awaitUntil(what = "socket 1") { harness.sockets.size == 1 }
        harness.open(0)

        harness.listeners[0].onClosed(harness.sockets[0], 4401, "UNAUTHORIZED")
        awaitUntil(what = "refresh hook") { harness.authRejections.get() == 1 }

        // The refresh installs a token; a refresh-driven identity change earns one reconnect
        // but must not re-arm the hook.
        harness.runtime.onIdentityChanged(fromAuthRefresh = true)
        awaitUntil(what = "reconnect socket") { harness.sockets.size == 2 }
        harness.open(1)
        harness.listeners[1].onClosed(harness.sockets[1], 4401, "UNAUTHORIZED")

        awaitUntil(what = "AuthenticationFailed") {
            harness.states.lastOrNull() is ConnectionState.AuthenticationFailed
        }
        Thread.sleep(400)
        assertEquals("the second 4401 must not refresh again", 1, harness.authRejections.get())
        assertEquals("no further reconnects", 2, harness.sockets.size)
    }

    @Test
    fun terminal4403_neverRefreshes() {
        val harness = Harness()
        harness.subscribe("c1")
        awaitUntil(what = "socket 1") { harness.sockets.size == 1 }
        harness.open(0)

        harness.listeners[0].onClosed(harness.sockets[0], 4403, "FORBIDDEN")
        awaitUntil(what = "AuthenticationFailed") {
            harness.states.lastOrNull() is ConnectionState.AuthenticationFailed
        }
        Thread.sleep(400)
        assertEquals("a permission problem must not burn a token refresh", 0, harness.authRejections.get())
        assertEquals(1, harness.sockets.size)
    }

    @Test
    fun acknowledgement_reArmsTheRefreshHook() {
        val harness = Harness()
        harness.subscribe("c1")
        awaitUntil(what = "socket 1") { harness.sockets.size == 1 }
        harness.open(0)
        harness.listeners[0].onClosed(harness.sockets[0], 4401, "UNAUTHORIZED")
        awaitUntil(what = "first refresh") { harness.authRejections.get() == 1 }

        harness.runtime.onIdentityChanged(fromAuthRefresh = true)
        awaitUntil(what = "socket 2") { harness.sockets.size == 2 }
        harness.open(1)
        // The server accepts the refreshed token for real work…
        harness.ack("in_app_chat:conversation:c1", 1)
        awaitUntil(what = "ack processed") { harness.established.size == 1 }

        // …so a LATER 4401 (ordinary expiry) earns a fresh refresh cycle.
        harness.listeners[1].onClosed(harness.sockets[1], 4401, "UNAUTHORIZED")
        awaitUntil(what = "second refresh") { harness.authRejections.get() == 2 }
    }

    @Test
    fun unacknowledgedSubscription_timesOutToSubscribeError_ackPreventsIt() {
        val harness = Harness(ackTimeoutMs = 150)
        harness.subscribe("c1")
        awaitUntil(what = "socket created") { harness.sockets.size == 1 }
        harness.open(0)
        awaitUntil(what = "subscribe error", timeoutMs = 2000) { harness.subscribeErrors.size == 1 }
        assertTrue(harness.subscribeErrors[0] is com.dashx.android.DashXError.SubscriptionFailed)

        // A second conversation whose ack arrives in time never errors.
        harness.subscribe("c2")
        awaitUntil(what = "SUBSCRIBE for c2") {
            harness.sockets[0].sent.any { it.contains("in_app_chat:conversation:c2") }
        }
        harness.ack("in_app_chat:conversation:c2", 0)
        Thread.sleep(400)
        assertEquals("an acked channel must not time out", 1, harness.subscribeErrors.size)
    }

    @Test
    fun reopenedChannel_onALiveSocket_getsAFreshAckDeadline() {
        val harness = Harness(ackTimeoutMs = 150)
        val first = harness.subscribe("c1")
        harness.subscribe("c2") // keeps the socket alive across c1's close
        awaitUntil(what = "socket created") { harness.sockets.size == 1 }
        harness.open(0)
        harness.ack("in_app_chat:conversation:c1", 0)
        harness.ack("in_app_chat:conversation:c2", 0)
        awaitUntil(what = "both acked") { harness.established.size == 2 }
        Thread.sleep(300)
        assertEquals("acked channels never time out", 0, harness.subscribeErrors.size)

        first.unsubscribe()
        awaitUntil(what = "UNSUBSCRIBE sent") {
            harness.sockets[0].sent.any { it.contains("UNSUBSCRIBE") && it.contains("conversation:c1") }
        }
        assertEquals("socket stays alive for c2", null, harness.sockets[0].closedCode)

        // Reopen c1 on the SAME connection; the server never acks this new attempt. The earlier
        // acknowledgement must not satisfy the new subscription's deadline.
        harness.subscribe("c1")
        awaitUntil(what = "fresh deadline fires", timeoutMs = 2000) { harness.subscribeErrors.size == 1 }
        assertTrue(harness.subscribeErrors[0] is com.dashx.android.DashXError.SubscriptionFailed)
        Thread.sleep(300)
        assertEquals("exactly one deadline for the new attempt", 1, harness.subscribeErrors.size)
    }

    @Test
    fun endSession_completesAck_andClosesEverything() = runBlocking {
        val harness = Harness()
        harness.subscribe("c1")
        awaitUntil(what = "socket created") { harness.sockets.size == 1 }
        harness.open()

        harness.runtime.endSession().await()
        assertNotNull(harness.sockets[0].closedCode)
        assertEquals(ConnectionState.Idle, harness.states.last())

        // The command channel is closed; late ingress must be a silent no-op, not a crash.
        harness.subscribe("c2")
        Thread.sleep(200)
        assertEquals(1, harness.sockets.size)
    }

    @Test
    fun subscribeAfterTerminalAuthClose_surfacesSubscriptionErrorImmediately() {
        val harness = Harness()
        harness.subscribe("c1")
        awaitUntil(what = "socket created") { harness.sockets.size == 1 }
        harness.open()
        harness.listeners[0].onClosed(harness.sockets[0], 4403, "forbidden")
        awaitUntil(what = "AuthenticationFailed") {
            harness.states.lastOrNull() is ConnectionState.AuthenticationFailed
        }

        // With connect() refusing to run, no SUBSCRIBE frame ever goes out for c2 — the error must
        // surface now instead of leaving the conversation loading forever.
        harness.subscribe("c2")
        awaitUntil(what = "c2 surfaces a subscription error") {
            harness.subscribeErrors.any {
                it is com.dashx.android.DashXError.SubscriptionFailed &&
                    it.message.contains("in_app_chat:conversation:c2")
            }
        }
        assertEquals("no new socket while auth-failed", 1, harness.sockets.size)
    }
}
