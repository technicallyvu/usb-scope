package com.technicallyvu.scope.core.session

import com.technicallyvu.scope.core.driver.DeviceDriver
import com.technicallyvu.scope.core.driver.Frame
import com.technicallyvu.scope.core.driver.FrameSource
import com.technicallyvu.scope.core.driver.StreamStats
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
)

/** Receives frames on the session's worker thread. Implementations must be fast and must not block. */
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
    private var lastDriverId: String? = null
    private var prevButton = false
    private var lastButtonNanos = Long.MIN_VALUE / 2

    fun start() {
        if (job != null) return
        job = scope.launch(workDispatcher) {
            while (isActive) {
                val found = findDevice()
                if (found == null) {
                    _state.update { it.copy(connection = ConnectionState.NoDevice(driverHintCheck())) }
                } else {
                    session(found.first, found.second)
                }
                delay(pollMillis)
            }
        }
    }

    /** Cancels the loop and waits (bounded) until the transport is closed. Safe to call from any thread. */
    fun stop(timeoutMillis: Long = 3_000) {
        val j = job ?: return
        job = null
        j.cancel()
        runBlocking { withTimeoutOrNull(timeoutMillis) { j.join() } }
    }

    fun rotate() = _state.update { if (it.recording) it else it.copy(rotation = (it.rotation + 90) % 360) }
    fun toggleMirror() = _state.update { if (it.recording) it else it.copy(mirror = !it.mirror) }
    fun setRecording(active: Boolean) = _state.update { it.copy(recording = active) }
    fun markSaved(name: String) = _state.update { it.copy(lastSaved = name) }

    private fun findDevice(): Pair<DeviceRef, DeviceDriver>? = try {
        devices.list().firstNotNullOfOrNull { ref -> drivers.firstOrNull { it.matches(ref.info) }?.let { ref to it } }
    } catch (e: UsbException) {
        null
    }

    private suspend fun session(ref: DeviceRef, driver: DeviceDriver) {
        _state.update { it.copy(connection = ConnectionState.Connecting(driver.displayName)) }
        if (driver.id != lastDriverId) _state.update { it.copy(rotation = driver.defaultRotation, mirror = false) }
        var transport: UsbTransport? = null
        var source: FrameSource? = null
        try {
            transport = devices.open(ref)
            source = driver.open(transport)
            lastDriverId = driver.id
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
            source?.close()
            if (source == null) transport?.let { runCatching { it.close() } }
            prevButton = false
        }
    }

    private fun onFrame(frame: Frame, stats: StreamStats) {
        _state.update { it.copy(stats = stats) }
        sink.onFrame(frame, _state.value)
        if (frame.buttonPressed && !prevButton && frame.timestampNanos - lastButtonNanos > BUTTON_DEBOUNCE_NANOS) {
            lastButtonNanos = frame.timestampNanos
            sink.onButtonSnapshot()
        }
        prevButton = frame.buttonPressed
    }

    companion object {
        const val BUTTON_DEBOUNCE_NANOS = 300_000_000L
    }
}
