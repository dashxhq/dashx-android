package com.dashx.android.push

import com.dashx.android.DashXPayload
import org.junit.Assert.assertEquals
import org.junit.Test

/** Tray identity: chat pushes replace per conversation; everything else keeps pre-1.4 stacking. */
class DashXPushTest {

    @Test
    fun chatPush_replacesPerConversation_intDerivesFromTheTag() {
        val payload = DashXPayload(
            id = "msg-1",
            title = "Reply",
            tag = "in_app_chat:c1",
            screenName = "in_app_chat_conversation",
            screenData = mapOf("conversationId" to "c1")
        )
        val (tag, id) = DashXPush.notificationIdentity(payload)
        assertEquals("in_app_chat:c1", tag)
        assertEquals("a second reply must land on the same tray entry", tag.hashCode(), id)
    }

    @Test
    fun taggedNonChatPush_keepsTheStackingIdentity() {
        val first = DashXPayload(id = "msg-1", title = "Promo", tag = "campaign-7")
        val second = DashXPayload(id = "msg-2", title = "Promo", tag = "campaign-7")
        val (tag1, id1) = DashXPush.notificationIdentity(first)
        val (tag2, id2) = DashXPush.notificationIdentity(second)
        assertEquals("campaign-7", tag1)
        assertEquals("campaign-7", tag2)
        assertEquals("msg-1".hashCode(), id1)
        assertEquals("msg-2".hashCode(), id2)
    }

    @Test
    fun untaggedPush_derivesBothFromTheMessageId() {
        val (tag, id) = DashXPush.notificationIdentity(DashXPayload(id = "msg-1", title = "Hello"))
        assertEquals("msg-1", tag)
        assertEquals("msg-1".hashCode(), id)
    }
}
