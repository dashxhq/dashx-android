package com.dashx.android

sealed class DashXError(val message: String) {
    class NotConfigured(
        message: String = "DashX.configure() must be called first"
    ) : DashXError(message)

    class NotIdentified(
        message: String = "accountUid is not set. Call setIdentity() first."
    ) : DashXError(message)

    /**
     * The server answered with GraphQL errors. [code] is the backend's `extensions.code` when
     * every error in the response carried the same one (`UNAUTHORIZED`, `FORBIDDEN`, `NOT_FOUND`,
     * `UNPROCESSABLE_ENTITY`, ...); null when the codes were mixed or absent.
     */
    class GraphQLError(
        message: String,
        val code: String? = null
    ) : DashXError(message) {
        companion object {
            const val UNAUTHORIZED = "UNAUTHORIZED"
            const val FORBIDDEN = "FORBIDDEN"
            const val NOT_FOUND = "NOT_FOUND"
            const val UNPROCESSABLE_ENTITY = "UNPROCESSABLE_ENTITY"
            const val INTERNAL_SERVER_ERROR = "INTERNAL_SERVER_ERROR"
        }
    }

    class NetworkError(
        message: String
    ) : DashXError(message)

    class AssetError(
        message: String
    ) : DashXError(message)

    /** The identity session ended (identity switch, reset, or shutdown) while this operation ran. */
    class SessionEnded(
        message: String = "The identity session ended before the operation completed"
    ) : DashXError(message)

    /** A realtime channel subscription was never acknowledged — invalid or unauthorized. */
    class SubscriptionFailed(
        message: String = "The realtime subscription was not acknowledged"
    ) : DashXError(message)

    /** Whether this error is transient and the operation can be retried. */
    val isRetryable: Boolean
        get() = when (this) {
            is NetworkError -> true
            is GraphQLError -> false
            is NotConfigured -> false
            is NotIdentified -> false
            is AssetError -> false
            is SessionEnded -> false
            is SubscriptionFailed -> true
        }

    override fun toString(): String = "${this::class.simpleName}: $message"
}

/** Thrown by suspend wrapper functions (e.g. [DashX.identifyAsync]) when the operation fails. */
class DashXException(val error: DashXError) : Exception(error.message)
