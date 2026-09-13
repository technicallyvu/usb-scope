package com.technicallyvu.scope.settings

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A per-driver rotation/mirror override, applied when that driver's session starts. */
data class DriverDefault(val rotation: Int, val mirror: Boolean)

data class Settings(
    val denoise: Boolean = true,
    val denoiseStrength: Float = 0.6f,
    val doublePressWindowMs: Int = 1500,
    val haptics: Boolean = true,
    val keepScreenOn: Boolean = true,
    val showStats: Boolean = false,
    val tipsDismissed: Boolean = false,
    val defaults: Map<String, DriverDefault> = emptyMap(),
)

/**
 * SharedPreferences-backed settings store. Loaded once at construction; [flow] is updated on
 * every [update]. Values are clamped both on load (so a stale/malformed value left by an older
 * build never reaches the rest of the app) and on write.
 *
 * Deliberately free of any Compose/Android UI type beyond [SharedPreferences] itself, so a plain
 * JVM unit test can drive it with a fake `SharedPreferences` implementation.
 */
class AppSettings(private val prefs: SharedPreferences) {
    private val _flow = MutableStateFlow(load())
    val flow: StateFlow<Settings> = _flow

    /** Applies [transform] to the current settings, clamps the result, publishes it, and persists it. */
    fun update(transform: (Settings) -> Settings) {
        val next = clamp(transform(_flow.value))
        _flow.value = next
        persist(next)
    }

    private fun load(): Settings = clamp(
        Settings(
            denoise = prefs.getBoolean(KEY_DENOISE, true),
            denoiseStrength = prefs.getFloat(KEY_DENOISE_STRENGTH, 0.6f),
            doublePressWindowMs = prefs.getInt(KEY_DOUBLE_PRESS_WINDOW_MS, 1500),
            haptics = prefs.getBoolean(KEY_HAPTICS, true),
            keepScreenOn = prefs.getBoolean(KEY_KEEP_SCREEN_ON, true),
            showStats = prefs.getBoolean(KEY_SHOW_STATS, false),
            tipsDismissed = prefs.getBoolean(KEY_TIPS_DISMISSED, false),
            defaults = parseDefaults(prefs.getString(KEY_DEFAULTS, null)),
        ),
    )

    private fun persist(s: Settings) {
        prefs.edit()
            .putBoolean(KEY_DENOISE, s.denoise)
            .putFloat(KEY_DENOISE_STRENGTH, s.denoiseStrength)
            .putInt(KEY_DOUBLE_PRESS_WINDOW_MS, s.doublePressWindowMs)
            .putBoolean(KEY_HAPTICS, s.haptics)
            .putBoolean(KEY_KEEP_SCREEN_ON, s.keepScreenOn)
            .putBoolean(KEY_SHOW_STATS, s.showStats)
            .putBoolean(KEY_TIPS_DISMISSED, s.tipsDismissed)
            .putString(KEY_DEFAULTS, serializeDefaults(s.defaults))
            .apply()
    }

    companion object {
        const val KEY_DENOISE = "denoise"
        const val KEY_DENOISE_STRENGTH = "denoise_strength"
        const val KEY_DOUBLE_PRESS_WINDOW_MS = "double_press_window_ms"
        const val KEY_HAPTICS = "haptics"
        const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        const val KEY_SHOW_STATS = "show_stats"
        const val KEY_TIPS_DISMISSED = "tips_dismissed"
        const val KEY_DEFAULTS = "driver_defaults"

        private const val ENTRY_SEPARATOR = ";"
        private const val FIELD_SEPARATOR = ":"
        private val DRIVER_ID_RE = Regex("[a-z0-9-]+")
        private val VALID_ROTATIONS = setOf(0, 90, 180, 270)

        private fun clampStrength(v: Float): Float = v.coerceIn(0.2f, 1.0f)
        private fun clampWindow(v: Int): Int = v.coerceIn(500, 5000)

        /** Snaps any int to the nearest member of [VALID_ROTATIONS], wrapping modulo 360. */
        private fun clampRotation(v: Int): Int {
            val snapped = (Math.round(v / 90.0).toInt() * 90) % 360
            return if (snapped < 0) snapped + 360 else snapped
        }

        private fun clamp(s: Settings): Settings = s.copy(
            denoiseStrength = clampStrength(s.denoiseStrength),
            doublePressWindowMs = clampWindow(s.doublePressWindowMs),
            defaults = s.defaults.mapValues { (_, d) -> DriverDefault(clampRotation(d.rotation), d.mirror) },
        )

        /**
         * Parses the `driverId:rotation:mirror;...` serialisation. Any entry that does not have
         * exactly 3 fields, has a driver id with characters outside `[a-z0-9-]`, a non-integer
         * rotation, or a mirror value other than "true"/"false" is dropped; the rest of the map
         * still parses. A completely malformed string simply yields an empty map.
         */
        private fun parseDefaults(raw: String?): Map<String, DriverDefault> {
            if (raw.isNullOrEmpty()) return emptyMap()
            val result = LinkedHashMap<String, DriverDefault>()
            for (entry in raw.split(ENTRY_SEPARATOR)) {
                if (entry.isEmpty()) continue
                val parts = entry.split(FIELD_SEPARATOR)
                if (parts.size != 3) continue
                val (id, rotStr, mirrorStr) = parts
                if (!DRIVER_ID_RE.matches(id)) continue
                val rotation = rotStr.toIntOrNull() ?: continue
                val mirror = when (mirrorStr) {
                    "true" -> true
                    "false" -> false
                    else -> continue
                }
                result[id] = DriverDefault(rotation, mirror)
            }
            return result
        }

        private fun serializeDefaults(defaults: Map<String, DriverDefault>): String =
            defaults.entries.joinToString(ENTRY_SEPARATOR) { (id, d) -> "$id$FIELD_SEPARATOR${d.rotation}$FIELD_SEPARATOR${d.mirror}" }
    }
}
