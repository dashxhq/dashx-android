package com.dashx.android

import com.apollographql.apollo.api.ApolloRequest
import com.apollographql.apollo.api.ApolloResponse
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
 *
 * The retry is generation-guarded: if the identity switched while the refresh ran, the new token
 * belongs to a different account and the old-era request must not be resent under it.
 */
internal class AuthRetryInterceptor(
    private val refreshToken: suspend () -> Boolean = { DashX.awaitTokenRefresh() },
    private val sessionGeneration: () -> Long = { DashX.currentSessionGeneration() }
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
            emit(first)
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
        return errors.all { (it.extensions?.get("code") as? String) == "UNAUTHORIZED" }
    }
}
