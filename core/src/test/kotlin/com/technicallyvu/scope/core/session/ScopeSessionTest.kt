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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class ScopeSessionTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val yuvInfo = UsbDeviceInfo(0x2CE3, 0x3828, 0xEF, listOf(UsbInterfaceInfo(0, 0xFF, 0xF0, 1)))
    private val ref = DeviceRef(yuvInfo, 0, 1)

    private class FakeDevices(var refs: List<DeviceRef>, val opener: () -> UsbTransport) : DeviceSource {
        override fun list() = refs
        override fun open(ref: DeviceRef) = opener()
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

    @AfterEach fun tearDown() = scope.cancel()

    @Test
    fun `no device reports NoDevice with the hint`() = runBlocking {
        val s = session(FakeDevices(emptyList()) { error("unused") }, CountingSink(), hint = { true })
        s.start()
        withTimeout(2_000) { s.state.first { (it.connection as? ConnectionState.NoDevice)?.needsDriverHint == true } }
        s.stop()
    }

    @Test
    fun `streams frames with the driver default rotation then returns to NoDevice`() = runBlocking {
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
    fun `button rising edge fires one snapshot`() = runBlocking {
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
    fun `rotation and mirror are locked while recording and survive a reconnect`() = runBlocking {
        val sink = CountingSink()
        val devices = FakeDevices(listOf(ref)) { stream(6) }   // ends by itself -> reconnect
        val s = session(devices, sink)
        s.start()
        withTimeout(5_000) { s.state.first { it.connection is ConnectionState.Streaming } }
        s.rotate(); s.toggleMirror()
        assertEquals(270, s.state.value.rotation); assertTrue(s.state.value.mirror)
        s.setRecording(true); s.rotate(); assertEquals(270, s.state.value.rotation); s.setRecording(false)
        withTimeout(5_000) { s.state.first { it.connection is ConnectionState.NoDevice } }
        withTimeout(5_000) { s.state.first { it.connection is ConnectionState.Streaming } }
        assertEquals(270, s.state.value.rotation); assertTrue(s.state.value.mirror)
        s.stop()
    }

    @Test
    fun `open failure reports Failed and keeps retrying`() = runBlocking {
        var attempts = 0
        val devices = FakeDevices(listOf(ref)) { attempts++; throw UsbException("no permission") }
        val s = session(devices, CountingSink())
        s.start()
        withTimeout(2_000) { s.state.first { it.connection is ConnectionState.Failed } }
        withTimeout(2_000) { while (attempts < 2) kotlinx.coroutines.delay(10) }
        s.stop()
    }

    @Test
    fun `stop joins the session so the transport is closed on return`() = runBlocking {
        val t = stream(4, loop = true)
        val devices = FakeDevices(listOf(ref)) { t }
        val s = session(devices, CountingSink())
        s.start()
        withTimeout(5_000) { s.state.first { it.connection is ConnectionState.Streaming } }
        s.stop()
        assertTrue(t.calls.contains("close"), "transport not closed when stop() returned: ${t.calls}")
    }
}
