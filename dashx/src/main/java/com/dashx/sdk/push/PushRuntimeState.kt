package com.dashx.android.push

import java.util.concurrent.atomic.AtomicReference

/**
 * The actor-to-push bridge: [DashXPush.handleMessage] runs on an FCM callback thread and must decide
 * synchronously whether to display, so foreground and visibility are published here rather than read
 * from mutable chat state.
 *
 * Two independent writers — the lifecycle observer owns [Snapshot.isForeground], the chat sessions
 * own [Snapshot.visibleConversationIds] — so every write is a CAS on the field it owns; a
 * read-copy-set would lose one side's update.
 */
internal class PushRuntimeState {

    internal data class Snapshot(
        val isForeground: Boolean,
        val visibleConversationIds: Set<String>
    )

    // Default isForeground = false: before the lifecycle observer reports, the safe assumption is
    // backgrounded, which DISPLAYS a notification rather than silently dropping it.
    private val ref = AtomicReference(Snapshot(isForeground = false, visibleConversationIds = emptySet()))

    fun get(): Snapshot = ref.get()

    fun setForeground(foreground: Boolean) {
        ref.updateAndGet { it.copy(isForeground = foreground) }
    }

    fun setConversationVisible(conversationId: String, visible: Boolean) {
        ref.updateAndGet {
            val ids = if (visible) it.visibleConversationIds + conversationId
            else it.visibleConversationIds - conversationId
            it.copy(visibleConversationIds = ids)
        }
    }

    /** Identity switch / reset: visibility is stale, but foreground is not — the observer stays
     * registered and may never fire again if the app never leaves the foreground. */
    fun clearVisible() {
        ref.updateAndGet { it.copy(visibleConversationIds = emptySet()) }
    }

    /** Shutdown only: the lifecycle observer is unregistered alongside this. */
    fun reset() {
        ref.set(Snapshot(isForeground = false, visibleConversationIds = emptySet()))
    }
}
