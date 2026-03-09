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

    override fun toString(): String = "${this::class.simpleName}: $message"
}
