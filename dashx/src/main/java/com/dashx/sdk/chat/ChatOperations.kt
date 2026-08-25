package com.dashx.android.chat

import com.apollographql.apollo.api.Optional
import com.dashx.android.DashX
import com.dashx.android.DashXError
import com.dashx.android.graphql.generated.FetchInAppChatConversationQuery
import com.dashx.android.graphql.generated.FetchInAppChatConversationsQuery
import com.dashx.android.graphql.generated.FetchInAppChatMessagesQuery
import com.dashx.android.graphql.generated.MarkInAppChatConversationReadMutation
import com.dashx.android.graphql.generated.ResolveInAppChatConversationMutation
import com.dashx.android.graphql.generated.SendInAppChatMessageMutation
import com.dashx.android.graphql.generated.SummarizeInAppChatConversationsQuery
import com.dashx.android.graphql.generated.SummarizeInAppChatMessagesQuery
import com.dashx.android.graphql.generated.SummarizeInAppChatUnreadQuery
import kotlinx.coroutines.Job
import kotlinx.serialization.json.JsonObject

// The nine raw in-app chat operations, identity-scoped by explicit parameter. Every one is
// owner-scoped to the visitor resolved from the identity token, so an identity must be set first.
// `DashX.chat(identityId)` is the managed surface over these.

private fun notIdentifiedJob(onError: (DashXError) -> Unit): Job =
    DashX.launchCallback { onError(DashXError.NotIdentified()) }

fun DashX.Companion.sendInAppChatMessage(
    conversationId: String,
    identityId: String,
    content: JsonObject,
    clientMessageId: String,
    onSuccess: (result: SendInAppChatMessageMutation.SendInAppChatMessage) -> Unit,
    onError: (error: DashXError) -> Unit
) { sendInAppChatMessageJob(conversationId, identityId, content, clientMessageId, onSuccess, onError) }

internal fun DashX.Companion.sendInAppChatMessageJob(
    conversationId: String,
    identityId: String,
    content: JsonObject,
    clientMessageId: String,
    onSuccess: (result: SendInAppChatMessageMutation.SendInAppChatMessage) -> Unit,
    onError: (error: DashXError) -> Unit
): Job {
    if (account.get().identityToken == null) return notIdentifiedJob(onError)
    val mutation = SendInAppChatMessageMutation(
        conversationId = conversationId,
        identityId = identityId,
        content = content,
        clientMessageId = clientMessageId
    )
    return executeMutation(mutation, onError) { result ->
        result.data?.sendInAppChatMessage?.let(onSuccess)
    }
}

fun DashX.Companion.fetchInAppChatMessages(
    conversationId: String,
    limit: Int? = null,
    page: Int? = null,
    afterMessageId: String? = null,
    onSuccess: (result: List<FetchInAppChatMessagesQuery.FetchInAppChatMessage>) -> Unit,
    onError: (error: DashXError) -> Unit
) { fetchInAppChatMessagesJob(conversationId, limit, page, afterMessageId, onSuccess, onError) }

internal fun DashX.Companion.fetchInAppChatMessagesJob(
    conversationId: String,
    limit: Int? = null,
    page: Int? = null,
    /** Cursor mode: rows strictly after this message. The backend rejects a non-null [page] with it. */
    afterMessageId: String? = null,
    onSuccess: (result: List<FetchInAppChatMessagesQuery.FetchInAppChatMessage>) -> Unit,
    onError: (error: DashXError) -> Unit
): Job {
    if (account.get().identityToken == null) return notIdentifiedJob(onError)
    val query = FetchInAppChatMessagesQuery(
        conversationId = conversationId,
        limit = limit?.let { Optional.Present(it) } ?: Optional.Absent,
        page = page?.let { Optional.Present(it) } ?: Optional.Absent,
        afterMessageId = afterMessageId?.let { Optional.Present(it) } ?: Optional.Absent
    )
    return executeQuery(query, onError) { result ->
        onSuccess(result.data?.fetchInAppChatMessages ?: listOf())
    }
}

fun DashX.Companion.summarizeInAppChatMessages(
    conversationId: String,
    onSuccess: (count: Int) -> Unit,
    onError: (error: DashXError) -> Unit
) { summarizeInAppChatMessagesJob(conversationId, onSuccess, onError) }

internal fun DashX.Companion.summarizeInAppChatMessagesJob(
    conversationId: String,
    onSuccess: (count: Int) -> Unit,
    onError: (error: DashXError) -> Unit
): Job {
    if (account.get().identityToken == null) return notIdentifiedJob(onError)
    val query = SummarizeInAppChatMessagesQuery(conversationId = conversationId)
    return executeQuery(query, onError) { result ->
        result.data?.summarizeInAppChatMessages?.count?.let(onSuccess)
    }
}

