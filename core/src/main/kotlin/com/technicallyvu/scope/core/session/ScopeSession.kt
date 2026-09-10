package com.technicallyvu.scope.core.session

import com.technicallyvu.scope.core.driver.DeviceDriver
import com.technicallyvu.scope.core.driver.Frame
import com.technicallyvu.scope.core.driver.FrameData
import com.technicallyvu.scope.core.driver.FrameSource
import com.technicallyvu.scope.core.driver.StreamStats
import com.technicallyvu.scope.core.image.TemporalDenoiser
import com.technicallyvu.scope.core.usb.DeviceRef
import com.technicallyvu.scope.core.usb.DeviceSource
import com.technicallyvu.scope.core.usb.UsbException
import com.technicallyvu.scope.core.usb.UsbTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
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

sealed interface ConnectionState {
    data class NoDevice(val needsDriverHint: Boolean) : ConnectionState
    data class Connecting(val name: String) : ConnectionState
    data class Streaming(val name: String) : ConnectionState
    data class Failed(val message: String) : ConnectionState
}

data class SessionState(
    val connection: ConnectionState = ConnectionState.NoDevice(false),
    val rotation: Int = 0,
    val mirror: Boolean = false,
    val stats: StreamStats = StreamStats(),
    val recording: Boolean = false,
    val lastSaved: String? = null,
    val denoise: Boolean = true,
)

/**
 * Receives frames on the session's worker thread. Implementations must be fast and must not block.
 * If a call throws, the session drops that frame (or button event) and continues; it does not end
 * the stream. The sink owns its own error reporting.
 */
interface FrameSink {
    fun onFrame(frame: Frame, state: SessionState)
    /** The cable button was pressed (debounced rising edge). */
    fun onButtonSnapshot()
}

/**
 * Platform-free session loop shared by shells: polls [devices], opens the first device a driver
 * claims, streams frames to [sink], and reconnects after errors. Owns rotation/mirror and the
 * recording flag (which locks them); the shell owns snapshot/recording implementations.
 */
class ScopeSession(
    private val devices: DeviceSource,
    private val drivers: List<DeviceDriver>,
    private val scope: CoroutineScope,
    private val sink: FrameSink,
    private val driverHintCheck: () -> Boolean = { false },
    private val pollMillis: Long = 1000,
    private val workDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val _state = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = _state

    @Volatile private var job: Job? = null
    @Volatile private var denoiser: TemporalDenoiser? = TemporalDenoiser()
    // The following are only ever read/written from the session coroutine (started in `start()`,
    // confined to `workDispatcher`), so they need no synchronization of their own.
    private var lastDriverId: String? = null
    private var prevButton = false
    private var lastButtonNanos: Long? = null

    fun start() {
        if (job != null) return
        job = scope.launch(workDispatcher) {
            while (isActive) {
                try {
                    val found = findDevice()
                    if (found == null) {
                        _state.update { it.copy(connection = ConnectionState.NoDevice(driverHintCheck())) }
                    } else {
                        session(found.first, found.second)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _state.update { it.copy(connection = ConnectionState.Failed(e.message ?: e.javaClass.simpleName)) }
                }
                delay(pollMillis)
            }
        }
    }

    /**
     * Cancels the loop and suspends up to [timeoutMillis] waiting for the transport to close.
     * Returns true if the join completed within the timeout, false if it timed out. Preferred over
     * [stop] on Android, where nothing may block the main thread.
     *
     * On a timeout, [job] is deliberately left set rather than nulled, so a subsequent [start]
     * call is a no-op instead of racing a second loop against the one still winding down.
     */
    suspend fun stopAndJoin(timeoutMillis: Long = 3_000): Boolean {
        val j = job ?: return true
        j.cancel()
        val joined = withTimeoutOrNull(timeoutMillis) { j.join() } != null
        if (joined) job = null
        return joined
    }

    /**
     * Cancels the loop and blocks the calling thread up to [timeoutMillis] waiting for the
     * transport to close. Returns true if the join completed within the timeout, false if it
     * timed out. Safe to call from any thread; on Android call it off the main thread or from a
     * lifecycle teardown that tolerates blocking — otherwise use [stopAndJoin].
     */
    fun stop(timeoutMillis: Long = 3_000): Boolean = runBlocking { stopAndJoin(timeoutMillis) }

    fun rotate() = _state.update { if (it.recording) it else it.copy(rotation = (it.rotation + 90) % 360) }
    fun toggleMirror() = _state.update { if (it.recording) it else it.copy(mirror = !it.mirror) }
    fun setRecording(active: Boolean) = _state.update { it.copy(recording = active) }
    fun markSaved(name: String) = _state.update { it.copy(lastSaved = name) }

    fun setDenoise(enabled: Boolean) {
        denoiser = if (enabled) TemporalDenoiser() else null
        _state.update { it.copy(denoise = enabled) }
    }

    private fun findDevice(): Pair<DeviceRef, DeviceDriver>? = try {
        devices.list().firstNotNullOfOrNull { ref -> drivers.firstOrNull { it.matches(ref.info) }?.let { ref to it } }
    } catch (e: UsbException) {
        null
    }

    private suspend fun session(ref: DeviceRef, driver: DeviceDriver) {
        _state.update { it.copy(connection = ConnectionState.Connecting(driver.displayName)) }
        var transport: UsbTransport? = null
        var source: FrameSource? = null
        try {
            transport = devices.open(ref)
            source = driver.open(transport)
            // Only reset rotation/mirror to the driver default once the device has actually opened,
            // so a device that keeps failing to open does not wipe the user's rotation every poll.
            if (driver.id != lastDriverId) _state.update { it.copy(rotation = driver.defaultRotation, mirror = false) }
            lastDriverId = driver.id
            denoiser?.reset()
            _state.update { it.copy(connection = ConnectionState.Streaming(driver.displayName)) }
            val src = source
            src.frames.collect { frame -> onFrame(frame, src.stats.value) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: UsbException) {
            val wasStreaming = _state.value.connection is ConnectionState.Streaming
            _state.update {
                it.copy(connection = if (wasStreaming) ConnectionState.NoDevice(false) else if (driverHintCheck()) ConnectionState.NoDevice(true) else ConnectionState.Failed(e.message ?: "USB error"))
            }
        } catch (e: Exception) {
            _state.update { it.copy(connection = ConnectionState.Failed(e.message ?: e.javaClass.simpleName)) }
        } finally {
            _state.update { it.copy(recording = false) }
            source?.let { runCatching { it.close() } }
            if (source == null) transport?.let { runCatching { it.close() } }
            prevButton = false
        }
    }

    private fun onFrame(frame: Frame, stats: StreamStats) {
        _state.update { it.copy(stats = stats) }
        val delivered = denoiser?.let { d ->
            (frame.data as? FrameData.Yuyv422)?.let { yuv ->
                Frame(d.apply(yuv), frame.timestampNanos, frame.buttonPressed, frame.cameraNumber, frame.sensorValue)
            }
        } ?: frame
        // Only Exception is swallowed (never Error, never cancellation): the shell owns its own error reporting.
        try {
            sink.onFrame(delivered, _state.value)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // drop the frame and keep streaming
        }
        val last = lastButtonNanos
        if (frame.buttonPressed && !prevButton && (last == null || frame.timestampNanos - last > BUTTON_DEBOUNCE_NANOS)) {
            lastButtonNanos = frame.timestampNanos
            try {
                sink.onButtonSnapshot()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // drop the button event and keep streaming
            }
        }
        prevButton = frame.buttonPressed
    }

    companion object {
        const val BUTTON_DEBOUNCE_NANOS = 300_000_000L
    }
}
