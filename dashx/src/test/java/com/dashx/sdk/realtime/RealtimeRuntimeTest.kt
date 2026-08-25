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
private class Harness(initialForeground: Boolean = true, var url: String? = "wss://realtime.test/socket") {
    val states = CopyOnWriteArrayList<ConnectionState>()
    val authRejections = AtomicInteger(0)
    val sockets = CopyOnWriteArrayList<FakeWebSocket>()
    val listeners = CopyOnWriteArrayList<WebSocketListener>()
    val established = CopyOnWriteArrayList<Boolean>()

    val runtime = RealtimeRuntime(
        urlProvider = { url },
        onAnyFrame = { },
        onAuthRejected = { authRejections.incrementAndGet() },
        publishState = { states.add(it) },
        initialForeground = initialForeground,
        socketFactory = { request, listener ->
            FakeWebSocket(request).also { sockets.add(it); listeners.add(listener) }
        }
    )

    fun subscribe(conversationId: String = "c1"): DashXRealtimeSubscription =
        runtime.subscribe(SubscriberHandle(
            channelName = RealtimeRuntime.chatChannelName(conversationId),
            onFrame = { },
            onEstablished = { established.add(it) }
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
}
