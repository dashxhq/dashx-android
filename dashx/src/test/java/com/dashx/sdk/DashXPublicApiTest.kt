package com.dashx.android

import org.junit.Assert.assertTrue
import org.junit.Test

class DashXPublicApiTest {

    /** Guards item 9 of the release contract: existing JVM callers of configure() must not break. */
    @Test
    fun configure_keepsThePre14JvmSignature() {
        val configures = DashX.Companion::class.java.methods.filter { it.name == "configure" }

        assertTrue(
            "the exact pre-1.4 5-parameter configure must survive",
            configures.any {
                it.parameterTypes.map { p -> p.simpleName } ==
                    listOf("Context", "String", "String", "String", "CoroutineDispatcher")
            }
        )
        assertTrue(
            "no widened configure overload; realtime URI goes through setRealtimeBaseUri",
            configures.none { it.parameterCount > 5 }
        )
        assertTrue(
            DashX.Companion::class.java.methods.any {
                it.name == "setRealtimeBaseUri" && it.parameterTypes.map { p -> p.simpleName } == listOf("String")
            }
        )
    }
}
