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
                "click_action": "com.example.OPEN_DETAIL",
                "screen_name": "ProductDetail",
                "screen_data": {"product_id": "abc"},
                "action_buttons": [
                    {"identifier": "buy", "label": "Buy", "url": "https://example.com/buy", "clickAction": "com.example.BUY"}
                ]
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
        assertEquals("ProductDetail", payload.screenName)
        assertEquals(mapOf("product_id" to "abc"), payload.screenData)
        assertEquals(1, payload.actionButtons?.size)
        assertEquals("buy", payload.actionButtons?.first()?.identifier)
        assertEquals("Buy", payload.actionButtons?.first()?.label)
        assertEquals("https://example.com/buy", payload.actionButtons?.first()?.url)
        assertEquals("com.example.BUY", payload.actionButtons?.first()?.clickAction)
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
        assertNull(payload.screenName)
        assertNull(payload.screenData)
        assertNull(payload.actionButtons)
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
    fun roundTrip_serialization_withScreenData() {
        val original = DashXPayload(
            id = "msg_rt",
            screenName = "Detail",
            screenData = mapOf("id" to "123", "type" to "order"),
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

    @Test
    fun resolveNavigationAction_prefersScreenOverUrl() {
        val payload = DashXPayload(
            id = "p1",
            url = "https://example.com",
            screenName = "Detail",
            screenData = mapOf("k" to "v"),
        )
        val action = payload.resolveNavigationAction()
        assertTrue(action is NavigationAction.Screen)
        val screen = action as NavigationAction.Screen
        assertEquals("Detail", screen.name)
        assertEquals(mapOf("k" to "v"), screen.data)
    }

    @Test
    fun resolveNavigationAction_deepLinkWhenNoScreen() {
        val payload = DashXPayload(
            id = "p2",
            url = "https://open.example",
        )
        val action = payload.resolveNavigationAction()
        assertTrue(action is NavigationAction.DeepLink)
        assertEquals("https://open.example", (action as NavigationAction.DeepLink).url)
    }

    @Test
    fun resolveNavigationAction_richLandingWhenFlagTrue() {
        val payload = DashXPayload(
            id = "p2rl",
            url = "https://landing.example",
            richLanding = true,
        )
        val action = payload.resolveNavigationAction()
        assertTrue(action is NavigationAction.RichLanding)
        assertEquals("https://landing.example", (action as NavigationAction.RichLanding).url)
    }

    @Test
    fun resolveNavigationAction_actionButton_richLanding() {
        val payload = DashXPayload(
            id = "p4rl",
            url = "https://main.example",
            actionButtons = listOf(
                ActionButton(
                    identifier = "open",
                    label = "Open",
                    url = "https://promo.example",
                    richLanding = true,
                ),
            ),
        )
        val action = payload.resolveNavigationAction("open")
        assertTrue(action is NavigationAction.RichLanding)
        assertEquals("https://promo.example", (action as NavigationAction.RichLanding).url)
    }

    @Test
    fun resolveNavigationAction_actionButton_prefersScreenOverUrl() {
        val payload = DashXPayload(
            id = "p3",
            url = "https://main.example",
            actionButtons = listOf(
                ActionButton(
                    identifier = "go",
                    label = "Go",
                    url = "https://btn.example",
                    screenName = "Cart",
                    screenData = mapOf("item" to "1"),
                ),
            ),
        )
        val action = payload.resolveNavigationAction("go")
        assertTrue(action is NavigationAction.Screen)
        val screen = action as NavigationAction.Screen
        assertEquals("Cart", screen.name)
        assertEquals(mapOf("item" to "1"), screen.data)
    }

    @Test
    fun resolveNavigationAction_actionButton_deepLink() {
        val payload = DashXPayload(
            id = "p4",
            url = "https://main.example",
            actionButtons = listOf(
                ActionButton(
                    identifier = "buy",
                    label = "Buy",
                    url = "https://buy.example",
                ),
            ),
        )
        val action = payload.resolveNavigationAction("buy")
        assertTrue(action is NavigationAction.DeepLink)
        assertEquals("https://buy.example", (action as NavigationAction.DeepLink).url)
    }

    @Test
    fun resolveNavigationAction_clickActionFallback() {
        val payload = DashXPayload(
            id = "pca",
            clickAction = "com.example.OPEN_DETAIL",
        )
        val action = payload.resolveNavigationAction()
        assertTrue(action is NavigationAction.ClickAction)
        assertEquals("com.example.OPEN_DETAIL", (action as NavigationAction.ClickAction).action)
    }

    @Test
    fun resolveNavigationAction_actionButton_clickActionFallback() {
        val payload = DashXPayload(
            id = "pcab",
            actionButtons = listOf(
                ActionButton(
                    identifier = "act",
                    label = "Act",
                    clickAction = "com.example.ACT",
                ),
            ),
        )
        val action = payload.resolveNavigationAction("act")
        assertTrue(action is NavigationAction.ClickAction)
        assertEquals("com.example.ACT", (action as NavigationAction.ClickAction).action)
    }

    @Test
    fun resolveNavigationAction_prefersScreenOverClickAction() {
        val payload = DashXPayload(
            id = "psca",
            screenName = "Home",
            clickAction = "com.example.OPEN",
        )
        val action = payload.resolveNavigationAction()
        assertTrue(action is NavigationAction.Screen)
        assertEquals("Home", (action as NavigationAction.Screen).name)
    }

    @Test
    fun resolveNavigationAction_actionButton_unknownIdFallsBackToMain() {
        val payload = DashXPayload(
            id = "p5",
            url = "https://main.example",
            actionButtons = listOf(
                ActionButton(identifier = "a", label = "A", url = "https://a"),
            ),
        )
        val action = payload.resolveNavigationAction("missing")
        assertTrue(action is NavigationAction.DeepLink)
        assertEquals("https://main.example", (action as NavigationAction.DeepLink).url)
    }

    @Test
    fun deserialize_screenData_asStringifiedJson() {
        val jsonStr = """
            {
                "id": "msg_stringified",
                "title": "Test",
                "body": "Body",
                "screen_name": "consultation",
                "screen_data": "{\"id\": \"abc-123\", \"type\": \"online\"}"
            }
        """.trimIndent()

        val payload = json.decodeFromString<DashXPayload>(jsonStr)

        assertEquals("msg_stringified", payload.id)
        assertEquals("consultation", payload.screenName)
        assertEquals(mapOf("id" to "abc-123", "type" to "online"), payload.screenData)
    }

    @Test
    fun deserialize_screenData_asJsonObject() {
        val jsonStr = """
            {
                "id": "msg_object",
                "screen_name": "chatroom",
                "screen_data": {"id": "xyz-789"}
            }
        """.trimIndent()

        val payload = json.decodeFromString<DashXPayload>(jsonStr)

        assertEquals("chatroom", payload.screenName)
        assertEquals(mapOf("id" to "xyz-789"), payload.screenData)
    }

    @Test
    fun deserialize_screenData_null() {
        val jsonStr = """{"id": "msg_null"}"""
        val payload = json.decodeFromString<DashXPayload>(jsonStr)
        assertNull(payload.screenData)
    }

    @Test
    fun deserialize_actionButton_screenData_asStringifiedJson() {
        val jsonStr = """
            {
                "id": "msg_btn_str",
                "action_buttons": [
                    {
                        "identifier": "view",
                        "label": "View",
                        "screenName": "details",
                        "screenData": "{\"orderId\": \"ord-456\"}"
                    }
                ]
            }
        """.trimIndent()

        val payload = json.decodeFromString<DashXPayload>(jsonStr)

        val button = payload.actionButtons?.first()
        assertNotNull(button)
        assertEquals("details", button?.screenName)
        assertEquals(mapOf("orderId" to "ord-456"), button?.screenData)
    }

    @Test
    fun deserialize_richLanding_asStringTrue() {
        val jsonStr = """{"id": "msg_rl", "url": "https://example.com", "rich_landing": "true"}"""
        val payload = json.decodeFromString<DashXPayload>(jsonStr)
        assertEquals(true, payload.richLanding)
    }

    @Test
    fun deserialize_richLanding_asStringFalse() {
        val jsonStr = """{"id": "msg_rl2", "rich_landing": "false"}"""
        val payload = json.decodeFromString<DashXPayload>(jsonStr)
        assertEquals(false, payload.richLanding)
    }

    @Test
    fun deserialize_richLanding_asNativeBool() {
        val jsonStr = """{"id": "msg_rl3", "rich_landing": true}"""
        val payload = json.decodeFromString<DashXPayload>(jsonStr)
        assertEquals(true, payload.richLanding)
    }

    @Test
    fun deserialize_actionButton_richLanding_asString() {
        val jsonStr = """
            {
                "id": "msg_btn_rl",
                "action_buttons": [
                    {"identifier": "open", "label": "Open", "url": "https://example.com", "richLanding": "true"}
                ]
            }
        """.trimIndent()
        val payload = json.decodeFromString<DashXPayload>(jsonStr)
        assertEquals(true, payload.actionButtons?.first()?.richLanding)
    }

    @Test
    fun deserialize_screenData_malformedString() {
        val jsonStr = """{"id": "msg_bad", "screen_data": "not-json"}"""
        val payload = json.decodeFromString<DashXPayload>(jsonStr)
        assertNull(payload.screenData)
    }

    @Test
    fun deserialize_screenData_nonStringPrimitives() {
        val jsonStr = """
            {"id": "msg_prim", "screen_data": {"count": 42, "active": true, "name": "test"}}
        """.trimIndent()

        val payload = json.decodeFromString<DashXPayload>(jsonStr)
        assertEquals(mapOf("count" to "42", "active" to "true", "name" to "test"), payload.screenData)
    }

    @Test
    fun deserialize_screenData_nestedObjects() {
        val jsonStr = """
            {"id": "msg_nested", "screen_data": {"id": "1", "filters": {"status": "active"}}}
        """.trimIndent()

        val payload = json.decodeFromString<DashXPayload>(jsonStr)
        assertEquals("1", payload.screenData?.get("id"))
        assertEquals("{\"status\":\"active\"}", payload.screenData?.get("filters"))
    }
}
