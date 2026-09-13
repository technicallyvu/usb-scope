package com.technicallyvu.scope.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.technicallyvu.scope.R
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** What the developer sheet shows about the running app. Only the bits that can change while it is open. */
data class DevState(val replaying: Boolean, val logFrameTiming: Boolean)

/**
 * The developer sheet's seam onto the view model. Built once per view model (see
 * `rememberDevControls`) so that handing it to [TrustScreen] does not recompose that screen — and
 * its licence text — at the stream frame rate.
 *
 * [state] is a flow rather than plain values for the same reason: only the sheet itself, while it
 * is open, reacts to a change.
 */
class DevControls(
    val state: StateFlow<DevState>,
    val onToggleReplay: () -> Unit,
    val onSetLogFrameTiming: (Boolean) -> Unit,
    /** A short technical dump for a bug report; built on demand, not per frame. */
    val diagnostics: () -> String,
)

/**
 * Debug-only developer controls, reached by long-pressing the version line on the About screen.
 * Release builds never construct a [DevControls], so this composable is never called there.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevSheet(controls: DevControls, onDismiss: () -> Unit) {
    val state by controls.state.collectAsState()
    val context = LocalContext.current
    // Reset when the sheet is reopened (this composable leaves the composition on dismiss), so the
    // button never claims a copy from a previous visit.
    var copied by remember { mutableStateOf(false) }
    // Sampled once per sheet opening: a live-updating dump would be unreadable, and what a bug
    // report wants is the state at the moment the developer looked.
    val diagnostics = remember(controls) { controls.diagnostics() }
    // Held explicitly so the Close button can play the slide-down before the composable leaves:
    // the caller drops us the instant `onDismiss` runs, so calling it directly made the sheet and
    // its scrim vanish. (Swipe-down and back already animate — ModalBottomSheet drives those.)
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    val close: () -> Unit = {
        scope.launch { sheetState.hide() }.invokeOnCompletion { if (!sheetState.isVisible) onDismiss() }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            // In landscape on a phone the title, replay button, switch, helper line, diagnostics
            // block, copy row and Close together exceed the sheet's maximum height; scroll rather
            // than clip Close off the bottom.
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.dev_sheet_title), style = MaterialTheme.typography.titleLarge)

            OutlinedButton(
                onClick = controls.onToggleReplay,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(stringResource(if (state.replaying) R.string.dev_replay_stop else R.string.dev_replay_start))
            }

            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.dev_log_frame_timing_label),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Switch(checked = state.logFrameTiming, onCheckedChange = controls.onSetLogFrameTiming)
            }
            Text(
                stringResource(R.string.dev_log_frame_timing_help_format, ScopeViewModel.LOG_TAG),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                diagnostics,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = { copyToClipboard(context, diagnostics); copied = true },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.dev_copy_diagnostics)) }
                // Android 13+ shows its own copy confirmation; this covers API 29..32, where
                // nothing else says the copy happened.
                if (copied) {
                    Text(
                        stringResource(R.string.dev_diagnostics_copied),
                        Modifier.padding(start = 12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            TextButton(onClick = close, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.action_close))
            }
        }
    }
}

/**
 * Puts [text] on the clipboard. No permission is involved (writing the clipboard never needed
 * one); the system service is used directly rather than Compose's clipboard so nothing here
 * depends on an API that is mid-deprecation.
 */
private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    val label = context.getString(R.string.dev_diagnostics_clip_label)
    runCatching { clipboard.setPrimaryClip(ClipData.newPlainText(label, text)) }
}
