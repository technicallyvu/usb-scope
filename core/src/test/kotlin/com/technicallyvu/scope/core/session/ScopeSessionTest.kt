package com.technicallyvu.scope.core.session

import com.technicallyvu.scope.core.driver.Frame
import com.technicallyvu.scope.core.driver.FrameData
import com.technicallyvu.scope.core.fixture.LoggedPacket
import com.technicallyvu.scope.core.fixture.ReplayTransport
import com.technicallyvu.scope.core.i4season.I4seasonTestFrames
import com.technicallyvu.scope.core.i4season.I4seasonYuvDriver
import com.technicallyvu.scope.core.usb.DeviceRef
import com.technicallyvu.scope.core.usb.DeviceSource
import com.technicallyvu.scope.core.usb.UsbDeviceInfo
import com.technicallyvu.scope.core.usb.UsbException
import com.technicallyvu.scope.core.usb.UsbInterfaceInfo
import com.technicallyvu.scope.core.usb.UsbTransport
import com.technicallyvu.scope.core.uvc.UvcUnsupportedException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class ScopeSessionTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val yuvInfo = UsbDeviceInfo(0x2CE3, 0x3828, 0xEF, listOf(UsbInterfaceInfo(0, 0xFF, 0xF0, 1)))
    private val ref = DeviceRef(yuvInfo, 0, 1)
    // Released in tearDown() so a StuckTransport's blocked bulkRead thread doesn't wedge the
    // shared Default dispatcher pool after the test that created it finishes.
    private val stuckLatch = CountDownLatch(1)

    private class FakeDevices(var refs: List<DeviceRef>, var opener: () -> UsbTransport) : DeviceSource {
        override fun list() = refs
        override fun open(ref: DeviceRef) = opener()
    }

    /** [DeviceSource] whose [list] throws for the first [failures] calls, then returns [refs]. */
    private class FlakyListDevices(
        private val failures: Int,
        private val refs: List<DeviceRef>,
        private val opener: () -> UsbTransport,
    ) : DeviceSource {
        private val calls = AtomicInteger()
        override fun list(): List<DeviceRef> {
            if (calls.getAndIncrement() < failures) throw IllegalStateException("binder")
            return refs
        }
        override fun open(ref: DeviceRef) = opener()
    }

    /**
     * [DeviceSource] that opens each [DeviceRef] differently and counts the opens per ref, so a test
     * can make one device refuse to open and check that the session moves on to the next one.
     */
    private class PerRefDevices(private val refs: List<DeviceRef>, private val opener: (DeviceRef) -> UsbTransport) : DeviceSource {
        private val counts = ConcurrentHashMap<DeviceRef, AtomicInteger>()
        override fun list() = refs
        override fun open(ref: DeviceRef): UsbTransport {
            counts.computeIfAbsent(ref) { AtomicInteger() }.incrementAndGet()
            return opener(ref)
        }
        fun opens(ref: DeviceRef): Int = counts[ref]?.get() ?: 0
    }

    /** Delegates every call to [delegate] except [bulkRead], which throws once [endNow] is set. */
    private class EndableTransport(private val delegate: UsbTransport) : UsbTransport by delegate {
        @Volatile var endNow = false
        override fun bulkRead(endpoint: Int, buffer: ByteArray, timeoutMs: Int): Int {
            if (endNow) throw UsbException("ended")
            return delegate.bulkRead(endpoint, buffer, timeoutMs)
        }
    }

    /**
     * A transport that wedges: every [bulkRead] blocks far longer than any stop timeout (until
     * [latch] is counted down, or 5s pass) and [close] does nothing, so the frame source cannot
     * take its io lock and the session job outlives a short join.
     */
    private class StuckTransport(private val delegate: UsbTransport, private val latch: CountDownLatch) : UsbTransport by delegate {
        override fun bulkRead(endpoint: Int, buffer: ByteArray, timeoutMs: Int): Int {
            latch.await(5, TimeUnit.SECONDS)
            return 0
        }
        override fun close() { /* deliberately ignored */ }
    }

    private class CountingSink : FrameSink {
        val frames = AtomicInteger(); val buttons = AtomicInteger(); val starts = AtomicInteger()
        val toggles = AtomicInteger()
        @Volatile var lastState: SessionState? = null
        @Volatile var lastFrame: Frame? = null
        @Volatile var frameBeforeStart = false
        override fun onFrame(frame: Frame, state: SessionState) {
            if (starts.get() == 0) frameBeforeStart = true
            frames.incrementAndGet(); lastState = state; lastFrame = frame
        }
        override fun onButtonSnapshot() { buttons.incrementAndGet() }
        override fun onButtonRecordToggle() { toggles.incrementAndGet() }
        override fun onStreamStarted() { starts.incrementAndGet() }
    }

    private fun stream(
        frames: Int,
        buttonOn: Set<Int> = emptySet(),
        loop: Boolean = false,
        fill: (Int) -> Byte = { it.toByte() },
    ): ReplayTransport {
        val bytes = (1..frames).fold(ByteArray(0)) { acc, i -> acc + I4seasonTestFrames.frame(4, 2, flags = if (i in buttonOn) 0x02 else 0, fill = fill(i)) }
        return ReplayTransport(I4seasonTestFrames.chunked(bytes, 100), loop = loop, sleep = Thread::sleep, videoEndpoint = I4seasonYuvDriver.EP_IN).apply {
            controlResponses[0xA0 to 0x00] = I4seasonTestFrames.info(4, 2)
        }
    }

    private fun session(devices: DeviceSource, sink: FrameSink, hint: () -> Boolean = { false }) =
        ScopeSession(devices, listOf(I4seasonYuvDriver(ioDispatcher = Dispatchers.Default)), scope, sink, driverHintCheck = hint, pollMillis = 50)

    /**
     * A stream whose chunks are exactly one frame's worth of bytes ([FRAME_BYTES]), so each
     * [ReplayTransport.bulkRead] emits exactly one frame and therefore one [clock] tick -- letting a
     * test place button presses at exact, controlled gaps regardless of real elapsed time.
     */
    private fun frameSizedStream(frames: Int, buttonOn: Set<Int>): ReplayTransport {
        val bytes = (1..frames).fold(ByteArray(0)) { acc, i -> acc + I4seasonTestFrames.frame(4, 2, flags = if (i in buttonOn) 0x02 else 0) }
        return ReplayTransport(I4seasonTestFrames.chunked(bytes, FRAME_BYTES), sleep = Thread::sleep, videoEndpoint = I4seasonYuvDriver.EP_IN).apply {
            controlResponses[0xA0 to 0x00] = I4seasonTestFrames.info(4, 2)
        }
    }

    private fun sessionWithClock(devices: DeviceSource, sink: FrameSink, clock: () -> Long) =
        ScopeSession(devices, listOf(I4seasonYuvDriver(clock = clock, ioDispatcher = Dispatchers.Default)), scope, sink, pollMillis = 50)

    @AfterEach fun tearDown() {
        stuckLatch.countDown()
        scope.cancel()
    }

    @Test
    fun `no device reports NoDevice with the hint`(): Unit = runBlocking {
        val s = session(FakeDevices(emptyList()) { error("unused") }, CountingSink(), hint = { true })
        s.start()
        withTimeout(2_000) { s.state.first { (it.connection as? ConnectionState.NoDevice)?.needsDriverHint == true } }
        s.stop()
    }

    @Test
    fun `streams frames with the driver default rotation then returns to NoDevice`(): Unit = runBlocking {
        val sink = CountingSink()
        val devices = FakeDevices(listOf(ref)) { stream(6) }
        val s = session(devices, sink)
        s.start()
        val st = withTimeout(5_000) { s.state.first { it.connection is ConnectionState.Streaming } }
        withTimeout(5_000) { while (sink.frames.get() < 3) kotlinx.coroutines.delay(10) }
        assertEquals(180, st.rotation)
        devices.refs = emptyList()
        withTimeout(5_000) { s.state.first { it.connection is ConnectionState.NoDevice } }
        s.stop()
    }

    @Test
    fun `button rising edge fires one snapshot`(): Unit = runBlocking {
        val sink = CountingSink()
        val devices = FakeDevices(listOf(ref)) { stream(8, buttonOn = setOf(5, 6)) }
        val s = session(devices, sink)
        s.start()
        withTimeout(5_000) { while (sink.buttons.get() < 1) kotlinx.coroutines.delay(10) }
        devices.refs = emptyList()
        withTimeout(5_000) { s.state.first { it.connection is ConnectionState.NoDevice } }
        assertEquals(1, sink.buttons.get())
        s.stop()
    }

    @Test
    fun `two presses within the window snapshot once then toggle recording`(): Unit = runBlocking {
        val sink = CountingSink()
        val counter = AtomicLong(0)
        val clock = { counter.getAndAdd(FRAME_NANOS) }
        // Frames 66 ms apart; a press at frame 1 and another 8 frames later (~528 ms) is well inside
        // the 1.5 s double-press window.
        val devices = FakeDevices(listOf(ref)) { frameSizedStream(12, buttonOn = setOf(1, 9)) }
        val s = sessionWithClock(devices, sink, clock)
        s.start()
        withTimeout(5_000) { while (sink.toggles.get() < 1) kotlinx.coroutines.delay(10) }
        devices.refs = emptyList()
        withTimeout(5_000) { s.state.first { it.connection is ConnectionState.NoDevice } }
        assertEquals(1, sink.buttons.get(), "the first press should still take its snapshot")
        assertEquals(1, sink.toggles.get(), "the second press should toggle recording, not snapshot again")
        s.stop()
    }

    @Test
    fun `two presses outside the window snapshot twice and never toggle`(): Unit = runBlocking {
        val sink = CountingSink()
        val counter = AtomicLong(0)
        val clock = { counter.getAndAdd(FRAME_NANOS) }
        // A press at frame 1 and another 46 frames later (~3.0 s) is well outside the 1.5 s window.
        val devices = FakeDevices(listOf(ref)) { frameSizedStream(50, buttonOn = setOf(1, 47)) }
        val s = sessionWithClock(devices, sink, clock)
        s.start()
        withTimeout(5_000) { while (sink.buttons.get() < 2) kotlinx.coroutines.delay(10) }
        devices.refs = emptyList()
        withTimeout(5_000) { s.state.first { it.connection is ConnectionState.NoDevice } }
        assertEquals(2, sink.buttons.get(), "two isolated presses should each take a snapshot")
        assertEquals(0, sink.toggles.get(), "presses this far apart must never toggle recording")
        s.stop()
    }

    @Test
    fun `a third press starts a new pair after the first pair toggled`(): Unit = runBlocking {
        val sink = CountingSink()
        val counter = AtomicLong(0)
        val clock = { counter.getAndAdd(FRAME_NANOS) }
        // Presses at frame 1, 9 and 17: ~528 ms apart each, all inside the window. The first pair
        // (1, 9) snapshots then toggles; the third press (17) starts a fresh pair of its own and
        // snapshots again rather than toggling back off.
        val devices = FakeDevices(listOf(ref)) { frameSizedStream(20, buttonOn = setOf(1, 9, 17)) }
        val s = sessionWithClock(devices, sink, clock)
        s.start()
        withTimeout(5_000) { while (sink.buttons.get() < 2) kotlinx.coroutines.delay(10) }
        devices.refs = emptyList()
        withTimeout(5_000) { s.state.first { it.connection is ConnectionState.NoDevice } }
        assertEquals(2, sink.buttons.get(), "presses 1 and 3 should each snapshot")
        assertEquals(1, sink.toggles.get(), "press 2 should toggle recording exactly once")
        s.stop()
    }

    @Test
    fun `rotation and mirror are locked while recording and survive a reconnect`(): Unit = runBlocking {
        val sink = CountingSink()
        val first = EndableTransport(stream(6, loop = true))   // never ends on its own
        val devices = FakeDevices(listOf(ref)) { first }
        val s = session(devices, sink)
        s.start()
        withTimeout(5_000) { s.state.first { it.connection is ConnectionState.Streaming } }
        s.rotate(); s.toggleMirror()
        assertEquals(270, s.state.value.rotation); assertTrue(s.state.value.mirror)
        s.setRecording(true); s.rotate(); assertEquals(270, s.state.value.rotation); s.setRecording(false)
        devices.opener = { stream(6, loop = true) }   // the reconnect gets a fresh looping stream
        first.endNow = true                           // deliberately end the current one
        withTimeout(5_000) { s.state.first { it.connection is ConnectionState.NoDevice } }
        withTimeout(5_000) { s.state.first { it.connection is ConnectionState.Streaming } }
        assertEquals(270, s.state.value.rotation); assertTrue(s.state.value.mirror)
        s.stop()
    }

    @Test
    fun `open failure reports Failed and keeps retrying`(): Unit = runBlocking {
        val attempts = AtomicInteger()
        val devices = FakeDevices(listOf(ref)) { attempts.incrementAndGet(); throw UsbException("no permission") }
        val s = session(devices, CountingSink())
        s.start()
        withTimeout(2_000) { s.state.first { it.connection is ConnectionState.Failed } }
        withTimeout(2_000) { while (attempts.get() < 2) kotlinx.coroutines.delay(10) }
        s.stop()
    }

    @Test
    fun `list failure reports Failed and keeps polling`(): Unit = runBlocking {
        val devices = FlakyListDevices(failures = 2, refs = listOf(ref)) { stream(6, loop = true) }
        val s = session(devices, CountingSink())
        s.start()
        withTimeout(2_000) { s.state.first { it.connection is ConnectionState.Failed } }
        withTimeout(5_000) { s.state.first { it.connection is ConnectionState.Streaming } }
        s.stop()
    }

    @Test
    fun `stop joins the session so the transport is closed on return`(): Unit = runBlocking {
        val t = stream(4, loop = true)
        val devices = FakeDevices(listOf(ref)) { t }
        val s = session(devices, CountingSink())
        s.start()
        withTimeout(5_000) { s.state.first { it.connection is ConnectionState.Streaming } }
        s.stop()
        assertTrue(t.calls.contains("close"), "transport not closed when stop() returned: ${t.calls}")
    }

    @Test
    fun `stop returns true when the transport closed in time`(): Unit = runBlocking {
        val t = stream(4, loop = true)
        val devices = FakeDevices(listOf(ref)) { t }
        val s = session(devices, CountingSink())
        s.start()
        withTimeout(5_000) { s.state.first { it.connection is ConnectionState.Streaming } }
        assertTrue(s.stop(), "expected stop() to report the join completed in time")
    }

    @Test
    fun `rotation survives a device that keeps failing to open`(): Unit = runBlocking {
        val sink = CountingSink()
        val first = EndableTransport(stream(6, loop = true))
        val devices = FakeDevices(listOf(ref)) { first }
        val s = session(devices, sink)
        s.start()
        withTimeout(5_000) { s.state.first { it.connection is ConnectionState.Streaming } }
        val fromDriver = s.state.value.rotation
        s.rotate(); s.rotate()
        val chosen = s.state.value.rotation
        assertTrue(chosen != fromDriver, "rotate() twice should have moved off the driver default $fromDriver")

        // The device now refuses to open, poll after poll.
        val attempts = AtomicInteger()
        devices.opener = { attempts.incrementAndGet(); throw UsbException("no permission") }
        first.endNow = true
        withTimeout(5_000) { s.state.first { it.connection is ConnectionState.Failed } }
        withTimeout(5_000) { while (attempts.get() < 3) kotlinx.coroutines.delay(10) }
        assertEquals(chosen, s.state.value.rotation, "a failing open must not reset the user's rotation")

        // And it is still the user's rotation once the device comes back.
        devices.opener = { stream(6, loop = true) }
        withTimeout(5_000) { s.state.first { it.connection is ConnectionState.Streaming } }
        assertEquals(chosen, s.state.value.rotation, "reconnecting must not reset the user's rotation")
        s.stop()
    }

    @Test
    fun `a device that can never open is skipped for good and the next one streams`(): Unit = runBlocking {
        // The realistic case: UvcBulkDriver matches any UVC function, so the first device enumerated
        // can be a webcam whose descriptors it cannot drive. That must not shadow the endoscope.
        val unopenable = DeviceRef(yuvInfo, 0, 1)
        val scope = DeviceRef(yuvInfo, 0, 2)
        val devices = PerRefDevices(listOf(unopenable, scope)) { r ->
            if (r == unopenable) throw UvcUnsupportedException("this camera has no bulk streaming endpoint")
            stream(6, loop = true)
        }
        val s = session(devices, CountingSink())
        s.start()
        // Two polls: one to learn the first device is hopeless, one to open the second.
        withTimeout(5_000) { s.state.first { it.connection is ConnectionState.Streaming } }
        assertEquals(1, devices.opens(unopenable), "the unsupported device should have been tried exactly once")
        assertTrue(devices.opens(scope) >= 1, "the second device should have been opened")

        kotlinx.coroutines.delay(300)   // several more poll intervals
        assertEquals(1, devices.opens(unopenable), "a permanently unsupported device must never be opened again")
        assertTrue(s.state.value.connection is ConnectionState.Streaming)
        s.stop()
    }

    @Test
    fun `a device that always fails to open does not block the next candidate`(): Unit = runBlocking {
        // A plain UsbException is not permanent (no permission yet, a busy handle), so this device
        // keeps its place in the rotation -- but the poll after each failure tries the next one.
        val flaky = DeviceRef(yuvInfo, 0, 1)
        val scope = DeviceRef(yuvInfo, 0, 2)
        val devices = PerRefDevices(listOf(flaky, scope)) { r ->
            if (r == flaky) throw UsbException("no permission")
            stream(6, loop = true)
        }
        val s = session(devices, CountingSink())
        s.start()
        withTimeout(5_000) { s.state.first { it.connection is ConnectionState.Streaming } }
        assertTrue(devices.opens(flaky) >= 1, "the first device should still have been tried")
        assertTrue(devices.opens(scope) >= 1, "the second device should have been opened")
        s.stop()
    }

    @Test
    fun `stopAndJoin returns false when the transport cannot close in time`(): Unit = runBlocking {
        val devices = FakeDevices(listOf(ref)) { StuckTransport(stream(4, loop = true), stuckLatch) }
        val s = session(devices, CountingSink())
        s.start()
        withTimeout(5_000) { s.state.first { it.connection is ConnectionState.Streaming } }
        val started = System.nanoTime()
        val joined = s.stopAndJoin(200)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertFalse(joined, "expected stopAndJoin to report the join timed out")
        assertTrue(elapsedMs < 2_000, "stopAndJoin should give up after its timeout, took ${elapsedMs}ms")
    }

    @Test
    fun `start after a timed-out stopAndJoin does not start a second loop`(): Unit = runBlocking {
        val opens = AtomicInteger()
        val devices = FakeDevices(listOf(ref)) { opens.incrementAndGet(); StuckTransport(stream(4, loop = true), stuckLatch) }
        val s = session(devices, CountingSink())
        s.start()
        withTimeout(5_000) { s.state.first { it.connection is ConnectionState.Streaming } }
        assertFalse(s.stopAndJoin(200), "expected stopAndJoin to report the join timed out")
        s.start()
        kotlinx.coroutines.delay(500)
        assertEquals(1, opens.get(), "start() after a timed-out stopAndJoin must not launch a second loop")
    }

    @Test
    fun `denoise replaces YUV frame data and can be toggled`() = runBlocking<Unit> {
        val sink = CountingSink()
        // A payload that alternates between two *close* fills: the block-mean motion stays under the
        // noise floor and the largest per-sample difference stays well under 2*motionThreshold, so
        // the filter really blends. A big step would trip the pass-through override and the sink
        // would see the raw bytes even with denoise on.
        val devices = FakeDevices(listOf(ref)) {
            stream(6, buttonOn = (1..6).toSet(), loop = true, fill = { i -> if (i % 2 == 1) FILL_A else FILL_B })
        }
        val s = session(devices, sink)
        s.start()
        withTimeout(5_000) { s.state.first { it.connection is ConnectionState.Streaming } }
        assertTrue(s.state.value.denoise)

        withTimeout(5_000) { while (sink.frames.get() < 4) kotlinx.coroutines.delay(10) }
        val denoised = requireNotNull(sink.lastFrame)
        val blended = (denoised.data as FrameData.Yuyv422).bytes
        assertTrue(
            blended.any { it != FILL_A && it != FILL_B },
            "denoise on should deliver blended bytes, got ${blended.joinToString()}",
        )
        // Everything but the pixels is carried across the swap untouched.
        assertTrue(denoised.timestampNanos != 0L, "timestampNanos must survive the denoise swap")
        assertTrue(denoised.buttonPressed, "buttonPressed must survive the denoise swap")
        assertEquals(0, denoised.cameraNumber)
        assertEquals(0, denoised.sensorValue)

        s.setDenoise(false)
        withTimeout(2_000) { s.state.first { !it.denoise } }
        val before = sink.frames.get()
        withTimeout(5_000) { while (sink.frames.get() < before + 3) kotlinx.coroutines.delay(10) }
        val raw = (requireNotNull(sink.lastFrame).data as FrameData.Yuyv422).bytes
        assertTrue(
            raw.all { it == raw[0] } && (raw[0] == FILL_A || raw[0] == FILL_B),
            "denoise off must deliver the frame's raw bytes, got ${raw.joinToString()}",
        )
        s.stop()
    }

    @Test
    fun `onStreamStarted fires once per session start, before the first frame`(): Unit = runBlocking {
        val sink = CountingSink()
        val first = EndableTransport(stream(6, loop = true))
        val devices = FakeDevices(listOf(ref)) { first }
        val s = session(devices, sink)
        s.start()
        withTimeout(5_000) { s.state.first { it.connection is ConnectionState.Streaming } }
        withTimeout(5_000) { while (sink.frames.get() < 3) kotlinx.coroutines.delay(10) }
        assertEquals(1, sink.starts.get(), "one stream start, one onStreamStarted")
        assertFalse(sink.frameBeforeStart, "onStreamStarted must precede the stream's first frame")

        devices.opener = { stream(6, loop = true) }
        first.endNow = true
        withTimeout(5_000) { while (sink.starts.get() < 2) kotlinx.coroutines.delay(10) }
        kotlinx.coroutines.delay(300)   // several poll intervals: nothing else may start a stream
        assertEquals(2, sink.starts.get(), "a reconnect is exactly one more stream start")
        s.stop()
    }

    @Test
    fun `denoise strength changes the blend and clamps into state`(): Unit = runBlocking {
        suspend fun lastByte(strength: Float): Byte {
            val sink = CountingSink()
            val devices = FakeDevices(listOf(ref)) {
                stream(6, fill = { i -> if (i % 2 == 1) FILL_A else FILL_B })
            }
            val s = session(devices, sink)
            s.setDenoise(true, strength)
            s.start()
            withTimeout(5_000) { s.state.first { it.connection is ConnectionState.Streaming } }
            // The stream is not looped and has exactly 6 frames, so waiting for all 6 lands on the
            // same input frame index in both runs regardless of scheduling jitter.
            withTimeout(5_000) { while (sink.frames.get() < 6) kotlinx.coroutines.delay(10) }
            val blended = (requireNotNull(sink.lastFrame).data as FrameData.Yuyv422).bytes
            s.stop()
            return blended[0]
        }

        val low = lastByte(0.2f)
        val high = lastByte(0.9f)
        assertTrue(low != high, "denoise strengths 0.2 and 0.9 should blend the same input differently, both gave $low")

        val s = session(FakeDevices(emptyList()) { error("unused") }, CountingSink())
        s.setDenoise(true, 5f)
        assertEquals(1.0f, s.state.value.denoiseStrength, "strength must clamp to the 0.2..1.0 range")
    }

    @Test
    fun `double-press window change alters button detection`(): Unit = runBlocking {
        suspend fun snapshotsAndToggles(windowNanos: Long): Pair<Int, Int> {
            val sink = CountingSink()
            val counter = AtomicLong(0)
            val stepNanos = 100_000_000L // 100 ms per frame
            val clock = { counter.getAndAdd(stepNanos) }
            // Presses at frame 1 and frame 21: exactly 20 * 100 ms = 2.0 s apart.
            val devices = FakeDevices(listOf(ref)) { frameSizedStream(25, buttonOn = setOf(1, 21)) }
            val s = sessionWithClock(devices, sink, clock)
            s.doublePressWindowNanos = windowNanos
            s.start()
            withTimeout(5_000) { s.state.first { it.connection is ConnectionState.Streaming } }
            withTimeout(5_000) { while (sink.frames.get() < 25) kotlinx.coroutines.delay(10) }
            devices.refs = emptyList()
            withTimeout(5_000) { s.state.first { it.connection is ConnectionState.NoDevice } }
            s.stop()
            return sink.buttons.get() to sink.toggles.get()
        }

        val (snapshots15, toggles15) = snapshotsAndToggles(1_500_000_000L)
        assertEquals(2, snapshots15, "presses 2.0s apart with a 1.5s window should snapshot twice")
        assertEquals(0, toggles15, "presses 2.0s apart with a 1.5s window must never toggle")

        val (snapshots25, toggles25) = snapshotsAndToggles(2_500_000_000L)
        assertEquals(1, snapshots25, "presses 2.0s apart with a 2.5s window should snapshot once")
        assertEquals(1, toggles25, "presses 2.0s apart with a 2.5s window should toggle once")
    }

    @Test
    fun `default override sets rotation and mirror at session start and reapplies on reconnect`(): Unit = runBlocking {
        val sink = CountingSink()
        val first = EndableTransport(stream(6, loop = true))
        val devices = FakeDevices(listOf(ref)) { first }
        val s = session(devices, sink)
        s.setDefaultOverride("i4season-yuv", 90, true)
        s.start()
        val st = withTimeout(5_000) { s.state.first { it.connection is ConnectionState.Streaming } }
        assertEquals(90, st.rotation, "override rotation should apply at session start")
        assertTrue(st.mirror, "override mirror should apply at session start")

        s.rotate() // 90 -> 180, for this session only
        assertEquals(180, s.state.value.rotation)

        devices.opener = { stream(6, loop = true) }
        first.endNow = true
        withTimeout(5_000) { s.state.first { it.connection is ConnectionState.NoDevice } }
        // The override is a stored default for this driver, so an unplug/replug of the same probe
        // brings it back rather than keeping the rotation this session happened to end on.
        withTimeout(5_000) { s.state.first { it.connection is ConnectionState.Streaming } }
        assertEquals(90, s.state.value.rotation, "an override must reapply on a reconnect of the same driver")
        assertTrue(s.state.value.mirror)
        s.stop()
    }

    @Test
    fun `an override with a null flag leaves that flag alone on a reconnect`(): Unit = runBlocking {
        val sink = CountingSink()
        val first = EndableTransport(stream(6, loop = true))
        val devices = FakeDevices(listOf(ref)) { first }
        val s = session(devices, sink)
        s.setDefaultOverride("i4season-yuv", null, true)   // mirror only
        s.start()
        withTimeout(5_000) { s.state.first { it.connection is ConnectionState.Streaming } }
        assertEquals(180, s.state.value.rotation, "a null rotation should leave the driver default in place")
        assertTrue(s.state.value.mirror)

        s.rotate() // 180 -> 270
        assertEquals(270, s.state.value.rotation)

        devices.opener = { stream(6, loop = true) }
        first.endNow = true
        withTimeout(5_000) { s.state.first { it.connection is ConnectionState.NoDevice } }
        withTimeout(5_000) { s.state.first { it.connection is ConnectionState.Streaming } }
        assertEquals(270, s.state.value.rotation, "a same-driver reconnect must not wipe the rotation an override says nothing about")
        assertTrue(s.state.value.mirror)
        s.stop()
    }

    @Test
    fun `an override set while streaming changes the live session`(): Unit = runBlocking {
        val sink = CountingSink()
        val devices = FakeDevices(listOf(ref)) { stream(6, loop = true) }
        val s = session(devices, sink)
        s.start()
        withTimeout(5_000) { s.state.first { it.connection is ConnectionState.Streaming } }
        assertEquals(180, s.state.value.rotation)
        assertFalse(s.state.value.mirror)

        s.setDefaultOverride("i4season-yuv", 90, true)
        assertEquals(90, s.state.value.rotation, "an override for the streaming driver must apply immediately")
        assertTrue(s.state.value.mirror, "an override for the streaming driver must apply immediately")

        s.setDefaultOverride("i4season-yuv", 270, null)
        assertEquals(270, s.state.value.rotation)
        assertTrue(s.state.value.mirror, "a null flag must leave the live value alone")

        s.setDefaultOverride("uvc-bulk", 0, false)
        assertEquals(270, s.state.value.rotation, "an override for another driver must not touch the live session")
        assertTrue(s.state.value.mirror, "an override for another driver must not touch the live session")

        s.setRecording(true)
        s.setDefaultOverride("i4season-yuv", 0, false)
        assertEquals(270, s.state.value.rotation, "geometry is locked while recording, as it is for rotate()")
        assertTrue(s.state.value.mirror, "geometry is locked while recording, as it is for toggleMirror()")
        s.setRecording(false)

        s.clearDefaultOverride("i4season-yuv")
        assertEquals(270, s.state.value.rotation, "clearing an override must not change the live session")
        assertTrue(s.state.value.mirror, "clearing an override must not change the live session")
        s.stop()
    }

    @Test
    fun `clearing an override makes the next open of that driver fall back to the driver default`(): Unit = runBlocking {
        val sink = CountingSink()
        val first = EndableTransport(stream(6, loop = true))
        val devices = FakeDevices(listOf(ref)) { first }
        val s = session(devices, sink)
        s.setDefaultOverride("i4season-yuv", 90, true)
        s.start()
        withTimeout(5_000) { s.state.first { it.connection is ConnectionState.Streaming } }
        assertEquals(90, s.state.value.rotation, "override rotation should apply at session start")
        assertTrue(s.state.value.mirror)

        // Forget it while the same driver is still the last one opened: the map entry going away
        // must not leave the session sticky on that driver, or the next open keeps 90/mirrored.
        s.clearDefaultOverride("i4season-yuv")
        assertEquals(90, s.state.value.rotation, "clearing an override must not change the live session")

        devices.opener = { stream(6, loop = true) }
        first.endNow = true
        withTimeout(5_000) { s.state.first { it.connection is ConnectionState.NoDevice } }
        withTimeout(5_000) { s.state.first { it.connection is ConnectionState.Streaming } }
        assertEquals(180, s.state.value.rotation, "a forgotten driver's next open must fall back to driver.defaultRotation")
        assertFalse(s.state.value.mirror, "a forgotten driver's next open must fall back to an unmirrored view")
        s.stop()
    }

    @Test
    fun `an override set while nothing is streaming does not touch the state`(): Unit = runBlocking {
        val s = session(FakeDevices(emptyList()) { error("unused") }, CountingSink())
        s.setDefaultOverride("i4season-yuv", 90, true)
        assertEquals(0, s.state.value.rotation, "no driver is streaming, so nothing is live to change")
        assertFalse(s.state.value.mirror)
    }

    @Test
    fun `the double-press window clamps to its bounds`() {
        val s = session(FakeDevices(emptyList()) { error("unused") }, CountingSink())
        assertEquals(
            ScopeSession.DOUBLE_PRESS_WINDOW_NANOS, s.doublePressWindowNanos,
            "the initializer bypasses the setter, so the default must already be in range",
        )

        s.doublePressWindowNanos = 1
        assertEquals(ScopeSession.MIN_DOUBLE_PRESS_WINDOW_NANOS, s.doublePressWindowNanos, "below the minimum should clamp up")

        s.doublePressWindowNanos = 10_000_000_000L
        assertEquals(ScopeSession.MAX_DOUBLE_PRESS_WINDOW_NANOS, s.doublePressWindowNanos, "above the maximum should clamp down")

        s.doublePressWindowNanos = 2_000_000_000L
        assertEquals(2_000_000_000L, s.doublePressWindowNanos, "an in-range value should pass through untouched")
    }

    @Test
    fun `driverId is set while streaming and cleared after disconnect`(): Unit = runBlocking {
        val sink = CountingSink()
        val devices = FakeDevices(listOf(ref)) { stream(6) }
        val s = session(devices, sink)
        s.start()
        val st = withTimeout(5_000) { s.state.first { it.connection is ConnectionState.Streaming } }
        assertEquals("i4season-yuv", st.driverId)
        devices.refs = emptyList()
        withTimeout(5_000) { s.state.first { it.connection is ConnectionState.NoDevice } }
        assertEquals(null, s.state.value.driverId, "driverId must clear once the session leaves Streaming")
        s.stop()
    }

    private companion object {
        const val FILL_A: Byte = 100
        const val FILL_B: Byte = 103
        /** Header (511) + a 4x2 YUYV payload (16): exactly one [I4seasonTestFrames.frame]'s size. */
        const val FRAME_BYTES = 511 + 4 * 2 * 2
        /** ~15 fps, matching the real driver's typical frame spacing. */
        const val FRAME_NANOS = 66_000_000L
    }
}
