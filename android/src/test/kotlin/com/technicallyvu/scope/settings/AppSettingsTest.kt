package com.technicallyvu.scope.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The in-memory [FakePrefs] this drives lives beside it, shared with [SettingsWiringTest]. */
class AppSettingsTest {

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
    fun `an id the parser would reject is never written, and its neighbours round-trip`() {
        val prefs = FakePrefs()
        val settings = AppSettings(prefs)
        settings.update {
            it.copy(
                defaults = linkedMapOf(
                    "good-id" to DriverDefault(90, false),
                    // A field separator, an entry separator, and characters merely outside the
                    // regex: written verbatim, the first two would corrupt the neighbouring entry
                    // and the third would simply vanish on the next load.
                    "bad:id" to DriverDefault(180, true),
                    "bad;id" to DriverDefault(270, false),
                    "Bad_Id" to DriverDefault(0, true),
                    "also-good" to DriverDefault(180, true),
                ),
            )
        }
        assertEquals("good-id:90:false;also-good:180:true", prefs.store["driver_defaults"])

        // Fresh instance over the same backing store simulates a process restart: what survives is
        // exactly what the writer kept, with no third entry conjured out of a mangled separator.
        val reloaded = AppSettings(prefs).flow.value.defaults
        assertEquals(
            mapOf("good-id" to DriverDefault(90, false), "also-good" to DriverDefault(180, true)),
            reloaded,
        )
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
