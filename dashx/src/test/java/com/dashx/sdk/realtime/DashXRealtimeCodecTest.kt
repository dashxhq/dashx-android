package com.dashx.android.realtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DashXRealtimeCodecTest {

    @Test
    fun decodesPingAndSubscriptionSucceeded() {
        assertTrue(DashXRealtimeCodec.decode("""{"type":"PING"}""") is DashXRealtimeMessage.Ping)

        val ack = DashXRealtimeCodec.decode(
            """{"type":"SUBSCRIPTION_SUCCEEDED","data":{"channel":"in_app_chat:conversation:c1"}}"""
        )
        assertTrue(ack is DashXRealtimeMessage.SubscriptionSucceeded)
        assertEquals("in_app_chat:conversation:c1", (ack as DashXRealtimeMessage.SubscriptionSucceeded).channel)
    }

    @Test
    fun decodesChatMessageFrame() {
        val frame = DashXRealtimeCodec.decode(
            """{"type":"IN_APP_CHAT_MESSAGE","data":{
                "id":"m1","externalUid":"ext-1","conversationId":"c1","senderId":null,
                "aiRole":"USER","turnSeq":7,"renderedContent":{"text":"hi"},
                "createdAt":"2026-08-25 10:00:00","sentAt":"2026-08-25 10:00:00"
            }}"""
        )
        assertTrue(frame is DashXRealtimeMessage.InAppChatMessage)
        val message = (frame as DashXRealtimeMessage.InAppChatMessage).message
        assertEquals("m1", message.id)
        assertEquals("c1", message.conversationId)
        assertEquals(7L, message.turnSeq)
        assertNull(message.senderId)
    }

    @Test
    fun unknownTypeDecodesToUnknown_notThrow() {
        val frame = DashXRealtimeCodec.decode("""{"type":"SOME_FUTURE_FRAME","data":{"x":1}}""")
        assertTrue(frame is DashXRealtimeMessage.Unknown)
        assertEquals("SOME_FUTURE_FRAME", (frame as DashXRealtimeMessage.Unknown).type)
    }

    @Test
    fun malformedInputDecodesToNull_notThrow() {
        assertNull(DashXRealtimeCodec.decode("not json"))
        assertNull(DashXRealtimeCodec.decode("""{"noType":true}"""))
        assertNull(DashXRealtimeCodec.decode(""))
    }

    @Test
    fun encodesChannelFrames() {
        assertEquals(
            """{"type":"SUBSCRIBE","data":{"channelName":"in_app_chat:conversation:c1"}}""",
            DashXRealtimeCodec.encodeChannelFrame(
                DashXRealtimeCodec.TYPE_SUBSCRIBE, "in_app_chat:conversation:c1"
            )
        )
        assertEquals("""{"type":"PONG"}""", DashXRealtimeCodec.encodeBareFrame(DashXRealtimeCodec.TYPE_PONG))
    }
}
