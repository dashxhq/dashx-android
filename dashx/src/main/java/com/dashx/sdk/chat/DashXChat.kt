package com.dashx.android.chat

import com.dashx.android.DashX
import com.dashx.android.DashXError
import com.dashx.android.DashXLog
import com.dashx.android.graphql.generated.FetchInAppChatConversationQuery
import com.dashx.android.graphql.generated.FetchInAppChatConversationsQuery
import com.dashx.android.graphql.generated.ResolveInAppChatConversationMutation
import com.dashx.android.graphql.generated.SendInAppChatMessageMutation
import com.dashx.android.realtime.DashXRealtimeMessage
import com.dashx.android.realtime.DashXRealtimeSubscription
import com.dashx.android.realtime.RealtimeRuntime
import com.dashx.android.realtime.SubscriberHandle
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/** Entry point: `DashX.chat(chatIdentityId)`. The identity id comes from the trusted backend that
 * created the conversation — the SDK never discovers or guesses it. */
fun DashX.Companion.chat(chatIdentityId: String): DashXChat = DashXChat(chatIdentityId)

/**
 * Identity-scoped chat surface. Conversations are NOT created here — creation is server-only; the
 * host's backend returns the `(conversationId, chatIdentityId)` pair this API consumes.
 */
class DashXChat internal constructor(val chatIdentityId: String) {

    fun openConversation(conversationId: String): DashXConversationLease =
        ChatCoordinator.open(ChatSessionKey(chatIdentityId, conversationId))

    fun fetchConversations(
        limit: Int? = null,
        page: Int? = null,
        statuses: List<String>? = null,
        properties: JsonObject? = null,
        onSuccess: (List<FetchInAppChatConversationsQuery.FetchInAppChatConversation>) -> Unit,
        onError: (DashXError) -> Unit
    ) = DashX.fetchInAppChatConversations(chatIdentityId, limit, page, statuses, properties, onSuccess, onError)

    fun fetchConversation(
        conversationId: String,
        onSuccess: (FetchInAppChatConversationQuery.FetchInAppChatConversation) -> Unit,
        onError: (DashXError) -> Unit
    ) = DashX.fetchInAppChatConversation(chatIdentityId, conversationId, onSuccess, onError)

    fun summarizeConversations(
        statuses: List<String>? = null,
        properties: JsonObject? = null,
        onSuccess: (Int) -> Unit,
        onError: (DashXError) -> Unit
    ) = DashX.summarizeInAppChatConversations(chatIdentityId, statuses, properties, onSuccess, onError)

    /** Near-real-time, not realtime: refreshed on foreground, push receipt, subscribed-conversation
     * frames, and successful mark-read — not on unsubscribed conversations' activity. */
    fun summarizeUnread(onSuccess: (Int) -> Unit, onError: (DashXError) -> Unit) =
        DashX.summarizeInAppChatUnread(chatIdentityId, onSuccess, onError)

    fun resolveConversation(
        conversationId: String,
        onSuccess: (ResolveInAppChatConversationMutation.ResolveInAppChatConversation) -> Unit,
        onError: (DashXError) -> Unit
    ) = DashX.resolveInAppChatConversation(chatIdentityId, conversationId, onSuccess, onError)
}

/**
 * A caller's handle on one conversation. Every [DashXChat.openConversation] returns a NEW lease;
 * internal subscription and history state is shared per `(identity, conversation)` and torn down
 * when the last lease closes. [close] is idempotent and affects only this lease.
 */
interface DashXConversationLease {
    val conversationId: String
    val state: StateFlow<ConversationState>

    fun addStateListener(listener: ConversationStateListener)
    fun removeStateListener(listener: ConversationStateListener)

    /** Fires when the session ends underneath this lease (identity switch, reset, shutdown). */
    fun setOnTerminated(callback: ((DashXSubscriptionEnd) -> Unit)?)

    /**
     * Sends a visitor message. Returns the client message id SYNCHRONOUSLY — the idempotency key: a
     * host-triggered retry of a failed send must reuse it via the raw operation.
     */
    fun sendMessage(
        content: JsonObject,
        onSuccess: (SendInAppChatMessageMutation.SendInAppChatMessage) -> Unit,
        onError: (DashXError) -> Unit
    ): String

    /** Prepends older history into the same [ConversationState.Ready] list. A failure leaves the
     * current list intact and reports through [onError]. */
    fun loadPreviousPage(onError: ((DashXError) -> Unit)? = null)

