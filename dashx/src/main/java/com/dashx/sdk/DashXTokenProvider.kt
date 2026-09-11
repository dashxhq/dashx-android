package com.dashx.android

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Supplies identity tokens on demand, registered via [DashX.setIdentityTokenProvider].
 *
 * Called off the main thread; deliver exactly once via the callback — only the first invocation is
 * accepted. `forceRefresh` is `true` when the SDK has seen the current token rejected. Register in
 * `Application.onCreate()`: the cached token survives process death, the provider cannot.
 */
fun interface DashXTokenProvider {
    fun loadToken(forceRefresh: Boolean, callback: DashXTokenCallback)

    companion object {
        private val adapterScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** Longer than the SDK's own load timeout, so a hung loader is cancelled rather than left running. */
        private const val LOADER_TIMEOUT_MS = 35_000L

        /** Adapter for a suspending loader. */
        fun suspending(loader: suspend (forceRefresh: Boolean) -> String?): DashXTokenProvider =
            DashXTokenProvider { forceRefresh, callback ->
                adapterScope.launch {
                    try {
                        val token = withTimeoutOrNull(LOADER_TIMEOUT_MS) { loader(forceRefresh) }
                        if (token != null) callback.onToken(token) else callback.onUnavailable(null)
                    } catch (t: Throwable) {
                        if (t is CancellationException) throw t
                        callback.onUnavailable(t)
                    }
                }
            }

        /** Adapter for a blocking loader; runs on [Dispatchers.IO]. */
        @JvmStatic
        fun blocking(loader: BlockingTokenLoader): DashXTokenProvider =
            suspending { forceRefresh -> loader.loadToken(forceRefresh) }
    }

    /** Java-friendly blocking loader shape for [blocking]. */
    fun interface BlockingTokenLoader {
        @Throws(Exception::class)
        fun loadToken(forceRefresh: Boolean): String?
    }
}

/** Receives the result of a [DashXTokenProvider.loadToken] call. */
interface DashXTokenCallback {
    fun onToken(token: String)

    /** No valid token is available — the SDK enters AuthenticationFailed rather than retrying. */
    fun onUnavailable(cause: Throwable?)
}
