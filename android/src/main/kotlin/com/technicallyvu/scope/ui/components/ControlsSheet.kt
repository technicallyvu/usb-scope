package com.technicallyvu.scope.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.technicallyvu.scope.BuildConfig
import com.technicallyvu.scope.R
import com.technicallyvu.scope.core.session.ConnectionState
import com.technicallyvu.scope.ui.UiState

private val ButtonSize = 56.dp

/**
 * Every on-screen control, as a translucent sheet across the bottom (compact) or a vertical rail on
 * the end side (wide). The caller places and fades it; the sheet itself only decides which buttons
 * are live.
 *
 * Each control calls [onInteract] before its own action so using a control restarts the caller's
 * auto-hide timer instead of racing it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ControlsSheet(
    ui: UiState,
    wide: Boolean,
    onInteract: () -> Unit,
    onSnapshot: () -> Unit,
    onToggleRecording: () -> Unit,
    onRotate: () -> Unit,
    onToggleMirror: () -> Unit,
    onToggleDenoise: () -> Unit,
    onToggleStats: () -> Unit,
    onSettings: () -> Unit,
    onAbout: () -> Unit,
    onToggleReplay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = ui.session
    // A live frame AND a live stream: after an unplug the last bitmap is dropped, and a stale one
    // must never leave Snapshot/Record enabled.
    val streaming = s.connection is ConnectionState.Streaming && ui.image != null

    val buttons: @Composable () -> Unit = {
        GlyphButton(
            contentDescription = stringResource(R.string.cd_snapshot),
            enabled = streaming,
            onClick = { onInteract(); onSnapshot() },
        ) { tint -> cameraGlyph(tint) }

        // Record/Stop. Disabled during the start transition too: the recorder is not published yet,
        // so a second tap could only race the one in flight. The description flips with the
        // button's role, so TalkBack never says "start" on a stop.
        val recordCd = if (s.recording) stringResource(R.string.cd_stop) else stringResource(R.string.cd_record)
        IconButton(
            onClick = { onInteract(); onToggleRecording() },
            enabled = streaming && !ui.recordingStarting,
            modifier = Modifier.size(ButtonSize).semantics { contentDescription = recordCd },
        ) {
            val enabled = streaming && !ui.recordingStarting
            val tint = if (enabled) RecordRed else LocalContentColor.current
            when {
                ui.recordingStarting -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = RecordRed)
                s.recording -> Canvas(Modifier.size(24.dp)) { stopGlyph(tint) }
                else -> Canvas(Modifier.size(24.dp)) { drawCircle(tint, radius = size.minDimension * 0.30f) }
            }
        }

        IconButton(
            onClick = { onInteract(); onRotate() },
            enabled = !s.recording,
            modifier = Modifier.size(ButtonSize),
        ) {
            Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.cd_rotate))
        }

        GlyphButton(
            contentDescription = stringResource(R.string.cd_mirror),
            enabled = !s.recording,
            onClick = { onInteract(); onToggleMirror() },
        ) { tint -> mirrorGlyph(tint) }

        GlyphToggleButton(
            contentDescription = stringResource(R.string.cd_denoise),
            checked = s.denoise,
            onCheckedChange = { onInteract(); onToggleDenoise() },
        ) { tint -> denoiseGlyph(tint) }

        GlyphToggleButton(
            contentDescription = stringResource(R.string.cd_stats),
            checked = ui.showStats,
            onCheckedChange = { onInteract(); onToggleStats() },
        ) { tint -> statsGlyph(tint) }

        IconButton(
            onClick = { onInteract(); onSettings() },
            modifier = Modifier.size(ButtonSize),
        ) {
            Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.cd_settings))
        }

        // Disabled while recording: About replaces the live view, and with it the REC badge — the
        // only on-screen sign that a clip is still being written.
        IconButton(
            onClick = { onInteract(); onAbout() },
            enabled = !s.recording,
            modifier = Modifier.size(ButtonSize),
        ) {
            Icon(Icons.Filled.Info, contentDescription = stringResource(R.string.cd_about))
        }

        // Debug-only; Task 5 moves it into the developer sheet.
        if (BuildConfig.DEBUG) {
            TextButton(onClick = { onInteract(); onToggleReplay() }) {
                Text(stringResource(if (ui.replaying) R.string.dev_replay_stop else R.string.dev_replay_start))
            }
        }
    }

    val shape = if (wide) {
        RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp)
    } else {
        RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    }
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
        shape = shape,
        modifier = modifier,
    ) {
        if (wide) {
            Column(
                Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) { buttons() }
        } else {
            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.Center,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) { buttons() }
        }
    }
}

@Composable
private fun GlyphButton(
    contentDescription: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
    glyph: DrawScope.(Color) -> Unit,
) {
    val cd = contentDescription
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(ButtonSize).semantics { this.contentDescription = cd },
    ) {
        val tint = LocalContentColor.current
        Canvas(Modifier.size(24.dp)) { glyph(tint) }
    }
}

@Composable
private fun GlyphToggleButton(
    contentDescription: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    glyph: DrawScope.(Color) -> Unit,
) {
    val cd = contentDescription
    FilledIconToggleButton(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = Modifier.size(ButtonSize).semantics { this.contentDescription = cd },
    ) {
        val tint = LocalContentColor.current
        Canvas(Modifier.size(24.dp)) { glyph(tint) }
    }
}

// ---- glyphs ----
// The material-icons-core set has no camera, mirror, denoise or record icon, so these five are
// drawn by hand at a common weight (stroke ≈ 8 % of the box) to sit consistently beside the three
// real icons (Refresh, Settings, Info).

/** A camera body with a lens ring and a viewfinder bump. */
private fun DrawScope.cameraGlyph(tint: Color) {
    val w = size.width
    val h = size.height
    val stroke = Stroke(width = w * 0.08f)
    drawRect(tint, topLeft = Offset(w * 0.34f, h * 0.14f), size = Size(w * 0.28f, h * 0.10f))
    drawRoundRect(
        tint,
        topLeft = Offset(w * 0.06f, h * 0.26f),
        size = Size(w * 0.88f, h * 0.58f),
        cornerRadius = CornerRadius(w * 0.12f, w * 0.12f),
        style = stroke,
    )
    drawCircle(tint, radius = w * 0.17f, center = Offset(w / 2f, h * 0.55f), style = stroke)
}