    /** Declares whether this conversation is on screen: drives read-marking and push suppression. */
    fun setVisible(visible: Boolean)

    fun close()
}

/** Owns the shared per-conversation sessions. */
internal object ChatCoordinator {

    private val sessions = ConcurrentHashMap<ChatSessionKey, ConversationSession>()

    fun open(key: ChatSessionKey): DashXConversationLease {
        while (true) {
            val session = sessions.computeIfAbsent(key) { ConversationSession(key) }
            val lease = session.newLease() ?: continue // lost a race with the session's teardown
            return lease
        }
    }

    internal fun remove(key: ChatSessionKey, session: ConversationSession) {
        sessions.remove(key, session)
    }

    /** T2 / T3 / T4: every open session ends; leases hold a terminal Error(SessionEnded). */
    fun closeAllSessions() {
        val open = sessions.values.toList()
        sessions.clear()
        open.forEach { it.endSession() }
    }

    /** T0 with waiting leases and no runtime signal yet. */
    fun onIdentityAvailable() {
        DashX.realtimeRuntime?.onIdentityChanged()
    }

    fun onAppForegrounded() {
        sessions.values.forEach { it.onAppForegrounded() }
    }

    /** Every decoded realtime frame — the unread-refresh trigger feed. */
    fun onGlobalFrame(frame: DashXRealtimeMessage) {
        // Reserved: near-real-time unread refresh hooks consume this.
    }
}

/**
 * Shared state for one `(identity, conversation)`: the realtime subscription, the reconciliation
 * buffer, the synchronized message list, and read marking. See the plan's §6 — first open and
 * rejected-state recovery both run the snapshot algorithm; reconnect currently rebuilds via the
 * same snapshot (the `afterMessageId` cursor replaces this once dashx-backend ships it).
 */
internal class ConversationSession(private val key: ChatSessionKey) {

    private val scope = CoroutineScope(SupervisorJob(DashX.chatSessionJob) + Dispatchers.IO)

    private val mutableState = MutableStateFlow<ConversationState>(ConversationState.Loading)
    val state: StateFlow<ConversationState> get() = mutableState
    private val listeners = CopyOnWriteArrayList<ConversationStateListener>()

    private val leases = CopyOnWriteArrayList<Lease>()
    private var ended = false
    private val lock = Any()

    // ---- synchronizer state (mutated only inside syncMutex-protected coroutines) ----
    private val buffer = ArrayList<ChatMessage>()
    private var buffering = true
    private var bufferOverflowed = false
    private var messages: List<ChatMessage> = emptyList()
    private var snapshotDone = false
    private var syncing = false
    private var oldestFetchedPage = Int.MAX_VALUE

    private var subscription: DashXRealtimeSubscription? = null

    // ---- read marking (validated tracker mechanics, session-internal) ----
    private var markedMessageId: String? = null
    private val markInFlight = AtomicBoolean(false)
    private var pendingMarkJob: Job? = null

    fun newLease(): DashXConversationLease? {
        synchronized(lock) {
            if (ended) return null
            val lease = Lease()
            leases.add(lease)
            if (subscription == null) {
                val handle = SubscriberHandle(
                    channelName = RealtimeRuntime.chatChannelName(key.conversationId),
                    onFrame = { frame -> onFrame(frame) },
                    onEstablished = { isResubscribe -> onEstablished(isResubscribe) }
                )
                subscription = DashX.requireRealtimeRuntime().subscribe(handle)
            }
            return lease
        }
    }

    // ---- realtime ingress (actor thread) ----

    private fun onFrame(frame: DashXRealtimeMessage) {
        val message = (frame as? DashXRealtimeMessage.InAppChatMessage)?.message ?: return
        val chatMessage = ChatMessage.from(message)
        scope.launch { mergeLive(chatMessage) }
    }

    private fun onEstablished(isResubscribe: Boolean) {
        scope.launch {
            if (!snapshotDone) {
                snapshotAndReplace()
            } else if (isResubscribe) {
                // Interim reconnect recovery: rebuild on a candidate and atomically replace. The
                // afterMessageId cursor loop replaces this once the backend argument ships.
                synchronized(lock) { buffering = true; buffer.clear(); bufferOverflowed = false }
                snapshotAndReplace()
            }
        }
    }

    // ---- synchronizer ----

