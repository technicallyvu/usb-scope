package com.technicallyvu.scope.ui

import android.app.Application
import android.graphics.Bitmap
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
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
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.concurrent.thread

data class UiState(
    val session: SessionState = SessionState(),
    val image: Bitmap? = null,
    val showStats: Boolean = false,
    val permissionDevice: UsbDevice? = null,
    val message: String? = null,
    val replaying: Boolean = false,
)

class ScopeViewModel(app: Application) : AndroidViewModel(app) {
    private val usbManager = app.getSystemService(UsbManager::class.java)
    private val usbDevices = AndroidDeviceSource(usbManager)
    private val saver = MediaStoreSaver(app.contentResolver)

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    @Volatile private lateinit var session: ScopeSession
    private var stateJob: Job? = null
    @Volatile private var lastFrame: FrameData? = null
    @Volatile private var lastImage: Bitmap? = null
    private val recorderLock = Any()
    // Mutated only under recorderLock; @Volatile so the state collector can read "is anything
    // recording?" from the main thread without taking the lock.
    @Volatile private var recorder: SurfaceRecorder? = null
    @Volatile private var pendingVideo: MediaStoreSaver.PendingVideo? = null
    private var recorderGeneration = 0L
    /** Guards against a second replay start/stop while the previous session is still winding down. */
    private var switchingSession = false
    /** Identifies the current session's sink so a stale sink from a swapped-out session (see
     * [attach]) drops frames instead of racing the new one. Mutated only from [attach] on Main. */
    @Volatile private var currentSessionToken = 0L
    private var nextSessionToken = 0L

    init {
        attach(usbDevices, replaying = false)
    }

    /**
     * A per-session [FrameSink]. [attach] mints a fresh one (with its own [FrameBitmaps]) for
     * every [ScopeSession] it creates; [token] lets it recognize when its session has been swapped
     * out (e.g. by a replay start/stop) and ignore any frames/button events still in flight from
     * the old session's worker thread.
     */
    private inner class SessionSink(private val token: Long, private val bitmaps: FrameBitmaps) : FrameSink {
        override fun onFrame(frame: Frame, state: SessionState) {
            if (token != currentSessionToken) return
            lastFrame = frame.data
            val bmp = bitmaps.toBitmap(frame.data) ?: return
            val shown = bitmaps.transform(bmp, state.rotation, state.mirror)
            lastImage = shown
            synchronized(recorderLock) {
                recorder?.let { rec -> runCatching { rec.record(shown) }.onFailure { stopRecordingLocked(keep = true) } }
            }
            _ui.update { it.copy(image = shown) }
        }

        override fun onButtonSnapshot() {
            if (token != currentSessionToken) return
            snapshot()
        }
    }

    /**
     * Cancels the previous state collector (if any), builds a fresh [ScopeSession] over [devices]
     * with its own [SessionSink] (and thus its own [FrameBitmaps] and identity token), stores it as
     * the current [session], starts it, and launches exactly one collector that mirrors its state
     * into [_ui]. The USB-permission device is surfaced only while a live (non-replay) session is
     * active.
     */
    private fun attach(devices: DeviceSource, replaying: Boolean) {
        stateJob?.cancel()
        val token = ++nextSessionToken
        currentSessionToken = token
        val newSession = ScopeSession(devices, DriverRegistry.all, viewModelScope, sink = SessionSink(token, FrameBitmaps()))
        session = newSession
        _ui.update { it.copy(replaying = replaying) }
        newSession.start()
        stateJob = viewModelScope.launch {
            newSession.state.collect { s ->
                val streaming = s.connection is ConnectionState.Streaming
                // Anything but Streaming means the last frame is stale (unplug, error, reconnect):
                // drop it so the view falls back to its status text instead of a frozen picture.
                if (!streaming) { lastImage = null; lastFrame = null }
                _ui.update {
                    it.copy(
                        session = s,
                        image = if (streaming) it.image else null,
                        permissionDevice = if (!replaying && s.connection is ConnectionState.Failed) usbDevices.pendingPermission else null,
                    )
                }
                // Off the main thread: stopping the recorder takes recorderLock and calls into
                // MediaRecorder/MediaStore, neither of which is quick.
                if (!s.recording && recorder != null) viewModelScope.launch(Dispatchers.IO) { stopRecordingIfActive() }
            }
        }
    }

    // ---- actions (main thread) ----
    fun rotate() = session.rotate()
    fun toggleMirror() = session.toggleMirror()
    fun toggleStats() = _ui.update { it.copy(showStats = !it.showStats) }

