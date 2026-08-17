package com.dashx.android

import android.content.Context
import android.content.SharedPreferences
import com.dashx.android.graphql.generated.type.TrackMessageStatus
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * Durable store for notification tracking captured before [DashX.configure] runs — e.g. a push that
 * cold-starts the app straight into the SDK's receiver. Persists to SharedPreferences (only needs a
 * [Context]) so entries survive process death, and replays them with their original timestamps once
 * configured. Persist and flush coordinate under [lock] with a post-persist re-check so an entry
 * written during the configure/flush handoff is never stranded (see [append]). Best-effort:
 * bounded and cleared on flush.
 */
internal object PendingNotificationTracker {

    private const val PREFS_NAME = "com.dashx.android.pending_notification_tracking"
    private const val PREFS_KEY = "pending_entries"
    private const val MAX_ENTRIES = 100
    private const val tag = "PendingNotificationTracker"

    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()
    private val entryListSerializer = ListSerializer(Entry.serializer())

    @Serializable
    internal data class Entry(
        val kind: String,
        val timestamp: String,
        val messageId: String? = null,
        val status: String? = null,
        val event: String? = null,
        val data: Map<String, String>? = null
    ) {
        companion object {
            const val KIND_MESSAGE = "message"
            const val KIND_EVENT = "event"
        }
    }

    /** Persistence backend. Production uses SharedPreferences; tests inject an in-memory fake. */
    internal interface PendingStore {
        fun read(): List<Entry>
        fun write(entries: List<Entry>)
    }

    // --- Seams (production defaults call into DashX / real prefs; overridden in tests) ---

    internal var isConfiguredProvider: () -> Boolean = { DashX.isConfigured() }
    internal var messageReplay: (String, TrackMessageStatus, String) -> Unit =
        { id, status, timestamp -> DashX.trackMessage(id, status, timestamp) }
    internal var eventReplay: (String, HashMap<String, String>) -> Unit =
        { event, data -> DashX.track(event, data) }

    internal fun resetSeamsForTesting() {
        isConfiguredProvider = { DashX.isConfigured() }
        messageReplay = { id, status, timestamp -> DashX.trackMessage(id, status, timestamp) }
        eventReplay = { event, data -> DashX.track(event, data) }
    }

    // --- Public API (production, Context-backed) ---

    fun enqueueMessage(context: Context, id: String, status: TrackMessageStatus) =
        enqueueMessage(SharedPrefsStore(context), id, status)

    fun enqueueEvent(context: Context, event: String, data: HashMap<String, String>) =
        enqueueEvent(SharedPrefsStore(context), event, data)

    fun flush(context: Context) = flush(SharedPrefsStore(context))

    // --- Core (store-backed, unit-testable without Android) ---

    internal fun enqueueMessage(store: PendingStore, id: String, status: TrackMessageStatus) {
        append(
            store,
            Entry(
                kind = Entry.KIND_MESSAGE,
                timestamp = nowIso(),
                messageId = id,
                status = status.rawValue
            )
        )
    }

    internal fun enqueueEvent(store: PendingStore, event: String, data: HashMap<String, String>) {
        append(
            store,
            Entry(
                kind = Entry.KIND_EVENT,
                // Navigation events stamp their tap time into `data["timestamp"]`; preserve it.
                timestamp = data["timestamp"] ?: nowIso(),
                event = event,
                data = data
            )
        )
    }

    /** Replay everything persisted, then clear — atomically under [lock] so concurrent flushes
     *  can't double-send. Called after configure() (from [DashX.init]) and from [append]. */
    internal fun flush(store: PendingStore) {
        val entries = synchronized(lock) {
            val current = store.read()
            if (current.isEmpty()) return
            store.write(emptyList())
            current
        }

        for (entry in entries) {
            replay(entry)
        }
    }

    private fun append(store: PendingStore, entry: Entry) {
        synchronized(lock) {
            val current = store.read().toMutableList()
            current.add(entry)
            while (current.size > MAX_ENTRIES) {
                current.removeAt(0)
            }
            store.write(current)
        }

        // Close the configure/flush handoff race: if configure()'s flush ran before this entry was
        // persisted (saw an empty store), it would sit pending until the next configure(). Re-check
        // and self-flush; [flush] clears under [lock], so a double-take is a harmless no-op.
        if (isConfiguredProvider()) {
            flush(store)
        }
    }

    private fun replay(entry: Entry) {
        try {
            when (entry.kind) {
                Entry.KIND_MESSAGE -> {
                    val id = entry.messageId ?: return
                    messageReplay(id, TrackMessageStatus.safeValueOf(entry.status ?: ""), entry.timestamp)
                }
                Entry.KIND_EVENT -> {
                    val event = entry.event ?: return
                    eventReplay(event, HashMap(entry.data ?: emptyMap()))
                }
            }
        } catch (e: Throwable) {
            DashXLog.e(tag, "Failed to replay pending ${entry.kind}: ${e.message}")
        }
    }

    private fun nowIso(): String = DateTimeFormatter.ISO_INSTANT.format(Instant.now())

    private class SharedPrefsStore(context: Context) : PendingStore {
        private val prefs: SharedPreferences =
            context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        override fun read(): List<Entry> {
            val raw = prefs.getString(PREFS_KEY, null) ?: return emptyList()
            return try {
                json.decodeFromString(entryListSerializer, raw)
            } catch (e: Throwable) {
                DashXLog.e(tag, "Failed to read pending tracking: ${e.message}")
                emptyList()
            }
        }

        override fun write(entries: List<Entry>) {
            prefs.edit().putString(PREFS_KEY, json.encodeToString(entryListSerializer, entries)).apply()
        }
    }
}
