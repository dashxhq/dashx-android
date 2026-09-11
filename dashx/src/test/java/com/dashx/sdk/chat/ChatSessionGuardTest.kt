package com.dashx.android.chat

import com.dashx.android.DashX
import com.dashx.android.DashXError
import kotlinx.coroutines.Job
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `sessionBound` — raw chat operations run on the global SDK scope, which an identity switch does
 * not cancel, so their callbacks must be gated on the session generation they began under.
 */
class ChatSessionGuardTest {

    @After
    fun clearIdentity() {
        DashX.setIdentity(null, null)
    }

    private fun <T> capture(
        onSuccess: (T) -> Unit,
        onError: (DashXError) -> Unit
    ): Pair<(T) -> Unit, (DashXError) -> Unit> {
        var ok: ((T) -> Unit)? = null
        var err: ((DashXError) -> Unit)? = null
        sessionBound(onSuccess, onError) { s, e ->
            ok = s
            err = e
            Job()
        }
        return ok!! to err!!
    }

    @Test
    fun completionAfterAnIdentitySwitch_deliversSessionEnded_notTheOldIdentitysData() {
        DashX.setIdentity("guard-user-a", "token-a")
        val delivered = mutableListOf<Any>()
        val (ok, _) = capture<String>({ delivered.add(it) }, { delivered.add(it) })

        DashX.setIdentity("guard-user-b", "token-b") // T2: new session generation

        ok("user-a-conversations")
        assertEquals(1, delivered.size)
        assertTrue(
            "a stale completion must deliver SessionEnded, never the old identity's data",
            delivered[0] is DashXError.SessionEnded
        )
    }

    @Test
    fun errorAfterAReset_deliversSessionEnded() {
        DashX.setIdentity("guard-user-a", "token-a")
        val delivered = mutableListOf<Any>()
        val (_, err) = capture<String>({ delivered.add(it) }, { delivered.add(it) })

        DashX.setIdentity(null, null) // logout bumps the generation like reset()

        err(DashXError.NetworkError("timed out"))
        assertEquals(1, delivered.size)
        assertTrue(delivered[0] is DashXError.SessionEnded)
    }

    @Test
    fun sameIdentityTokenRefresh_leavesTheOperationValid() {
        DashX.setIdentity("guard-user-a", "token-a")
        val delivered = mutableListOf<Any>()
        val (ok, _) = capture<String>({ delivered.add(it) }, { delivered.add(it) })

        DashX.setIdentity("guard-user-a", "token-a2") // T1: generation unchanged

        ok("still-user-a")
        assertEquals(listOf<Any>("still-user-a"), delivered)
    }
}