fun DashX.Companion.fetchInAppChatConversations(
    identityId: String,
    limit: Int? = null,
    page: Int? = null,
    statuses: List<String>? = null,
    properties: JsonObject? = null,
    onSuccess: (result: List<FetchInAppChatConversationsQuery.FetchInAppChatConversation>) -> Unit,
    onError: (error: DashXError) -> Unit
) {
    if (account.get().identityToken == null) { notIdentifiedJob(onError); return }
    val query = FetchInAppChatConversationsQuery(
        identityId = identityId,
        limit = limit?.let { Optional.Present(it) } ?: Optional.Absent,
        page = page?.let { Optional.Present(it) } ?: Optional.Absent,
        statuses = statuses?.let { Optional.Present(it) } ?: Optional.Absent,
        properties = properties?.let { Optional.Present(it) } ?: Optional.Absent
    )
    executeQuery(query, onError) { result ->
        onSuccess(result.data?.fetchInAppChatConversations ?: listOf())
    }
}

fun DashX.Companion.fetchInAppChatConversation(
    identityId: String,
    conversationId: String,
    onSuccess: (result: FetchInAppChatConversationQuery.FetchInAppChatConversation) -> Unit,
    onError: (error: DashXError) -> Unit
) {
    if (account.get().identityToken == null) { notIdentifiedJob(onError); return }
    val query = FetchInAppChatConversationQuery(identityId = identityId, conversationId = conversationId)
    executeQuery(query, onError) { result ->
        result.data?.fetchInAppChatConversation?.let(onSuccess)
    }
}

fun DashX.Companion.summarizeInAppChatConversations(
    identityId: String,
    statuses: List<String>? = null,
    properties: JsonObject? = null,
    onSuccess: (count: Int) -> Unit,
    onError: (error: DashXError) -> Unit
) {
    if (account.get().identityToken == null) { notIdentifiedJob(onError); return }
    val query = SummarizeInAppChatConversationsQuery(
        identityId = identityId,
        statuses = statuses?.let { Optional.Present(it) } ?: Optional.Absent,
        properties = properties?.let { Optional.Present(it) } ?: Optional.Absent
    )
    executeQuery(query, onError) { result ->
        result.data?.summarizeInAppChatConversations?.count?.let(onSuccess)
    }
}

fun DashX.Companion.summarizeInAppChatUnread(
    identityId: String,
    onSuccess: (count: Int) -> Unit,
    onError: (error: DashXError) -> Unit
) { summarizeInAppChatUnreadJob(identityId, onSuccess, onError) }

internal fun DashX.Companion.summarizeInAppChatUnreadJob(
    identityId: String,
    onSuccess: (count: Int) -> Unit,
    onError: (error: DashXError) -> Unit
): Job {
    if (account.get().identityToken == null) return notIdentifiedJob(onError)
    val query = SummarizeInAppChatUnreadQuery(identityId = identityId)
    return executeQuery(query, onError) { result ->
        result.data?.summarizeInAppChatUnread?.count?.let(onSuccess)
    }
}

fun DashX.Companion.markInAppChatConversationRead(
    identityId: String,
    conversationId: String,
    lastMessageId: String,
    onSuccess: (success: Boolean) -> Unit,
    onError: (error: DashXError) -> Unit
) { markInAppChatConversationReadJob(identityId, conversationId, lastMessageId, onSuccess, onError) }

internal fun DashX.Companion.markInAppChatConversationReadJob(
    identityId: String,
    conversationId: String,
    lastMessageId: String,
    onSuccess: (success: Boolean) -> Unit,
    onError: (error: DashXError) -> Unit
): Job {
    if (account.get().identityToken == null) return notIdentifiedJob(onError)
    val mutation = MarkInAppChatConversationReadMutation(
        identityId = identityId,
        conversationId = conversationId,
        lastMessageId = lastMessageId
    )
    return executeMutation(mutation, onError) { result ->
        result.data?.markInAppChatConversationRead?.success?.let(onSuccess)
    }
}

fun DashX.Companion.resolveInAppChatConversation(
    identityId: String,
    conversationId: String,
    onSuccess: (result: ResolveInAppChatConversationMutation.ResolveInAppChatConversation) -> Unit,
    onError: (error: DashXError) -> Unit
) {
    if (account.get().identityToken == null) { notIdentifiedJob(onError); return }
    val mutation = ResolveInAppChatConversationMutation(identityId = identityId, conversationId = conversationId)
    executeMutation(mutation, onError) { result ->
        result.data?.resolveInAppChatConversation?.let(onSuccess)
    }
}
