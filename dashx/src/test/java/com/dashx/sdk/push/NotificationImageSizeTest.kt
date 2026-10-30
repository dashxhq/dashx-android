package com.dashx.android.push

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream

class NotificationImageSizeTest {

    @Test
    fun sampleSize_isOne_whenTheImageAlreadyFits() {
        assertEquals(1, NotificationRenderer.sampleSizeFor(1000, 500, 1024))
        assertEquals(1, NotificationRenderer.sampleSizeFor(2047, 100, 1024))
    }

    @Test
    fun sampleSize_bringsAnOversizedImageDownToTheLimit() {
        // 8192x8192 decodes to 256 MiB at full size; sampled by 8 it is 1024x1024, 4 MiB.
        assertEquals(8, NotificationRenderer.sampleSizeFor(8192, 8192, 1024))
        assertEquals(2, NotificationRenderer.sampleSizeFor(2048, 1024, 1024))
    }

    @Test
    fun sampleSize_followsTheLongerSide() {
        assertEquals(32, NotificationRenderer.sampleSizeFor(100, 8192, 256))
    }

    @Test
    fun readAtMost_returnsTheWholeStream_withinTheLimit() {
        val bytes = ByteArray(20_000) { it.toByte() }
        assertArrayEquals(bytes, NotificationRenderer.readAtMost(ByteArrayInputStream(bytes), 20_000))
    }

    @Test
    fun readAtMost_isNull_onceTheStreamExceedsTheLimit() {
        assertNull(NotificationRenderer.readAtMost(ByteArrayInputStream(ByteArray(20_001)), 20_000))
    }
}
