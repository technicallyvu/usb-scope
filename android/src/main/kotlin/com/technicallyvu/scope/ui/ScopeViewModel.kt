package com.technicallyvu.scope.ui

import android.app.Application
import android.graphics.Bitmap
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.technicallyvu.scope.BuildConfig
import com.technicallyvu.scope.core.driver.DriverRegistry
import com.technicallyvu.scope.core.driver.Frame
import com.technicallyvu.scope.core.driver.FrameData
import com.technicallyvu.scope.core.fixture.ReplayDeviceSource
import com.technicallyvu.scope.core.i4season.I4seasonYuvDriver
import com.technicallyvu.scope.core.image.ExifOrientation
import com.technicallyvu.scope.core.session.ConnectionState
import com.technicallyvu.scope.core.session.FrameSink
import com.technicallyvu.scope.core.session.ScopeSession
import com.technicallyvu.scope.core.session.SessionState
import com.technicallyvu.scope.core.usb.DeviceSource
import com.technicallyvu.scope.core.usb.UsbDeviceInfo
import com.technicallyvu.scope.core.usb.UsbInterfaceInfo
import com.technicallyvu.scope.media.FrameBitmaps
import com.technicallyvu.scope.media.MediaStoreSaver
import com.technicallyvu.scope.media.SurfaceRecorder
import com.technicallyvu.scope.usb.AndroidDeviceSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

data class UiState(
    val session: SessionState = SessionState(),
    val image: Bitmap? = null,
    val showStats: Boolean = false,
    val permissionDevice: UsbDevice? = null,
    val message: String? = null,
    val replaying: Boolean = false,
)

class ScopeViewModel(app: Application) : AndroidViewModel(app), FrameSink {
    private val usbManager = app.getSystemService(UsbManager::class.java)
    private val usbDevices = AndroidDeviceSource(usbManager)
    private val saver = MediaStoreSaver(app.contentResolver)
    private val bitmaps = FrameBitmaps()

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    private lateinit var session: ScopeSession
    private var stateJob: Job? = null
    @Volatile private var lastFrame: FrameData? = null
    @Volatile private var lastImage: Bitmap? = null
    private val recorderLock = Any()
    private var recorder: SurfaceRecorder? = null
    private var pendingVideo: MediaStoreSaver.PendingVideo? = null
    private var recorderGeneration = 0L

    init {
        attach(newSession(usbDevices), replaying = false)
    }

    private fun newSession(devices: DeviceSource) = ScopeSession(devices, DriverRegistry.all, viewModelScope, sink = this)

    /**
     * Cancels the previous state collector (if any), swaps in [newSessionInstance] as the current
     * [session], starts it, and launches exactly one collector that mirrors its state into [_ui].
     * The USB-permission device is surfaced only while a live (non-replay) session is active.
     */
    private fun attach(newSessionInstance: ScopeSession, replaying: Boolean) {
        stateJob?.cancel()
        session = newSessionInstance
        _ui.update { it.copy(replaying = replaying) }
        session.start()
        stateJob = viewModelScope.launch {
            session.state.collect { s ->
                _ui.update {
                    it.copy(
                        session = s,
                        permissionDevice = if (!replaying && s.connection is ConnectionState.Failed) usbDevices.pendingPermission else null,
                    )
                }
                if (!s.recording) stopRecordingIfActive()
            }
        }
    }

    // ---- FrameSink (worker thread) ----
    override fun onFrame(frame: Frame, state: SessionState) {
        lastFrame = frame.data
        val bmp = bitmaps.toBitmap(frame.data) ?: return
        val shown = bitmaps.transform(bmp, state.rotation, state.mirror)
        lastImage = shown
        synchronized(recorderLock) {
            recorder?.let { rec -> runCatching { rec.record(shown) }.onFailure { stopRecordingLocked(keep = true) } }
        }
        _ui.update { it.copy(image = shown) }
    }

