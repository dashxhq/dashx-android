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

// Raw in-app chat operations. Every one is owner-scoped to the visitor resolved from the identity
// token, so an identity must be set first. `DashX.chat(identityId)` is the managed surface.

private fun notIdentifiedJob(onError: (DashXError) -> Unit): Job =
    DashX.launchCallback { onError(DashXError.NotIdentified()) }

/**
 * Gates an operation's callbacks on the identity session it began under. These jobs run on the
 * SDK's global scope — an identity switch or reset does NOT cancel them — so without the gate a
 * request begun as user A would deliver A's data into whatever UI user B is looking at. A stale
 * completion delivers [DashXError.SessionEnded] instead. Same-identity token refreshes leave the
 * generation unchanged, so they never invalidate an in-flight operation.
 */
internal fun <T> sessionBound(
    onSuccess: (T) -> Unit,
    onError: (DashXError) -> Unit,
    start: ((T) -> Unit, (DashXError) -> Unit) -> Job
): Job {
    val generation = DashX.currentSessionGeneration()
    fun stale() = DashX.currentSessionGeneration() != generation
    return start(
        { result -> if (stale()) onError(DashXError.SessionEnded()) else onSuccess(result) },
        { error -> if (stale()) onError(DashXError.SessionEnded()) else onError(error) }
    )
}

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
    return sessionBound(onSuccess, onError) { ok, err ->
        executeMutation(mutation, err) { result ->
            result.data?.sendInAppChatMessage?.let(ok)
        }
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
    return sessionBound(onSuccess, onError) { ok, err ->
        executeQuery(query, err) { result ->
            ok(result.data?.fetchInAppChatMessages ?: listOf())
        }
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
    return sessionBound(onSuccess, onError) { ok, err ->
        executeQuery(query, err) { result ->
            result.data?.summarizeInAppChatMessages?.count?.let(ok)
        }
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
    sessionBound(onSuccess, onError) { ok, err ->
        executeQuery(query, err) { result ->
            ok(result.data?.fetchInAppChatConversations ?: listOf())
        }
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
    sessionBound(onSuccess, onError) { ok, err ->
        executeQuery(query, err) { result ->
            result.data?.fetchInAppChatConversation?.let(ok)
        }
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
    sessionBound(onSuccess, onError) { ok, err ->
        executeQuery(query, err) { result ->
            result.data?.summarizeInAppChatConversations?.count?.let(ok)
        }
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
    return sessionBound(onSuccess, onError) { ok, err ->
        executeQuery(query, err) { result ->
            result.data?.summarizeInAppChatUnread?.count?.let(ok)
        }
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
    return sessionBound(onSuccess, onError) { ok, err ->
        executeMutation(mutation, err) { result ->
            result.data?.markInAppChatConversationRead?.success?.let(ok)
        }
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
    sessionBound(onSuccess, onError) { ok, err ->
        executeMutation(mutation, err) { result ->
            result.data?.resolveInAppChatConversation?.let(ok)
        }
    }
}