/** The stop square, matched in visual weight to the record circle. */
private fun DrawScope.stopGlyph(tint: Color) {
    val side = size.minDimension * 0.54f
    drawRoundRect(
        tint,
        topLeft = Offset((size.width - side) / 2f, (size.height - side) / 2f),
        size = Size(side, side),
        cornerRadius = CornerRadius(side * 0.15f, side * 0.15f),
    )
}

/** Two triangles facing each other across a dashed axis: one solid, one outlined. */
private fun DrawScope.mirrorGlyph(tint: Color) {
    val w = size.width
    val h = size.height
    val cx = w / 2f
    drawLine(
        tint,
        start = Offset(cx, h * 0.06f),
        end = Offset(cx, h * 0.94f),
        strokeWidth = w * 0.07f,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(w * 0.13f, w * 0.10f)),
    )
    val solid = Path().apply {
        moveTo(cx - w * 0.13f, h * 0.14f)
        lineTo(cx - w * 0.13f, h * 0.86f)
        lineTo(w * 0.08f, h * 0.86f)
        close()
    }
    drawPath(solid, tint)
    val outlined = Path().apply {
        moveTo(cx + w * 0.13f, h * 0.14f)
        lineTo(cx + w * 0.13f, h * 0.86f)
        lineTo(w * 0.92f, h * 0.86f)
        close()
    }
    drawPath(outlined, tint, style = Stroke(width = w * 0.08f))
}

/** A filter funnel: the closest simple shape to "smooth out the noise". */
private fun DrawScope.denoiseGlyph(tint: Color) {
    val w = size.width
    val h = size.height
    val funnel = Path().apply {
        moveTo(w * 0.10f, h * 0.18f)
        lineTo(w * 0.90f, h * 0.18f)
        lineTo(w * 0.58f, h * 0.52f)
        lineTo(w * 0.58f, h * 0.88f)
        lineTo(w * 0.42f, h * 0.74f)
        lineTo(w * 0.42f, h * 0.52f)
        close()
    }
    drawPath(funnel, tint, style = Stroke(width = w * 0.08f))
}

/** Three bars, ascending-ish: the stats overlay toggle. */
private fun DrawScope.statsGlyph(tint: Color) {
    val w = size.width
    val h = size.height
    val barWidth = w * 0.16f
    val gap = w * 0.10f
    val heights = floatArrayOf(0.42f, 0.68f, 0.28f)
    heights.forEachIndexed { i, frac ->
        val x = w * 0.17f + i * (barWidth + gap)
        drawRect(tint, topLeft = Offset(x, h * (0.84f - frac)), size = Size(barWidth, h * frac))
    }
}
