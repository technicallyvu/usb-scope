package com.technicallyvu.scope.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.technicallyvu.scope.BuildConfig
import com.technicallyvu.scope.R
import com.technicallyvu.scope.core.TrustInfo

/**
 * "About & privacy": what the app requests from the OS, what it does with the network (nothing),
 * and who the reverse-engineered protocol work is credited to. Read-only; a [LazyColumn] since the
 * license text alone is long enough to need scrolling on a phone.
 *
 * @param devControls debug builds only (the caller passes null otherwise): long-pressing the
 * version line then opens the developer sheet. In a release build the long-press does not exist —
 * the modifier is not applied and [DevSheet] is never called.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrustScreen(onBack: () -> Unit, devControls: DevControls? = null) {
    BackHandler(enabled = true, onBack = onBack)
    val context = LocalContext.current
    val requestedPermissions = remember {
        runCatching {
            context.packageManager
                .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
                .requestedPermissions
                ?.size ?: 0
        }.getOrDefault(0)
    }
    var showDevSheet by remember { mutableStateOf(false) }

    // enableEdgeToEdge() (and targetSdk 36, which enforces it regardless) draws this screen behind
    // the status and gesture bars; without this the title sits under the clock and the last line of
    // the licence under the gesture bar.
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        Surface(tonalElevation = 2.dp) {
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                Text(stringResource(R.string.about_title), style = MaterialTheme.typography.titleLarge)
                OutlinedButton(onClick = onBack, modifier = Modifier.padding(top = 8.dp)) {
                    Text(stringResource(R.string.action_back))
                }
            }
        }
        LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                // The developer sheet's only door, and only in a debug build: a long-press here.
                // Nothing advertises it, and a release build attaches no gesture at all.
                val versionModifier =
                    if (devControls != null && BuildConfig.DEBUG) {
                        Modifier.combinedClickable(
                            // A plain tap must keep doing nothing; only the long-press opens the sheet.
                            onClick = {},
                            onLongClick = { showDevSheet = true },
                            onLongClickLabel = stringResource(R.string.cd_open_developer_options),
                        )
                    } else {
                        Modifier
                    }
                Text(
                    stringResource(
                        R.string.about_version_format,
                        BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, BuildConfig.GIT_SHA,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = versionModifier,
                )
                Text(
                    stringResource(R.string.about_permissions_requested_format, requestedPermissions),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            item { HorizontalDivider() }
            item { Text(TrustInfo.noNetworkStatement, style = MaterialTheme.typography.bodyMedium) }
            item {
                Column {
                    Text(
                        stringResource(R.string.about_permissions_not_requested_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    TrustInfo.permissionsNotRequested.forEach { perm ->
                        Text(
                            stringResource(R.string.about_bullet_format, perm.removePrefix("android.permission.")),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            item {
                val sourceUrl = stringResource(R.string.source_url)
                Text(
                    sourceUrl,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.clickable { openLink(context, sourceUrl) },
                )
            }
            item { HorizontalDivider() }
            item { Text(TrustInfo.protocolNote, style = MaterialTheme.typography.bodyMedium) }
            item { Text(stringResource(R.string.about_attributions_title), style = MaterialTheme.typography.titleSmall) }
            items(TrustInfo.attributions) { a ->
                Column {
                    Text(
                        a.name,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.clickable { openLink(context, a.url) },
                    )
                    Text(
                        stringResource(R.string.about_attribution_detail_format, a.license, a.note),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            item { HorizontalDivider() }
            item { Text(stringResource(R.string.about_license_title), style = MaterialTheme.typography.titleSmall) }
            item { Text(TrustInfo.licenseText, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
        }
    }

    if (BuildConfig.DEBUG && showDevSheet && devControls != null) {
        DevSheet(devControls, onDismiss = { showDevSheet = false })
    }
}

/**
 * Hands [url] to whatever can view it. A device with no browser (or with links disabled by policy)
 * throws ActivityNotFoundException from startActivity; the tap then does nothing and the URL stays
 * on screen as plain text to copy, which must never be worth crashing the app over.
 */
private fun openLink(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        .onFailure { /* no browser; show the URL text as-is */ }
}
