package com.technicallyvu.scope.desktop.ui

import com.technicallyvu.scope.core.driver.DeviceDriver
import com.technicallyvu.scope.core.driver.Frame
import com.technicallyvu.scope.core.driver.FrameData
import com.technicallyvu.scope.core.driver.FrameSource
import com.technicallyvu.scope.core.driver.StreamStats
import com.technicallyvu.scope.core.image.Sharpener
import com.technicallyvu.scope.core.image.TemporalDenoiser
import com.technicallyvu.scope.core.session.DeviceCandidates
import com.technicallyvu.scope.core.usb.DeviceRef
import com.technicallyvu.scope.core.usb.DeviceSource
import com.technicallyvu.scope.core.usb.UsbException
import com.technicallyvu.scope.core.usb.UsbTransport
import com.technicallyvu.scope.desktop.media.ImageTransforms
import com.technicallyvu.scope.desktop.media.Mp4Recorder
import com.technicallyvu.scope.desktop.media.SnapshotWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

sealed interface ConnectionState {
    data class NoDevice(val needsDriverHint: Boolean) : ConnectionState
    data class Connecting(val name: String) : ConnectionState
    data class Streaming(val name: String) : ConnectionState
    data class Failed(val message: String) : ConnectionState
}

data class UiState(
    val outputDir: Path,
    val connection: ConnectionState = ConnectionState.NoDevice(false),
    val image: BufferedImage? = null,
    /** Sensor is mounted sideways; set from the driver's defaultRotation once a device is found. */
    val rotation: Int = 0,
    val mirror: Boolean = false,
    val stats: StreamStats = StreamStats(),
    val recording: Boolean = false,
    val showDebug: Boolean = false,
    val lastSaved: String? = null,
    val denoise: Boolean = true,
    /** Cosmetic luma unsharp mask, applied after the denoiser. Off by default; it adds no detail. */
    val sharpen: Boolean = false,
)

/**
 * Owns the device session loop and every user action. UI-toolkit free so it is testable headless.
 * Runs on Dispatchers.Default; state is exposed as a StateFlow.
 * Action methods may be called from any thread; recording state is guarded by [recorderLock].
 * The encoder is constructed outside the lock (start-up can take seconds) and only published
 * under [recorderLock] once ready, so building it never stalls frame collection.
 */
