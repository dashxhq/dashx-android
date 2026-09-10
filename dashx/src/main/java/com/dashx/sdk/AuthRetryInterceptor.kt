package com.dashx.android

import com.apollographql.apollo.api.ApolloRequest
import com.apollographql.apollo.api.ApolloResponse
import com.apollographql.apollo.api.Error
import com.apollographql.apollo.api.Operation
import com.apollographql.apollo.interceptor.ApolloInterceptor
import com.apollographql.apollo.interceptor.ApolloInterceptorChain
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow

/**
 * Retries a request rejected before resolver execution with a freshly loaded identity token — once.
 *
 * The auth signal is NOT an HTTP status: the backend returns `UNAUTHORIZED` in
 * `errors[].extensions.code` of an HTTP 200. The retry predicate requires all three conditions —
 * `data == null`, a non-empty `errors` list, and every error `UNAUTHORIZED`:
 *
 * - `data != null` means something executed; retrying a mutation then is how a message double-sends.
 * - An empty errors list must not refresh: `all {}` is vacuously true on it.
 * - `FORBIDDEN` never refreshes — a new token will not grant permission.
 * - An `UNAUTHORIZED` whose message names a rejection a new token cannot fix (bad signature,
 *   malformed token, deleted account, wrong public key) is returned as-is; see [isRefreshable].
 *
 * The retry is generation-guarded: if the identity switched while the refresh ran, the new token
 * belongs to a different account and the old-era request must not be resent under it.
 */
internal class AuthRetryInterceptor(
    private val refreshToken: suspend () -> Boolean = { DashX.awaitTokenRefresh() },
    private val sessionGeneration: () -> Long = { DashX.currentSessionGeneration() },
    /** Clears an expired token nothing can refresh, so the retry runs on the public key alone. */
    private val dropExpiredToken: () -> Boolean = { DashX.dropUnrefreshableIdentityToken() }
) : ApolloInterceptor {

    override fun <D : Operation.Data> intercept(
        request: ApolloRequest<D>,
        chain: ApolloInterceptorChain
    ): Flow<ApolloResponse<D>> = flow {
        val generationAtStart = sessionGeneration()
        val first = chain.proceed(request).first()
        if (!isPreExecutionUnauthorized(first)) {
            emit(first)
            return@flow
        }
        // awaitTokenRefresh joins any in-flight load and completes only after the new token is
        // installed in the snapshot, so the retried request picks it up via the HTTP interceptor.
        if (!refreshToken()) {
            // No provider (a host that never opened chat) or the refresh failed. An expired token
            // would otherwise fail every call, including identify/track/subscribe, which pre-1.4
            // hosts ran unauthenticated; drop it and retry that way once.
            if (isExpired(first) && dropExpiredToken()) {
                emitAll(chain.proceed(request))
            } else {
                emit(first)
            }
            return@flow
        }
        if (sessionGeneration() != generationAtStart) {
            emit(first)
            return@flow
        }
        emitAll(chain.proceed(request))
    }

    private fun isPreExecutionUnauthorized(response: ApolloResponse<*>): Boolean {
        if (response.data != null) return false
        val errors = response.errors
        if (errors.isNullOrEmpty()) return false
        return errors.all { it.isUnauthorized() && isRefreshable(it) }
    }

    private fun isExpired(response: ApolloResponse<*>): Boolean =
        response.errors.orEmpty().let { errors -> errors.isNotEmpty() && errors.all { it.isUnauthorized() && it.isExpiry() } }

    private fun Error.isUnauthorized() = (extensions?.get("code") as? String) == "UNAUTHORIZED"

    /** A structured `extensions.reason` wins when the backend sends one; the message text is the fallback. */
    private fun Error.reason(): String? = extensions?.get("reason") as? String

    private fun Error.isExpiry(): Boolean =
        reason()?.let { it == REASON_IDENTITY_TOKEN_EXPIRED } ?: (message.startsWith("Incorrect Identity Token") && message.contains("Expired"))

    /**
     * The backend reports every token problem as `UNAUTHORIZED`; the reason (or, absent one, the
     * message) tells expiry apart from rejections a fresh token cannot fix (bad signature, malformed
     * token, deleted account, wrong public key). Unknown messages refresh — failing open costs one
     * provider call, failing closed would leave an expired token in place.
     */
    private fun isRefreshable(error: Error): Boolean {
        error.reason()?.let { return it == REASON_IDENTITY_TOKEN_EXPIRED }
        val message = error.message
        if (message.contains("Public Key") || message.contains("API Key Pair")) return false
        if (message.startsWith("Incorrect Identity Token") && !message.contains("Expired")) return false
        return true
    }

    private companion object {
        const val REASON_IDENTITY_TOKEN_EXPIRED = "IDENTITY_TOKEN_EXPIRED"
    }
}
