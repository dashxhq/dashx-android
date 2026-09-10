package com.dashx.android

import com.apollographql.apollo.api.ApolloRequest
import com.apollographql.apollo.api.ApolloResponse
import com.apollographql.apollo.api.Error
import com.apollographql.apollo.api.Operation
import com.apollographql.apollo.interceptor.ApolloInterceptorChain
import com.dashx.android.graphql.generated.SummarizeInAppChatMessagesQuery
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class AuthRetryInterceptorTest {

    private val operation = SummarizeInAppChatMessagesQuery(conversationId = "c1")
    private val request = ApolloRequest.Builder(operation).build()

    private fun response(
        data: SummarizeInAppChatMessagesQuery.Data? = null,
        errorCodes: List<String?> = emptyList()
    ): ApolloResponse<SummarizeInAppChatMessagesQuery.Data> {
        val errors = errorCodes.map { code ->
            Error.Builder("rejected").apply { code?.let { putExtension("code", it) } }.build()
        }
        return ApolloResponse.Builder(operation, UUID.randomUUID())
            .apply { if (data != null) data(data) }
            .apply { if (errors.isNotEmpty()) errors(errors) }
            .build()
    }

    private fun unauthorized(message: String): ApolloResponse<SummarizeInAppChatMessagesQuery.Data> =
        ApolloResponse.Builder(operation, UUID.randomUUID())
            .errors(listOf(Error.Builder(message).putExtension("code", "UNAUTHORIZED").build()))
            .build()

    private fun executedData() = SummarizeInAppChatMessagesQuery.Data(
        SummarizeInAppChatMessagesQuery.SummarizeInAppChatMessages(count = 7)
    )

    /** Chain returning canned responses in order; repeats the last one if over-proceeded. */
    private class FakeChain(private val responses: List<ApolloResponse<*>>) : ApolloInterceptorChain {
        val proceeds = AtomicInteger(0)
        override fun <D : Operation.Data> proceed(request: ApolloRequest<D>): Flow<ApolloResponse<D>> {
            val index = minOf(proceeds.getAndIncrement(), responses.size - 1)
            @Suppress("UNCHECKED_CAST")
            return flowOf(responses[index] as ApolloResponse<D>)
        }
    }

    private val drops = AtomicInteger(0)

    private fun run(
        chain: FakeChain,
        refreshResult: Boolean = true,
        generation: AtomicLong = AtomicLong(1),
        onRefresh: () -> Unit = {},
        dropResult: Boolean = false
    ): Pair<ApolloResponse<SummarizeInAppChatMessagesQuery.Data>, Int> {
        val refreshes = AtomicInteger(0)
        val interceptor = AuthRetryInterceptor(
            refreshToken = { refreshes.incrementAndGet(); onRefresh(); refreshResult },
            sessionGeneration = { generation.get() },
            dropExpiredToken = { drops.incrementAndGet(); dropResult }
        )
        val result = runBlocking { interceptor.intercept(request, chain).first() }
        return result to refreshes.get()
    }

    @Test
    fun expiredToken_noRefreshPossible_dropsTokenAndRetriesUnauthenticated() {
        val chain = FakeChain(listOf(unauthorized("Incorrect Identity Token: Expired."), response(data = executedData())))

        val (result, refreshes) = run(chain, refreshResult = false, dropResult = true)

        assertEquals(2, chain.proceeds.get())
        assertEquals(1, refreshes)
        assertEquals(1, drops.get())
        assertEquals(7, result.data?.summarizeInAppChatMessages?.count)
    }

    @Test
    fun expiredToken_noRefreshPossible_nothingToDrop_emitsRejection() {
        val rejected = unauthorized("Incorrect Identity Token: Expired.")
        val chain = FakeChain(listOf(rejected))

        val (result, _) = run(chain, refreshResult = false, dropResult = false)

        assertEquals(1, chain.proceeds.get())
        assertSame(rejected, result)
    }

    @Test
    fun genericUnauthorized_noRefreshPossible_doesNotDropToken() {
        val rejected = response(errorCodes = listOf("UNAUTHORIZED"))
        val chain = FakeChain(listOf(rejected))

        val (result, _) = run(chain, refreshResult = false, dropResult = true)

        assertEquals(1, chain.proceeds.get())
        assertEquals(0, drops.get())
        assertSame(rejected, result)
    }

    @Test
    fun structuredReason_expiredIsRefreshable_otherReasonIsNot() {
        val expired = ApolloResponse.Builder(operation, UUID.randomUUID())
            .errors(listOf(Error.Builder("rejected").putExtension("code", "UNAUTHORIZED").putExtension("reason", "IDENTITY_TOKEN_EXPIRED").build()))
            .build()
        val (_, refreshesExpired) = run(FakeChain(listOf(expired, response(data = executedData()))))
        assertEquals(1, refreshesExpired)

        val revoked = ApolloResponse.Builder(operation, UUID.randomUUID())
            .errors(listOf(Error.Builder("Incorrect Identity Token: Expired.").putExtension("code", "UNAUTHORIZED").putExtension("reason", "IDENTITY_TOKEN_REVOKED").build()))
            .build()
        val (result, refreshesRevoked) = run(FakeChain(listOf(revoked)))
        assertEquals(0, refreshesRevoked)
        assertSame(revoked, result)
    }

    @Test
    fun preExecutionUnauthorized_refreshesAndRetriesOnce() {
        val rejected = response(errorCodes = listOf("UNAUTHORIZED"))
        val succeeded = response(data = executedData())
        val chain = FakeChain(listOf(rejected, succeeded))

        val (result, refreshes) = run(chain)

        assertEquals(2, chain.proceeds.get())
        assertEquals(1, refreshes)
        assertEquals(7, result.data?.summarizeInAppChatMessages?.count)
    }

    @Test
    fun failedRefresh_emitsTheOriginalRejection_noRetry() {
        val rejected = response(errorCodes = listOf("UNAUTHORIZED"))
        val chain = FakeChain(listOf(rejected))

        val (result, refreshes) = run(chain, refreshResult = false)

        assertEquals(1, chain.proceeds.get())
        assertEquals(1, refreshes)
        assertSame(rejected, result)
    }

    @Test
    fun expiredToken_refreshesAndRetries() {
        val chain = FakeChain(listOf(unauthorized("Incorrect Identity Token: Expired."), response(data = executedData())))
        val (result, refreshes) = run(chain)
        assertEquals(2, chain.proceeds.get())
        assertEquals(1, refreshes)
        assertEquals(7, result.data?.summarizeInAppChatMessages?.count)
    }

    @Test
    fun rejectionsANewTokenCannotFix_neverRefresh() {
        // Every token problem is UNAUTHORIZED; only the message separates expiry from the rest.
        for (message in listOf(
            "Incorrect Identity Token: Invalid signature.",
            "Incorrect Identity Token: Improper format.",
            "Incorrect Identity Token: Missing account.",
            "Incorrect Identity Token: Missing identity 'uid'.",
            "Incorrect Public Key.",
            "Your API Key Pair has expired."
        )) {
            val rejected = unauthorized(message)
            val chain = FakeChain(listOf(rejected, response(data = executedData())))
            val (result, refreshes) = run(chain)
            assertEquals(message, 1, chain.proceeds.get())
            assertEquals(message, 0, refreshes)
            assertSame(message, rejected, result)
        }
    }

    @Test
    fun forbidden_neverRefreshes() {
        val chain = FakeChain(listOf(response(errorCodes = listOf("FORBIDDEN"))))
        val (_, refreshes) = run(chain)
        assertEquals(1, chain.proceeds.get())
        assertEquals(0, refreshes)
    }

    @Test
    fun mixedCodes_neverRefresh() {
        val chain = FakeChain(listOf(response(errorCodes = listOf("UNAUTHORIZED", "FORBIDDEN"))))
        val (_, refreshes) = run(chain)
        assertEquals(1, chain.proceeds.get())
        assertEquals(0, refreshes)
    }

    @Test
    fun emptyErrors_neverRefresh() {
        // `all {}` is vacuously true on an empty list — the guard must not treat that as auth.
        val chain = FakeChain(listOf(response()))
        val (_, refreshes) = run(chain)
        assertEquals(1, chain.proceeds.get())
        assertEquals(0, refreshes)
    }

    @Test
    fun executedData_withAuthError_neverRetries() {
        // data != null means something executed; retrying a mutation here is a double-send.
        val chain = FakeChain(listOf(response(data = executedData(), errorCodes = listOf("UNAUTHORIZED"))))
        val (_, refreshes) = run(chain)
        assertEquals(1, chain.proceeds.get())
        assertEquals(0, refreshes)
    }

    @Test
    fun identitySwitchDuringRefresh_emitsOriginal_neverRetriesWithNewIdentity() {
        val rejected = response(errorCodes = listOf("UNAUTHORIZED"))
        val chain = FakeChain(listOf(rejected, response(data = executedData())))
        val generation = AtomicLong(1)

        // The refresh succeeds — but the session generation moved while it ran (identity switch).
        val (result, refreshes) = run(chain, generation = generation, onRefresh = { generation.set(2) })

        assertEquals("an A-era request must not resend under B's token", 1, chain.proceeds.get())
        assertEquals(1, refreshes)
        assertSame(rejected, result)
    }
}
