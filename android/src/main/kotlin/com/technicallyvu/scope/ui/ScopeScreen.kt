package com.technicallyvu.scope.ui

import android.hardware.usb.UsbDevice
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.technicallyvu.scope.R
import com.technicallyvu.scope.core.driver.StreamStats
import com.technicallyvu.scope.core.session.ConnectionState
import com.technicallyvu.scope.ui.components.ControlsSheet
import com.technicallyvu.scope.ui.components.RecBadge
import com.technicallyvu.scope.ui.components.ShutterFlash
import com.technicallyvu.scope.ui.components.SnapshotToast
import com.technicallyvu.scope.ui.components.StatusChip
import kotlinx.coroutines.delay

/** How long the controls stay up after the last interaction, while streaming. */
private const val AUTO_HIDE_MILLIS = 4_000L

@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@Composable
fun ScopeScreen(vm: ScopeViewModel, onRequestPermission: (UsbDevice) -> Unit) {
    val ui by vm.ui.collectAsState()
    // No activity (a preview, or a non-activity context): fall back to the compact layout rather
    // than crashing on a cast.
    val activity = LocalActivity.current
    val wide = if (activity == null) false else calculateWindowSizeClass(activity).widthSizeClass == WindowWidthSizeClass.Expanded

    // Remembered, not `vm::navigate`: a method reference is a fresh instance on every
    // recomposition, and this screen recomposes per frame, which would recompose all of
    // TrustScreen (license text included) at the frame rate while it is open.
    val onBack = remember(vm) { { vm.navigate(Screen.Live) } }
    when (ui.screen) {
        Screen.Trust -> {
            TrustScreen(onBack = onBack)
            return
        }
        Screen.Settings -> {
            SettingsPlaceholder(onBack = onBack)
            return
        }
        Screen.Live -> Unit
    }

    // A haptic tick on every cable-button press (single or double). The view model bumps
    // ui.hapticTick on each button event; the initial value must not itself buzz on screen entry.
    val haptic = LocalHapticFeedback.current
    var sawFirstTick by remember { mutableStateOf(false) }
    LaunchedEffect(ui.hapticTick) {
        if (sawFirstTick) haptic.performHapticFeedback(HapticFeedbackType.LongPress) else sawFirstTick = true
    }

    // Auto-hide. Every interaction bumps `interactions`, which restarts the effect below; the
    // controls are only ever hidden while a stream is actually running, so a user staring at
    // "No device" is never left with a bare black screen.
    val streaming = ui.session.connection is ConnectionState.Streaming
    var interactions by remember { mutableIntStateOf(0) }
    var chromeVisible by remember { mutableStateOf(true) }
    val onInteract: () -> Unit = remember { { interactions++ } }
    LaunchedEffect(streaming, interactions) {
        chromeVisible = true
        if (!streaming) return@LaunchedEffect
        delay(AUTO_HIDE_MILLIS)
        chromeVisible = false
    }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(ui.message) {
        val msg = ui.message ?: return@LaunchedEffect
        // Suspends until the snackbar is dismissed (or a newer message cancels this effect, which
        // dismisses it for us); only then is the message consumed.
        snackbarHostState.showSnackbar(msg)
        vm.clearMessage()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            // A tap anywhere brings the controls back. Taps the buttons themselves consume never
            // reach this detector; those restart the timer through onInteract instead.
            .pointerInput(Unit) { detectTapGestures { interactions++ } },
    ) {
        LiveContent(ui)

        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            AnimatedVisibility(
                visible = chromeVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatusChip(ui)
                    if (ui.showStats) StatsOverlay(ui.session.stats)
                }
            }

            // Outside the fade on purpose: a clip being written must stay visible even after
            // everything else has gone.
            if (ui.session.recording) {
                RecBadge(ui.recordingStartedAtNanos, Modifier.align(Alignment.TopEnd).padding(8.dp))
            }

            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(end = if (wide) 88.dp else 0.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SnapshotToast(
                    thumb = ui.lastSnapshotThumb,
                    name = ui.session.lastSaved,
                    visible = chromeVisible,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                ui.permissionDevice?.let { device ->
                    Button(
                        onClick = { onInteract(); onRequestPermission(device) },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    ) { Text(stringResource(R.string.action_allow_usb_access)) }
                }
                SnackbarHost(snackbarHostState)
                if (!wide) {
                    AnimatedVisibility(visible = chromeVisible, enter = fadeIn(), exit = fadeOut()) {
                        Sheet(ui, wide = false, onInteract = onInteract, actions = rememberSheetActions(vm))
                    }
                }
            }

            if (wide) {
                AnimatedVisibility(
                    visible = chromeVisible,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.align(Alignment.CenterEnd),
                ) {
                    Sheet(ui, wide = true, onInteract = onInteract, actions = rememberSheetActions(vm))
                }
            }
        }

        ShutterFlash(ui.flashTick)
    }
}

