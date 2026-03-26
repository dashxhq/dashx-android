package com.dashx.android

sealed class DashXError(val message: String) {
    class NotConfigured(
        message: String = "DashX.configure() must be called first"
    ) : DashXError(message)

    class NotIdentified(
        message: String = "accountUid is not set. Call setIdentity() first."
    ) : DashXError(message)

    class GraphQLError(
        message: String
    ) : DashXError(message)

    class NetworkError(
        message: String
    ) : DashXError(message)

    class AssetError(
        message: String
    ) : DashXError(message)

    /** Whether this error is transient and the operation can be retried. */
    val isRetryable: Boolean
        get() = when (this) {
            is NetworkError -> true
            is GraphQLError -> false
            is NotConfigured -> false
            is NotIdentified -> false
            is AssetError -> false
        }

    override fun toString(): String = "${this::class.simpleName}: $message"
}

/** Thrown by suspend wrapper functions (e.g. [DashX.identifyAsync]) when the operation fails. */
class DashXException(val error: DashXError) : Exception(error.message)
