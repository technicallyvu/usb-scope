package com.technicallyvu.scope.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.technicallyvu.scope.R

/**
 * The four things a first-time user needs to know (spec §14), shown over the live view until they
 * tap "Got it". The caller decides when it is up (until dismissed, never while recording); while it
 * is up it also holds off the chrome's auto-hide timer, so the card cannot fade out from under
 * someone still reading it.
 *
 * @param onDismiss sets `tipsDismissed`; the settings screen can bring the card back.
 */
@Composable
fun TipsCard(onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface,
            // Explicit: `surface` is a colour-scheme role here, but naming its pair keeps the card
            // readable if the container colour is ever tweaked, and matches the other chrome
            // surfaces (see ControlsSheet).
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 6.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(stringResource(R.string.tips_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.tips_line_plug_in), style = MaterialTheme.typography.bodyMedium)
            // Spec §14 requires this one: the driver debounces the cable button, so a quick tap
            // does nothing at all and a first-time user has no way to discover why.
            Text(stringResource(R.string.tips_line_hold_button), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.tips_line_press_toggle), style = MaterialTheme.typography.bodyMedium)
            // The album name is the one the saver actually uses, not a copy of it.
            Text(
                stringResource(R.string.tips_line_gallery_location, stringResource(R.string.media_folder_name)),
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.tips_got_it)) }
        }
    }
}
