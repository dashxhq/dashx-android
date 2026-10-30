package com.dashx.android

import com.dashx.android.utils.trackTimestamp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class EventQueueTest {

    private val json = Json { ignoreUnknownKeys = true }

    @After
    fun tearDown() {
        EventQueue.resetForTesting()
    }

    // --- QueuedEvent serialization ---

    @Test
    fun queuedEvent_serialization_roundTrip() {
        val event = EventQueue.QueuedEvent(
            event = "button_click",
            dataJson = """{"key":"value"}""",
            accountUid = "user_123",
            accountAnonymousUid = "anon_456",
            enqueuedAt = 1700000000000,
            retryCount = 3
        )

        val serialized = json.encodeToString(event)
        val deserialized = json.decodeFromString<EventQueue.QueuedEvent>(serialized)

        assertEquals(event.event, deserialized.event)
        assertEquals(event.dataJson, deserialized.dataJson)
        assertEquals(event.accountUid, deserialized.accountUid)
        assertEquals(event.accountAnonymousUid, deserialized.accountAnonymousUid)
        assertEquals(event.enqueuedAt, deserialized.enqueuedAt)
        assertEquals(event.retryCount, deserialized.retryCount)
    }

    @Test
    fun queuedEvent_minimalFields() {
        val event = EventQueue.QueuedEvent(event = "page_view")
        assertEquals("page_view", event.event)
        assertNull(event.dataJson)
        assertNull(event.accountUid)
        assertNull(event.accountAnonymousUid)
        assertEquals(0, event.retryCount)
        assertTrue(event.enqueuedAt > 0)
    }

    @Test
    fun queuedEvent_retryCountMutable() {
        val event = EventQueue.QueuedEvent(event = "test")
        assertEquals(0, event.retryCount)
        event.retryCount = 5
        assertEquals(5, event.retryCount)
    }

    @Test
    fun queuedEventList_serialization() {
        val events = listOf(
            EventQueue.QueuedEvent(event = "event_1", accountUid = "u1"),
            EventQueue.QueuedEvent(event = "event_2", dataJson = """{"a":"b"}""")
        )

        val serialized = json.encodeToString(events)
        val deserialized = json.decodeFromString<List<EventQueue.QueuedEvent>>(serialized)

        assertEquals(2, deserialized.size)
        assertEquals("event_1", deserialized[0].event)
        assertEquals("u1", deserialized[0].accountUid)
        assertEquals("event_2", deserialized[1].event)
        assertEquals("""{"a":"b"}""", deserialized[1].dataJson)
    }

    // --- Event time ---

    @Test
    fun enqueue_keepsOccurredAt_andReplaysIt() = runBlocking {
        val queue = EventQueue.shared()
        val replayedAt = CompletableDeferred<Long>()
        queue.trackFunction = { _, _, _, _, enqueuedAt ->
            replayedAt.complete(enqueuedAt)
            true
        }

        queue.enqueue("button_click", occurredAt = 1700000000123)
        queue.flush()

        assertEquals(1700000000123, withTimeout(5000) { replayedAt.await() })
    }

    // --- Retry worker ---

    @Test
    fun enqueue_startsTheWorker_withoutAnExplicitFlush() = runBlocking {
        val queue = EventQueue.shared()
        val replayed = CompletableDeferred<String>()
        queue.trackFunction = { event, _, _, _, _ ->
            replayed.complete(event)
            true
        }

        queue.enqueue("screen_view")

        assertEquals("screen_view", withTimeout(5000) { replayed.await() })
        withTimeout(5000) { while (queue.size() > 0) delay(10) }
    }

    @Test
    fun enqueue_duringBackoff_doesNotRestartTheWorker() = runBlocking {
        val queue = EventQueue.shared()
        val attempts = AtomicInteger(0)
        val firstAttempt = CompletableDeferred<Unit>()
        queue.trackFunction = { _, _, _, _, _ ->
            attempts.incrementAndGet()
            firstAttempt.complete(Unit)
            false
        }

        queue.enqueue("first")
        withTimeout(5000) { firstAttempt.await() }
        repeat(5) { queue.enqueue("during_outage_$it") }
        delay(500)

        // The first retry is at least 2s out; a restarted worker would have retried at once.
        assertEquals(1, attempts.get())
        assertEquals(6, queue.size())
    }

    @Test
    fun enqueue_afterTheQueueDrains_startsANewWorker() = runBlocking {
        val queue = EventQueue.shared()
        val replayed = Channel<String>(Channel.UNLIMITED)
        queue.trackFunction = { event, _, _, _, _ ->
            replayed.send(event)
            true
        }

        queue.enqueue("first")
        assertEquals("first", withTimeout(5000) { replayed.receive() })
        withTimeout(5000) { while (queue.size() > 0) delay(10) }

        queue.enqueue("second")
        assertEquals("second", withTimeout(5000) { replayed.receive() })
    }

    @Test
    fun enqueue_atCapacity_whileTheWorkerDrains_neverThrows() {
        val queue = EventQueue.shared()
        val failures = CopyOnWriteArrayList<Throwable>()
        repeat(200) {
            val gate = CompletableDeferred<Unit>()
            queue.trackFunction = { _, _, _, _, _ ->
                gate.await()
                true
            }
            repeat(EventQueue.MAX_QUEUE_SIZE) { queue.enqueue("fill") }

            val start = CountDownLatch(1)
            val producers = (1..8).map {
                thread {
                    start.await()
                    repeat(20) {
                        try {
                            queue.enqueue("burst")
                        } catch (t: Throwable) {
                            failures.add(t)
                        }
                    }
                }
            }
            start.countDown()
            gate.complete(Unit)
            producers.forEach { it.join() }
            runBlocking { withTimeout(10_000) { while (queue.size() > 0) delay(5) } }
        }
        assertEquals(emptyList<Throwable>(), failures)
    }

    @Test
    fun enqueue_concurrently_atCapacity_keepsTheQueueAtItsCap() {
        val queue = EventQueue.shared()
        val gate = CompletableDeferred<Unit>()
        queue.trackFunction = { _, _, _, _, _ ->
            gate.await()
            true
        }
        repeat(EventQueue.MAX_QUEUE_SIZE) { queue.enqueue("fill") }

        val start = CountDownLatch(1)
        val producers = (1..8).map {
            thread {
                start.await()
                repeat(200) { queue.enqueue("burst") }
            }
        }
        start.countDown()
        producers.forEach { it.join() }

        assertEquals(EventQueue.MAX_QUEUE_SIZE, queue.size())
        gate.complete(Unit)
    }

    @Test
    fun trackTimestamp_isIsoInstantInUtc() {
        assertEquals("2023-11-14T22:13:20Z", trackTimestamp(1700000000000))
        assertEquals("2023-11-14T22:13:20.123Z", trackTimestamp(1700000000123))
    }

    // --- Backoff calculation ---

    @Test
    fun calculateBackoff_increasesExponentially() {
        val queue = EventQueue.shared()
        val b0 = queue.calculateBackoff(0)
        val b1 = queue.calculateBackoff(1)
        val b2 = queue.calculateBackoff(2)

        // Base is 2000ms * 2^retryCount + jitter(0-1000)
        // retry 0: 2000-3000
        assertTrue("Backoff at retry 0 should be >= 2000, was $b0", b0 >= 2000)
        assertTrue("Backoff at retry 0 should be <= 3000, was $b0", b0 <= 3000)

        // retry 1: 4000-5000
        assertTrue("Backoff at retry 1 should be >= 4000, was $b1", b1 >= 4000)
        assertTrue("Backoff at retry 1 should be <= 5000, was $b1", b1 <= 5000)

        // retry 2: 8000-9000
        assertTrue("Backoff at retry 2 should be >= 8000, was $b2", b2 >= 8000)
        assertTrue("Backoff at retry 2 should be <= 9000, was $b2", b2 <= 9000)
    }

    @Test
    fun calculateBackoff_cappedAtMaxDelay() {
        val queue = EventQueue.shared()
        // retry 20: 2000 * 2^20 = 2,097,152,000ms — should be capped at 300,000ms
        val backoff = queue.calculateBackoff(20)
        assertTrue("Backoff should be capped at 300000ms, was $backoff", backoff <= 300_000)
    }

    // --- Constants ---

    @Test
    fun maxQueueSize_is1000() {
        assertEquals(1000, EventQueue.MAX_QUEUE_SIZE)
    }

    @Test
    fun maxRetries_is10() {
        assertEquals(10, EventQueue.MAX_RETRIES)
    }
}
