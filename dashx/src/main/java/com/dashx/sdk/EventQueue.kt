package com.dashx.android

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.min
import kotlin.math.pow

/**
 * Offline event queue with persistence and exponential-backoff retry.
 *
 * When the network is unavailable or a track call fails, events are enqueued here.
 * They are persisted to SharedPreferences so they survive app restarts.
 * On flush (called after configure or when network becomes available), events are
 * replayed one-at-a-time through [trackFunction] preserving the original identity
 * captured at enqueue time.
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
        val enqueuedAt: Long = System.currentTimeMillis(),
        var retryCount: Int = 0
    )

    private val pendingEvents = CopyOnWriteArrayList<QueuedEvent>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var retryJob: Job? = null
    private var prefs: SharedPreferences? = null
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The function used to replay events. Set by [DashX.configure].
     * Signature: (event, dataJson, accountUid, accountAnonymousUid) -> success
     */
    var trackFunction: (suspend (String, String?, String?, String?) -> Boolean)? = null

    /** Must be called once from [DashX.configure] to enable persistence. */
    fun configure(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadFromDisk()
    }

    fun enqueue(
        event: String,
        dataJson: String? = null,
        accountUid: String? = null,
        accountAnonymousUid: String? = null
    ) {
        if (pendingEvents.size >= MAX_QUEUE_SIZE) {
            pendingEvents.removeAt(0) // drop oldest (FIFO eviction)
        }
        pendingEvents.add(
            QueuedEvent(
                event = event,
                dataJson = dataJson,
                accountUid = accountUid,
                accountAnonymousUid = accountAnonymousUid
            )
        )
        saveToDisk()
    }

    /** Attempt to send all queued events, one at a time. */
    fun flush() {
        retryJob?.cancel()
        retryJob = scope.launch { flushSequentially() }
    }

    fun size(): Int = pendingEvents.size

    fun stop() {
        retryJob?.cancel()
        scope.cancel()
    }

    // ---- Internal ----

    private suspend fun flushSequentially() {
        while (pendingEvents.isNotEmpty()) {
            val head = pendingEvents.firstOrNull() ?: break

            if (head.retryCount >= MAX_RETRIES) {
                DashXLog.e("EventQueue", "Dropping event '${head.event}' after $MAX_RETRIES retries")
                pendingEvents.removeAt(0)
                saveToDisk()
                continue
            }

            val fn = trackFunction
            if (fn == null) {
                DashXLog.e("EventQueue", "trackFunction not set, cannot flush")
                return
            }

            val success = try {
                fn(head.event, head.dataJson, head.accountUid, head.accountAnonymousUid)
            } catch (_: Throwable) {
                false
            }

            if (success) {
                pendingEvents.removeAt(0)
                saveToDisk()
            } else {
                head.retryCount++
                saveToDisk()
                val delayMs = calculateBackoff(head.retryCount)
                DashXLog.d("EventQueue", "Retrying '${head.event}' in ${delayMs}ms (attempt ${head.retryCount})")
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
