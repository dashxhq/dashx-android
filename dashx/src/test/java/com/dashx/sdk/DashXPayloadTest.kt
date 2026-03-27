package com.dashx.android

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class DashXPayloadTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun deserialize_fullPayload() {
        val jsonStr = """
            {
                "id": "msg_123",
                "title": "Hello",
                "body": "World",
                "image": "https://example.com/img.png",
                "url": "https://example.com",
                "small_icon": "ic_notification",
                "large_icon": "https://example.com/icon.png",
                "channel_id": "my_channel",
                "sound": "notification_sound",
                "visibility": "1",
                "notification_count": "5",
                "light_settings": "{\"color\":\"#FF0000\",\"light_on_duration\":300,\"light_off_duration\":1000}",
                "color": "#00FF00",
                "tag": "promo",
                "click_action": "com.example.OPEN_DETAIL"
            }
        """.trimIndent()

        val payload = json.decodeFromString<DashXPayload>(jsonStr)

        assertEquals("msg_123", payload.id)
        assertEquals("Hello", payload.title)
        assertEquals("World", payload.body)
        assertEquals("https://example.com/img.png", payload.image)
        assertEquals("https://example.com", payload.url)
        assertEquals("ic_notification", payload.smallIcon)
        assertEquals("https://example.com/icon.png", payload.largeIcon)
        assertEquals("my_channel", payload.channelId)
        assertEquals("notification_sound", payload.sound)
        assertEquals("1", payload.visibility)
        assertEquals("5", payload.notificationCount)
        assertNotNull(payload.lightSettings)
        assertEquals("#00FF00", payload.color)
        assertEquals("promo", payload.tag)
        assertEquals("com.example.OPEN_DETAIL", payload.clickAction)
    }

    @Test
    fun deserialize_minimalPayload() {
        val jsonStr = """{"id": "msg_456"}"""
        val payload = json.decodeFromString<DashXPayload>(jsonStr)

        assertEquals("msg_456", payload.id)
        assertNull(payload.title)
        assertNull(payload.body)
        assertNull(payload.image)
        assertNull(payload.url)
        assertNull(payload.smallIcon)
        assertNull(payload.largeIcon)
        assertNull(payload.channelId)
        assertNull(payload.sound)
        assertNull(payload.visibility)
        assertNull(payload.notificationCount)
        assertNull(payload.lightSettings)
        assertNull(payload.color)
        assertNull(payload.tag)
        assertNull(payload.clickAction)
    }

    @Test
    fun deserialize_ignoresUnknownFields() {
        val jsonStr = """{"id": "msg_789", "unknown_field": "value", "another": 42}"""
        val payload = json.decodeFromString<DashXPayload>(jsonStr)
        assertEquals("msg_789", payload.id)
    }

    @Test
    fun roundTrip_serialization() {
        val original = DashXPayload(
            id = "msg_100",
            title = "Test",
            body = "Body text",
            image = null,
            url = "https://example.com",
            smallIcon = null,
            largeIcon = null,
            channelId = "ch_1",
            sound = null,
            visibility = null,
            notificationCount = null,
            lightSettings = null,
            color = null,
            tag = null,
            clickAction = null
        )

        val serialized = json.encodeToString(original)
        val deserialized = json.decodeFromString<DashXPayload>(serialized)

        assertEquals(original, deserialized)
    }

    @Test
    fun lightSettings_deserialization() {
        val jsonStr = """
            {
                "color": "#FF0000",
                "light_on_duration": 300,
                "light_off_duration": 1000
            }
        """.trimIndent()

        val settings = json.decodeFromString<LightSettings>(jsonStr)

        assertEquals("#FF0000", settings.color)
        assertEquals(300, settings.on)
        assertEquals(1000, settings.off)
    }

    @Test
    fun lightSettings_roundTrip() {
        val original = LightSettings(color = "#0000FF", on = 500, off = 2000)
        val serialized = json.encodeToString(original)
        val deserialized = json.decodeFromString<LightSettings>(serialized)
        assertEquals(original, deserialized)
    }
}
