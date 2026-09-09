package com.technicallyvu.scope.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.technicallyvu.scope.core.driver.StreamStats
import java.awt.image.BufferedImage
import java.nio.file.Path
import java.util.Locale
import javax.swing.JFileChooser

@Composable
fun ScopeScreen(vm: ScopeViewModel) {
    val s by vm.state.collectAsState()
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        StatusBar(s.connection)
        Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black), contentAlignment = Alignment.Center) {
            val img = s.image
            if (img != null) {
                val bitmap = remember(img) { img.toArgb().toComposeImageBitmap() }
                Image(bitmap = bitmap, contentDescription = "Live view", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            } else {
                WaitingMessage(s.connection)
            }
            if (s.showDebug) DebugOverlay(s.stats, Modifier.align(Alignment.TopStart).padding(8.dp))
            if (s.recording) Badge("REC", Color(0xFFD32F2F), Modifier.align(Alignment.TopEnd).padding(8.dp))
        }
        Controls(s, vm)
    }
}

@Composable
private fun StatusBar(c: ConnectionState) {
    val text = when (c) {
        is ConnectionState.NoDevice -> "No device"
        is ConnectionState.Connecting -> "Connecting to ${c.name}…"
        is ConnectionState.Streaming -> "Streaming from ${c.name}"
        is ConnectionState.Failed -> "Error: ${c.message} (retrying)"
    }
    Surface(tonalElevation = 2.dp) {
        Text(text, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun WaitingMessage(c: ConnectionState) {
    val text = when (c) {
        is ConnectionState.NoDevice ->
            if (c.needsDriverHint) "The endoscope is plugged in, but Windows has no driver bound to it.\nSee docs/windows-setup.md for the one-time Zadig step."
            else "Plug in your endoscope."
        is ConnectionState.Connecting -> "Connecting…"
        is ConnectionState.Streaming -> "Waiting for the first frame…"
        is ConnectionState.Failed -> "Could not start the device.\n${c.message}\nRetrying automatically."
    }
    Text(text, color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(24.dp))
}

@Composable
private fun DebugOverlay(st: StreamStats, modifier: Modifier) {
    Text(
        "%.1f fps   frames %d   partial %d   dropped %d   %.1f MB".format(Locale.ROOT, st.fps, st.framesEmitted, st.framesPartial, st.framesDropped, st.bytesReceived / 1e6),
        color = Color(0xFF00FF66),
        style = MaterialTheme.typography.bodySmall,
        modifier = modifier.background(Color(0x99000000)).padding(6.dp),
    )
}

@Composable
private fun Badge(text: String, color: Color, modifier: Modifier) {
    Text(text, color = Color.White, style = MaterialTheme.typography.labelLarge, modifier = modifier.background(color).padding(horizontal = 10.dp, vertical = 4.dp))
}

@Composable
private fun Controls(s: UiState, vm: ScopeViewModel) {
    val streaming = s.image != null
    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = vm::snapshot, enabled = streaming) { Text("Snapshot") }
            Button(onClick = vm::toggleRecording, enabled = streaming) { Text(if (s.recording) "Stop" else "Record") }
            OutlinedButton(onClick = vm::rotate, enabled = !s.recording) { Text("Rotate (${s.rotation}°)") }
            OutlinedButton(onClick = vm::toggleMirror, enabled = !s.recording) { Text(if (s.mirror) "Mirror: on" else "Mirror: off") }
            OutlinedButton(onClick = vm::toggleDebug) { Text(if (s.showDebug) "Hide stats" else "Stats") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { chooseFolder(s.outputDir)?.let(vm::setOutputDir) }) { Text("Folder…") }
            Text(s.outputDir.toString(), style = MaterialTheme.typography.bodySmall)
            s.lastSaved?.let { Text("Saved: $it", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

private fun chooseFolder(current: Path): Path? {
    val chooser = JFileChooser(current.toFile()).apply {
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        dialogTitle = "Choose where to save photos and clips"
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile.toPath() else null
}

/**
 * Compose's BufferedImage conversion renders TYPE_3BYTE_BGR frames as a solid green block (observed
 * on Compose 1.12); it is reliable for TYPE_INT_ARGB, so convert before handing frames to Skia.
 */
private fun BufferedImage.toArgb(): BufferedImage {
    if (type == BufferedImage.TYPE_INT_ARGB) return this
    val out = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    val g = out.createGraphics()
    try {
        g.drawImage(this, 0, 0, null)
    } finally {
        g.dispose()
    }
    return out
}
