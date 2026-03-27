package com.dashx.android

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class EventQueueTest {

    private val json = Json { ignoreUnknownKeys = true }

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
