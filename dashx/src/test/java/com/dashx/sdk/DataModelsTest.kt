package com.dashx.android

import com.dashx.android.data.PlaybackData
import com.dashx.android.data.Preference
import com.dashx.android.data.PrepareAssetResponse
import com.dashx.android.data.UploadAssetUrl
import com.dashx.android.data.UploadData
import com.dashx.android.utils.generateMuxVideoUrl
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class DataModelsTest {

    private val json = Json { ignoreUnknownKeys = true }

    // --- Preference ---

    @Test
    fun preference_defaultValues() {
        val pref = Preference()
        assertFalse(pref.enabled)
        assertFalse(pref.email)
        assertFalse(pref.push)
        assertFalse(pref.sms)
        assertFalse(pref.whatsapp)
    }

    @Test
    fun preference_serialization() {
        val pref = Preference(enabled = true, email = true, push = false, sms = true, whatsapp = false)
        val serialized = json.encodeToString(pref)
        val deserialized = json.decodeFromString<Preference>(serialized)
        assertEquals(pref, deserialized)
    }

    @Test
    fun preference_deserializeFromJson() {
        val jsonStr = """{"enabled":true,"email":false,"push":true,"sms":false,"whatsapp":true}"""
        val pref = json.decodeFromString<Preference>(jsonStr)
        assertTrue(pref.enabled)
        assertFalse(pref.email)
        assertTrue(pref.push)
        assertFalse(pref.sms)
        assertTrue(pref.whatsapp)
    }

    // --- PrepareAssetResponse ---

    @Test
    fun prepareAssetResponse_deserialization() {
        val jsonStr = """{"upload":{"url":"https://storage.example.com/upload/abc123"}}"""
        val response = json.decodeFromString<PrepareAssetResponse>(jsonStr)
        assertEquals("https://storage.example.com/upload/abc123", response.upload.url)
    }

    @Test
    fun prepareAssetResponse_roundTrip() {
        val original = PrepareAssetResponse(upload = UploadAssetUrl(url = "https://example.com/upload"))
        val serialized = json.encodeToString(original)
        val deserialized = json.decodeFromString<PrepareAssetResponse>(serialized)
        assertEquals(original, deserialized)
    }

    // --- UploadData / PlaybackData ---

    @Test
    fun uploadData_withPlaybackIds() {
        val data = UploadData(
            status = "ready",
            url = "https://example.com/video.mp4",
            playbackIds = listOf(PlaybackData(id = "pb_1", policy = "public"))
        )
        assertEquals("ready", data.status)
        assertEquals(1, data.playbackIds.size)
        assertEquals("pb_1", data.playbackIds[0].id)
        assertEquals("public", data.playbackIds[0].policy)
    }

    @Test
    fun uploadData_mutableUrl() {
        val data = UploadData(status = "ready", url = "", playbackIds = listOf(PlaybackData(id = "pb_1", policy = "public")))
        data.url = "https://stream.mux.com/pb_1.m3u8"
        assertEquals("https://stream.mux.com/pb_1.m3u8", data.url)
    }

    @Test
    fun playbackData_optionalId() {
        val data = PlaybackData(policy = "signed")
        assertNull(data.id)
        assertEquals("signed", data.policy)
    }

    // --- generateMuxVideoUrl ---

    @Test
    fun generateMuxVideoUrl_formatsCorrectly() {
        val url = generateMuxVideoUrl("abc123")
        assertEquals("https://stream.mux.com/abc123.m3u8", url)
    }

    @Test
    fun generateMuxVideoUrl_nullPlaybackId() {
        val url = generateMuxVideoUrl(null)
        assertEquals("https://stream.mux.com/null.m3u8", url)
    }
}
