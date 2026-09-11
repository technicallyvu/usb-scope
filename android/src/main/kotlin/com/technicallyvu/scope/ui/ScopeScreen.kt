package com.technicallyvu.scope.ui

import android.hardware.usb.UsbDevice
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.technicallyvu.scope.BuildConfig
import com.technicallyvu.scope.core.driver.StreamStats
import com.technicallyvu.scope.core.session.ConnectionState
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3WindowSizeClassApi::class)
@Composable
fun ScopeScreen(vm: ScopeViewModel, onRequestPermission: (UsbDevice) -> Unit) {
    val ui by vm.ui.collectAsState()
    // No activity (a preview, or a non-activity context): fall back to the compact layout rather
    // than crashing on a cast.
    val activity = LocalActivity.current
    val wide = if (activity == null) false else calculateWindowSizeClass(activity).widthSizeClass == WindowWidthSizeClass.Expanded

    // Remembered, not `vm::toggleTrust`: a method reference is a fresh instance on every
    // recomposition, and this screen recomposes per frame, which would recompose all of
    // TrustScreen (license text included) at the frame rate while it is open.
    val onBack = remember(vm) { { vm.toggleTrust() } }
    if (ui.showTrust) {
        TrustScreen(onBack = onBack)
        return
    }

    // A haptic tick on every cable-button press (single or double). The view model bumps
    // ui.hapticTick on each button event; the initial value must not itself buzz on screen entry.
    val haptic = LocalHapticFeedback.current
    var sawFirstTick by remember { mutableStateOf(false) }
    LaunchedEffect(ui.hapticTick) {
        if (sawFirstTick) haptic.performHapticFeedback(HapticFeedbackType.LongPress) else sawFirstTick = true
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        StatusBar(ui)
        if (wide) {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                LiveView(ui, Modifier.weight(1f).fillMaxHeight())
                Column(Modifier.width(220.dp).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Controls(ui, vm, onRequestPermission) }
            }
        } else {
            LiveView(ui, Modifier.weight(1f).fillMaxWidth())
            FlowRow(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Controls(ui, vm, onRequestPermission) }
        }
        ui.message?.let { msg -> Snackbar(action = { OutlinedButton(onClick = vm::clearMessage) { Text("OK") } }) { Text(msg) } }
    }
}

@Composable
private fun StatusBar(ui: UiState) {
    val text = when (val c = ui.session.connection) {
        is ConnectionState.NoDevice -> "No device"
        is ConnectionState.Connecting -> "Connecting…"
        is ConnectionState.Streaming -> if (ui.replaying) "Replaying fixture" else "Streaming"
        is ConnectionState.Failed -> "Error: ${c.message}"
    }
    Surface(tonalElevation = 2.dp) {
        Text(text, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun LiveView(ui: UiState, modifier: Modifier) {
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        val img = ui.image
        if (img != null) {
            Image(bitmap = img.asImageBitmap(), contentDescription = "Live view", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        } else {
            val text = when (val c = ui.session.connection) {
                is ConnectionState.NoDevice -> "Plug in your endoscope."
                is ConnectionState.Connecting -> "Connecting…"
                is ConnectionState.Streaming -> "Waiting for the first frame…"
                is ConnectionState.Failed -> if (ui.permissionDevice != null) "USB permission needed." else "Could not start the device.\n${c.message}\nRetrying automatically."
            }
            Text(text, color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(24.dp))
        }
        if (ui.showStats) StatsOverlay(ui.session.stats, Modifier.align(Alignment.TopStart).padding(8.dp))
        if (ui.session.recording) Text("REC", color = Color.White, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).background(Color(0xFFD32F2F)).padding(horizontal = 10.dp, vertical = 4.dp))
    }
}

@Composable
private fun StatsOverlay(st: StreamStats, modifier: Modifier) {
    Text(
        String.format(Locale.ROOT, "%.1f fps  frames %d  partial %d  dropped %d  %.1f MB", st.fps, st.framesEmitted, st.framesPartial, st.framesDropped, st.bytesReceived / 1e6),
        color = Color(0xFF00FF66), style = MaterialTheme.typography.bodySmall,
        modifier = modifier.background(Color(0x99000000)).padding(6.dp),
    )
}

@Composable
private fun Controls(ui: UiState, vm: ScopeViewModel, onRequestPermission: (UsbDevice) -> Unit) {
    // A live frame AND a live stream: after an unplug the last bitmap is dropped, and a stale one
    // must never leave Snapshot/Record enabled.
    val streaming = ui.session.connection is ConnectionState.Streaming && ui.image != null
    val s = ui.session
    ui.permissionDevice?.let { dev -> Button(onClick = { onRequestPermission(dev) }) { Text("Allow USB access") } }
    Button(onClick = vm::snapshot, enabled = streaming) { Text("Snapshot") }
    Button(onClick = vm::toggleRecording, enabled = streaming) { Text(if (s.recording) "Stop" else "Record") }
    OutlinedButton(onClick = vm::rotate, enabled = !s.recording) { Text("Rotate (${s.rotation}°)") }
    OutlinedButton(onClick = vm::toggleMirror, enabled = !s.recording) { Text(if (s.mirror) "Mirror: on" else "Mirror: off") }
    OutlinedButton(onClick = vm::toggleDenoise) { Text(if (s.denoise) "Denoise: on" else "Denoise: off") }
    OutlinedButton(onClick = vm::toggleStats) { Text(if (ui.showStats) "Hide stats" else "Stats") }
    // Disabled while recording: the About screen replaces the live view, and with it the REC badge —
    // the only on-screen sign that a clip is still being written.
    OutlinedButton(onClick = vm::toggleTrust, enabled = !s.recording) { Text("About") }
    if (BuildConfig.DEBUG) {
        OutlinedButton(onClick = { if (ui.replaying) vm.stopReplay() else vm.startReplay() }) { Text(if (ui.replaying) "Stop replay" else "Replay fixture") }
    }
    s.lastSaved?.let { Text("Saved: $it", style = MaterialTheme.typography.bodySmall) }
}
