package com.dashx.android.chat

import com.dashx.android.DashX
import com.dashx.android.DashXError
import com.dashx.android.DashXLog
import com.dashx.android.graphql.generated.FetchInAppChatConversationQuery
import com.dashx.android.graphql.generated.FetchInAppChatConversationsQuery
import com.dashx.android.graphql.generated.FetchInAppChatMessagesQuery
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

    /** On-demand count — the SDK does not push updates to it. Re-query on the triggers the host
     * cares about: foreground, push receipt, mark-read. */
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
    @Suppress("UNUSED_PARAMETER")
    fun onGlobalFrame(frame: DashXRealtimeMessage) {
        // Reserved: near-real-time unread refresh hooks consume this.
    }
}

/**
 * Operations one conversation session needs, seamed so the synchronizer is testable without a
 * socket or GraphQL transport. The default delegates to DashX.
 */
internal interface ChatSessionBackend {
    fun subscribe(handle: SubscriberHandle): DashXRealtimeSubscription
    suspend fun summarizeMessages(conversationId: String): Int
    suspend fun fetchPage(conversationId: String, limit: Int, page: Int): List<ChatMessage>
    suspend fun fetchAfter(conversationId: String, limit: Int, afterMessageId: String): List<ChatMessage>
    fun markRead(
        identityId: String,
        conversationId: String,
        lastMessageId: String,
        onSuccess: (Boolean) -> Unit,
        onError: (DashXError) -> Unit
    )
    fun setConversationVisible(conversationId: String, visible: Boolean)
    fun dismissConversationNotifications(conversationId: String)
}

internal object DashXChatSessionBackend : ChatSessionBackend {
    override fun subscribe(handle: SubscriberHandle): DashXRealtimeSubscription =
        DashX.requireRealtimeRuntime().subscribe(handle)

    override suspend fun summarizeMessages(conversationId: String): Int =
        DashX.awaitOperation { ok, err -> DashX.summarizeInAppChatMessagesJob(conversationId, ok, err) }

    override suspend fun fetchPage(conversationId: String, limit: Int, page: Int): List<ChatMessage> =
        DashX.awaitOperation<List<FetchInAppChatMessagesQuery.FetchInAppChatMessage>> { ok, err ->
            DashX.fetchInAppChatMessagesJob(conversationId, limit, page, null, ok, err)
        }.map { ChatMessage.from(it) }

    override suspend fun fetchAfter(conversationId: String, limit: Int, afterMessageId: String): List<ChatMessage> =
        DashX.awaitOperation<List<FetchInAppChatMessagesQuery.FetchInAppChatMessage>> { ok, err ->
            DashX.fetchInAppChatMessagesJob(conversationId, limit, null, afterMessageId, ok, err)
        }.map { ChatMessage.from(it) }

    override fun markRead(
        identityId: String,
        conversationId: String,
        lastMessageId: String,
        onSuccess: (Boolean) -> Unit,
        onError: (DashXError) -> Unit
    ) {
        DashX.markInAppChatConversationReadJob(identityId, conversationId, lastMessageId, onSuccess, onError)
    }

    override fun setConversationVisible(conversationId: String, visible: Boolean) {
        DashX.pushRuntime.setConversationVisible(conversationId, visible)
    }

    override fun dismissConversationNotifications(conversationId: String) {
        com.dashx.android.push.DashXPush.dismissConversation(conversationId)
    }
}

/**
 * Shared state for one `(identity, conversation)`: the realtime subscription, the reconciliation
 * buffer, the synchronized message list, and read marking. First open runs the history snapshot;
 * reconnect fetches forward from the high-water mark with the `afterMessageId` cursor, preserving
 * already-loaded history.
 *
 * Every synchronizer mutation runs on [syncLane], a single-parallelism dispatcher: frames, sync
 * cycles, paging, and read marking are serialized, so no two coroutines ever interleave writes to
 * [messages]. Suspension points inside a sync cycle still let queued frame merges run — that is
 * what the reconciliation buffer absorbs — but every non-suspending stretch is atomic.
 */
