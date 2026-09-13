package com.technicallyvu.scope.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.technicallyvu.scope.R
import com.technicallyvu.scope.core.session.ConnectionState
import com.technicallyvu.scope.ui.UiState

private val DotNoDevice = Color(0xFF9E9E9E)
private val DotConnecting = Color(0xFFFFB300)
private val DotStreaming = Color(0xFF43A047)
private val DotFailed = Color(0xFFE53935)

/**
 * The one glanceable line of state: a coloured dot plus a short label, on a translucent scrim so it
 * stays readable over any picture. The frame rate is appended only while streaming with the stats
 * overlay on — the chip itself must stay short enough to read at a glance.
 */
@Composable
fun StatusChip(ui: UiState, modifier: Modifier = Modifier) {
    val connection = ui.session.connection
    val dot = when (connection) {
        is ConnectionState.NoDevice -> DotNoDevice
        is ConnectionState.Connecting -> DotConnecting
        is ConnectionState.Streaming -> DotStreaming
        is ConnectionState.Failed -> DotFailed
    }
    val label = when (connection) {
        is ConnectionState.NoDevice -> stringResource(R.string.status_no_device)
        is ConnectionState.Connecting -> stringResource(R.string.status_connecting)
        is ConnectionState.Streaming ->
            if (ui.replaying) stringResource(R.string.status_replaying) else stringResource(R.string.status_streaming)
        is ConnectionState.Failed -> stringResource(R.string.status_error_prefix, connection.message)
    }
    val text = if (connection is ConnectionState.Streaming && ui.showStats) {
        stringResource(R.string.status_chip_fps_format, label, ui.session.stats.fps)
    } else {
        label
    }
    Surface(color = Color(0x99000000), shape = RoundedCornerShape(50), modifier = modifier) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Canvas(Modifier.size(8.dp)) { drawCircle(dot) }
            Text(text, color = Color.White, style = MaterialTheme.typography.labelLarge)
        }
    }
}