    override fun onButtonSnapshot() = snapshot()

    // ---- actions (main thread) ----
    fun rotate() = session.rotate()
    fun toggleMirror() = session.toggleMirror()
    fun toggleStats() = _ui.update { it.copy(showStats = !it.showStats) }

    fun snapshot() {
        val data = lastFrame ?: return
        val s = session.state.value
        val name = "SCOPE_" + LocalDateTime.now().format(STAMP) + ".jpg"
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                when (data) {
                    is FrameData.Jpeg -> saver.saveJpeg(data.bytes, ExifOrientation.of(s.rotation, s.mirror), name)
                    is FrameData.Yuyv422 -> saver.saveBitmapJpeg(bitmaps.transform(bitmaps.toBitmap(data)!!, s.rotation, s.mirror), name)
                }
            }.onSuccess { session.markSaved(name) }
             .onFailure { e -> _ui.update { it.copy(message = "Snapshot failed: ${e.message}") } }
        }
    }

    fun toggleRecording() {
        val active = synchronized(recorderLock) { recorder != null }
        if (active) { stopRecordingIfActive(); return }
        val img = lastImage ?: return
        val generation = synchronized(recorderLock) { recorderGeneration }
        val name = "SCOPE_" + LocalDateTime.now().format(STAMP) + ".mp4"
        val video = runCatching { saver.createVideo(name) }.getOrElse { e -> _ui.update { it.copy(message = "Recording could not start: ${e.message}") }; return }
        val created = runCatching { SurfaceRecorder(getApplication(), video.fd, img.width, img.height) }
            .getOrElse { e -> saver.finishVideo(video, keep = false); _ui.update { it.copy(message = "Recording could not start: ${e.message}") }; return }
        synchronized(recorderLock) {
            if (recorder != null || recorderGeneration != generation) { runCatching { created.close() }; saver.finishVideo(video, keep = false); return }
            recorder = created
            pendingVideo = video
        }
        session.setRecording(true)
        session.markSaved(name)
    }

    private fun stopRecordingIfActive() = synchronized(recorderLock) { stopRecordingLocked(keep = true) }

    private fun stopRecordingLocked(keep: Boolean) {
        val rec = recorder ?: return
        val video = pendingVideo
        recorder = null
        pendingVideo = null
        recorderGeneration++
        runCatching { rec.close() }
        if (video != null) saver.finishVideo(video, keep = keep && rec.framesWritten > 0)
        session.setRecording(false)
    }

    // ---- USB events from the activity ----
    fun onDeviceDetached(deviceName: String) = usbDevices.onDetached(deviceName)

    fun onPermissionResult(granted: Boolean) {
        usbDevices.pendingPermission = null
        _ui.update { it.copy(permissionDevice = null, message = if (granted) null else "USB permission denied") }
    }

    fun clearMessage() = _ui.update { it.copy(message = null) }

    // ---- debug replay ----
    fun startReplay() {
        if (!BuildConfig.DEBUG || _ui.value.replaying) return
        session.stop()
        val app = getApplication<Application>()
        val info = UsbDeviceInfo(0x2CE3, 0x3828, 0xEF, listOf(UsbInterfaceInfo(0, 0xFF, 0xF0, 1)))
        val replay = ReplayDeviceSource({ app.assets.open("fixtures/i4season-yuv-320x240.upkt") }, info, videoEndpoint = I4seasonYuvDriver.EP_IN)
        attach(newSession(replay), replaying = true)
        _ui.update { it.copy(image = null) }
    }

    fun stopReplay() {
        if (!_ui.value.replaying) return
        session.stop()
        attach(newSession(usbDevices), replaying = false)
        _ui.update { it.copy(image = null) }
    }

    override fun onCleared() {
        session.stop()
        stopRecordingIfActive()
        super.onCleared()
    }

    companion object {
        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.ROOT)
    }
}