    private suspend fun mergeLive(message: ChatMessage) {
        val shouldBuffer = synchronized(lock) {
            if (buffering) {
                if (buffer.size >= BUFFER_LIMIT) bufferOverflowed = true else buffer.add(message)
                true
            } else false
        }
        if (shouldBuffer) return

        val merged = mergeInto(messages, listOf(message))
        messages = merged
        emitReady(merged)
        maybeMarkRead()
    }

    /** First open, and (interim) reconnect: candidate snapshot, one merge, one atomic emission. */
    private suspend fun snapshotAndReplace() {
        synchronized(lock) {
            if (syncing) return
            syncing = true
        }
        try {
            while (true) {
                val count = DashX.awaitOperation<Int> { ok, err ->
                    DashX.summarizeInAppChatMessagesJob(key.conversationId, ok, err)
                }
                val lastPage = maxOf(1, (count + PAGE_SIZE - 1) / PAGE_SIZE)
                val rows = DashX.awaitOperation<List<com.dashx.android.graphql.generated.FetchInAppChatMessagesQuery.FetchInAppChatMessage>> { ok, err ->
                    DashX.fetchInAppChatMessagesJob(key.conversationId, PAGE_SIZE, lastPage, ok, err)
                }
                val candidate = rows.map { ChatMessage.from(it) }

                val (bufferSnapshot, overflowed) = synchronized(lock) {
                    val copy = buffer.toList()
                    val over = bufferOverflowed
                    if (!over) {
                        buffer.clear()
                        buffering = false
                    } else {
                        // Overflow: discard the candidate and repeat with a fresh buffer snapshot.
                        buffer.clear()
                        bufferOverflowed = false
                    }
                    copy to over
                }
                if (overflowed) continue

                val replacement = mergeInto(candidate.sortedWith(ChatMessage.ORDER), bufferSnapshot)
                messages = replacement
                snapshotDone = true
                oldestFetchedPage = lastPage
                emitReady(replacement)
                maybeMarkRead()
                return
            }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            synchronized(lock) { buffering = false }
            val error = (t as? com.dashx.android.DashXException)?.error
                ?: DashXError.NetworkError(t.message ?: "chat synchronization failed")
            mutableState.value = ConversationState.Error(error)
            notifyListeners(ConversationState.Error(error))
        } finally {
            synchronized(lock) { syncing = false }
        }
    }

    private fun mergeInto(base: List<ChatMessage>, additions: List<ChatMessage>): List<ChatMessage> {
        if (additions.isEmpty()) return base
        val byId = LinkedHashMap<String, ChatMessage>(base.size + additions.size)
        base.forEach { byId[it.id] = it }
        additions.forEach { byId[it.id] = it }
        return byId.values.sortedWith(ChatMessage.ORDER)
    }

    private fun emitReady(list: List<ChatMessage>) {
        val ready = ConversationState.Ready(list)
        mutableState.value = ready
        notifyListeners(ready)
    }

    private fun notifyListeners(state: ConversationState) {
        listeners.forEach { l ->
            DashX.launchCallback { runCatching { l.onConversationStateChanged(state) } }
        }
    }

    private fun loadPreviousPageInternal(onError: ((DashXError) -> Unit)?) {
        scope.launch {
            val page = oldestFetchedPage - 1
            if (!snapshotDone || page < 1) return@launch
            try {
                val rows = DashX.awaitOperation<List<com.dashx.android.graphql.generated.FetchInAppChatMessagesQuery.FetchInAppChatMessage>> { ok, err ->
                    DashX.fetchInAppChatMessagesJob(key.conversationId, PAGE_SIZE, page, ok, err)
                }
                oldestFetchedPage = page
                // Prepends older rows; the high-water mark (newest message) cannot move backward.
                val merged = mergeInto(messages, rows.map { ChatMessage.from(it) })
                messages = merged
                emitReady(merged)
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                val error = (t as? com.dashx.android.DashXException)?.error
                    ?: DashXError.NetworkError(t.message ?: "loadPreviousPage failed")
                onError?.let { cb -> DashX.launchCallback { cb(error) } }
            }
        }
    }

    // ---- read marking ----

    private fun anyLeaseVisible() = leases.any { it.visibleNow }

    private fun maybeMarkRead() {
        if (!anyLeaseVisible()) return
        val newest = messages.lastOrNull()?.id ?: return
        if (newest == markedMessageId) return
        pendingMarkJob?.cancel()
        pendingMarkJob = scope.launch {
            delay(MARK_DEBOUNCE_MS) // coalesce the burst a history load or rapid exchange produces
            markNow(newest)
        }
    }

