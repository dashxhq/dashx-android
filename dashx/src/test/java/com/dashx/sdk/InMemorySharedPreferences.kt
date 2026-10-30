package com.dashx.android

import android.content.SharedPreferences

/**
 * In-memory SharedPreferences for JVM tests. [undecryptable] keys stand in for EncryptedSharedPreferences
 * values sealed under another keyset: reading one throws as the real class does.
 */
internal class InMemorySharedPreferences(
    val values: MutableMap<String, Any?> = mutableMapOf(),
    private val undecryptable: Set<String> = emptySet(),
) : SharedPreferences {
    override fun getString(key: String?, defValue: String?): String? {
        if (key in undecryptable && values.containsKey(key)) {
            throw SecurityException("Could not decrypt value. decryption failed")
        }
        return values[key] as String? ?: defValue
    }

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val changes = mutableListOf<(MutableMap<String, Any?>) -> Unit>()
        private fun change(block: (MutableMap<String, Any?>) -> Unit) = apply { changes += block }

        override fun putString(key: String?, value: String?) = change { it[key!!] = value }
        override fun putStringSet(key: String?, values: MutableSet<String>?) = change { it[key!!] = values }
        override fun putInt(key: String?, value: Int) = change { it[key!!] = value }
        override fun putLong(key: String?, value: Long) = change { it[key!!] = value }
        override fun putFloat(key: String?, value: Float) = change { it[key!!] = value }
        override fun putBoolean(key: String?, value: Boolean) = change { it[key!!] = value }
        override fun remove(key: String?) = change { it.remove(key) }
        override fun clear() = change { it.clear() }
        override fun commit(): Boolean {
            apply()
            return true
        }
        override fun apply() = synchronized(values) { changes.forEach { it(values) } }
    }

    override fun getAll(): MutableMap<String, *> = values
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?) =
        values[key] as MutableSet<String>? ?: defValues
    override fun getInt(key: String?, defValue: Int) = values[key] as Int? ?: defValue
    override fun getLong(key: String?, defValue: Long) = values[key] as Long? ?: defValue
    override fun getFloat(key: String?, defValue: Float) = values[key] as Float? ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean) = values[key] as Boolean? ?: defValue
    override fun contains(key: String?) = values.containsKey(key)
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
}
