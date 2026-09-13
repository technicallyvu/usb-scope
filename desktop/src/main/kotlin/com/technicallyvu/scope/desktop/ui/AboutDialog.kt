package com.technicallyvu.scope.desktop.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import com.technicallyvu.scope.core.TrustInfo
import com.technicallyvu.scope.desktop.APP_VERSION
import java.awt.Desktop
import java.net.URI

/**
 * Read-only "About" window for the desktop dev bench: version, the no-network statement,
 * attributions for the reverse-engineered protocol work, and the full license. Everything lives
 * in one scrollable column since the license text alone runs long.
 */
@Composable
fun AboutDialog(onClose: () -> Unit) {
    DialogWindow(
        onCloseRequest = onClose,
        title = "About USB Scope $APP_VERSION",
        state = rememberDialogState(width = 520.dp, height = 640.dp),
    ) {
        MaterialTheme {
            Surface(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize()) {
                    val scroll = rememberScrollState()
                    Column(
                        Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("USB Scope", style = MaterialTheme.typography.titleLarge)
                        Text("Version $APP_VERSION (dev bench)", style = MaterialTheme.typography.bodyMedium)
                        HorizontalDivider()
                        // Not the Android sentence: there is no permission manifest on the desktop
                        // to point at, so the claim has to be about what this build does.
                        Text(TrustInfo.desktopNoNetworkStatement, style = MaterialTheme.typography.bodyMedium)
                        HorizontalDivider()
                        Text("Support this project", style = MaterialTheme.typography.titleSmall)
                        Text(
                            TrustInfo.donateUrl,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { openInBrowser(TrustInfo.donateUrl) },
                        )
                        HorizontalDivider()
                        Text(TrustInfo.protocolNote, style = MaterialTheme.typography.bodyMedium)
                        Text("Attributions", style = MaterialTheme.typography.titleSmall)
                        TrustInfo.attributions.forEach { a ->
                            Column {
                                Text("${a.name} (${a.license})", style = MaterialTheme.typography.bodyMedium)
                                Text(a.url, style = MaterialTheme.typography.bodySmall)
                                Text(a.note, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        HorizontalDivider()
                        Text("License", style = MaterialTheme.typography.titleSmall)
                        Text(TrustInfo.licenseText, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        OutlinedButton(onClick = onClose) { Text("Close") }
                    }
                    VerticalScrollbar(
                        rememberScrollbarAdapter(scroll),
                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxSize(),
                    )
                }
            }
        }
    }
}

/**
 * Hands [url] to the desktop browser. A headless JVM, or a Linux session with no BROWSE support,
 * makes this a no-op rather than an exception: the URL stays on screen as text to copy, which must
 * never be worth crashing the dev bench over.
 */
private fun openInBrowser(url: String) {
    runCatching {
        val desktop = if (Desktop.isDesktopSupported()) Desktop.getDesktop() else null
        if (desktop?.isSupported(Desktop.Action.BROWSE) == true) desktop.browse(URI(url))
    }
}
