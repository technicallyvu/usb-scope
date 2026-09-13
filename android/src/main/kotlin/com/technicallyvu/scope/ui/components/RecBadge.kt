package com.technicallyvu.scope.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.technicallyvu.scope.R
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.delay
import java.util.Locale

/** The badge's fill: the same red as the record button, readable on any picture. */
internal val RecordRed = Color(0xFFD32F2F)

/**
 * An elapsed duration in nanoseconds as "mm:ss".
 *
 * Minutes are not wrapped at 60 and there is no hours field: an hour-long clip reads "60:00", never
 * "00:00" a second time. A negative input (a clock that stepped backwards, or a badge drawn a hair
 * before its own start stamp) clamps to zero rather than printing "-1:-1".
 */
fun formatElapsed(nanos: Long): String {
    val seconds = (nanos / 1_000_000_000L).coerceAtLeast(0L)
    return String.format(Locale.ROOT, "%02d:%02d", seconds / 60L, seconds % 60L)
}

/**
 * "REC 00:12" while a clip is being written. Deliberately outside the auto-hide fade: a recording
 * in progress must stay visible even when every other control has faded away.
 *
 * @param startedAtNanos [System.nanoTime] of the moment the recorder was published, or null when
 * the start stamp is not known yet (the badge then shows "00:00" rather than nothing).
 */
@Composable
fun RecBadge(startedAtNanos: Long?, modifier: Modifier = Modifier) {
    var nowNanos by remember { mutableLongStateOf(System.nanoTime()) }
    // One recomposition per second while the badge is on screen, restarted whenever the recording
    // does; nothing ticks once it leaves the composition.
    LaunchedEffect(startedAtNanos) {
        while (true) {
            nowNanos = System.nanoTime()
            delay(1_000L)
        }
    }
    val elapsed = if (startedAtNanos == null) 0L else nowNanos - startedAtNanos
    // contentColor is set explicitly (RecordRed is not a colour-scheme role, so the default would be
    // LocalContentColor) even though the Text below names its own colour.
    Surface(color = RecordRed, contentColor = Color.White, shape = RoundedCornerShape(50), modifier = modifier) {
        Text(
            stringResource(R.string.rec_badge_format, formatElapsed(elapsed)),
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}
