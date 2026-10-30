package com.dashx.android

import android.content.SharedPreferences
import com.dashx.android.utils.getStringOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class PreferenceReadTest {

    /** Stands in for EncryptedSharedPreferences: a value sealed under another keyset fails to decrypt. */
    private class FakePreferences(
        private val values: MutableMap<String, String>,
        private val undecryptable: Set<String> = emptySet(),
    ) : SharedPreferences {
        override fun getString(key: String?, defValue: String?): String? {
            if (key in undecryptable && values.containsKey(key)) {
                throw SecurityException("Could not decrypt value. decryption failed")
            }
            return values[key] ?: defValue
        }

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            override fun remove(key: String?) = apply { values.remove(key) }
            override fun apply() {}
            override fun commit() = true
            override fun putString(key: String?, value: String?) = this
            override fun putStringSet(key: String?, values: MutableSet<String>?) = this
            override fun putInt(key: String?, value: Int) = this
            override fun putLong(key: String?, value: Long) = this
            override fun putFloat(key: String?, value: Float) = this
            override fun putBoolean(key: String?, value: Boolean) = this
            override fun clear() = this
        }

        override fun getAll(): MutableMap<String, *> = values
        override fun getStringSet(key: String?, defValues: MutableSet<String>?) = defValues
        override fun getInt(key: String?, defValue: Int) = defValue
        override fun getLong(key: String?, defValue: Long) = defValue
        override fun getFloat(key: String?, defValue: Float) = defValue
        override fun getBoolean(key: String?, defValue: Boolean) = defValue
        override fun contains(key: String?) = values.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }

    @Test
    fun getStringOrNull_readsAStoredValue() {
        val prefs = FakePreferences(mutableMapOf("token" to "abc"))
        assertEquals("abc", prefs.getStringOrNull("token"))
    }

    @Test
    fun getStringOrNull_isNullForAMissingKey() {
        assertNull(FakePreferences(mutableMapOf()).getStringOrNull("token"))
    }

    @Test
    fun getStringOrNull_isNullInsteadOfThrowing_whenTheValueCannotBeDecrypted() {
        val prefs = FakePreferences(mutableMapOf("token" to "sealed"), undecryptable = setOf("token"))
        assertNull(prefs.getStringOrNull("token"))
    }

    @Test
    fun getStringOrNull_removesTheUndecryptableEntry_soTheNextWriteReplacesIt() {
        val values = mutableMapOf("token" to "sealed", "other" to "kept")
        FakePreferences(values, undecryptable = setOf("token")).getStringOrNull("token")
        assertFalse(values.containsKey("token"))
        assertEquals("kept", values["other"])
    }
}
