package com.technicallyvu.scope.settings

import android.content.SharedPreferences
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AppSettingsTest {

    /**
     * Minimal in-memory [SharedPreferences] fake. Only the methods [AppSettings] actually calls
     * have real behaviour; everything else throws so an accidental new dependency on the Android
     * stub jar (which throws "not mocked" on every real method) fails loudly here instead.
     */
    private class FakePrefs : SharedPreferences {
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

    @Test
    fun `defaults when empty`() {
        val settings = AppSettings(FakePrefs())
        assertEquals(Settings(), settings.flow.value)
    }

    @Test
    fun `round trip of every field`() {
        val prefs = FakePrefs()
        val settings = AppSettings(prefs)
        settings.update {
            it.copy(
                denoise = false,
                denoiseStrength = 0.8f,
                doublePressWindowMs = 2000,
                haptics = false,
                keepScreenOn = false,
                showStats = true,
                tipsDismissed = true,
                defaults = mapOf("i4season-yuv" to DriverDefault(180, true)),
            )
        }
        // Fresh instance over the same backing store simulates a process restart.
        val reloaded = AppSettings(prefs).flow.value
        assertEquals(false, reloaded.denoise)
        assertEquals(0.8f, reloaded.denoiseStrength)
        assertEquals(2000, reloaded.doublePressWindowMs)
        assertEquals(false, reloaded.haptics)
        assertEquals(false, reloaded.keepScreenOn)
        assertEquals(true, reloaded.showStats)
        assertEquals(true, reloaded.tipsDismissed)
        assertEquals(mapOf("i4season-yuv" to DriverDefault(180, true)), reloaded.defaults)
    }

    @Test
    fun `out-of-range values are clamped on write and on load`() {
        val prefs = FakePrefs()
        val settings = AppSettings(prefs)
        settings.update {
            it.copy(
                denoiseStrength = 5.0f,
                doublePressWindowMs = 100,
                defaults = mapOf("cam" to DriverDefault(100, false)),
            )
        }
        assertEquals(1.0f, settings.flow.value.denoiseStrength)
        assertEquals(500, settings.flow.value.doublePressWindowMs)
        assertTrue(settings.flow.value.defaults.getValue("cam").rotation in setOf(0, 90, 180, 270))

        settings.update { it.copy(denoiseStrength = -3.0f, doublePressWindowMs = 50_000) }
        assertEquals(0.2f, settings.flow.value.denoiseStrength)
        assertEquals(5000, settings.flow.value.doublePressWindowMs)

        // Values written directly to the backing store (as if by an older/buggy build) are
        // clamped again on load, not just on write.
        val prefs2 = FakePrefs().apply {
            store["denoise_strength"] = 9.0f
            store["double_press_window_ms"] = 1
        }
        val reloaded = AppSettings(prefs2).flow.value
        assertEquals(1.0f, reloaded.denoiseStrength)
        assertEquals(500, reloaded.doublePressWindowMs)
    }

    @Test
    fun `a malformed defaults string is ignored rather than crashing`() {
        val prefs = FakePrefs().apply { store["driver_defaults"] = "not-even-close-to-valid" }
        val settings = AppSettings(prefs)
        assertEquals(emptyMap<String, DriverDefault>(), settings.flow.value.defaults)
    }

    @Test
    fun `a driver id with characters outside a-z0-9- is skipped, valid entries survive`() {
        val prefs = FakePrefs().apply { store["driver_defaults"] = "Cam 1:90:false;good-id-2:180:true" }
        val settings = AppSettings(prefs)
        val defaults = settings.flow.value.defaults
        assertFalse(defaults.containsKey("Cam 1"))
        assertEquals(DriverDefault(180, true), defaults["good-id-2"])
        assertEquals(1, defaults.size)
    }
}
