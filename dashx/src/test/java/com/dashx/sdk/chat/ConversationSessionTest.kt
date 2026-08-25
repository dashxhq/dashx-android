package com.dashx.android.chat

import com.dashx.android.DashXError
import com.dashx.android.DashXException
import com.dashx.android.realtime.DashXRealtimeChatMessage
import com.dashx.android.realtime.DashXRealtimeMessage
import com.dashx.android.realtime.DashXRealtimeSubscription
import com.dashx.android.realtime.SubscriberHandle
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private fun awaitUntil(timeoutMs: Long = 5000, what: String = "condition", condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (condition()) return
        Thread.sleep(10)
    }
    fail("Timed out waiting for: $what")
}

private fun msg(id: String, seq: Long) = ChatMessage(
    id = id,
    conversationId = "c1",
    externalUid = null,
    senderId = null,
    aiRole = "ASSISTANT",
    turnSeq = seq,
    renderedContent = JsonObject(emptyMap()),
    createdAt = "2026-08-25 10:00:00",
    sentAt = null
)

private fun frame(id: String, seq: Long) = DashXRealtimeMessage.InAppChatMessage(
    DashXRealtimeChatMessage(
        id = id,
        externalUid = "e-$id",
        conversationId = "c1",
        turnSeq = seq
    )
)

private class FakeBackend : ChatSessionBackend {
    val handles = CopyOnWriteArrayList<SubscriberHandle>()
    val unsubscribeCount = AtomicInteger(0)
    val summarizeCalls = AtomicInteger(0)
    val fetchPageCalls = CopyOnWriteArrayList<Int>()
    val fetchAfterCursors = CopyOnWriteArrayList<String>()

    @Volatile var count = 0
    @Volatile var pages: Map<Int, List<ChatMessage>> = emptyMap()
    @Volatile var after: (String) -> List<ChatMessage> = { emptyList() }
    /** When set, fetchPage suspends until completed — frames arriving meanwhile must buffer. */
    @Volatile var pageGate: CompletableDeferred<Unit>? = null

    override fun subscribe(handle: SubscriberHandle): DashXRealtimeSubscription {
        handles.add(handle)
        return object : DashXRealtimeSubscription {
            override fun unsubscribe() { unsubscribeCount.incrementAndGet() }
        }
    }

    override suspend fun summarizeMessages(conversationId: String): Int {
        summarizeCalls.incrementAndGet()
        return count
    }

    override suspend fun fetchPage(conversationId: String, limit: Int, page: Int): List<ChatMessage> {
        fetchPageCalls.add(page)
        pageGate?.await()
        return pages[page] ?: emptyList()
    }

    override suspend fun fetchAfter(conversationId: String, limit: Int, afterMessageId: String): List<ChatMessage> {
        fetchAfterCursors.add(afterMessageId)
        return after(afterMessageId)
    }

    override fun markRead(
        identityId: String,
        conversationId: String,
        lastMessageId: String,
        onSuccess: (Boolean) -> Unit,
        onError: (DashXError) -> Unit
    ) { onSuccess(true) }

    override fun setConversationVisible(conversationId: String, visible: Boolean) {}
    override fun dismissConversationNotifications(conversationId: String) {}
}

class ConversationSessionTest {

    private val key = ChatSessionKey(chatIdentityId = "identity-1", conversationId = "c1")

    private fun readyIds(lease: DashXConversationLease): List<String>? =
        (lease.state.value as? ConversationState.Ready)?.messages?.map { it.id }

    private fun openReady(backend: FakeBackend, history: List<ChatMessage>): Pair<ConversationSession, DashXConversationLease> {
        backend.count = history.size
        backend.pages = mapOf(maxOf(1, (history.size + 49) / 50) to history.takeLast(50))
        if (history.size > 50) {
            backend.pages = backend.pages + (1 to history.take(50))
        }
        val session = ConversationSession(key, backend)
        val lease = session.newLease()!!
        backend.handles[0].onEstablished(false)
        awaitUntil(what = "initial Ready") { readyIds(lease) == history.takeLast(50).map { it.id } || readyIds(lease) == history.map { it.id } }
        return session to lease
    }

