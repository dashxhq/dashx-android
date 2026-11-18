package com.dashx.android.chat

import com.dashx.android.DashX
import com.dashx.android.DashXError
import com.dashx.android.DashXLog
import com.dashx.android.graphql.generated.FetchInAppChatConversationQuery
import com.dashx.android.graphql.generated.FetchInAppChatConversationsQuery
import com.dashx.android.graphql.generated.FetchInAppChatMessagesQuery
import com.dashx.android.graphql.generated.ResolveInAppChatConversationMutation
import com.dashx.android.realtime.DashXRealtimeChatStream
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

    /** Also delivers the current state immediately, so a listener added to an already-loaded
     * conversation renders without waiting for the next change. Listeners are lease-owned:
     * [close] drops them. */
    fun addStateListener(listener: ConversationStateListener)
    fun removeStateListener(listener: ConversationStateListener)

    /** Fires when the session ends underneath this lease (identity switch, reset, shutdown). */
    fun setOnTerminated(callback: ((DashXSubscriptionEnd) -> Unit)?)

    /**
     * Sends a visitor message; [onSuccess] delivers the committed message as the SDK's own
     * [ChatMessage] and merges it into [state]. Returns the client message id SYNCHRONOUSLY — the
     * idempotency key: a host-triggered retry of a failed send must reuse it via the raw
     * operation, and the committed row reports it back as [ChatMessage.clientMessageId].
     *
     * [content] must be `{"text": "<non-empty, at most 4096 characters>"}`; the backend rejects
     * anything else with a `GraphQLError` (`UNPROCESSABLE_ENTITY`).
     */
    fun sendMessage(
        content: JsonObject,
        onSuccess: (ChatMessage) -> Unit,
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
    fun send(
        identityId: String,
        conversationId: String,
        content: JsonObject,
        clientMessageId: String,
        onSuccess: (ChatMessage) -> Unit,
        onError: (DashXError) -> Unit
    )
    fun markRead(
        identityId: String,
        conversationId: String,
        lastMessageId: String,
        onSuccess: (Boolean) -> Unit,
        onError: (DashXError) -> Unit
    )
    fun setConversationVisible(conversationId: String, visible: Boolean)
    fun dismissConversationNotifications(conversationId: String)
    /** Read marking requires a foregrounded process, not just a visible lease. */
    fun isAppForeground(): Boolean = true
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

    override fun send(
        identityId: String,
        conversationId: String,
        content: JsonObject,
        clientMessageId: String,
        onSuccess: (ChatMessage) -> Unit,
        onError: (DashXError) -> Unit
    ) {
        DashX.sendInAppChatMessageJob(
            conversationId = conversationId,
            identityId = identityId,
            content = content,
            clientMessageId = clientMessageId,
            onSuccess = { result -> onSuccess(ChatMessage.from(result.chatMessageFragment)) },
            onError = onError
        )
    }

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

    override fun isAppForeground(): Boolean = DashX.pushRuntime.get().isForeground
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
    private val backend: ChatSessionBackend = DashXChatSessionBackend,
    private val streamTimeouts: ReplyStreamTimeouts = ReplyStreamTimeouts()
) {

    private val scope = CoroutineScope(SupervisorJob(DashX.chatSessionJob) + Dispatchers.IO)
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val syncLane = Dispatchers.IO.limitedParallelism(1)

    private val mutableState = MutableStateFlow<ConversationState>(ConversationState.Loading)
    val state: StateFlow<ConversationState> get() = mutableState
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
    /** Consecutive transient sync failures; a completed cycle or a fresh acknowledgement resets it. */
    private var syncAttempts = 0
    private var oldestFetchedPage = Int.MAX_VALUE
    /** Newest unconfirmed tail id a resync was already requested for — one request per tail. */
    private var resyncRequestedFor: String? = null
    /** Server-confirmed reconnect cursor: every message through this id is in [messages].
     * Advanced only by snapshot/cursor FETCH results, never by realtime frames — frames can
     * arrive out of order, and a frame-advanced cursor would leap past a lost sibling that no
     * `afterMessageId` walk could ever return. */
    private var lastKnownMessageId: String? = null
    /** An acknowledgement's sync has not completed: frames sent while disconnected may be
     * missing, so live messages wait for it rather than show past the gap. */
    private var reconnectGap = false
    /** A stream timeout's sync is running on a loaded conversation. Live messages keep showing
     * meanwhile, so a reply that arrives over the socket is not held back behind the fetch. */
    private var showLiveDuringSync = false
    /** Channel acknowledgements so far; a pass that sees this change during its fetch was
     * overtaken by a reconnect. */
    private var acknowledgements = 0
    /** Set by a pass a reconnect overtook: its buffer carries, unshown, into the pass after. */
    private var carryBuffer = false

    // ---- read marking ----
    private var markedMessageId: String? = null
    private val markInFlight = AtomicBoolean(false)
    private var pendingMarkJob: Job? = null

    // ---- streamed reply: confined to [syncLane] ----
    /** The agent reply being streamed, at most one: a later turn or a retried run replaces it. */
    private var replyStream: ReplyStreamAccumulator? = null
    /** The newest run already settled, by its stored reply or an `END` that stored none; its late
     * frames, and any older run's, are ignored. */
    private var settledStreamRun: ReplyStreamRun? = null
    /** The newest run any frame has named. Kept apart from [replyStream], which a reconnect
     * clears: a superseded run's delayed frames must not come back afterwards. */
    private var newestStreamRun: ReplyStreamRun? = null
    /** Pending idle checks of unfinished runs. A frame of the same turn reschedules or cancels
     * its turn's check and an `END` cancels its run's; a later turn's frames leave it to finish. */
    private val idleChecks = HashMap<ReplyStreamRun, Job>()

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
        when (frame) {
            is DashXRealtimeMessage.InAppChatMessage -> {
                val chatMessage = ChatMessage.from(frame.message)
                scope.launch(syncLane) { mergeLive(chatMessage) }
            }
            is DashXRealtimeMessage.InAppChatStream ->
                scope.launch(syncLane) { mergeStream(frame.stream) }
            else -> Unit
        }
    }

    private fun onEstablished(isResubscribe: Boolean) {
        DashXLog.d(TAG, "Channel acknowledged for ${key.conversationId} (isResubscribe=$isResubscribe)")
        scope.launch(syncLane) {
            // Frames sent while disconnected are gone, an `END` that stored nothing among them,
            // so an unfinished reply could otherwise stay on screen. A finished one waits for its
            // stored reply, which the reconcile below fetches if its frame was lost too.
            if (isResubscribe && replyStream?.isComplete == false) {
                replyStream = null
                // Now, not after the reconcile: a failing fetch would otherwise keep it on screen.
                publishReadyIfShown()
            }
            reconnectGap = true
            acknowledgements += 1
            syncAttempts = 0
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
            // Shown as well as buffered: the fetch still merges it by id. Read marking waits for
            // the cycle, which marks only through the server-confirmed cursor.
            if (showsLiveDuringSync()) applyMerge(listOf(message))
            return
        }
        applyMerge(listOf(message))
        maybeMarkRead()
    }

    private fun showsLiveDuringSync() =
        showLiveDuringSync && !reconnectGap && mutableState.value is ConversationState.Ready

    private suspend fun runSync() {
        syncing = true
        rebuildUsed = false
        try {
            do {
                resyncPending = false
                if (!snapshotDone || lastKnownMessageId == null) snapshotAndReplace() else cursorReconcile()
            } while (resyncPending)
            syncAttempts = 0
            reconnectGap = false
            showLiveDuringSync = false
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            val error = (t as? com.dashx.android.DashXException)?.error
                ?: DashXError.NetworkError(t.message ?: "chat synchronization failed")
            if (keepsSnapshot(error) && (snapshotDone || syncAttempts < MAX_INITIAL_SYNC_RETRIES)) {
                // The socket may stay connected, so no reconnect would re-run this: retry with
                // backoff. An established snapshot stays on screen for as long as it takes (frames
                // buffer until a cycle completes); before one exists, three attempts then Error.
                syncAttempts += 1
                val attempt = syncAttempts
                val delayMs = cursorRetryDelay(attempt)
                DashXLog.e(TAG, "Sync failed for ${key.conversationId} " +
                    "(attempt $attempt, retrying in ${delayMs}ms): ${error.message}")
                scope.launch(syncLane) {
                    delay(delayMs)
                    if (!syncing && syncAttempts == attempt) runSync() // no newer cycle ran meanwhile
                }
            } else {
                publishState(ConversationState.Error(error))
            }
            // Buffering stays on: a partial live list must not follow the Error, and the next
            // acknowledged sync starts from a fresh buffer anyway.
        } finally {
            syncing = false
        }
    }

    /**
     * A failure that leaves an already-loaded conversation on screen rather than replacing it with
     * [ConversationState.Error]: transport problems, a rejected token (the auth retry or a provider
     * refresh recovers it), a server fault, or a GraphQL response without a single code. Permission
     * loss, a missing conversation, and a cursor rejected twice are terminal for this session.
     */
    private fun keepsSnapshot(error: DashXError): Boolean = when (error) {
        is DashXError.NetworkError -> true
        is DashXError.GraphQLError -> when (error.code) {
            null,
            DashXError.GraphQLError.UNAUTHORIZED,
            DashXError.GraphQLError.INTERNAL_SERVER_ERROR -> true
            else -> false
        }
        else -> false
    }

    /** First open, no-cursor recovery, and rejected-cursor rebuild: candidate snapshot, one merge,
     * one atomic replacement, one emission. */
    private suspend fun snapshotAndReplace() {
        while (true) {
            startBuffering()
            val acks = acknowledgements
            val count = backend.summarizeMessages(key.conversationId)
            val lastPage = maxOf(1, (count + PAGE_SIZE - 1) / PAGE_SIZE)
            val candidate = backend.fetchPage(key.conversationId, PAGE_SIZE, lastPage)
                .sortedWith(ChatMessage.ORDER)
            if (overtakenByReconnect(acks)) continue
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
            publishReady()
            maybeMarkRead()
            return
        }
    }

    /** Reconnect: fetch strictly after the high-water mark, page forward until a short page, then
     * merge the buffered frames. Never touches already-loaded history. */
    private suspend fun cursorReconcile() {
        while (true) {
            startBuffering()
            val acks = acknowledgements
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
                // Only UNPROCESSABLE_ENTITY means the cursor itself was rejected (deleted message):
                // rebuild — never merge, or the deleted id survives as the mark and rejects forever;
                // a rebuilt mark rejected again is terminal. Everything else goes to [runSync]. The
                // incomplete walk is discarded whole either way: merging its partial fetch or the
                // buffer would advance the high-water mark past the unfetched gap.
                val cursorRejected = error is DashXError.GraphQLError &&
                    error.code == DashXError.GraphQLError.UNPROCESSABLE_ENTITY
                if (!cursorRejected || rebuildUsed) throw t
                rebuildUsed = true
                snapshotAndReplace()
                return
            }
            if (overtakenByReconnect(acks)) continue
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

    /**
     * A reconnect acknowledged during this pass's fetch: the fetch may predate messages the drop
     * lost, so neither its rows nor the buffer is shown. The pass repeats from a fetch started
     * after the acknowledgement, which is the rerun it queued, and the buffer carries into it,
     * since frames can hold rows a cursor walk does not return.
     */
    private fun overtakenByReconnect(acksAtStart: Int): Boolean {
        if (acknowledgements == acksAtStart) return false
        carryBuffer = true
        resyncPending = false
        return true
    }

    private fun startBuffering() {
        buffering = true
        if (carryBuffer) {
            carryBuffer = false
            return
        }
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
        if (additions.isNotEmpty()) messages = mergeInto(messages, additions)
        publishReady()
    }

    private fun mergeInto(base: List<ChatMessage>, additions: List<ChatMessage>): List<ChatMessage> {
        if (additions.isEmpty()) return base
        val byId = LinkedHashMap<String, ChatMessage>(base.size + additions.size)
        base.forEach { byId[it.id] = it }
        additions.forEach { byId[it.id] = it }
        return byId.values.sortedWith(ChatMessage.ORDER)
    }

    /** Publishes the current list, paging flag and streamed reply; a no-op when none changed. */
    private fun publishReady() {
        settleAnsweredStream()
        val next = ConversationState.Ready(
            messages,
            hasOlderMessages = oldestFetchedPage > 1,
            streamingReply = replyStream?.reply
        )
        if (mutableState.value == next) return
        publishState(next)
    }

    // ---- streamed reply (all lane-confined) ----

    /** Frames of one run in order; a later turn or a retried run of the same turn replaces the
     * current one. Published only while the state is [ConversationState.Ready]. */
    private fun mergeStream(frame: DashXRealtimeChatStream) {
        val run = ReplyStreamRun(frame.turnSeq, frame.run)
        // Before the run checks, which drop a run a later turn or a newer run has ended: its
        // `END` still replaces the idle fallback. Nothing was stored, or the stored reply is in
        // history and fetched if its frame does not follow.
        if (frame.kind == DashXRealtimeChatStream.Kind.END) {
            idleChecks.remove(run)?.cancel()
            if (frame.persisted == true) fetchStoredReplyIfMissing(run)
        }
        settledStreamRun?.let { if (run <= it) return }
        newestStreamRun?.let { if (run < it) return }
        newestStreamRun = run
        if (replyStream?.run != run) replyStream = ReplyStreamAccumulator(run)

        when (frame.kind) {
            DashXRealtimeChatStream.Kind.DELTA ->
                replyStream?.add(frame.seq, frame.responseId, frame.text.orEmpty())
            DashXRealtimeChatStream.Kind.END ->
                if (frame.persisted == true) replyStream?.complete(deltaCount = frame.seq) else settleStream()
        }
        armStreamTimeout(run)
        publishReadyIfShown()
    }

    /** A conversation showing `Loading` or `Error` keeps it: a stream change alone must not
     * replace an error with a stale list. */
    private fun publishReadyIfShown() {
        if (mutableState.value is ConversationState.Ready) publishReady()
    }

    /**
     * A lost frame must not leave a preview up forever. An unfinished run that goes quiet is
     * hidden but kept, since a tool call sends nothing for a while and its answer may still
     * stream, and history is reconciled in case its `END` was lost. A finished run is left to
     * [fetchStoredReplyIfMissing].
     *
     * The check outlives the preview: a later turn ends the preview, but the run's reply may be
     * in history with its `END` and stored frame both lost, as a lagging socket drops frames
     * without reconnecting. Only hiding depends on the run still being shown.
     */
    private fun armStreamTimeout(run: ReplyStreamRun) {
        idleChecks.keys.filter { it.turnSeq == run.turnSeq }
            .forEach { idleChecks.remove(it)?.cancel() }
        if (replyStream?.run != run || replyStream?.isComplete != false) return
        idleChecks[run] = scope.launch(syncLane) {
            delay(streamTimeouts.idleMs)
            idleChecks.remove(run)
            if (hasStoredReply(run.turnSeq)) return@launch
            if (replyStream?.run == run) replyStream?.hide()
            reconcileAfterStreamTimeout()
        }
    }

    /**
     * A finished run's reply is in history, so its fetch outlives the preview: a later turn or a
     * newer run ends the preview, not this. Skipped once the stored reply has arrived; otherwise
     * the preview, if still this run's, is dropped.
     */
    private fun fetchStoredReplyIfMissing(run: ReplyStreamRun) {
        scope.launch(syncLane) {
            delay(streamTimeouts.storedReplyGraceMs)
            if (hasStoredReply(run.turnSeq)) return@launch
            if (replyStream?.run == run) settleStream()
            reconcileAfterStreamTimeout()
        }
    }

    private fun reconcileAfterStreamTimeout() {
        if (mutableState.value is ConversationState.Ready) showLiveDuringSync = true
        // A first load still in flight may predate the stored reply, so it is followed by a
        // reconcile too. One not yet started fetches everything when it runs.
        if (snapshotDone || syncing) requestResync()
        publishReadyIfShown()
    }

    /** The stored reply replaces the stream, whether it arrived before the stream's frames or
     * after. A later turn ends it too: the server stores a visible reply only while its turn is
     * current. */
    private fun settleAnsweredStream() {
        val turnSeq = replyStream?.run?.turnSeq ?: return
        val overtaken = messages.lastOrNull()?.let { it.turnSeq > turnSeq } == true
        if (overtaken || hasStoredReply(turnSeq)) settleStream()
    }

    private fun settleStream() {
        val run = replyStream?.run ?: return
        settledStreamRun = maxOf(settledStreamRun ?: run, run)
        replyStream = null
    }

    /** [messages] is ordered by turn, so the scan stops at the first earlier turn. */
    private fun hasStoredReply(turnSeq: Long): Boolean {
        for (message in messages.asReversed()) {
            if (message.turnSeq < turnSeq) return false
            if (message.turnSeq == turnSeq && !message.isFromVisitor) return true
        }
        return false
    }

    private fun publishState(state: ConversationState) {
        mutableState.value = state
        notifyListeners(state)
    }

    private fun notifyListeners(state: ConversationState) {
        leases.forEach { it.notifyStateListeners(state) }
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
                onError?.let { cb -> DashX.launchCallback { runCatching { cb(error) } } }
            }
        }
    }

    // ---- read marking (lane-confined; network callbacks hop back onto the lane) ----

    private fun anyLeaseVisible() = leases.any { it.visibleNow }

    /** A visible lease in a foregrounded process; re-checked at every step of the mark path. */
    private fun canMarkRead() = anyLeaseVisible() && backend.isAppForeground()

    private fun maybeMarkRead() {
        if (!canMarkRead()) return
        val newest = messages.lastOrNull()?.id ?: return
        if (newest == markedMessageId) return
        // Read-through is evaluated by message order, so marking a live frame that overtook a
        // lost sibling would mark the unseen sibling read and kill its push. Only the
        // server-confirmed cursor is a safe boundary: an unconfirmed tail reconciles first — which
        // also surfaces any gap on screen — and the completed cycle re-enters here. One resync per
        // distinct tail id, or a row the server never returns would spin the lane hot.
        if (newest != lastKnownMessageId) {
            if (resyncRequestedFor != newest) {
                resyncRequestedFor = newest
                requestResync()
            }
            return
        }
        pendingMarkJob?.cancel()
        pendingMarkJob = scope.launch(syncLane) {
            delay(MARK_DEBOUNCE_MS) // coalesce the burst a history load or rapid exchange produces
            if (!canMarkRead()) return@launch // hidden or backgrounded during the debounce
            markNow(newest)
        }
    }

    /** Lane-confined. Piggybacks on a running cycle; otherwise starts one. */
    private fun requestResync() {
        if (syncing) {
            resyncPending = true
            return
        }
        scope.launch(syncLane) { if (!syncing) runSync() }
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
        // The leases list is already cleared; the captured list still gets the terminal state.
        toNotify.forEach { lease ->
            lease.notifyStateListeners(terminal)
            lease.terminatedCallback?.let { cb ->
                DashX.launchCallback { runCatching { cb(DashXSubscriptionEnd.SessionEnded) } }
            }
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
        private val stateListeners = CopyOnWriteArrayList<ConversationStateListener>()

        override val conversationId: String get() = key.conversationId
        override val state: StateFlow<ConversationState> get() = this@ConversationSession.state

        fun notifyStateListeners(state: ConversationState) {
            stateListeners.forEach { l ->
                DashX.launchCallback { runCatching { l.onConversationStateChanged(state) } }
            }
        }

        override fun addStateListener(listener: ConversationStateListener) {
            stateListeners.add(listener)
            // Replay: a lease opened into an already-loaded conversation renders now, not on the
            // next change. Reads the freshest state at delivery, on the callback dispatcher like
            // every other notification — a duplicate render is harmless, a blank screen is not.
            DashX.launchCallback { runCatching { listener.onConversationStateChanged(mutableState.value) } }
        }
        override fun removeStateListener(listener: ConversationStateListener) { stateListeners.remove(listener) }
        override fun setOnTerminated(callback: ((DashXSubscriptionEnd) -> Unit)?) { terminatedCallback = callback }

        override fun sendMessage(
            content: JsonObject,
            onSuccess: (ChatMessage) -> Unit,
            onError: (DashXError) -> Unit
        ): String {
            // Generated BEFORE the network attempt and returned synchronously: the idempotency key a
            // host-triggered retry must reuse.
            val clientMessageId = UUID.randomUUID().toString()
            backend.send(
                identityId = key.chatIdentityId,
                conversationId = key.conversationId,
                content = content,
                clientMessageId = clientMessageId,
                onSuccess = { message ->
                    // The committed row goes into the list directly. The realtime frame usually
                    // beats this callback and the merge dedupes by id; but the frame is missed
                    // while the socket is reconnecting, and an idempotent retry of an already
                    // committed send gets no frame at all — without this merge the visitor's own
                    // message would not appear until the next reconcile. Display-only: the
                    // cursor still advances only on fetch results.
                    scope.launch(syncLane) { mergeLive(message) }
                    onSuccess(message)
                },
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
            stateListeners.clear() // a closed screen must not keep painting from a live sibling lease
            closeLease(this)
        }
    }

    companion object {
        private const val TAG = "DashXChat"
        internal const val PAGE_SIZE = 50
        private const val BUFFER_LIMIT = 500
        private const val MARK_DEBOUNCE_MS = 400L
        private const val MAX_INITIAL_SYNC_RETRIES = 3
        private const val CURSOR_RETRY_BASE_MS = 1_000L
        private const val CURSOR_RETRY_MAX_MS = 30_000L
    }
}

/** Identifies one reply run: the turn it answers, then the run number a retry raises. */
internal data class ReplyStreamRun(val turnSeq: Long, val run: Long) : Comparable<ReplyStreamRun> {
    override fun compareTo(other: ReplyStreamRun): Int =
        compareValuesBy(this, other, { it.turnSeq }, { it.run })
}

internal class ReplyStreamTimeouts(
    /** Quiet time after which an unfinished run is hidden. */
    val idleMs: Long = 20_000L,
    /** Time a finished run waits for its stored reply before it is dropped. */
    val storedReplyGraceMs: Long = 5_000L
)

/**
 * The deltas of one reply run. Text is read up to the first missing `seq`, so a lost frame never
 * shows as a hole, and only the run's last model response counts: earlier ones are text produced
 * before a tool call.
 */
internal class ReplyStreamAccumulator(val run: ReplyStreamRun) {
    private class Delta(val responseId: String?, val text: String)

    private val deltas = HashMap<Int, Delta>()
    var isComplete = false
        private set
    /** Deltas the run sent, from its `END`'s `seq`; fewer received means one was lost. */
    private var expectedDeltaCount: Int? = null
    /** Gone quiet; a new delta or its `END` shows it again. */
    private var isHidden = false

    fun add(seq: Int, responseId: String?, text: String) {
        if (deltas.containsKey(seq)) return
        deltas[seq] = Delta(responseId, text)
        isHidden = false
    }

    fun complete(deltaCount: Int) {
        isComplete = true
        expectedDeltaCount = deltaCount
        isHidden = false
    }

    fun hide() {
        isHidden = true
    }

    val reply: ChatStreamingReply?
        get() {
            if (isHidden) return null
            val contiguous = ArrayList<Delta>()
            while (true) contiguous += deltas[contiguous.size] ?: break
            // A finished run missing a delta would present truncated text as the whole reply; it
            // stays hidden until the delta arrives or the stored reply replaces it.
            expectedDeltaCount?.let { if (contiguous.size < it) return null }
            val last = contiguous.lastOrNull() ?: return null
            val text = contiguous.filter { it.responseId == last.responseId }.joinToString("") { it.text }
            return if (text.isEmpty()) null else ChatStreamingReply(run.turnSeq, text, isComplete)
        }
}