    fun snapshot() {
        // Both are published together in onFrame, so they describe the same frame.
        val data = lastFrame ?: return
        val shown = lastImage ?: return
        val s = session.state.value
        val name = "SCOPE_" + LocalDateTime.now().format(STAMP) + ".jpg"
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                when (data) {
                    // Original bytes plus an EXIF orientation tag: no re-encode, no quality loss.
                    is FrameData.Jpeg -> saver.saveJpeg(data.bytes, ExifOrientation.of(s.rotation, s.mirror), name)
                    // Compress the bitmap the user is looking at. Never re-convert: FrameBitmaps
                    // reuses its pixel buffer for the worker's next frame. Bitmaps out of
                    // Bitmap.createBitmap are immutable, so compressing here while the worker
                    // moves on is safe.
                    is FrameData.Yuyv422 -> saver.saveBitmapJpeg(shown, name)
                }
            }.onSuccess { session.markSaved(name) }
             .onFailure { e -> _ui.update { it.copy(message = "Snapshot failed: ${e.message}") } }
        }
    }

    /**
     * Guards a start or stop transition in flight so a second tap (e.g. a fast double-tap on
     * Stop) is ignored instead of racing the in-progress one. Set on Main before launching the
     * transition, cleared in a `finally` once the launched coroutine finishes.
     */
    @Volatile private var recordingTransition = false

    fun toggleRecording() {
        if (recordingTransition) return
        val active = recorder != null
        recordingTransition = true
        // Both start and stop touch MediaRecorder/MediaStore (and, for start, SurfaceRecorder
        // construction); keep all of it off the main thread.
        if (active) {
            viewModelScope.launch(Dispatchers.IO) {
                try { stopRecordingIfActive() } finally { recordingTransition = false }
            }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val img = lastImage ?: return@launch
                val generation = synchronized(recorderLock) { recorderGeneration }
                val name = "SCOPE_" + LocalDateTime.now().format(STAMP) + ".mp4"
                val video = runCatching { saver.createVideo(name) }.getOrElse { e -> _ui.update { it.copy(message = "Recording could not start: ${e.message}") }; return@launch }
                val created = runCatching { SurfaceRecorder(getApplication(), video.fd, img.width, img.height) }
                    .getOrElse { e -> saver.finishVideo(video, keep = false); _ui.update { it.copy(message = "Recording could not start: ${e.message}") }; return@launch }
                synchronized(recorderLock) {
                    if (recorder != null || recorderGeneration != generation) { runCatching { created.close() }; saver.finishVideo(video, keep = false); return@launch }
                    recorder = created
                    pendingVideo = video
                }
                session.setRecording(true)
                // "Saved" is reported by stopRecordingLocked, once the clip is actually finalised and kept.
            } finally {
                recordingTransition = false
            }
        }
    }

    private fun stopRecordingIfActive() = synchronized(recorderLock) { stopRecordingLocked(keep = true) }

    private fun stopRecordingLocked(keep: Boolean) {
        val rec = recorder ?: return
        val video = pendingVideo
        recorder = null
        pendingVideo = null
        recorderGeneration++
        runCatching { rec.close() }
        if (video != null) {
            // A clip is only playable when frames were written AND the encoder stopped cleanly
            // (a failed stop means the MP4 was never finalised).
            val kept = keep && rec.framesWritten > 0 && !rec.stopFailed
            saver.finishVideo(video, keep = kept)
            if (kept) session.markSaved(video.displayName)
        }
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
        if (!BuildConfig.DEBUG || _ui.value.replaying || switchingSession) return
        val app = getApplication<Application>()
        switchSession {
            val info = UsbDeviceInfo(0x2CE3, 0x3828, 0xEF, listOf(UsbInterfaceInfo(0, 0xFF, 0xF0, 1)))
            val replay = ReplayDeviceSource({ app.assets.open("fixtures/i4season-yuv-320x240.upkt") }, info, videoEndpoint = I4seasonYuvDriver.EP_IN)
            attach(replay, replaying = true)
        }
    }

    fun stopReplay() {
        if (!_ui.value.replaying || switchingSession) return
        switchSession { attach(usbDevices, replaying = false) }
    }

    /**
     * Tears the current session down off the main thread, then runs [start] (which calls [attach])
     * back on Main. Nothing here may block Main: the session join waits on an in-flight USB read.
     */
    private fun switchSession(start: () -> Unit) {
        switchingSession = true
        viewModelScope.launch {
            try {
                val joined = withContext(Dispatchers.IO) { session.stopAndJoin() }
                if (!joined) Log.w(TAG, "session did not stop in time")
                lastImage = null
                lastFrame = null
                start()
                _ui.update { it.copy(image = null) }
            } finally {
                switchingSession = false
            }
        }
    }

    override fun onCleared() {
        // The session join blocks until the current USB read returns; never on Main.
        val closing = session
        thread(name = "scope-shutdown") {
            stopRecordingIfActive()
            closing.stop()
        }
        super.onCleared()
    }

    companion object {
        private const val TAG = "ScopeViewModel"
        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.ROOT)
    }
}
