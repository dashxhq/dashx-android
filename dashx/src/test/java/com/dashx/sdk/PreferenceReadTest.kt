package com.dashx.android

import com.dashx.android.utils.getStringOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
}