    private fun markNow(messageId: String) {
        if (messageId == markedMessageId) return
        if (!markInFlight.compareAndSet(false, true)) return
        DashX.markInAppChatConversationReadJob(
            identityId = key.chatIdentityId,
            conversationId = key.conversationId,
            lastMessageId = messageId,
            onSuccess = { success ->
                markInFlight.set(false)
                if (success) markedMessageId = messageId
                maybeMarkRead() // a newer message may have rendered while this was in flight
            },
            onError = {
                // Left unmarked on purpose: the next message retries, and the only cost of a missed
                // mark is a push the visitor did not need.
                markInFlight.set(false)
                DashXLog.e(TAG, "Failed to mark ${key.conversationId} read: ${it.message}")
            }
        )
    }

    fun onAppForegrounded() {
        scope.launch { maybeMarkRead() }
    }

    // ---- lifecycle ----

    fun endSession() {
        val toNotify: List<Lease>
        synchronized(lock) {
            if (ended) return
            ended = true
            toNotify = leases.toList()
            leases.clear()
        }
        subscription?.unsubscribe()
        subscription = null
        DashX.pushRuntime.setConversationVisible(key.conversationId, false)
        val terminal = ConversationState.Error(DashXError.SessionEnded())
        mutableState.value = terminal
        notifyListeners(terminal)
        toNotify.forEach { lease ->
            lease.terminatedCallback?.let { cb -> DashX.launchCallback { cb(DashXSubscriptionEnd.SessionEnded) } }
        }
        ChatCoordinator.remove(key, this)
        scope.cancel()
    }

    private fun closeLease(lease: Lease) {
        val teardown: Boolean
        synchronized(lock) {
            if (!leases.remove(lease)) return
            if (lease.visibleNow) {
                lease.visibleNow = false
                if (!anyLeaseVisible()) DashX.pushRuntime.setConversationVisible(key.conversationId, false)
            }
            teardown = leases.isEmpty() && !ended
            if (teardown) ended = true
        }
        if (teardown) {
            subscription?.unsubscribe()
            subscription = null
            ChatCoordinator.remove(key, this)
            scope.cancel()
        }
    }

    private inner class Lease : DashXConversationLease {
        @Volatile var visibleNow = false
        @Volatile var terminatedCallback: ((DashXSubscriptionEnd) -> Unit)? = null
        private val closed = AtomicBoolean(false)

        override val conversationId: String get() = key.conversationId
        override val state: StateFlow<ConversationState> get() = this@ConversationSession.state

        override fun addStateListener(listener: ConversationStateListener) { listeners.add(listener) }
        override fun removeStateListener(listener: ConversationStateListener) { listeners.remove(listener) }
        override fun setOnTerminated(callback: ((DashXSubscriptionEnd) -> Unit)?) { terminatedCallback = callback }

        override fun sendMessage(
            content: JsonObject,
            onSuccess: (SendInAppChatMessageMutation.SendInAppChatMessage) -> Unit,
            onError: (DashXError) -> Unit
        ): String {
            // Generated BEFORE the network attempt and returned synchronously: the idempotency key a
            // host-triggered retry must reuse.
            val clientMessageId = UUID.randomUUID().toString()
            DashX.sendInAppChatMessageJob(
                conversationId = key.conversationId,
                identityId = key.chatIdentityId,
                content = content,
                clientMessageId = clientMessageId,
                onSuccess = onSuccess,
                onError = onError
            )
            return clientMessageId
        }

        override fun loadPreviousPage(onError: ((DashXError) -> Unit)?) {
            loadPreviousPageInternal(onError)
        }

        override fun setVisible(visible: Boolean) {
            if (closed.get()) return
            this.visibleNow = visible
            val any = anyLeaseVisible()
            DashX.pushRuntime.setConversationVisible(key.conversationId, any)
            if (visible) {
                com.dashx.android.push.DashXPush.dismissConversation(key.conversationId)
                scope.launch { maybeMarkRead() }
            }
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            closeLease(this)
        }
    }

    companion object {
        private const val TAG = "DashXChat"
        internal const val PAGE_SIZE = 50
        private const val BUFFER_LIMIT = 500
        private const val MARK_DEBOUNCE_MS = 400L
    }
}
