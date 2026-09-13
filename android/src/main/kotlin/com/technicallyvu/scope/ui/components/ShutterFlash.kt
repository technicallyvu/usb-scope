package com.technicallyvu.scope.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

private const val FLASH_MILLIS = 150

/**
 * A shutter blink: the whole view goes white at 80 % and fades out over 150 ms every time
 * [flashTick] changes. The tick's *initial* value is deliberately skipped, otherwise the screen
 * would flash once on entry (and again after every configuration change).
 */
@Composable
fun ShutterFlash(flashTick: Long, modifier: Modifier = Modifier) {
    val alpha = remember { Animatable(0f) }
    var sawFirstTick by remember { mutableStateOf(false) }
    LaunchedEffect(flashTick) {
        if (!sawFirstTick) {
            sawFirstTick = true
            return@LaunchedEffect
        }
        alpha.snapTo(0.8f)
        alpha.animateTo(0f, tween(durationMillis = FLASH_MILLIS))
    }
    // Nothing is drawn (and nothing intercepts a tap) while the flash is idle.
    if (alpha.value > 0f) {
        Box(modifier.fillMaxSize().background(Color.White.copy(alpha = alpha.value)))
    }
}
