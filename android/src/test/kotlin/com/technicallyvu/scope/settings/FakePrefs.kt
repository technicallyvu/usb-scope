package com.technicallyvu.scope.settings

import android.content.SharedPreferences

/**
 * Minimal in-memory [SharedPreferences] fake, shared by the settings tests. Only the methods
 * [AppSettings] actually calls have real behaviour; everything else throws so an accidental new
 * dependency on the Android stub jar (which throws "not mocked" on every real method) fails loudly
 * here instead.
 */
internal class FakePrefs : SharedPreferences {
    val store = mutableMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = throw UnsupportedOperationException()
    override fun getString(key: String?, defValue: String?): String? =
        if (store.containsKey(key)) store[key] as String? else defValue
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = throw UnsupportedOperationException()
    override fun getInt(key: String?, defValue: Int): Int = store[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = throw UnsupportedOperationException()
    override fun getFloat(key: String?, defValue: Float): Float = store[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = store[key] as? Boolean ?: defValue
    override fun contains(key: String?): Boolean = store.containsKey(key)
    override fun edit(): SharedPreferences.Editor = FakeEditor()
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = throw UnsupportedOperationException()
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = throw UnsupportedOperationException()

    inner class FakeEditor : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply { pending[key!!] = value }
        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = throw UnsupportedOperationException()
        override fun putInt(key: String?, value: Int): SharedPreferences.Editor = apply { pending[key!!] = value }
        override fun putLong(key: String?, value: Long): SharedPreferences.Editor = throw UnsupportedOperationException()
        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = apply { pending[key!!] = value }
        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply { pending[key!!] = value }
        override fun remove(key: String?): SharedPreferences.Editor = apply { pending.remove(key) }
        override fun clear(): SharedPreferences.Editor = apply { pending.clear(); store.clear() }
        override fun commit(): Boolean { apply(); return true }
        override fun apply() { store.putAll(pending) }
    }
}
