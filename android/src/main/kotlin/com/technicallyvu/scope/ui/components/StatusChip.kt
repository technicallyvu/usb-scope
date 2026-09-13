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

private val DotNoDevice = Color(0xFF9E9E9E)
private val DotConnecting = Color(0xFFFFB300)
private val DotStreaming = Color(0xFF43A047)
private val DotFailed = Color(0xFFE53935)

/**
 * The four states the chip distinguishes. A plain enum rather than [ConnectionState] itself: that
 * sealed class comes from `core`, which Compose cannot see stability metadata for, so taking it as
 * a parameter would make the chip unskippable and redraw it at the stream frame rate.
 */
enum class ConnStatus { NoDevice, Connecting, Streaming, Failed }

/** @see ConnStatus */
fun ConnectionState.toConnStatus(): ConnStatus = when (this) {
    is ConnectionState.NoDevice -> ConnStatus.NoDevice
    is ConnectionState.Connecting -> ConnStatus.Connecting
    is ConnectionState.Streaming -> ConnStatus.Streaming
    is ConnectionState.Failed -> ConnStatus.Failed
}

/**
 * The one glanceable line of state: a coloured dot plus a short label, on a translucent scrim so it
 * stays readable over any picture.
 *
 * @param errorMessage the failure text, for [ConnStatus.Failed] only.
 * @param fps appended to the label when non-null — the caller passes it only while streaming with
 * the stats overlay on, so that the chip stays short enough to read at a glance and, on every other
 * frame, keeps a value this composable can skip on.
 */
@Composable
fun StatusChip(
    status: ConnStatus,
    errorMessage: String?,
    replaying: Boolean,
    fps: Double?,
    modifier: Modifier = Modifier,
) {
    val dot = when (status) {
        ConnStatus.NoDevice -> DotNoDevice
        ConnStatus.Connecting -> DotConnecting
        ConnStatus.Streaming -> DotStreaming
        ConnStatus.Failed -> DotFailed
    }
    val label = when (status) {
        ConnStatus.NoDevice -> stringResource(R.string.status_no_device)
        ConnStatus.Connecting -> stringResource(R.string.status_connecting)
        ConnStatus.Streaming ->
            if (replaying) stringResource(R.string.status_replaying) else stringResource(R.string.status_streaming)
        ConnStatus.Failed -> stringResource(R.string.status_error_prefix, errorMessage.orEmpty())
    }
    val text = if (fps != null) stringResource(R.string.status_chip_fps_format, label, fps) else label
    Surface(
        color = Color(0x99000000),
        // Not a colour-scheme role, so the default contentColor would be LocalContentColor (black).
        contentColor = Color.White,
        shape = RoundedCornerShape(50),
        modifier = modifier,
    ) {
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
