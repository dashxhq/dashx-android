package com.dashx.android.realtime

import com.dashx.android.DashXLog
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** Handle returned by a subscription; call [unsubscribe] to stop receiving frames. */
interface DashXRealtimeSubscription {
    fun unsubscribe()
}

/**
 * One realtime channel subscriber. [onEstablished] fires on every server acknowledgement;
 * `isResubscribe` is `true` when this channel was already acknowledged under an earlier
 * connection generation — the signal to reconcile missed history.
 */
internal class SubscriberHandle(
    val channelName: String,
    val onFrame: (DashXRealtimeMessage) -> Unit,
    val onEstablished: (isResubscribe: Boolean) -> Unit
)

internal sealed interface RealtimeCommand {
    data class SocketOpened(val generation: Long, val socket: WebSocket) : RealtimeCommand
    data class SocketClosed(val generation: Long, val code: Int, val reason: String) : RealtimeCommand
    data class SocketFailed(val generation: Long, val cause: Throwable) : RealtimeCommand
    data class FrameReceived(val generation: Long, val text: String) : RealtimeCommand
    data class RetryConnect(val generation: Long) : RealtimeCommand
    data class Subscribe(val handle: SubscriberHandle) : RealtimeCommand
    data class Unsubscribe(val handle: SubscriberHandle) : RealtimeCommand
    data object Foregrounded : RealtimeCommand
    data object Backgrounded : RealtimeCommand
    /** Identity installed, replaced, or refreshed: recycle the socket under the new credentials. */
    data object IdentityChanged : RealtimeCommand
    data class EndSession(val ack: CompletableDeferred<Unit>) : RealtimeCommand
}

/**
 * Owns the single realtime WebSocket as an actor: OkHttp callbacks never mutate state, they enqueue
 * a generation-stamped command and return. One consumer coroutine — the sole writer — drains the
 * channel, so no field here needs volatile or atomic access.
 *
 * Instances are per-runtime: `shutdown()` sends [RealtimeCommand.EndSession] and detaches, and the
 * actor closes its own socket, channel, and scope as its last act. Nothing outside cancels it, so an
 * immediate re-`configure()` builds a fresh runtime the old teardown cannot touch.
 */