    @Test
    fun framesArrivingDuringSnapshot_bufferAndAppearExactlyOnce() {
        val backend = FakeBackend()
        backend.count = 2
        backend.pages = mapOf(1 to listOf(msg("m1", 1), msg("m2", 2)))
        val gate = CompletableDeferred<Unit>()
        backend.pageGate = gate

        val session = ConversationSession(key, backend)
        val lease = session.newLease()!!
        backend.handles[0].onEstablished(false)
        awaitUntil(what = "fetch started") { backend.fetchPageCalls.size == 1 }

        // Arrives mid-fetch: must be buffered, then merged into the SAME emission as the history.
        backend.handles[0].onFrame(frame("m3", 3))
        Thread.sleep(100)
        assertTrue("no partial emission while the snapshot runs", lease.state.value is ConversationState.Loading)

        gate.complete(Unit)
        awaitUntil(what = "merged Ready") { readyIds(lease) == listOf("m1", "m2", "m3") }
    }

    @Test
    fun concurrentFrames_noneLostOrMisordered() {
        val backend = FakeBackend()
        val (_, lease) = openReady(backend, emptyList())

        val perThread = 50
        val threads = (0 until 4).map { t ->
            Thread {
                repeat(perThread) { i ->
                    val n = t * perThread + i
                    backend.handles[0].onFrame(frame("f%03d".format(n), n.toLong()))
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        awaitUntil(what = "all 200 frames merged") { readyIds(lease)?.size == 200 }
        val ids = readyIds(lease)!!
        assertEquals("no frame lost or duplicated", (0 until 200).map { "f%03d".format(it) }, ids)
    }

    @Test
    fun reconnect_fetchesForwardFromTheMark_preservingHistory() {
        val backend = FakeBackend()
        val (_, lease) = openReady(backend, listOf(msg("m1", 1), msg("m2", 2)))

        backend.after = { cursor -> if (cursor == "m2") listOf(msg("m3", 3), msg("m4", 4)) else emptyList() }
        backend.handles[0].onEstablished(true)

        awaitUntil(what = "forward merge") { readyIds(lease) == listOf("m1", "m2", "m3", "m4") }
        assertEquals("reconnect must not re-summarize", 1, backend.summarizeCalls.get())
        assertEquals("reconnect must not refetch pages", 1, backend.fetchPageCalls.size)
        assertEquals(listOf("m2"), backend.fetchAfterCursors)
    }

    @Test
    fun reconnect_gapLargerThanOnePage_loopsUntilShortPage() {
        val backend = FakeBackend()
        val (_, lease) = openReady(backend, listOf(msg("m1", 1), msg("m2", 2)))

        val fullPage = (1..50).map { msg("g%03d".format(it), 100L + it) }
        val shortPage = (51..60).map { msg("g%03d".format(it), 100L + it) }
        backend.after = { cursor ->
            when (cursor) {
                "m2" -> fullPage
                "g050" -> shortPage
                else -> emptyList()
            }
        }
        backend.handles[0].onEstablished(true)

        awaitUntil(what = "both pages merged") { readyIds(lease)?.size == 62 }
        assertEquals(listOf("m2", "g050"), backend.fetchAfterCursors)
        assertEquals("nothing duplicated", 62, readyIds(lease)!!.distinct().size)
    }

    @Test
    fun reconnect_withNoGap_causesNoStateChurn() {
        val backend = FakeBackend()
        val (_, lease) = openReady(backend, listOf(msg("m1", 1)))
        val before = lease.state.value

        backend.handles[0].onEstablished(true)
        awaitUntil(what = "cursor fetch ran") { backend.fetchAfterCursors.size == 1 }
        Thread.sleep(150)
        assertSame("an empty page must not emit a new state", before, lease.state.value)
    }

    @Test
    fun rejectedCursor_rebuildsOnce_andTheNextReconnectUsesAValidCursor() {
        val backend = FakeBackend()
        val (_, lease) = openReady(backend, listOf(msg("m1", 1), msg("m2", 2)))

        // The retained cursor (m2) was deleted server-side: the fetch is rejected, and the rebuilt
        // snapshot no longer contains it.
        backend.after = { throw DashXException(DashXError.GraphQLError("invalid afterMessageId")) }
        backend.count = 1
        backend.pages = mapOf(1 to listOf(msg("m1", 1)))
        backend.handles[0].onEstablished(true)

        awaitUntil(what = "rebuilt Ready") { readyIds(lease) == listOf("m1") }
        assertEquals("initial + rebuild", 2, backend.summarizeCalls.get())

        // The mark was recomputed from the replacement: the next reconnect submits m1, not m2.
        backend.after = { emptyList() }
        backend.handles[0].onEstablished(true)
        awaitUntil(what = "second reconnect fetch") { backend.fetchAfterCursors.size == 2 }
        assertEquals("m1", backend.fetchAfterCursors[1])
    }

    @Test
    fun partialCursorWalkFailure_keepsTheStableCursor_andRetriesTheWholeWalk() {
        val backend = FakeBackend()
        val (_, lease) = openReady(backend, listOf(msg("m1", 1), msg("m2", 2)))

        // First walk: one full page succeeds, the next request fails, and a newer frame is
        // buffered meanwhile. Nothing may merge — merging would advance the high-water mark past
        // the unfetched gap and skip those messages forever.
        val fullPage = (1..50).map { msg("g%03d".format(it), 100L + it) }
        val tail = (51..60).map { msg("g%03d".format(it), 100L + it) } + msg("z999", 999)
        val failures = AtomicInteger(0)
        backend.after = { cursor ->
            when {
                cursor == "m2" -> fullPage
                cursor == "g050" && failures.incrementAndGet() == 1 ->
                    throw DashXException(DashXError.NetworkError("mid-walk blip"))
                cursor == "g050" -> tail
                else -> emptyList()
            }
        }
        backend.handles[0].onEstablished(true)
        backend.handles[0].onFrame(frame("z999", 999)) // buffered while the walk runs

        awaitUntil(what = "first walk failed") { failures.get() == 1 }
        Thread.sleep(100)
        assertEquals("nothing merged from the incomplete walk", listOf("m1", "m2"), readyIds(lease))

        awaitUntil(what = "retried walk completes") { readyIds(lease)?.size == 63 }
        assertEquals("no message skipped or duplicated", 63, readyIds(lease)!!.distinct().size)
        assertEquals(
            "the retry restarts from the STABLE cursor, not the partial walk's tail",
            listOf("m2", "g050", "m2", "g050"),
            backend.fetchAfterCursors
        )
    }

    @Test
    fun subscribeError_beforeAnySnapshot_surfacesError_lateAckRecovers() {
        val backend = FakeBackend()
        backend.count = 1
        backend.pages = mapOf(1 to listOf(msg("m1", 1)))
        val session = ConversationSession(key, backend)
        val lease = session.newLease()!!

        backend.handles[0].onSubscribeError(DashXError.SubscriptionFailed())
        awaitUntil(what = "Error state") {
            (lease.state.value as? ConversationState.Error)?.cause is DashXError.SubscriptionFailed
        }

        backend.handles[0].onEstablished(false)
        awaitUntil(what = "late ack recovers") { readyIds(lease) == listOf("m1") }
    }

    @Test
    fun subscribeError_afterReady_keepsTheSnapshot() {
        val backend = FakeBackend()
        val (_, lease) = openReady(backend, listOf(msg("m1", 1)))

        backend.handles[0].onSubscribeError(DashXError.SubscriptionFailed())
        Thread.sleep(150)
        assertEquals(listOf("m1"), readyIds(lease))
    }

    @Test
    fun loadPreviousPage_neverMovesTheReconnectCursorBackward() {
        val backend = FakeBackend()
        val newest = (51..60).map { msg("m%03d".format(it), it.toLong()) }
        val oldest = (1..50).map { msg("m%03d".format(it), it.toLong()) }
        backend.count = 60
        backend.pages = mapOf(2 to newest, 1 to oldest)

        val session = ConversationSession(key, backend)
        val lease = session.newLease()!!
        backend.handles[0].onEstablished(false)
        awaitUntil(what = "newest page Ready") { readyIds(lease)?.size == 10 }

        lease.loadPreviousPage()
        awaitUntil(what = "older page prepended") { readyIds(lease)?.size == 60 }

        backend.after = { emptyList() }
        backend.handles[0].onEstablished(true)
        awaitUntil(what = "cursor fetch") { backend.fetchAfterCursors.size == 1 }
        assertEquals("the mark is the newest message, not the last-fetched page's tail", "m060", backend.fetchAfterCursors[0])
    }

    @Test
    fun outOfOrderFrame_doesNotAdvanceTheCursor_reconnectRecoversTheLostSibling() {
        val backend = FakeBackend()
        val (_, lease) = openReady(backend, listOf(msg("m1", 1)))

        // m2 and m3 commit server-side, but m3's frame overtakes m2's (frames are keyed per
        // message, not per conversation) and m2's is lost with the connection. m3 is displayed,
        // yet the server has only confirmed history through m1.
        backend.handles[0].onFrame(frame("m3", 3))
        awaitUntil(what = "m3 displayed") { readyIds(lease) == listOf("m1", "m3") }

        backend.after = { cursor -> if (cursor == "m1") listOf(msg("m2", 2), msg("m3", 3)) else emptyList() }
        backend.handles[0].onEstablished(true)

        awaitUntil(what = "lost sibling recovered") { readyIds(lease) == listOf("m1", "m2", "m3") }
        assertEquals(
            "the reconnect walk must start at the server-confirmed cursor, not the frame — " +
                "afterMessageId=m3 could never return m2",
            listOf("m1"),
            backend.fetchAfterCursors
        )

        // The walk's last fetched row is now server-confirmed and becomes the cursor.
        backend.handles[0].onEstablished(true)
        awaitUntil(what = "second cursor fetch") { backend.fetchAfterCursors.size == 2 }
        assertEquals("m3", backend.fetchAfterCursors[1])
    }

    @Test
    fun bufferedFrameDuringSnapshot_doesNotAdvanceTheCursor() {
        val backend = FakeBackend()
        backend.count = 1
        backend.pages = mapOf(1 to listOf(msg("m1", 1)))
        val gate = CompletableDeferred<Unit>()
        backend.pageGate = gate

        val session = ConversationSession(key, backend)
        val lease = session.newLease()!!
        backend.handles[0].onEstablished(false)
        awaitUntil(what = "fetch started") { backend.fetchPageCalls.size == 1 }

        // Same overtake, one layer deeper: the frame lands while the snapshot is still fetching,
        // so it merges from the buffer — the cursor must still come from the fetched page only.
        backend.handles[0].onFrame(frame("m3", 3))
        gate.complete(Unit)
        awaitUntil(what = "snapshot + buffered frame Ready") { readyIds(lease) == listOf("m1", "m3") }

        backend.after = { cursor -> if (cursor == "m1") listOf(msg("m2", 2), msg("m3", 3)) else emptyList() }
        backend.handles[0].onEstablished(true)

        awaitUntil(what = "gap healed") { readyIds(lease) == listOf("m1", "m2", "m3") }
        assertEquals(listOf("m1"), backend.fetchAfterCursors)
    }
}
