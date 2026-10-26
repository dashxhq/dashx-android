package com.dashx.android

import com.dashx.android.graphql.generated.type.TrackMessageStatus
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PendingNotificationTrackerTest {

    /** In-memory stand-in for the SharedPreferences-backed store. */
    private class InMemoryStore : PendingNotificationTracker.PendingStore {
        @Volatile
        private var entries: List<PendingNotificationTracker.Entry> = emptyList()

        override fun read(): List<PendingNotificationTracker.Entry> = entries

        override fun write(entries: List<PendingNotificationTracker.Entry>) {
            this.entries = entries
        }

        fun size(): Int = entries.size
    }

    private val replayedMessages = CopyOnWriteArrayList<String>()
    private val replayedEvents = CopyOnWriteArrayList<String>()

    @Volatile
    private var configured = false

    @Before
    fun setUp() {
        replayedMessages.clear()
        replayedEvents.clear()
        configured = false
        PendingNotificationTracker.isConfiguredProvider = { configured }
        PendingNotificationTracker.messageReplay = { id, _, _ -> replayedMessages.add(id) }
        PendingNotificationTracker.eventReplay = { event, _ -> replayedEvents.add(event) }
    }

    @After
    fun tearDown() {
        PendingNotificationTracker.resetSeamsForTesting()
    }

    @Test
    fun `entry persisted while unconfigured stays pending`() {
        val store = InMemoryStore()

        PendingNotificationTracker.enqueueMessage(store, "m1", TrackMessageStatus.OPENED)

        assertEquals(1, store.size())
        assertTrue(replayedMessages.isEmpty())
    }

    @Test
    fun `flush replays and clears once configured`() {
        val store = InMemoryStore()
        PendingNotificationTracker.enqueueMessage(store, "m1", TrackMessageStatus.OPENED)
        PendingNotificationTracker.enqueueEvent(store, "dx_notification_navigated", hashMapOf())

        configured = true
        PendingNotificationTracker.flush(store)

        assertEquals(listOf("m1"), replayedMessages.toList())
        assertEquals(listOf("dx_notification_navigated"), replayedEvents.toList())
        assertEquals(0, store.size())
    }

    /**
     * Reproduces the configure/flush handoff race deterministically:
     *   1. a notification decides to persist because the SDK looked unconfigured,
     *   2. configure() flips the flag and flushes — but the store is still empty,
     *   3. the notification finally persists.
     * Without the post-persist re-check in [PendingNotificationTracker.append] the entry strands.
     */
    @Test
    fun `entry persisted during configure-flush handoff is not stranded`() {
        val store = InMemoryStore()

        // (2) configure() wins the race: flag flips, flush finds nothing.
        configured = true
        PendingNotificationTracker.flush(store)
        assertTrue("nothing to flush yet", replayedMessages.isEmpty())

        // (3) notification persists after configure()'s flush already ran.
        PendingNotificationTracker.enqueueMessage(store, "m1", TrackMessageStatus.OPENED)

        assertEquals(
            "entry written during the handoff must be replayed by the post-persist re-check",
            listOf("m1"),
            replayedMessages.toList()
        )
        assertEquals(0, store.size())
    }

    /** Stresses the real two-thread interleaving: persist vs configure()+flush must never strand. */
    @Test
    fun `concurrent persist and configure never strand an entry`() {
        repeat(1000) { i ->
            val store = InMemoryStore()
            replayedMessages.clear()
            configured = false
            val id = "m$i"
            val start = CountDownLatch(1)

            val persister = Thread {
                start.await()
                PendingNotificationTracker.enqueueMessage(store, id, TrackMessageStatus.OPENED)
            }
            val configurer = Thread {
                start.await()
                configured = true
                PendingNotificationTracker.flush(store)
            }

            persister.start()
            configurer.start()
            start.countDown()
            persister.join()
            configurer.join()

            // Whichever thread saw the entry (configure's flush or the persister's post-persist
            // re-check) must have replayed it exactly once; nothing may remain pending.
            assertEquals("iteration $i stranded an entry", 0, store.size())
            assertEquals("iteration $i did not replay exactly once", listOf(id), replayedMessages.toList())
        }
    }
}
