package com.dashx.android

import org.junit.Assert.*
import org.junit.Test

class DashXErrorTest {

    @Test
    fun notConfigured_defaultMessage() {
        val error = DashXError.NotConfigured()
        assertEquals("DashX.configure() must be called first", error.message)
    }

    @Test
    fun notConfigured_customMessage() {
        val error = DashXError.NotConfigured("custom msg")
        assertEquals("custom msg", error.message)
    }

    @Test
    fun notIdentified_defaultMessage() {
        val error = DashXError.NotIdentified()
        assertEquals("accountUid is not set. Call setIdentity() first.", error.message)
    }

    @Test
    fun graphQLError_message() {
        val error = DashXError.GraphQLError("query failed")
        assertEquals("query failed", error.message)
    }

    @Test
    fun networkError_message() {
        val error = DashXError.NetworkError("timeout")
        assertEquals("timeout", error.message)
    }

    @Test
    fun assetError_message() {
        val error = DashXError.AssetError("upload failed")
        assertEquals("upload failed", error.message)
    }

    @Test
    fun toString_includesClassName() {
        val error = DashXError.GraphQLError("something went wrong")
        assertEquals("GraphQLError: something went wrong", error.toString())
    }

    @Test
    fun toString_notConfigured() {
        val error = DashXError.NotConfigured()
        assertTrue(error.toString().startsWith("NotConfigured:"))
    }

    @Test
    fun sealedClass_exhaustiveWhen() {
        val errors: List<DashXError> = listOf(
            DashXError.NotConfigured(),
            DashXError.NotIdentified(),
            DashXError.GraphQLError("err"),
            DashXError.NetworkError("err"),
            DashXError.AssetError("err"),
            DashXError.SessionEnded(),
            DashXError.SubscriptionFailed()
        )

        errors.forEach { error ->
            when (error) {
                is DashXError.NotConfigured -> assertNotNull(error.message)
                is DashXError.NotIdentified -> assertNotNull(error.message)
                is DashXError.GraphQLError -> assertNotNull(error.message)
                is DashXError.NetworkError -> assertNotNull(error.message)
                is DashXError.AssetError -> assertNotNull(error.message)
                is DashXError.SessionEnded -> assertNotNull(error.message)
                is DashXError.SubscriptionFailed -> assertNotNull(error.message)
            }
        }
    }

    @Test
    fun dashXException_wrapsError() {
        val error = DashXError.NetworkError("connection lost")
        val exception = DashXException(error)

        assertSame(error, exception.error)
        assertEquals("connection lost", exception.message)
    }

    @Test
    fun isRetryable_networkError() {
        assertTrue(DashXError.NetworkError("timeout").isRetryable)
    }

    @Test
    fun isRetryable_nonRetryableErrors() {
        assertFalse(DashXError.NotConfigured().isRetryable)
        assertFalse(DashXError.NotIdentified().isRetryable)
        assertFalse(DashXError.GraphQLError("bad query").isRetryable)
        assertFalse(DashXError.AssetError("upload failed").isRetryable)
    }

    @Test
    fun dashXException_usableInTryCatch() {
        val error = DashXError.GraphQLError("bad query")
        try {
            throw DashXException(error)
        } catch (e: DashXException) {
            assertTrue(e.error is DashXError.GraphQLError)
            assertEquals("bad query", e.error.message)
        }
    }
}
