package com.dashx.android

import android.content.SharedPreferences

/**
 * In-memory SharedPreferences for JVM tests. [undecryptable] keys stand in for EncryptedSharedPreferences
 * values sealed under another keyset: reading one throws as the real class does, until it is rewritten or removed.
 */
internal class InMemorySharedPreferences(
    val values: MutableMap<String, Any?> = mutableMapOf(),
    undecryptable: Set<String> = emptySet(),
) : SharedPreferences {
    private val undecryptable = undecryptable.toMutableSet()

    @Suppress("UNCHECKED_CAST")
    private fun <T> read(key: String?, defValue: T): T {
        if (key in undecryptable && values.containsKey(key)) {
            throw SecurityException("Could not decrypt value. decryption failed")
        }
        return values[key] as T? ?: defValue
    }

    override fun getString(key: String?, defValue: String?): String? = read(key, defValue)
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = read(key, defValues)
    override fun getInt(key: String?, defValue: Int): Int = read(key, defValue)
    override fun getLong(key: String?, defValue: Long): Long = read(key, defValue)
    override fun getFloat(key: String?, defValue: Float): Float = read(key, defValue)
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = read(key, defValue)

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val changes = mutableListOf<() -> Unit>()
        private fun put(key: String?, value: Any?) = apply {
            changes += {
                values[key!!] = value
                undecryptable.remove(key)
            }
        }

        override fun putString(key: String?, value: String?) = put(key, value)
        override fun putStringSet(key: String?, values: MutableSet<String>?) = put(key, values)
        override fun putInt(key: String?, value: Int) = put(key, value)
        override fun putLong(key: String?, value: Long) = put(key, value)
        override fun putFloat(key: String?, value: Float) = put(key, value)
        override fun putBoolean(key: String?, value: Boolean) = put(key, value)
        override fun remove(key: String?) = apply {
            changes += {
                values.remove(key)
                undecryptable.remove(key)
            }
        }
        override fun clear() = apply {
            changes += {
                values.clear()
                undecryptable.clear()
            }
        }
        override fun commit(): Boolean {
            apply()
            return true
        }
        override fun apply() = synchronized(values) { changes.forEach { it() } }
    }

    override fun getAll(): MutableMap<String, *> = values
    override fun contains(key: String?) = values.containsKey(key)
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
}