/**
 * The sheet's callbacks, bundled so the compact sheet and the wide rail cannot drift apart.
 * Remembered per view model: the sheet is recomposed on every frame while a stream runs.
 */
@Composable
private fun rememberSheetActions(vm: ScopeViewModel): SheetActions = remember(vm) {
    SheetActions(
        onSnapshot = vm::snapshot,
        onToggleRecording = { vm.toggleRecording() },
        onRotate = vm::rotate,
        onToggleMirror = vm::toggleMirror,
        onToggleDenoise = vm::toggleDenoise,
        onToggleStats = vm::toggleStats,
        onSettings = { vm.navigate(Screen.Settings) },
        onAbout = { vm.navigate(Screen.Trust) },
        onToggleReplay = { if (vm.ui.value.replaying) vm.stopReplay() else vm.startReplay() },
    )
}

/** @see rememberSheetActions */
class SheetActions(
    val onSnapshot: () -> Unit,
    val onToggleRecording: () -> Unit,
    val onRotate: () -> Unit,
    val onToggleMirror: () -> Unit,
    val onToggleDenoise: () -> Unit,
    val onToggleStats: () -> Unit,
    val onSettings: () -> Unit,
    val onAbout: () -> Unit,
    val onToggleReplay: () -> Unit,
)

@Composable
private fun Sheet(ui: UiState, wide: Boolean, onInteract: () -> Unit, actions: SheetActions) {
    ControlsSheet(
        ui = ui,
        wide = wide,
        onInteract = onInteract,
        onSnapshot = actions.onSnapshot,
        onToggleRecording = actions.onToggleRecording,
        onRotate = actions.onRotate,
        onToggleMirror = actions.onToggleMirror,
        onToggleDenoise = actions.onToggleDenoise,
        onToggleStats = actions.onToggleStats,
        onSettings = actions.onSettings,
        onAbout = actions.onAbout,
        onToggleReplay = actions.onToggleReplay,
    )
}

/** The picture, or the one line that explains why there isn't one. */
@Composable
private fun LiveContent(ui: UiState) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val img = ui.image
        if (img != null) {
            Image(
                bitmap = img.asImageBitmap(),
                contentDescription = stringResource(R.string.cd_live_view),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        } else {
            val text = when (val c = ui.session.connection) {
                is ConnectionState.NoDevice -> stringResource(R.string.live_view_no_device)
                is ConnectionState.Connecting -> stringResource(R.string.live_view_connecting)
                is ConnectionState.Streaming -> stringResource(R.string.live_view_waiting_first_frame)
                is ConnectionState.Failed ->
                    if (ui.permissionDevice != null) stringResource(R.string.live_view_permission_needed)
                    else stringResource(R.string.live_view_error_format, c.message)
            }
            Text(text, color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(24.dp))
        }
    }
}

@Composable
private fun StatsOverlay(st: StreamStats) {
    Text(
        stringResource(
            R.string.stats_overlay_format,
            st.fps, st.framesEmitted, st.framesPartial, st.framesDropped, st.bytesReceived / 1e6,
        ),
        color = Color(0xFF00FF66),
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.background(Color(0x99000000)).padding(6.dp),
    )
}

/**
 * Stand-in for the real settings list, which Task 4 adds. Present so the Settings button in the
 * controls sheet navigates somewhere it can also come back from.
 */
@Composable
private fun SettingsPlaceholder(onBack: () -> Unit) {
    BackHandler(enabled = true, onBack = onBack)
    Column(Modifier.fillMaxSize()) {
        Surface(tonalElevation = 2.dp) {
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.titleLarge)
                OutlinedButton(onClick = onBack, modifier = Modifier.padding(top = 8.dp)) {
                    Text(stringResource(R.string.action_back))
                }
            }
        }
        Text(stringResource(R.string.settings_coming_soon), Modifier.padding(24.dp), style = MaterialTheme.typography.bodyMedium)
    }
}