internal class RealtimeRuntime(
    private val urlProvider: () -> String?,
    /** Every decoded frame, after channel routing — feeds unread refresh triggers. */
    private val onAnyFrame: (DashXRealtimeMessage) -> Unit,
    /** A terminal 44xx close: DashX asks the bound token provider for a fresh token, once. */
    private val onAuthRejected: () -> Unit,
    /** State sink owned by DashX, which drops a detached runtime's late writes. */
    private val publishState: (ConnectionState) -> Unit,
    initialForeground: Boolean,
    /** Test seam; production uses the OkHttp client below. */
    private val socketFactory: ((Request, WebSocketListener) -> WebSocket)? = null
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val commands = Channel<RealtimeCommand>(Channel.UNLIMITED)

    private val client = OkHttpClient.Builder()
        // Protocol-level keepalive, independent of the app-level PING/PONG: keeps NAT/proxy
        // timeouts from silently blackholing an idle socket.
        .pingInterval(PING_INTERVAL_SECONDS, TimeUnit.SECONDS)
        .build()

    // ---- actor-owned state: single writer, no synchronization ----
    private var connectionGeneration = 0L
    private var socket: WebSocket? = null
    private var connectingSocket: WebSocket? = null
    private var isForeground = initialForeground
    private var authFailed = false
    private var reconnectAttempts = 0
    private val subscriptions = LinkedHashMap<String, CopyOnWriteArrayList<SubscriberHandle>>()
    /** Channel → generation of its first acknowledgement. NOT cleared on reconnect: a later-generation
     * acknowledgement is exactly how a re-subscribe is recognised. */
    private val firstAckGeneration = HashMap<String, Long>()

    init {
        scope.launch {
            for (command in commands) {
                try {
                    handle(command)
                } catch (t: Throwable) {
                    DashXLog.e(TAG, "Realtime actor error on $command: ${t.message}")
                }
                if (command is RealtimeCommand.EndSession) break
            }
        }
    }

    // ---- ingress (any thread) ----

    fun subscribe(handle: SubscriberHandle): DashXRealtimeSubscription {
        commands.trySend(RealtimeCommand.Subscribe(handle))
        return object : DashXRealtimeSubscription {
            override fun unsubscribe() {
                commands.trySend(RealtimeCommand.Unsubscribe(handle))
            }
        }
    }

    fun onForeground() { commands.trySend(RealtimeCommand.Foregrounded) }
    fun onBackground() { commands.trySend(RealtimeCommand.Backgrounded) }
    fun onIdentityChanged() { commands.trySend(RealtimeCommand.IdentityChanged) }

    /** Initiates teardown; the actor completes it. Returns a deferred for tests needing confirmation. */
    fun endSession(): CompletableDeferred<Unit> {
        val ack = CompletableDeferred<Unit>()
        if (commands.trySend(RealtimeCommand.EndSession(ack)).isFailure) ack.complete(Unit)
        return ack
    }

    // ---- actor ----

    private fun handle(command: RealtimeCommand) {
        when (command) {
            is RealtimeCommand.Subscribe -> {
                val isFirstForChannel = subscriptions
                    .getOrPut(command.handle.channelName) { CopyOnWriteArrayList() }
                    .let { it.add(command.handle); it.size == 1 }
                when {
                    socket == null && connectingSocket == null -> connect()
                    socket != null && isFirstForChannel ->
                        socket?.send(DashXRealtimeCodec.encodeChannelFrame(
                            DashXRealtimeCodec.TYPE_SUBSCRIBE, command.handle.channelName))
                    else -> Unit
                }
                publishState()
            }

            is RealtimeCommand.Unsubscribe -> {
                val list = subscriptions[command.handle.channelName] ?: return
                list.remove(command.handle)
                if (list.isEmpty()) {
                    subscriptions.remove(command.handle.channelName)
                    firstAckGeneration.remove(command.handle.channelName)
                    socket?.send(DashXRealtimeCodec.encodeChannelFrame(
                        DashXRealtimeCodec.TYPE_UNSUBSCRIBE, command.handle.channelName))
                }
                if (subscriptions.isEmpty()) closeSocket()
                publishState()
            }

            RealtimeCommand.Foregrounded -> {
                isForeground = true
                if (socket == null && connectingSocket == null) connect()
                publishState()
            }

            RealtimeCommand.Backgrounded -> {
                isForeground = false
                closeSocket()
                publishState()
            }

            RealtimeCommand.IdentityChanged -> {
                // A new identity is a fresh intent: terminal auth failure no longer applies, and any
                // socket — connected OR still connecting — carries the old token in its URL.
                authFailed = false
                reconnectAttempts = 0
                closeSocket()
                connect()
                publishState()
            }

            is RealtimeCommand.SocketOpened -> {
                if (command.generation != connectionGeneration) { command.socket.close(NORMAL_CLOSURE, null); return }
                connectingSocket = null
                socket = command.socket
                reconnectAttempts = 0
                // Server subscription state is per connection: every channel re-subscribes.
                subscriptions.keys.forEach {
                    command.socket.send(DashXRealtimeCodec.encodeChannelFrame(DashXRealtimeCodec.TYPE_SUBSCRIBE, it))
                }
                publishState()
            }

            is RealtimeCommand.FrameReceived -> {
                if (command.generation != connectionGeneration) return
                val frame = DashXRealtimeCodec.decode(command.text) ?: return
                when (frame) {
                    is DashXRealtimeMessage.Ping ->
                        socket?.send(DashXRealtimeCodec.encodeBareFrame(DashXRealtimeCodec.TYPE_PONG))
                    is DashXRealtimeMessage.SubscriptionSucceeded -> {
                        val first = firstAckGeneration[frame.channel]
                        val isResubscribe = first != null && first < connectionGeneration
                        if (first == null) firstAckGeneration[frame.channel] = connectionGeneration
                        subscriptions[frame.channel]?.forEach { it.onEstablished(isResubscribe) }
                    }
                    is DashXRealtimeMessage.InAppChatMessage ->
                        subscriptions[chatChannelName(frame.message.conversationId)]
                            ?.forEach { it.onFrame(frame) }
                    else -> Unit
                }
                onAnyFrame(frame)
            }

            is RealtimeCommand.SocketClosed -> {
                if (command.generation != connectionGeneration) return
                socket = null
                connectingSocket = null
                if (isTerminalCloseCode(command.code)) {
                    authFailed = true
                    DashXLog.e(TAG, "Realtime closed with terminal code ${command.code} (${command.reason})")
                    onAuthRejected()
                } else {
                    scheduleReconnect()
                }
                publishState()
            }

            is RealtimeCommand.SocketFailed -> {
                if (command.generation != connectionGeneration) return
                socket = null
                connectingSocket = null
                DashXLog.e(TAG, "Realtime failure: ${command.cause.message ?: command.cause::class.java.simpleName}")
                scheduleReconnect()
                publishState()
            }

            is RealtimeCommand.RetryConnect -> {
                if (command.generation != connectionGeneration) return
                if (socket == null && connectingSocket == null) connect()
            }

            is RealtimeCommand.EndSession -> {
                closeSocket()
                subscriptions.clear()
                firstAckGeneration.clear()
                publishState(ConnectionState.Idle)
                commands.close()
                command.ack.complete(Unit)
                scope.cancel()
            }
        }
    }

    /** Bumps the generation, so callbacks from the departing socket become stamped no-ops. */
    private fun closeSocket() {
        connectionGeneration += 1
        connectingSocket?.cancel()
        connectingSocket = null
        socket?.close(NORMAL_CLOSURE, null)
        socket = null
    }

    private fun connect() {
        if (authFailed || !isForeground || subscriptions.isEmpty()) return
        val url = urlProvider() ?: return  // no identity yet: stay Idle, T0 connects later

        connectionGeneration += 1
        val generation = connectionGeneration
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                commands.trySend(RealtimeCommand.SocketOpened(generation, webSocket))
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                commands.trySend(RealtimeCommand.FrameReceived(generation, text))
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                // OkHttp does not complete a server-initiated close for us.
                webSocket.close(code, reason)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                commands.trySend(RealtimeCommand.SocketClosed(generation, code, reason))
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                commands.trySend(RealtimeCommand.SocketFailed(generation, t))
            }
        }
        val request = Request.Builder().url(url).build()
        connectingSocket = socketFactory?.invoke(request, listener)
            ?: client.newWebSocket(request, listener)
        publishState()
    }

    private fun scheduleReconnect() {
        if (authFailed || !isForeground || subscriptions.isEmpty()) return
        reconnectAttempts += 1
        val base = (INITIAL_RECONNECT_DELAY_MS * (1L shl (reconnectAttempts - 1).coerceAtMost(5)))
            .coerceAtMost(MAX_RECONNECT_DELAY_MS)
        val delayMs = base / 2 + Random.nextLong(base / 2 + 1)
        val generation = connectionGeneration
        scope.launch {
            delay(delayMs)
            commands.trySend(RealtimeCommand.RetryConnect(generation))
        }
    }

    private fun publishState() {
        publishState(when {
            authFailed -> ConnectionState.AuthenticationFailed(null)
            socket != null -> ConnectionState.Connected
            connectingSocket != null -> ConnectionState.Connecting
            !isForeground && subscriptions.isNotEmpty() -> ConnectionState.Suspended
            subscriptions.isNotEmpty() && isForeground && urlProvider() != null -> ConnectionState.Connecting
            else -> ConnectionState.Idle
        })
    }

    companion object {
        private const val TAG = "DashXRealtime"
        private const val NORMAL_CLOSURE = 1000
        private const val PING_INTERVAL_SECONDS = 30L
        private const val INITIAL_RECONNECT_DELAY_MS = 1_000L
        private const val MAX_RECONNECT_DELAY_MS = 30_000L
        private const val TERMINAL_CLOSE_CODE_MIN = 4400
        private const val TERMINAL_CLOSE_CODE_MAX = 4499

        fun isTerminalCloseCode(code: Int): Boolean =
            code in TERMINAL_CLOSE_CODE_MIN..TERMINAL_CLOSE_CODE_MAX

        /** Chat is routed per conversation — a visitor may have several concurrent threads. */
        fun chatChannelName(conversationId: String): String =
            "in_app_chat:conversation:$conversationId"
    }
}
