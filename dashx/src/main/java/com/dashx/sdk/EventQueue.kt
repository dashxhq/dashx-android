package com.dashx.android

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.min
import kotlin.math.pow

/**
 * Offline event queue with persistence and exponential-backoff retry.
 *
 * When the network is unavailable or a track call fails, events are enqueued here.
 * They are persisted to SharedPreferences so they survive app restarts.
 * On flush (called after configure or when network becomes available), events are
 * replayed one-at-a-time through [trackFunction] preserving the original identity
 * and time captured when track() was called.
 */
class EventQueue private constructor() {

    companion object {
        @Volatile
        private var instance: EventQueue? = null

        private const val PREFS_NAME = "com.dashx.android.event_queue"
        private const val PREFS_KEY_EVENTS = "queued_events"

        const val MAX_QUEUE_SIZE = 1000
        const val MAX_RETRIES = 10
        private const val BASE_RETRY_INTERVAL_MS = 2000L
        private const val MAX_DELAY_MS = 300_000L // 5 minutes

        fun shared(): EventQueue {
            return instance ?: synchronized(this) {
                instance ?: EventQueue().also { instance = it }
            }
        }

        /** Visible for testing — resets singleton. */
        internal fun resetForTesting() {
            instance?.stop()
            instance = null
        }
    }

    @Serializable
    data class QueuedEvent(
        val event: String,
        val dataJson: String? = null,
        val accountUid: String? = null,
        val accountAnonymousUid: String? = null,
        /** When track() was called, sent as the event's timestamp on replay. Named for the stored key. */
        val enqueuedAt: Long = System.currentTimeMillis(),
        var retryCount: Int = 0
    )

    /**
     * Guards [pendingEvents], [retryJob], each event's retryCount and every disk write. Insertion,
     * eviction, the worker's removal and reload never interleave, and the last write is the latest state.
     */
    private val lock = Any()
    private val pendingEvents = ArrayList<QueuedEvent>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var retryJob: Job? = null
    private var prefs: SharedPreferences? = null
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The function used to replay events. Set by [DashX.configure].
     * Signature: (event, dataJson, accountUid, accountAnonymousUid, enqueuedAt) -> success
     */
    var trackFunction: (suspend (String, String?, String?, String?, Long) -> Boolean)? = null

    /** Must be called once from [DashX.configure] to enable persistence. */
    fun configure(context: Context) {
        synchronized(lock) {
            prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            loadFromDisk()
        }
    }

    fun enqueue(
        event: String,
        dataJson: String? = null,
        accountUid: String? = null,
        accountAnonymousUid: String? = null,
        occurredAt: Long = System.currentTimeMillis()
    ) {
        synchronized(lock) {
            if (pendingEvents.size >= MAX_QUEUE_SIZE) {
                pendingEvents.removeAt(0) // drop oldest (FIFO eviction)
            }
            pendingEvents.add(
                QueuedEvent(
                    event = event,
                    dataJson = dataJson,
                    accountUid = accountUid,
                    accountAnonymousUid = accountAnonymousUid,
                    enqueuedAt = occurredAt
                )
            )
            saveToDisk()
            ensureFlushing()
        }
    }

    /** Attempt to send all queued events, one at a time. */
    fun flush() {
        synchronized(lock) {
            retryJob?.cancel()
            retryJob = scope.launch { flushSequentially() }
        }
    }

    fun size(): Int = synchronized(lock) { pendingEvents.size }

    fun stop() {
        synchronized(lock) { retryJob?.cancel() }
        scope.cancel()
    }

    // ---- Internal ----

    /**
     * Starts the worker unless one is running. A running worker keeps its backoff: restarting it per
     * enqueue would retry immediately for every event tracked during an outage.
     */
    private fun ensureFlushing() {
        synchronized(lock) {
            if (retryJob?.isActive == true) return
            retryJob = scope.launch { flushSequentially() }
        }
    }

    private suspend fun flushSequentially() {
        val self = currentCoroutineContext()[Job]
        while (true) {
            // Finding the queue empty and giving up the worker slot happen under one lock, so an
            // event enqueued as the worker drains always finds either this worker or no worker.
            val head = synchronized(lock) {
                pendingEvents.firstOrNull().also { if (it == null && retryJob === self) retryJob = null }
            } ?: return

            if (head.retryCount >= MAX_RETRIES) {
                DashXLog.e("EventQueue", "Dropping event '${head.event}' after $MAX_RETRIES retries")
                synchronized(lock) {
                    pendingEvents.removeIf { it === head }
                    saveToDisk()
                }
                continue
            }

            val fn = trackFunction
            if (fn == null) {
                DashXLog.e("EventQueue", "trackFunction not set, cannot flush")
                return
            }

            val success = try {
                fn(head.event, head.dataJson, head.accountUid, head.accountAnonymousUid, head.enqueuedAt)
            } catch (_: Throwable) {
                false
            }

            val attempt = synchronized(lock) {
                // By identity: a concurrent enqueue's FIFO eviction may already have removed the head.
                if (success) pendingEvents.removeIf { it === head } else head.retryCount++
                saveToDisk()
                head.retryCount
            }
            if (!success) {
                val delayMs = calculateBackoff(attempt)
                DashXLog.d("EventQueue", "Retrying '${head.event}' in ${delayMs}ms (attempt $attempt)")
                delay(delayMs)
                // Loop will re-check head (which may now exceed MAX_RETRIES)
            }
        }
    }

    internal fun calculateBackoff(retryCount: Int): Long {
        val delay = BASE_RETRY_INTERVAL_MS * 2.0.pow(retryCount.toDouble())
        val jitter = (Math.random() * 1000).toLong()
        return min(delay.toLong() + jitter, MAX_DELAY_MS)
    }

    /** Caller holds [lock]. */
    private fun loadFromDisk() {
        val raw = prefs?.getString(PREFS_KEY_EVENTS, null) ?: return
        try {
            val events = json.decodeFromString<List<QueuedEvent>>(raw)
            pendingEvents.clear()
            pendingEvents.addAll(events)
        } catch (e: Throwable) {
            DashXLog.e("EventQueue", "Failed to load event queue: ${e.message}")
        }
    }

    /** Caller holds [lock], so writes land in the order the queue changed. */
    private fun saveToDisk() {
        val p = prefs ?: return
        try {
            val raw = json.encodeToString(pendingEvents.toList())
            p.edit().putString(PREFS_KEY_EVENTS, raw).apply()
        } catch (e: Throwable) {
            DashXLog.e("EventQueue", "Failed to save event queue: ${e.message}")
        }
    }
}
