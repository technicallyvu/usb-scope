package com.technicallyvu.scope.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The settings-to-session wiring, exercised through [SettingsTarget] so no USB transport, driver or
 * coroutine scope is needed. The view model owns only the plumbing around this: one applier per
 * session, fed by the settings flow.
 */
class SettingsWiringTest {

    private class FakeTarget : SettingsTarget {
        val denoise = mutableListOf<Pair<Boolean, Float>>()
        val sharpen = mutableListOf<Pair<Boolean, Float>>()
        val windowNanos = mutableListOf<Long>()
        val overrides = mutableListOf<Triple<String, Int?, Boolean?>>()
        val cleared = mutableListOf<String>()

        override fun setDenoise(enabled: Boolean, strength: Float) { denoise += enabled to strength }
        override fun setSharpen(enabled: Boolean, strength: Float) { sharpen += enabled to strength }
        override fun setDoublePressWindowNanos(nanos: Long) { windowNanos += nanos }
        override fun setDefaultOverride(driverId: String, rotation: Int?, mirror: Boolean?) {
            overrides += Triple(driverId, rotation, mirror)
        }
        override fun clearDefaultOverride(driverId: String) { cleared += driverId }
    }

    @Test
    fun `the double-press window converts to nanoseconds and clamps to the session's range`() {
        assertEquals(1_500_000_000L, doublePressWindowNanos(1500))
        assertEquals(3_000_000_000L, doublePressWindowNanos(3000))
        // Below/above what ScopeSession accepts: clamped here rather than left to the setter, so
        // the value the session reports back always matches what was asked for.
        assertEquals(500_000_000L, doublePressWindowNanos(0))
        assertEquals(500_000_000L, doublePressWindowNanos(-1))
        assertEquals(5_000_000_000L, doublePressWindowNanos(60_000))
    }

    @Test
    fun `applying settings pushes denoise, sharpen, the window and every remembered default`() {
        val target = FakeTarget()
        SettingsApplier(target).apply(
            Settings(
                denoise = true,
                denoiseStrength = 0.8f,
                sharpen = true,
                sharpenStrength = 0.4f,
                doublePressWindowMs = 2000,
                defaults = mapOf("i4season-yuv" to DriverDefault(180, true), "uvc-bulk" to DriverDefault(0, false)),
            ),
        )
        assertEquals(listOf(true to 0.8f), target.denoise)
        assertEquals(listOf(true to 0.4f), target.sharpen)
        assertEquals(listOf(2_000_000_000L), target.windowNanos)
        assertEquals(
            setOf(Triple("i4season-yuv", 180, true), Triple("uvc-bulk", 0, false)),
            target.overrides.toSet(),
        )
        assertTrue(target.cleared.isEmpty())
    }

    @Test
    fun `a second apply only re-sends the default that changed`() {
        val target = FakeTarget()
        val applier = SettingsApplier(target)
        val first = Settings(defaults = mapOf("a" to DriverDefault(90, false), "b" to DriverDefault(0, false)))
        applier.apply(first)
        target.overrides.clear()

        applier.apply(first.copy(defaults = mapOf("a" to DriverDefault(270, true), "b" to DriverDefault(0, false))))
        assertEquals(listOf(Triple("a", 270, true)), target.overrides)
        assertTrue(target.cleared.isEmpty())
    }

    @Test
    fun `forgetting a device clears its override on the running session`() {
        val target = FakeTarget()
        val applier = SettingsApplier(target)
        applier.apply(Settings(defaults = mapOf("a" to DriverDefault(90, false), "b" to DriverDefault(180, true))))
        target.overrides.clear()

        applier.apply(Settings(defaults = mapOf("b" to DriverDefault(180, true))))
        assertEquals(listOf("a"), target.cleared)
        assertTrue(target.overrides.isEmpty())

        // A reset forgets the rest.
        applier.apply(Settings())
        assertEquals(listOf("a", "b"), target.cleared)
    }

    @Test
    fun `a new session gets every setting again, defaults included`() {
        val settings = Settings(denoise = false, defaults = mapOf("a" to DriverDefault(90, false)))
        val first = FakeTarget()
        SettingsApplier(first).apply(settings)

        // A replay start (or any reconnect) builds a fresh ScopeSession, which carries no overrides
        // and no denoise state of its own; its applier must therefore install everything anew.
        val second = FakeTarget()
        SettingsApplier(second).apply(settings)
        assertEquals(listOf(false to 0.6f), second.denoise)
        assertEquals(listOf(false to 0.5f), second.sharpen, "a fresh session has no sharpen state either")
        assertEquals(listOf(1_500_000_000L), second.windowNanos)
        assertEquals(listOf(Triple("a", 90, false)), second.overrides)
    }

    @Test
    fun `the round trip through the store leaves the values the session gets unchanged`() {
        // Guards the seam between AppSettings clamping and what reaches the session: an
        // out-of-range strength or window is clamped by the store, so the applier never sees one.
        val stored = Settings(denoiseStrength = 9f, sharpenStrength = 9f, doublePressWindowMs = 50_000)
        val store = AppSettings(FakePrefs())
        store.update { stored }
        val target = FakeTarget()
        SettingsApplier(target).apply(store.flow.value)
        assertEquals(listOf(true to 1.0f), target.denoise)
        assertEquals(listOf(false to 1.0f), target.sharpen)
        assertEquals(listOf(5_000_000_000L), target.windowNanos)
    }
}
