package com.technicallyvu.scope.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/** Seed colour for the static (pre-API-31) palette: a neutral teal, unrelated to any vendor brand. */
private val SeedTeal = Color(0xFF2A7F86)

/**
 * App-wide Material 3 theme. Uses the system's dynamic (wallpaper-derived) colour scheme on
 * Android 12+ (API 31+); falls back to a static dark/light scheme seeded with [SeedTeal] on
 * older devices, where dynamic colour isn't available.
 */
@Composable
fun ScopeTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colorScheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        if (dark) darkColorScheme(primary = SeedTeal) else lightColorScheme(primary = SeedTeal)
    }
    MaterialTheme(colorScheme = colorScheme, typography = Typography(), content = content)
}
