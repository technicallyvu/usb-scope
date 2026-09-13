package com.technicallyvu.scope.settings

import com.technicallyvu.scope.core.session.ScopeSession

/**
 * The slice of [ScopeSession] the settings actually drive. Narrow on purpose: it lets the wiring
 * below be unit-tested with a recording fake, without a USB transport, a driver or a coroutine
 * scope anywhere near the test.
 */
interface SettingsTarget {
    fun setDenoise(enabled: Boolean, strength: Float)
    fun setSharpen(enabled: Boolean, strength: Float)
    fun setDoublePressWindowNanos(nanos: Long)
    fun setDefaultOverride(driverId: String, rotation: Int?, mirror: Boolean?)
    fun clearDefaultOverride(driverId: String)
}

/** [SettingsTarget] over a real session. */
class SessionSettingsTarget(private val session: ScopeSession) : SettingsTarget {
    override fun setDenoise(enabled: Boolean, strength: Float) = session.setDenoise(enabled, strength)
    override fun setSharpen(enabled: Boolean, strength: Float) = session.setSharpen(enabled, strength)
    override fun setDoublePressWindowNanos(nanos: Long) { session.doublePressWindowNanos = nanos }
    override fun setDefaultOverride(driverId: String, rotation: Int?, mirror: Boolean?) =
        session.setDefaultOverride(driverId, rotation, mirror)
    override fun clearDefaultOverride(driverId: String) = session.clearDefaultOverride(driverId)
}

/** The double-press window as the session wants it: nanoseconds, clamped to the range it accepts. */
fun doublePressWindowNanos(milliseconds: Int): Long =
    milliseconds.toLong().coerceIn(MIN_DOUBLE_PRESS_WINDOW_MS, MAX_DOUBLE_PRESS_WINDOW_MS) * 1_000_000L

const val MIN_DOUBLE_PRESS_WINDOW_MS = 500L
const val MAX_DOUBLE_PRESS_WINDOW_MS = 5_000L

/**
 * Pushes [Settings] into one session's [SettingsTarget]. One applier belongs to one session: it
 * remembers which per-driver defaults it has already installed on *that* session, so a settings
 * change only touches what actually changed, and a default the user forgot is cleared from the
 * session rather than left behind. A new session gets a new applier, which therefore installs
 * everything from scratch (a fresh [ScopeSession] starts with no overrides at all).
 *
 * Denoise, sharpen and the double-press window are cheap idempotent setters, so they are written on
 * every apply rather than diffed.
 */
class SettingsApplier(private val target: SettingsTarget) {
    private var appliedDefaults: Map<String, DriverDefault> = emptyMap()

    fun apply(settings: Settings) {
        target.setDenoise(settings.denoise, settings.denoiseStrength)
        target.setSharpen(settings.sharpen, settings.sharpenStrength)
        target.setDoublePressWindowNanos(doublePressWindowNanos(settings.doublePressWindowMs))
        for ((driverId, default) in settings.defaults) {
            if (appliedDefaults[driverId] != default) {
                target.setDefaultOverride(driverId, default.rotation, default.mirror)
            }
        }
        for (driverId in appliedDefaults.keys - settings.defaults.keys) {
            target.clearDefaultOverride(driverId)
        }
        appliedDefaults = settings.defaults
    }
}
