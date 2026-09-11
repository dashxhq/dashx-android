package com.dashx.android.realtime

/**
 * The realtime connection's state, observable via [com.dashx.android.DashX.connectionState] or
 * [com.dashx.android.DashX.addConnectionStateListener].
 */
sealed interface ConnectionState {
    /** Configured; nothing subscribed. No socket by design. */
    data object Idle : ConnectionState

    /** Connect attempt in flight, retry backoff, or an identity-token load in flight. */
    data object Connecting : ConnectionState

    /** Socket open. Individual channels may still be awaiting acknowledgement. */
    data object Connected : ConnectionState

    /** Process backgrounded with subscriptions still registered; resumes on foreground. */
    data object Suspended : ConnectionState

    /**
     * Terminal authentication failure: the server closed with a 44xx code and no provider can
     * refresh, the provider's retry failed, or it timed out. A later
     * [com.dashx.android.DashX.setIdentity] or
     * [com.dashx.android.DashX.setIdentityTokenProvider] recovers the session.
     */
    data class AuthenticationFailed(val cause: Throwable?) : ConnectionState
}

/** Java-friendly observer for [ConnectionState] changes. */
fun interface ConnectionStateListener {
    fun onConnectionStateChanged(state: ConnectionState)
}