internal class ConversationSession(
    private val key: ChatSessionKey,
    private val backend: ChatSessionBackend = DashXChatSessionBackend
) {

    private val scope = CoroutineScope(SupervisorJob(DashX.chatSessionJob) + Dispatchers.IO)
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val syncLane = Dispatchers.IO.limitedParallelism(1)

    private val mutableState = MutableStateFlow<ConversationState>(ConversationState.Loading)
    val state: StateFlow<ConversationState> get() = mutableState
    private val listeners = CopyOnWriteArrayList<ConversationStateListener>()

    private val leases = CopyOnWriteArrayList<Lease>()
    private var ended = false
    private val lock = Any()

    // ---- synchronizer state: confined to [syncLane] ----
    private val buffer = ArrayList<ChatMessage>()
    private var buffering = true
    private var bufferOverflowed = false
    private var messages: List<ChatMessage> = emptyList()
    private var snapshotDone = false
    private var syncing = false
    private var resyncPending = false
    private var rebuildUsed = false
    private var oldestFetchedPage = Int.MAX_VALUE
    /** Server-confirmed reconnect cursor: the backend has confirmed that every visible message
     * through this id is in [messages]. Advanced only by snapshot/cursor FETCH results, never by
     * realtime frames — frames can arrive out of commit order (they are keyed per message, not per
     * conversation), and a frame-advanced cursor would leap past a lost sibling that no
     * `afterMessageId` walk could ever return. */
    private var lastKnownMessageId: String? = null

    // ---- read marking ----
    private var markedMessageId: String? = null
    private val markInFlight = AtomicBoolean(false)
    private var pendingMarkJob: Job? = null

    private var subscription: DashXRealtimeSubscription? = null

    fun newLease(): DashXConversationLease? {
        synchronized(lock) {
            if (ended) return null
            val lease = Lease()
            leases.add(lease)
            if (subscription == null) {
                val handle = SubscriberHandle(
                    channelName = RealtimeRuntime.chatChannelName(key.conversationId),
                    onFrame = { frame -> onFrame(frame) },
                    onEstablished = { isResubscribe -> onEstablished(isResubscribe) },
                    onSubscribeError = { error -> onSubscribeError(error) }
                )
                subscription = backend.subscribe(handle)
            }
            return lease
        }
    }

    // ---- realtime ingress (actor thread → lane) ----

    private fun onFrame(frame: DashXRealtimeMessage) {
        val message = (frame as? DashXRealtimeMessage.InAppChatMessage)?.message ?: return
        val chatMessage = ChatMessage.from(message)
        scope.launch(syncLane) { mergeLive(chatMessage) }
    }

    private fun onEstablished(isResubscribe: Boolean) {
        DashXLog.d(TAG, "Channel acknowledged for ${key.conversationId} (isResubscribe=$isResubscribe)")
        scope.launch(syncLane) {
            if (syncing) {
                // A reconnect acknowledged mid-cycle: the running fetch may predate the gap, so
                // re-run once this cycle completes rather than assuming it covered everything.
                resyncPending = true
                return@launch
            }
            runSync()
        }
    }

    /** The subscription was rejected or never acknowledged (invalid or unauthorized conversation).
     * Only a conversation with nothing to show surfaces it; an established snapshot stays on screen
     * and a late acknowledgement still reconciles. */
    private fun onSubscribeError(error: DashXError) {
        scope.launch(syncLane) {
            if (snapshotDone) {
                DashXLog.e(TAG, "Subscription problem on ${key.conversationId} after sync: ${error.message}")
                return@launch
            }
            publishState(ConversationState.Error(error))
        }
    }

    // ---- synchronizer (all lane-confined) ----

    private fun mergeLive(message: ChatMessage) {
        if (buffering) {
            if (buffer.size >= BUFFER_LIMIT) bufferOverflowed = true else buffer.add(message)
            return
        }
        applyMerge(listOf(message))
        maybeMarkRead()
    }

    private suspend fun runSync() {
        syncing = true
        rebuildUsed = false
        try {
            do {
                resyncPending = false
                if (!snapshotDone || lastKnownMessageId == null) snapshotAndReplace() else cursorReconcile()
            } while (resyncPending)
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            val error = (t as? com.dashx.android.DashXException)?.error
                ?: DashXError.NetworkError(t.message ?: "chat synchronization failed")
            if (snapshotDone && error is DashXError.NetworkError) {
                // Keep the displayed snapshot on a transient failure; the next reconnect retries.
                DashXLog.e(TAG, "Reconciliation failed for ${key.conversationId}: ${error.message}")
            } else {
                publishState(ConversationState.Error(error))
            }
            // Buffering stays on: a partial live list must not follow the Error, and the next
            // acknowledged sync starts from a fresh buffer anyway.
        } finally {
            syncing = false
        }
    }

    /** First open, no-cursor recovery, and rejected-cursor rebuild: candidate snapshot, one merge,
     * one atomic replacement, one emission. */
    private suspend fun snapshotAndReplace() {
        while (true) {
            startBuffering()
            val count = backend.summarizeMessages(key.conversationId)
            val lastPage = maxOf(1, (count + PAGE_SIZE - 1) / PAGE_SIZE)
            val candidate = backend.fetchPage(key.conversationId, PAGE_SIZE, lastPage)
                .sortedWith(ChatMessage.ORDER)
            val (bufferSnapshot, overflowed) = drainBufferAndResumeLive()
            if (overflowed) continue // discard the candidate; repeat with a fresh buffer
            val replacement = mergeInto(candidate, bufferSnapshot)
            messages = replacement
            snapshotDone = true
            oldestFetchedPage = lastPage
            // From the fetched candidate only: a buffered frame is not server-confirmed — its
            // out-of-order sibling may be missing, and a cursor set past that gap could never
            // recover it. The frames stay displayed and are re-fetched (deduped) on reconnect.
            lastKnownMessageId = candidate.lastOrNull()?.id
            emitReady(replacement)
            maybeMarkRead()
            return
        }
    }

    /** Reconnect: fetch strictly after the high-water mark, page forward until a short page, then
     * merge the buffered frames. Never touches already-loaded history. */
    private suspend fun cursorReconcile() {
        var retryAttempt = 0
        while (true) {
            startBuffering()
            var cursor = lastKnownMessageId ?: run { snapshotAndReplace(); return }
            val fetched = ArrayList<ChatMessage>()
            try {
                while (true) {
                    val rows = backend.fetchAfter(key.conversationId, PAGE_SIZE, cursor)
                    if (rows.isEmpty()) break
                    fetched += rows
                    cursor = rows.last().id // ascending on (turnSeq, createdAt, id)
                    if (rows.size < PAGE_SIZE) break
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                val error = (t as? com.dashx.android.DashXException)?.error
                if (error is DashXError.GraphQLError) {
                    if (rebuildUsed) throw t // a rebuilt mark rejected again is terminal, not a loop
                    // The server rejected the retained cursor (deleted message). Rebuild — never
                    // merge, or the deleted id survives as the mark and rejects forever.
                    rebuildUsed = true
                    snapshotAndReplace()
                    return
                }
                // Incomplete walk: merging the partial fetch or the buffer would advance the
                // high-water mark past the unfetched gap and skip those messages forever. Discard
                // both, keep the snapshot and the stable cursor, and retry the whole walk — every
                // discarded message is still on the server, after the unchanged mark.
                retryAttempt += 1
                DashXLog.e(TAG, "Cursor walk failed for ${key.conversationId} " +
                    "(attempt $retryAttempt): ${t.message}")
                delay(cursorRetryDelay(retryAttempt))
                continue
            }
            val (bufferSnapshot, overflowed) = drainBufferAndResumeLive()
            if (overflowed) continue // restart from the unchanged cursor
            applyMerge(fetched + bufferSnapshot)
            // Advance only to the walk's last FETCHED row — the server confirmed everything
            // through it. Buffered frames don't move the cursor (see the field doc); an empty
            // walk leaves it unchanged.
            fetched.lastOrNull()?.let { lastKnownMessageId = it.id }
            maybeMarkRead()
            return
        }
    }

    private fun cursorRetryDelay(attempt: Int): Long =
        (CURSOR_RETRY_BASE_MS shl (attempt - 1).coerceAtMost(5)).coerceAtMost(CURSOR_RETRY_MAX_MS)

    private fun startBuffering() {
        buffering = true
        buffer.clear()
        bufferOverflowed = false
    }

    private fun drainBufferAndResumeLive(): Pair<List<ChatMessage>, Boolean> {
        val copy = buffer.toList()
        val overflowed = bufferOverflowed
        buffer.clear()
        bufferOverflowed = false
        if (!overflowed) buffering = false
        return copy to overflowed
    }

    /** Merges into the displayed list; emits only when something actually changed. Never touches
     * [lastKnownMessageId] — live frames reach here, and they are display-only until a fetch
     * confirms them. */
    private fun applyMerge(additions: List<ChatMessage>) {
        if (additions.isEmpty()) return
        val merged = mergeInto(messages, additions)
        if (merged == messages) return // duplicates only → no state churn
        messages = merged
        emitReady(merged)
    }

    private fun mergeInto(base: List<ChatMessage>, additions: List<ChatMessage>): List<ChatMessage> {
        if (additions.isEmpty()) return base
        val byId = LinkedHashMap<String, ChatMessage>(base.size + additions.size)
        base.forEach { byId[it.id] = it }
        additions.forEach { byId[it.id] = it }
        return byId.values.sortedWith(ChatMessage.ORDER)
    }

    private fun emitReady(list: List<ChatMessage>) {
        publishState(ConversationState.Ready(list))
    }

    private fun publishState(state: ConversationState) {
        mutableState.value = state
        notifyListeners(state)
    }

    private fun notifyListeners(state: ConversationState) {
        listeners.forEach { l ->
            DashX.launchCallback { runCatching { l.onConversationStateChanged(state) } }
        }
    }

    private fun loadPreviousPageInternal(onError: ((DashXError) -> Unit)?) {
        scope.launch(syncLane) {
            val page = oldestFetchedPage - 1
            if (!snapshotDone || page < 1) return@launch
            try {
                val rows = backend.fetchPage(key.conversationId, PAGE_SIZE, page)
                oldestFetchedPage = page
                applyMerge(rows)
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                val error = (t as? com.dashx.android.DashXException)?.error
                    ?: DashXError.NetworkError(t.message ?: "loadPreviousPage failed")
                onError?.let { cb -> DashX.launchCallback { cb(error) } }
            }
        }
    }

    // ---- read marking (lane-confined; network callbacks hop back onto the lane) ----

    private fun anyLeaseVisible() = leases.any { it.visibleNow }

    private fun maybeMarkRead() {
        if (!anyLeaseVisible()) return
        val newest = messages.lastOrNull()?.id ?: return
        if (newest == markedMessageId) return
        pendingMarkJob?.cancel()
        pendingMarkJob = scope.launch(syncLane) {
            delay(MARK_DEBOUNCE_MS) // coalesce the burst a history load or rapid exchange produces
            markNow(newest)
        }
    }

    private fun markNow(messageId: String) {
        if (messageId == markedMessageId) return
        if (!markInFlight.compareAndSet(false, true)) return
        backend.markRead(
            identityId = key.chatIdentityId,
            conversationId = key.conversationId,
            lastMessageId = messageId,
            onSuccess = { success ->
                scope.launch(syncLane) {
                    markInFlight.set(false)
                    if (success) markedMessageId = messageId
                    maybeMarkRead() // a newer message may have rendered while this was in flight
                }
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
        scope.launch(syncLane) { maybeMarkRead() }
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
        backend.setConversationVisible(key.conversationId, false)
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
                if (!anyLeaseVisible()) backend.setConversationVisible(key.conversationId, false)
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
            backend.setConversationVisible(key.conversationId, any)
            if (visible) {
                backend.dismissConversationNotifications(key.conversationId)
                scope.launch(syncLane) { maybeMarkRead() }
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
        private const val CURSOR_RETRY_BASE_MS = 1_000L
        private const val CURSOR_RETRY_MAX_MS = 30_000L
    }
}
