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
    /** Raw server key. Prefer [clientMessageId] for reconciling a pending send. */
    val externalUid: String?,
    val senderId: String?,
    /** `user` for the visitor's own message; anything else is an agent or AI reply. */
    val aiRole: String?,
    val turnSeq: Long,
    val renderedContent: JsonObject,
    val createdAt: String?,
    val sentAt: String?
) {
    /**
     * The `clientMessageId` this visitor message was sent with (the value
     * [DashXConversationLease.sendMessage] returned), or null for agent, AI, and system rows.
     * Stable per send attempt — reconcile an optimistic row against this, not [id], which is only
     * known once the server commits.
     */
    val clientMessageId: String?
        get() = externalUid
            ?.takeIf { it.startsWith(CLIENT_MESSAGE_PREFIX) }
            ?.removePrefix(CLIENT_MESSAGE_PREFIX)

    internal val isFromVisitor: Boolean get() = aiRole.equals(VISITOR_ROLE, ignoreCase = true)

    companion object {
        /** Prefix under which a visitor's `clientMessageId` is returned in `externalUid`. */
        internal const val CLIENT_MESSAGE_PREFIX = "in_app_chat:"
        private const val VISITOR_ROLE = "user"

        /** Server message order. */
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
            turnSeq = f.turnSeq.toLong(),
            renderedContent = f.renderedContent as? JsonObject ?: JsonObject(emptyMap()),
            createdAt = f.createdAt.toString(),
            sentAt = f.sentAt?.toString()
        )

        internal fun from(row: FetchInAppChatMessagesQuery.FetchInAppChatMessage): ChatMessage =
            from(row.chatMessageFragment)
    }
}

/**
 * An agent reply while it is generated, for a deployment that streams replies. It is replaced by
 * the stored reply: once that arrives in [ConversationState.Ready.messages], the conversation
 * drops it.
 */
data class ChatStreamingReply(
    /** The turn being answered: the visitor message's `turnSeq`, which the stored reply carries too. */
    val turnSeq: Long,
    /** The reply text received so far. */
    val text: String,
    /** Generation is over and the stored reply is on its way; [text] will not grow. */
    val isComplete: Boolean
)

/** A conversation's synchronized state, exposed on every lease. */
sealed interface ConversationState {
    data object Loading : ConversationState
    /** [hasOlderMessages] is true while history older than [messages] can still be paged in with
     * [DashXConversationLease.loadPreviousPage]. [streamingReply] is the agent reply being
     * generated, or null when none is. */
    data class Ready(
        val messages: List<ChatMessage>,
        val hasOlderMessages: Boolean = false,
        val streamingReply: ChatStreamingReply? = null
    ) : ConversationState
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
 * different chat identity is a different resource; sharing state across identities would hide
 * a mismatch the server rejects.
 */
internal data class ChatSessionKey(val chatIdentityId: String, val conversationId: String)
