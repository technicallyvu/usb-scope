package com.technicallyvu.scope.desktop.ui

import com.technicallyvu.scope.core.fixture.ReplayTransport
import com.technicallyvu.scope.core.usb.DeviceRef
import com.technicallyvu.scope.core.usb.DeviceSource
import com.technicallyvu.scope.core.usb.UsbDeviceInfo
import com.technicallyvu.scope.core.usb.UsbException
import com.technicallyvu.scope.core.usb.UsbTransport
import com.technicallyvu.scope.core.useeplus.UseeplusDriver
import com.technicallyvu.scope.core.useeplus.UseeplusPacket
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

class ScopeViewModelTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ref = DeviceRef(UsbDeviceInfo(0x2CE3, 0x3828, 0xFF), 1, 2)

    private class FakeDevices(var refs: List<DeviceRef>, val opener: () -> UsbTransport) : DeviceSource {
        override fun list() = refs
        override fun open(ref: DeviceRef) = opener()
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
}
