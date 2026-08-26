package com.dashx.android.realtime

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Frames exchanged with the realtime server.
 *
 * The wire shape is `{"type": "SCREAMING_SNAKE_CASE", "data": {...}}` — serde's
 * tag/content tagging on the server side — and every `data` object is camelCase.
 *
 * Unknown types decode to [Unknown] rather than throwing: the server adds frame types
 * (assistant streams, product releases) that an older SDK must ignore instead of dropping
 * the connection.
 */
sealed class DashXRealtimeMessage {
    object Ping : DashXRealtimeMessage()

    object Pong : DashXRealtimeMessage()

    data class Connected(val connectionId: String) : DashXRealtimeMessage()

    data class SubscriptionSucceeded(val channel: String) : DashXRealtimeMessage()

    data class UnsubscriptionSucceeded(val channel: String) : DashXRealtimeMessage()

    /** Two-way chat message. Distinct from [InAppMessage], which is notification-style. */
    data class InAppChatMessage(val message: DashXRealtimeChatMessage) : DashXRealtimeMessage()

    data class InAppMessage(val message: DashXRealtimeInAppMessage) : DashXRealtimeMessage()

    /** Read-state change for one notification; `readAt == null` means marked unread. */
    data class InAppMessageRead(val id: String, val readAt: String?) : DashXRealtimeMessage()

    /** Bulk read-all — one frame instead of one per message. */
    data class InAppMessagesReadAll(val readAt: String) : DashXRealtimeMessage()

    data class Error(val code: Int, val extensionCode: String, val message: String) :
        DashXRealtimeMessage()

    /** A frame this SDK version doesn't model; carries the raw payload. */
    data class Unknown(val type: String, val data: JsonObject?) : DashXRealtimeMessage()
}

@Serializable
data class DashXRealtimeChatMessage(
    val id: String,
    val externalUid: String,
    val conversationId: String,
    val senderId: String? = null,
    /** `USER` for the visitor's own message; anything else is an agent or AI reply. */
    val aiRole: String? = null,
    val turnSeq: Long = 0,
    val renderedContent: JsonObject = JsonObject(emptyMap()),
    val createdAt: String? = null,
    val sentAt: String? = null
)

@Serializable
data class DashXRealtimeInAppMessage(
    val id: String,
    val renderedContent: JsonObject = JsonObject(emptyMap()),
    val readAt: String? = null,
    val sentAt: String? = null
)

internal object DashXRealtimeCodec {
    // Server frames carry fields this SDK version may not model yet; ignoring them keeps an
    // older client working against a newer server.
    private val json = Json { ignoreUnknownKeys = true }

    const val TYPE_PING = "PING"
    const val TYPE_PONG = "PONG"
    const val TYPE_SUBSCRIBE = "SUBSCRIBE"
    const val TYPE_UNSUBSCRIBE = "UNSUBSCRIBE"

    /** Returns null for anything that isn't a JSON object with a `type` — never throws. */
    fun decode(text: String): DashXRealtimeMessage? {
        val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
        val type = root.string("type") ?: return null
        val data = root["data"] as? JsonObject

        return when (type) {
            TYPE_PING -> DashXRealtimeMessage.Ping
            TYPE_PONG -> DashXRealtimeMessage.Pong
            "CONNECTED" -> DashXRealtimeMessage.Connected(data.string("connectionId").orEmpty())
            "SUBSCRIPTION_SUCCEEDED" ->
                DashXRealtimeMessage.SubscriptionSucceeded(data.string("channel").orEmpty())
            "UNSUBSCRIPTION_SUCCEEDED" ->
                DashXRealtimeMessage.UnsubscriptionSucceeded(data.string("channel").orEmpty())
            "IN_APP_CHAT_MESSAGE" -> data.decodeAs(DashXRealtimeChatMessage.serializer())
                ?.let(DashXRealtimeMessage::InAppChatMessage)
            "IN_APP_MESSAGE" -> data.decodeAs(DashXRealtimeInAppMessage.serializer())
                ?.let(DashXRealtimeMessage::InAppMessage)
            "IN_APP_MESSAGE_READ" -> DashXRealtimeMessage.InAppMessageRead(
                id = data.string("id").orEmpty(),
                readAt = data.string("readAt")
            )
            "IN_APP_MESSAGES_READ_ALL" ->
                DashXRealtimeMessage.InAppMessagesReadAll(data.string("readAt").orEmpty())
            "ERROR" -> DashXRealtimeMessage.Error(
                code = data.string("code")?.toIntOrNull() ?: 0,
                extensionCode = data.string("extensionCode").orEmpty(),
                message = data.string("message").orEmpty()
            )
            else -> DashXRealtimeMessage.Unknown(type, data)
        }
    }

    fun encodeChannelFrame(type: String, channelName: String): String =
        buildJsonObject {
            put("type", type)
            put("data", buildJsonObject { put("channelName", channelName) })
        }.toString()

    fun encodeBareFrame(type: String): String = buildJsonObject { put("type", type) }.toString()

    private fun <T> JsonObject?.decodeAs(deserializer: kotlinx.serialization.DeserializationStrategy<T>): T? =
        this?.let { runCatching { json.decodeFromJsonElement(deserializer, it) }.getOrNull() }

    private fun JsonObject?.string(key: String): String? {
        val element = this?.get(key) as? JsonPrimitive ?: return null
        if (element is JsonNull) return null
        return element.content
    }
}
