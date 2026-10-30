package com.dashx.android

import com.dashx.android.utils.getBooleanOrDefault
import com.dashx.android.utils.getLongOrDefault
import com.dashx.android.utils.getStringOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreferenceReadTest {

    @Test
    fun getStringOrNull_readsAStoredValue() {
        val prefs = InMemorySharedPreferences(mutableMapOf<String, Any?>("token" to "abc"))
        assertEquals("abc", prefs.getStringOrNull("token"))
    }

    @Test
    fun getStringOrNull_isNullForAMissingKey() {
        assertNull(InMemorySharedPreferences(mutableMapOf()).getStringOrNull("token"))
    }

    @Test
    fun getStringOrNull_isNullInsteadOfThrowing_whenTheValueCannotBeDecrypted() {
        val prefs = InMemorySharedPreferences(mutableMapOf<String, Any?>("token" to "sealed"), undecryptable = setOf("token"))
        assertNull(prefs.getStringOrNull("token"))
    }

    @Test
    fun getStringOrNull_removesTheUndecryptableEntry_soTheNextWriteReplacesIt() {
        val values = mutableMapOf<String, Any?>("token" to "sealed", "other" to "kept")
        InMemorySharedPreferences(values, undecryptable = setOf("token")).getStringOrNull("token")
        assertFalse(values.containsKey("token"))
        assertEquals("kept", values["other"])
    }

    @Test
    fun getBooleanOrDefault_readsAStoredValue_andFallsBackForAnUnreadableOne() {
        val values = mutableMapOf<String, Any?>("enabled" to true, "sealed" to true)
        val prefs = InMemorySharedPreferences(values, undecryptable = setOf("sealed"))
        assertTrue(prefs.getBooleanOrDefault("enabled", false))
        assertFalse(prefs.getBooleanOrDefault("sealed", false))
        assertFalse(values.containsKey("sealed"))
    }

    @Test
    fun getLongOrDefault_readsAStoredValue_andFallsBackForAnUnreadableOne() {
        val values = mutableMapOf<String, Any?>("build" to 42L, "sealed" to 7L)
        val prefs = InMemorySharedPreferences(values, undecryptable = setOf("sealed"))
        assertEquals(42L, prefs.getLongOrDefault("build", Long.MIN_VALUE))
        assertEquals(Long.MIN_VALUE, prefs.getLongOrDefault("sealed", Long.MIN_VALUE))
        assertFalse(values.containsKey("sealed"))
    }
}
