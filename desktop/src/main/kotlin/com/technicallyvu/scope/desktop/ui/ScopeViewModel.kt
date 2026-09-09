package com.technicallyvu.scope.desktop.ui

import com.technicallyvu.scope.core.driver.DeviceDriver
import com.technicallyvu.scope.core.driver.Frame
import com.technicallyvu.scope.core.driver.FrameSource
import com.technicallyvu.scope.core.driver.StreamStats
import com.technicallyvu.scope.core.usb.DeviceRef
import com.technicallyvu.scope.core.usb.DeviceSource
import com.technicallyvu.scope.core.usb.UsbException
import com.technicallyvu.scope.desktop.media.ImageTransforms
import com.technicallyvu.scope.desktop.media.Mp4Recorder
import com.technicallyvu.scope.desktop.media.SnapshotWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

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
    /** Sensor is mounted sideways; 90 shows the picture upright for most units. */
    val rotation: Int = 90,
    val mirror: Boolean = false,
    val stats: StreamStats = StreamStats(),
    val recording: Boolean = false,
    val showDebug: Boolean = false,
    val lastSaved: String? = null,
)

/**
 * Owns the device session loop and every user action. UI-toolkit free so it is testable headless.
 * Runs on Dispatchers.Default; state is exposed as a StateFlow.
 * Action methods may be called from any thread; recording state is guarded by [recorderLock].
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
    private var job: Job? = null
    @Volatile private var lastJpeg: ByteArray? = null
    @Volatile private var lastImage: BufferedImage? = null
    @Volatile private var recorder: Mp4Recorder? = null
    @Volatile private var prevButton = false
    private var lastButtonSnapNanos = Long.MIN_VALUE / 2

    fun start() {
        if (job != null) return
        job = scope.launch(Dispatchers.Default) {
            while (isActive) {
                val found = findDevice()
                if (found == null) {
                    _state.update { it.copy(connection = ConnectionState.NoDevice(driverHintCheck()), image = null) }
                } else {
                    session(found.first, found.second)
                }
                delay(pollMillis)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        stopRecording()
    }

    fun snapshot() {
        val jpeg = lastJpeg ?: return
        val s = _state.value
        val path = SnapshotWriter.write(jpeg, s.rotation, s.mirror, s.outputDir)
        _state.update { it.copy(lastSaved = path.fileName.toString()) }
    }

    fun toggleRecording() {
        synchronized(recorderLock) { if (recorder != null) stopRecordingLocked() else startRecordingLocked() }
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

    fun setOutputDir(dir: Path) = _state.update { it.copy(outputDir = dir) }

    private fun findDevice(): Pair<DeviceRef, DeviceDriver>? = try {
        devices.list().firstNotNullOfOrNull { ref -> drivers.firstOrNull { it.matches(ref.info) }?.let { ref to it } }
    } catch (e: UsbException) {
        null
    }

    private suspend fun session(ref: DeviceRef, driver: DeviceDriver) {
        _state.update { it.copy(connection = ConnectionState.Connecting(driver.displayName)) }
        var source: FrameSource? = null
        try {
            val transport = devices.open(ref)
            source = driver.open(transport)
            _state.update { it.copy(connection = ConnectionState.Streaming(driver.displayName)) }
            val src = source
            src.frames.collect { frame -> onFrame(frame, src.stats.value) }
        } catch (e: UsbException) {
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
        } finally {
            stopRecording()
            source?.close()
            lastJpeg = null
            lastImage = null
            prevButton = false
        }
    }

    private fun onFrame(frame: Frame, stats: StreamStats) {
        val decoded = ImageTransforms.decodeJpeg(frame.jpeg) ?: return
        val s = _state.value
        val shown = ImageTransforms.apply(decoded, s.rotation, s.mirror)
        lastJpeg = frame.jpeg
        lastImage = shown
        synchronized(recorderLock) { recorder?.record(shown, frame.timestampNanos) }
        if (frame.buttonPressed && !prevButton && frame.timestampNanos - lastButtonSnapNanos > BUTTON_DEBOUNCE_NANOS) {
            lastButtonSnapNanos = frame.timestampNanos
            snapshot()
        }
        prevButton = frame.buttonPressed
        _state.update { it.copy(image = shown, stats = stats) }
    }

    private fun startRecordingLocked() {
        val img = lastImage ?: return
        val s = _state.value
        Files.createDirectories(s.outputDir)
        val file = s.outputDir.resolve("SCOPE_" + LocalDateTime.now().format(FILE_STAMP) + ".mp4")
        recorder = try {
            recorderFactory(file, img.width, img.height)
        } catch (e: Exception) {
            System.err.println("Recording could not start: ${e.message}")
            return
        }
        _state.update { it.copy(recording = true, lastSaved = file.fileName.toString()) }
    }

    private fun stopRecordingLocked() {
        recorder?.let { runCatching { it.close() } }
        recorder = null
        _state.update { it.copy(recording = false) }
    }

    private fun stopRecording() = synchronized(recorderLock) { stopRecordingLocked() }

    companion object {
        const val BUTTON_DEBOUNCE_NANOS = 300_000_000L
        val FILE_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
    }
}
