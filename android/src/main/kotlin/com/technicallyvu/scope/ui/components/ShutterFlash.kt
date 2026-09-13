package com.technicallyvu.scope.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

private const val FLASH_MILLIS = 150
private const val FLASH_ALPHA = 0.8f

/**
 * Drives the shutter blink: white at 80 %, fading out over 150 ms, once for every change of
 * [flashTick]. Returns the current alpha for [ShutterFlash] to draw, and calls [onFlash] as each
 * blink starts (the live view counts a snapshot as an interaction, so the chrome and its "Saved"
 * toast come back for their own four seconds).
 *
 * **Call this above the destination switch in `ScopeScreen`, not inside the live subtree.** A
 * cable-button snapshot navigates back to the live view (`screenAfterButtonEvent`), which rebuilds
 * that subtree: a seed remembered inside it would be read *after* the tick had already been bumped
 * and would swallow the flash for the very press that brought the user back, while a seed captured
 * only on first entry would replay the last snapshot's flash on every return from Settings. So the
 * state is not "the tick I entered on" but "the tick I have already answered", and it lives, with
 * the animation, above the switch.
 */
@Composable
fun rememberShutterFlashAlpha(flashTick: Long, onFlash: () -> Unit = {}): Float {
    val alpha = remember { Animatable(0f) }
    var answeredTick by remember { mutableLongStateOf(flashTick) }
    LaunchedEffect(flashTick) {
        if (flashTick == answeredTick) return@LaunchedEffect
        answeredTick = flashTick
        onFlash()
        alpha.snapTo(FLASH_ALPHA)
        alpha.animateTo(0f, tween(durationMillis = FLASH_MILLIS))
    }
    return alpha.value
}

/**
 * Draws the blink over everything else. Nothing is drawn (and nothing intercepts a tap) while it is
 * idle. [alpha] comes from [rememberShutterFlashAlpha].
 */
@Composable
fun ShutterFlash(alpha: Float, modifier: Modifier = Modifier) {
    if (alpha > 0f) {
        Box(modifier.fillMaxSize().background(Color.White.copy(alpha = alpha)))
    }
}
