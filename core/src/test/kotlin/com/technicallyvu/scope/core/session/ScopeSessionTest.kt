package com.technicallyvu.scope.core.session

import com.technicallyvu.scope.core.driver.Frame
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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
        val frames = AtomicInteger(); val buttons = AtomicInteger(); @Volatile var lastState: SessionState? = null
        override fun onFrame(frame: Frame, state: SessionState) { frames.incrementAndGet(); lastState = state }
        override fun onButtonSnapshot() { buttons.incrementAndGet() }
    }

    private fun stream(frames: Int, buttonOn: Set<Int> = emptySet(), loop: Boolean = false): ReplayTransport {
        val bytes = (1..frames).fold(ByteArray(0)) { acc, i -> acc + I4seasonTestFrames.frame(4, 2, flags = if (i in buttonOn) 0x02 else 0, fill = i.toByte()) }
        return ReplayTransport(I4seasonTestFrames.chunked(bytes, 100), loop = loop, sleep = Thread::sleep, videoEndpoint = I4seasonYuvDriver.EP_IN).apply {
            controlResponses[0xA0 to 0x00] = I4seasonTestFrames.info(4, 2)
        }
    }

    private fun session(devices: DeviceSource, sink: FrameSink, hint: () -> Boolean = { false }) =
        ScopeSession(devices, listOf(I4seasonYuvDriver(ioDispatcher = Dispatchers.Default)), scope, sink, driverHintCheck = hint, pollMillis = 50)

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
        val devices = FakeDevices(listOf(ref)) { stream(6, loop = true) }
        val s = session(devices, sink)
        s.start()
        withTimeout(5_000) { s.state.first { it.connection is ConnectionState.Streaming } }
        assertTrue(s.state.value.denoise)
        s.setDenoise(false)
        withTimeout(2_000) { s.state.first { !it.denoise } }
        s.stop()
    }
}