class ScopeViewModel(
    private val devices: DeviceSource,
    private val drivers: List<DeviceDriver>,
    private val scope: CoroutineScope,
    outputDir: Path,
    private val driverHintCheck: () -> Boolean = { false },
    private val pollMillis: Long = 1000,
    private val recorderFactory: (Path, Int, Int) -> Mp4Recorder = { f, w, h -> Mp4Recorder(f, w, h) },
) {
    private val _state = MutableStateFlow(UiState(outputDir = outputDir))
    val state: StateFlow<UiState> = _state

    private val recorderLock = Any()
    @Volatile private var job: Job? = null
    @Volatile private var lastFrame: FrameData? = null
    @Volatile private var lastImage: BufferedImage? = null
    @Volatile private var recorder: Mp4Recorder? = null
    private var recorderGeneration = 0L
    @Volatile private var prevButton = false
    private var lastButtonSnapNanos = Long.MIN_VALUE / 2
    /**
     * Timestamp of an unconsumed single press: set when a press takes a snapshot, cleared once a
     * second press within [DOUBLE_PRESS_WINDOW_NANOS] consumes it as a recording toggle (or once a
     * press outside the window replaces it with a new pending press of its own). Mirrors
     * ScopeSession's core double-press logic for the shell that has not yet adopted it.
     */
    private var lastPressNanos: Long? = null
    @Volatile private var yuvDenoiser: TemporalDenoiser? = null
    @Volatile private var argbDenoiser: TemporalDenoiser? = null
    /** Stateless apart from a scratch buffer, so unlike the denoisers it survives across sessions. */
    private val sharpener = Sharpener()
    /** The id of the driver that last successfully streamed; used to avoid resetting rotation on a same-device reconnect. */
    private var lastDriverId: String? = null
    /** Which attached device to try next: skips what can never open, rotates past what just failed. */
    private val candidates = DeviceCandidates(drivers)

    fun start() {
        if (job != null) return
        job = scope.launch(Dispatchers.Default) {
            while (isActive) {
                val found = candidates.next(findCandidates())
                if (found == null) {
                    _state.update { it.copy(connection = ConnectionState.NoDevice(driverHintCheck()), image = null) }
                } else {
                    session(found.first, found.second)
                }
                delay(pollMillis)
            }
        }
    }

    /** Cancels the session loop and waits (bounded) for it to finish, so USB handles can be closed safely. */
    fun stop(timeoutMillis: Long = 3_000) {
        stopRecording()
        val j = job ?: return
        job = null
        j.cancel()
        runBlocking { withTimeoutOrNull(timeoutMillis) { j.join() } }
    }

    /**
     * Saves what the user sees. With denoise or sharpen on that is the filtered picture, which only
     * exists as decoded pixels, so the shown image is re-encoded (rotation/mirror already baked in);
     * with both off a JPEG frame keeps its original bytes and an EXIF orientation tag.
     */
    fun snapshot() {
        val s = _state.value
        val path = try {
            if (s.denoise || s.sharpen) SnapshotWriter.write(lastImage ?: return, s.outputDir)
            else SnapshotWriter.write(lastFrame ?: return, s.rotation, s.mirror, s.outputDir)
        } catch (e: Exception) {
            System.err.println("Snapshot failed: ${e.message}")
            return
        }
        _state.update { it.copy(lastSaved = path.fileName.toString()) }
    }

    /** @return true when this call actually started or stopped a recording (false on a no-op or failure). */
    fun toggleRecording(): Boolean {
        val generation = synchronized(recorderLock) {
            if (recorder != null) {          // lost a race with another start; keep the existing one
                stopRecordingLocked()
                return true
            }
            recorderGeneration
        }
        val img = lastImage ?: return false
        val s = _state.value
        val file = try {
            Files.createDirectories(s.outputDir)
            s.outputDir.resolve("SCOPE_" + LocalDateTime.now().format(FILE_STAMP) + ".mp4")
        } catch (e: Exception) {
            System.err.println("Recording could not start: ${e.message}")
            return false
        }
        val created = try {
            recorderFactory(file, img.width, img.height)
        } catch (e: Throwable) {
            System.err.println("Recording could not start: ${e.message}")
            return false
        }
        synchronized(recorderLock) {
            if (recorder != null || recorderGeneration != generation) {
                runCatching { created.close() }   // a concurrent start or stop won; discard ours
                return false
            }
            recorder = created
        }
        _state.update { it.copy(recording = true, lastSaved = file.fileName.toString()) }
        return true
    }

    fun rotate() {
        if (_state.value.recording) return
        _state.update { it.copy(rotation = (it.rotation + 90) % 360) }
    }

    fun toggleMirror() {
        if (_state.value.recording) return
        _state.update { it.copy(mirror = !it.mirror) }
    }

    fun toggleDebug() = _state.update { it.copy(showDebug = !it.showDebug) }

    /** Re-enabling starts from a clean slate: the stale history is from before the toggle-off. */
    fun toggleDenoise() {
        val enabled = !_state.value.denoise
        if (enabled) { yuvDenoiser?.reset(); argbDenoiser?.reset() }
        _state.update { it.copy(denoise = enabled) }
    }

    /** Cosmetic sharpening on/off. Applied after the denoiser, never before; see `Sharpener`. */
    fun setSharpen(enabled: Boolean) = _state.update { it.copy(sharpen = enabled) }

    fun setOutputDir(dir: Path) = _state.update { it.copy(outputDir = dir) }

    /** Every attached device a driver claims, in enumeration order. A list error means "none, this poll". */
    private fun findCandidates(): List<Pair<DeviceRef, DeviceDriver>> = try {
        candidates.candidates(devices.list())
    } catch (e: UsbException) {
        emptyList()
    }

    private suspend fun session(ref: DeviceRef, driver: DeviceDriver) {
        _state.update { it.copy(connection = ConnectionState.Connecting(driver.displayName)) }
        if (driver.id != lastDriverId) _state.update { it.copy(rotation = driver.defaultRotation) }
        var transport: UsbTransport? = null
        var source: FrameSource? = null
        var opened = false
        try {
            transport = devices.open(ref)
            source = driver.open(transport)
            opened = true
            candidates.onOpened(ref)
            yuvDenoiser = TemporalDenoiser()
            argbDenoiser = TemporalDenoiser()
            _state.update { it.copy(connection = ConnectionState.Streaming(driver.displayName)) }
            lastDriverId = driver.id
            val src = source
            src.frames.collect { frame -> onFrame(frame, src.stats.value) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: UsbException) {
            // Only a failure to *open* rotates the cursor; a stream that ended keeps this device first.
            if (!opened) candidates.onOpenFailed(ref, e)
            val wasStreaming = _state.value.connection is ConnectionState.Streaming
            _state.update {
                it.copy(
                    connection = when {
                        wasStreaming -> ConnectionState.NoDevice(false)
                        driverHintCheck() -> ConnectionState.NoDevice(needsDriverHint = true)
                        else -> ConnectionState.Failed(e.message ?: "USB error")
                    },
                    image = null,
                )
            }
        } catch (e: Exception) {
            System.err.println("Session error: $e")
            _state.update { it.copy(connection = ConnectionState.Failed(e.message ?: e.javaClass.simpleName), image = null) }
        } finally {
            stopRecording()
            source?.close()
            if (source == null) transport?.let { runCatching { it.close() } }
            lastFrame = null
            lastImage = null
            prevButton = false
            lastPressNanos = null
        }
    }

    private fun onFrame(frame: Frame, stats: StreamStats) {
        val s = _state.value
        val decoded = ImageTransforms.decode(
            frame.data,
            if (s.denoise) yuvDenoiser else null,
            if (s.denoise) argbDenoiser else null,
            if (s.sharpen) sharpener else null,
        ) ?: return
        val shown = ImageTransforms.apply(decoded, s.rotation, s.mirror)
        lastFrame = frame.data
        lastImage = shown
        synchronized(recorderLock) {
            recorder?.let { rec ->
                try {
                    rec.record(shown, frame.timestampNanos)
                } catch (e: Exception) {
                    System.err.println("Recording stopped: ${e.message}")
                    stopRecordingLocked()
                }
            }
        }
        if (frame.buttonPressed && !prevButton && frame.timestampNanos - lastButtonSnapNanos > BUTTON_DEBOUNCE_NANOS) {
            lastButtonSnapNanos = frame.timestampNanos
            val prevPress = lastPressNanos
            if (prevPress != null && frame.timestampNanos - prevPress <= DOUBLE_PRESS_WINDOW_NANOS) {
                // Second press of a pair: the first already took its snapshot, this one toggles.
                lastPressNanos = null
                if (toggleRecording()) {
                    val recording = _state.value.recording
                    _state.update { it.copy(lastSaved = if (recording) "Recording started" else "Recording stopped") }
                }
            } else {
                lastPressNanos = frame.timestampNanos
                snapshot()
            }
        }
        prevButton = frame.buttonPressed
        _state.update { it.copy(image = shown, stats = stats) }
    }

    private fun stopRecordingLocked() {
        recorder?.let { runCatching { it.close() } }
        recorder = null
        recorderGeneration++
        _state.update { it.copy(recording = false) }
    }

    private fun stopRecording() = synchronized(recorderLock) { stopRecordingLocked() }

    companion object {
        const val BUTTON_DEBOUNCE_NANOS = 300_000_000L
        /** A second press within this long of the first toggles recording instead of snapshotting again. */
        const val DOUBLE_PRESS_WINDOW_NANOS = 1_500_000_000L
        val FILE_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.ROOT)
    }
}
