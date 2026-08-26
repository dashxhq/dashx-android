package com.dashx.android.chat

import com.dashx.android.DashXError
import com.dashx.android.graphql.generated.FetchInAppChatMessagesQuery
import com.dashx.android.graphql.generated.fragment.ChatMessageFragment
import com.dashx.android.realtime.DashXRealtimeChatMessage
import kotlinx.serialization.json.JsonObject

/** One chat message, normalized from history fetches and realtime frames alike. */
data class ChatMessage(
    val id: String,
    val conversationId: String,
    /** Stable per send attempt — reconcile a pending send against this, not [id]. */
    val externalUid: String?,
    val senderId: String?,
    /** `USER` for the visitor's own message; anything else is an agent or AI reply. */
    val aiRole: String?,
    val turnSeq: Long,
    val renderedContent: JsonObject,
    val createdAt: String?,
    val sentAt: String?
) {
    companion object {
        /** The backend's own total order: `(turn_seq, created_at, id)`. */
        val ORDER: Comparator<ChatMessage> =
            compareBy({ it.turnSeq }, { it.createdAt ?: "" }, { it.id })

        internal fun from(frame: DashXRealtimeChatMessage): ChatMessage = ChatMessage(
            id = frame.id,
            conversationId = frame.conversationId,
            externalUid = frame.externalUid,
            senderId = frame.senderId,
            aiRole = frame.aiRole,
            turnSeq = frame.turnSeq,
            renderedContent = frame.renderedContent,
            createdAt = frame.createdAt,
            sentAt = frame.sentAt
        )

        internal fun from(f: ChatMessageFragment): ChatMessage = ChatMessage(
            id = f.id.toString(),
            conversationId = f.conversationId?.toString() ?: "",
            externalUid = f.externalUid,
            senderId = f.senderId?.toString(),
            aiRole = f.aiRole,
            turnSeq = f.turnSeq?.toLong() ?: 0L,
            renderedContent = f.renderedContent as? JsonObject ?: JsonObject(emptyMap()),
            createdAt = f.createdAt.toString(),
            sentAt = f.sentAt?.toString()
        )

        internal fun from(row: FetchInAppChatMessagesQuery.FetchInAppChatMessage): ChatMessage =
            from(row.chatMessageFragment)
    }
}

/** A conversation's synchronized state, exposed on every lease. */
sealed interface ConversationState {
    data object Loading : ConversationState
    data class Ready(val messages: List<ChatMessage>) : ConversationState
    data class Error(val cause: DashXError) : ConversationState
}

/** Java-friendly observer for [ConversationState] changes. */
fun interface ConversationStateListener {
    fun onConversationStateChanged(state: ConversationState)
}

/** Why a lease's subscription ended. */
enum class DashXSubscriptionEnd { SessionEnded, Unsubscribed }

/**
 * Internal shared-state key. Identity participates because the same conversation id under a
 * different chat identity is a different backend resource — sharing state across identities would
 * hide a mismatch the backend rejects.
 */
internal data class ChatSessionKey(val chatIdentityId: String, val conversationId: String)
