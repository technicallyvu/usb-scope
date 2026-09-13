package com.technicallyvu.scope.desktop.ui

import com.technicallyvu.scope.core.fixture.ReplayTransport
import com.technicallyvu.scope.core.usb.DeviceRef
import com.technicallyvu.scope.core.usb.DeviceSource
import com.technicallyvu.scope.core.usb.UsbDeviceInfo
import com.technicallyvu.scope.core.usb.UsbException
import com.technicallyvu.scope.core.usb.UsbTransport
import com.technicallyvu.scope.core.useeplus.UseeplusDriver
import com.technicallyvu.scope.core.useeplus.UseeplusPacket
import com.technicallyvu.scope.core.uvc.UvcUnsupportedException
import com.technicallyvu.scope.desktop.TestPackets
import com.technicallyvu.scope.desktop.media.Mp4Recorder
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
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO

class ScopeViewModelTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ref = DeviceRef(UsbDeviceInfo(0x2CE3, 0x3828, 0xFF), 1, 2)

    private class FakeDevices(var refs: List<DeviceRef>, val opener: () -> UsbTransport) : DeviceSource {
        override fun list() = refs
        override fun open(ref: DeviceRef): UsbTransport = opener()
    }

    /**
     * Wraps a [ReplayTransport] and makes every bulk read take [readMillis], standing in for a real
     * libusb transfer that cancellation cannot preempt. ReplayTransport is final, hence delegation.
     */
    private class SlowTransport(private val inner: ReplayTransport, private val readMillis: Long = 200) : UsbTransport {
        val calls: List<String> get() = inner.calls
        override fun claimInterface(iface: Int) = inner.claimInterface(iface)
        override fun releaseInterface(iface: Int) = inner.releaseInterface(iface)
        override fun setAltSetting(iface: Int, alt: Int) = inner.setAltSetting(iface, alt)
        override fun clearHalt(endpoint: Int) = inner.clearHalt(endpoint)
        override fun bulkWrite(endpoint: Int, data: ByteArray, timeoutMs: Int) = inner.bulkWrite(endpoint, data, timeoutMs)
        override fun bulkRead(endpoint: Int, buffer: ByteArray, timeoutMs: Int): Int {
            Thread.sleep(readMillis)
            return inner.bulkRead(endpoint, buffer, timeoutMs)
        }
        override fun controlTransfer(requestType: Int, request: Int, value: Int, index: Int, data: ByteArray, timeoutMs: Int) =
            inner.controlTransfer(requestType, request, value, index, data, timeoutMs)
        override fun resetDevice() = inner.resetDevice()
        override fun close() = inner.close()
    }

    private fun vm(
        devices: DeviceSource,
        dir: Path,
        hint: () -> Boolean = { false },
        recorderFactory: (Path, Int, Int) -> Mp4Recorder = { f, w, h -> Mp4Recorder(f, w, h) },
    ) = ScopeViewModel(
        devices,
        listOf(UseeplusDriver(ioDispatcher = Dispatchers.Default)),
        scope,
        dir,
        driverHintCheck = hint,
        pollMillis = 50,
        recorderFactory = recorderFactory,
    )

    @AfterEach
    fun tearDown() = scope.cancel()

    @Test
    fun `no device shows waiting state with driver hint`(@TempDir dir: Path) = runBlocking {
        val vm = vm(FakeDevices(emptyList()) { error("unused") }, dir, hint = { true })
        vm.start()
        val s = withTimeout(2_000) {
            vm.state.first {
                val conn = it.connection
                conn is ConnectionState.NoDevice && conn.needsDriverHint
            }
        }
        assertTrue(s.image == null)
        vm.stop()
    }

    @Test
    fun `open failure without a prior stream reports NoDevice with hint or Failed`(@TempDir dir: Path) = runBlocking {
        val vmHint = vm(FakeDevices(listOf(ref)) { throw UsbException("open failed") }, dir, hint = { true })
        vmHint.start()
        withTimeout(2_000) {
            vmHint.state.first {
                val conn = it.connection
                conn is ConnectionState.NoDevice && conn.needsDriverHint
            }
        }
        vmHint.stop()

        val vmNoHint = vm(FakeDevices(listOf(ref)) { throw UsbException("open failed") }, dir, hint = { false })
        vmNoHint.start()
        withTimeout(2_000) { vmNoHint.state.first { it.connection is ConnectionState.Failed } }
        vmNoHint.stop()
    }

    @Test
    fun `streams frames then returns to waiting when the replay ends`(@TempDir dir: Path) = runBlocking {
        val packets = TestPackets.stream(6, buttonMask = UseeplusPacket.BUTTON_MASK)
        val devices = FakeDevices(listOf(ref)) { ReplayTransport(packets) }
        val vm = vm(devices, dir)
        vm.start()
        val streaming = withTimeout(5_000) { vm.state.first { it.image != null } }
        assertTrue(streaming.connection is ConnectionState.Streaming)
        // default rotation is 90: the 32x24 sensor frame shows as 24x32
        val img = requireNotNull(streaming.image)
        assertEquals(24, img.width)
        assertEquals(32, img.height)
        devices.refs = emptyList()
        withTimeout(5_000) { vm.state.first { it.connection is ConnectionState.NoDevice } }
        vm.stop()
    }

    @Test
    fun `rotation survives a reconnect`(@TempDir dir: Path) = runBlocking {
        val packets = TestPackets.stream(20, buttonMask = UseeplusPacket.BUTTON_MASK)
        val devices = FakeDevices(listOf(ref)) { ReplayTransport(packets, sleep = Thread::sleep) }
        val vm = vm(devices, dir)
        vm.start()
        withTimeout(5_000) { vm.state.first { it.image != null } }
        vm.rotate()
        assertEquals(180, vm.state.value.rotation)   // default rotation (90) + one rotate()

        // The replay ends, dropping the device; the polling loop reconnects it automatically.
        withTimeout(5_000) {
            vm.state.first { it.connection is ConnectionState.NoDevice || it.connection is ConnectionState.Failed }
        }
        withTimeout(5_000) { vm.state.first { it.image != null } }

        assertEquals(180, vm.state.value.rotation)   // unchanged: same driver reconnected
        vm.stop()
    }

    @Test
    fun `cable button saves a snapshot once per press`(@TempDir dir: Path) = runBlocking {
        val packets = TestPackets.stream(8, buttonOn = setOf(5, 6), buttonMask = UseeplusPacket.BUTTON_MASK)
        val devices = FakeDevices(listOf(ref)) { ReplayTransport(packets) }
        val vm = vm(devices, dir)
        vm.start()
        withTimeout(5_000) { vm.state.first { it.lastSaved != null } }
        devices.refs = emptyList()
        withTimeout(5_000) { vm.state.first { it.connection is ConnectionState.NoDevice } }
        val saved = Files.list(dir).use { it.toList() }
        assertEquals(1, saved.size, "expected exactly one snapshot, got $saved")
        assertTrue(saved[0].fileName.toString().endsWith(".jpg"))
        vm.stop()
    }

    @Test
    fun `manual snapshot and recording produce files and rotation is locked while recording`(@TempDir dir: Path) = runBlocking {
        val packets = TestPackets.stream(40, buttonMask = UseeplusPacket.BUTTON_MASK)
        val devices = FakeDevices(listOf(ref)) { ReplayTransport(packets, loop = true, sleep = Thread::sleep) }
        val vm = vm(devices, dir)
        vm.start()
        withTimeout(5_000) { vm.state.first { it.image != null } }
        vm.snapshot()
        vm.toggleRecording()
        assertTrue(vm.state.value.recording)
        vm.rotate()
        assertEquals(90, vm.state.value.rotation)   // unchanged while recording
        Thread.sleep(700)
        vm.toggleRecording()
        assertTrue(!vm.state.value.recording)
        val names = Files.list(dir).use { it.toList() }.map { it.fileName.toString() }
        assertTrue(names.any { it.endsWith(".jpg") }, "no jpg in $names")
        val mp4 = requireNotNull(Files.list(dir).use { it.toList() }.firstOrNull { it.fileName.toString().endsWith(".mp4") }) { "no mp4 in $names" }
        assertTrue(Files.size(mp4) > 500)
        vm.rotate()
        assertEquals(180, vm.state.value.rotation)
        vm.stop()
    }

    @Test
    fun `failing recorder construction leaves recording off and writes no mp4`(@TempDir dir: Path) = runBlocking {
        val packets = TestPackets.stream(40, buttonMask = UseeplusPacket.BUTTON_MASK)
        val devices = FakeDevices(listOf(ref)) { ReplayTransport(packets, loop = true, sleep = Thread::sleep) }
        val vm = vm(devices, dir, recorderFactory = { _, _, _ -> throw IllegalStateException("no encoder") })
        vm.start()
        withTimeout(5_000) { vm.state.first { it.image != null } }
        vm.toggleRecording()
        assertTrue(!vm.state.value.recording)
        val names = Files.list(dir).use { it.toList() }.map { it.fileName.toString() }
        assertTrue(names.none { it.endsWith(".mp4") }, "unexpected mp4 in $names")
        vm.stop()
    }

    @Test
    fun `stop does not leave a Failed state`(@TempDir dir: Path) = runBlocking {
        val packets = TestPackets.stream(40, buttonMask = UseeplusPacket.BUTTON_MASK)
        val devices = FakeDevices(listOf(ref)) { ReplayTransport(packets, loop = true, sleep = Thread::sleep) }
        val vm = vm(devices, dir)
        vm.start()
        withTimeout(5_000) { vm.state.first { it.image != null } }
        vm.stop()
        Thread.sleep(500)
        assertTrue(vm.state.value.connection !is ConnectionState.Failed)
    }

    @Test
    fun `stop joins the session so the transport is closed before it returns`(@TempDir dir: Path) = runBlocking {
        val packets = TestPackets.stream(40, buttonMask = UseeplusPacket.BUTTON_MASK)
        val transport = SlowTransport(ReplayTransport(packets, loop = true))
        val devices = FakeDevices(listOf(ref)) { transport }
        val vm = vm(devices, dir)
        vm.start()
        withTimeout(10_000) { vm.state.first { it.image != null } }
        val startNanos = System.nanoTime()
        vm.stop()
        val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000
        assertTrue(
            transport.calls.contains("close"),
            "session finally had not run when stop() returned; calls=${transport.calls.takeLast(6)}",
        )
        assertTrue(elapsedMs < 3_000, "stop() took $elapsedMs ms")
    }

    @Test
    fun `a device that can never open is skipped for good and the next one streams`(@TempDir dir: Path) = runBlocking {
        // UvcBulkDriver matches any UVC function, so the first device enumerated can be a webcam it
        // cannot drive. Without the skip set it would shadow the endoscope behind it forever.
        val unopenable = ref
        val scope = ref.copy(address = ref.address + 1)
        val counts = ConcurrentHashMap<DeviceRef, AtomicInteger>()
        val packets = TestPackets.stream(40, buttonMask = UseeplusPacket.BUTTON_MASK)
        val devices = object : DeviceSource {
            override fun list() = listOf(unopenable, scope)
            override fun open(ref: DeviceRef): UsbTransport {
                counts.computeIfAbsent(ref) { AtomicInteger() }.incrementAndGet()
                if (ref == unopenable) throw UvcUnsupportedException("this camera has no bulk streaming endpoint")
                return ReplayTransport(packets, loop = true, sleep = Thread::sleep)
            }
        }
        val vm = vm(devices, dir)
        vm.start()
        withTimeout(5_000) { vm.state.first { it.image != null } }
        assertEquals(1, counts[unopenable]?.get(), "the unsupported device should have been tried exactly once")
        assertTrue((counts[scope]?.get() ?: 0) >= 1, "the second device should have been opened")

        Thread.sleep(300)   // several more poll intervals
        assertEquals(1, counts[unopenable]?.get(), "a permanently unsupported device must never be opened again")
        vm.stop()
    }

    @Test
    fun `toggleDenoise flips the flag`(@TempDir dir: Path) = runBlocking {
        val vm = vm(FakeDevices(emptyList()) { error("unused") }, dir)
        assertTrue(vm.state.value.denoise)
        vm.toggleDenoise()
        assertTrue(!vm.state.value.denoise)
        vm.toggleDenoise()
        assertTrue(vm.state.value.denoise)
    }

    @Test
    fun `sharpening is off by default and setSharpen flips it`(@TempDir dir: Path) = runBlocking {
        val vm = vm(FakeDevices(emptyList()) { error("unused") }, dir)
        assertTrue(!vm.state.value.sharpen, "sharpening is opt-in: it adds no detail, only apparent crispness")
        vm.setSharpen(true)
        assertTrue(vm.state.value.sharpen)
        vm.setSharpen(false)
        assertTrue(!vm.state.value.sharpen)
    }

    @Test
    fun `with sharpening on the picture changes and the snapshot is the shown one`(@TempDir dir: Path) = runBlocking {
        val packets = TestPackets.stream(40, buttonMask = UseeplusPacket.BUTTON_MASK)
        val devices = FakeDevices(listOf(ref)) { ReplayTransport(packets, loop = true, sleep = Thread::sleep) }
        val vm = vm(devices, dir)
        // Denoise off, so this is the sharpener's doing and nothing else.
        vm.toggleDenoise()
        vm.setSharpen(true)
        vm.start()
        val shown = requireNotNull(withTimeout(5_000) { vm.state.first { it.image != null } }.image)
        vm.snapshot()
        val jpg = requireNotNull(
            Files.list(dir).use { it.toList() }.firstOrNull { it.fileName.toString().endsWith(".jpg") },
        ) { "no jpg written" }
        val saved = requireNotNull(ImageIO.read(jpg.toFile()))
        // Sharpening only exists in the decoded pixels, so the snapshot must be the shown picture
        // re-encoded (rotation baked in), not the frame's original bytes plus an EXIF tag.
        assertEquals(shown.width, saved.width)
        assertEquals(shown.height, saved.height)
        vm.stop()
    }

    @Test
    fun `with denoise on the snapshot has the dimensions of the shown image`(@TempDir dir: Path) = runBlocking {
        val packets = TestPackets.stream(40, buttonMask = UseeplusPacket.BUTTON_MASK)
        val devices = FakeDevices(listOf(ref)) { ReplayTransport(packets, loop = true, sleep = Thread::sleep) }
        val vm = vm(devices, dir)
        vm.start()
        val shown = requireNotNull(withTimeout(5_000) { vm.state.first { it.image != null } }.image)
        assertTrue(vm.state.value.denoise, "denoise is on by default; this test is about that path")
        // The 32x24 sensor frame shown through the driver's default 90-degree rotation.
        assertEquals(24, shown.width)
        assertEquals(32, shown.height)

        vm.snapshot()
        val jpg = requireNotNull(
            Files.list(dir).use { it.toList() }.firstOrNull { it.fileName.toString().endsWith(".jpg") },
        ) { "no jpg written" }
        val saved = requireNotNull(ImageIO.read(jpg.toFile()))
        // Re-encoded from the filtered picture, so the rotation is in the pixels, not in a tag.
        assertEquals(shown.width, saved.width)
        assertEquals(shown.height, saved.height)
        vm.stop()
    }

    @Test
    fun `a stop during encoder start-up discards the new recorder`(@TempDir dir: Path) = runBlocking {
        val packets = TestPackets.stream(40, buttonMask = UseeplusPacket.BUTTON_MASK)
        val devices = FakeDevices(listOf(ref)) { ReplayTransport(packets, loop = true, sleep = Thread::sleep) }
        val release = CountDownLatch(1)
        val vm = vm(devices, dir, recorderFactory = { f, w, h ->
            release.await(5, TimeUnit.SECONDS)
            Mp4Recorder(f, w, h)
        })
        vm.start()
        withTimeout(5_000) { vm.state.first { it.image != null } }
        val starter = Thread { vm.toggleRecording() }
        starter.start()
        Thread.sleep(200)
        vm.stop()
        release.countDown()
        starter.join()
        assertTrue(!vm.state.value.recording)
    }
}
