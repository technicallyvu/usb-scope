# Phase 1 Addendum: i4season YUV driver — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stream live video from Anthony's actual endoscope (the single-interface "i4season YUV" personality of USB `2CE3:3828`) through the existing desktop bench, so Phase 1 acceptance can run.

**Architecture:** Extends the Phase 1 plan (`2026-09-08-phase1-windows-bench.md`, Tasks 1–13, of which 1–6, 9–11 and Task 12 Steps 1–2 are done). The core frame model becomes format-agnostic (`FrameData` = JPEG or YUYV), the transport gains control transfers, device info gains the interface layout so drivers match on it, and a second driver (`I4seasonYuvDriver`) is added next to the existing `UseeplusDriver`. The desktop shell decodes both formats. See spec §12.

**Tech Stack:** unchanged (Kotlin 2.4.10, Gradle 9.7.1, Compose 1.12.0, usb4java 1.3.0, JavaCV 1.5.14, Commons Imaging 1.0.0-alpha6, JUnit 6.1.3).

## Global Constraints

- All constraints of the Phase 1 plan still apply (package root `com.technicallyvu.scope`; pinned versions; no network; no vendor trademarks; JUnit 5 Jupiter; TDD with RED/GREEN evidence; commits end with `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`).
- Protocol (spec §12, verified on hardware 2026-09-08): interface 0; bulk IN `0x82`; control requests on endpoint 0: info `bmRequestType 0xA0, bRequest 0, wValue 5, wIndex 0, wLength 512` → 480 bytes with vendor at `[1..16]`, product `[17..32]`, firmware `[33..40]`, width LE at `[46..47]`, height LE at `[48..49]`; start `0x20, 1, 5, 0` + 64 zero bytes; stop `0x20, 2, 5, 0` no data. Stream = `[511-byte header][width*height*2 bytes YUYV]`; header starts `DD CC`, byte 7 = button flags (`0x02` pressed, `0` idle).
- Known compile pattern: JUnit Jupiter's `assertNotNull(x)` is void in Kotlin; use `requireNotNull(x)`.
- The vendor APK and its decompiled output live in `.superpowers/re/` (git-ignored). Never commit them; never copy code from them. We reimplement from the protocol facts above.
- Run from `C:\Projects\usb-endoscope-app` in PowerShell with `.\gradlew.bat ...`.

---

## File Structure

```
core/src/main/kotlin/com/technicallyvu/scope/core/
  usb/UsbTransport.kt            + UsbInterfaceInfo, UsbDeviceInfo.interfaces, UsbTransport.controlTransfer
  driver/DeviceDriver.kt         + FrameData (Jpeg | Yuyv422); Frame.data; DeviceDriver.defaultRotation, withPacketSink
  driver/DriverRegistry.kt       order: I4seasonYuvDriver, UseeplusDriver
  useeplus/FrameReassembler.kt   emits FrameData.Jpeg
  useeplus/UseeplusDriver.kt     matches on interface layout when known; defaultRotation 90; withPacketSink
  fixture/ReplayTransport.kt     + controlTransfer recording and canned IN replies
  fixture/ReplayDeviceSource.kt  + UsbDeviceInfo parameter
  i4season/I4seasonInfoBlock.kt  parses the 480-byte info block
  i4season/I4seasonFrameParser.kt DD CC framing → Frame(Yuyv422)
  i4season/I4seasonYuvDriver.kt  control-transfer handshake, read loop, stop on close
core/src/test/kotlin/com/technicallyvu/scope/core/
  TestPackets.kt                 + jpegBytes() helper
  i4season/I4seasonTestFrames.kt, I4seasonInfoBlockTest.kt, I4seasonFrameParserTest.kt, I4seasonYuvDriverTest.kt, I4seasonRealCaptureTest.kt
  fixture/PacketLogTest.kt       + controlTransfer tests
core/src/test/resources/fixtures/i4season-yuv-320x240.upkt   (captured in Task 17)
desktop/src/main/kotlin/com/technicallyvu/scope/desktop/
  usb/LibUsbTransport.kt         + controlTransfer
  usb/LibUsbDevices.kt           + interface enumeration
  media/ImageTransforms.kt       + decode(FrameData), yuyvToImage
  media/SnapshotWriter.kt        + write(FrameData, ...)
  ui/ScopeViewModel.kt           FrameData; rotation from driver.defaultRotation
  Probe.kt                       driver by registry; frame kind in output
  Main.kt                        --replay <file> [--layout yuv|jpeg]
desktop/src/test/kotlin/com/technicallyvu/scope/desktop/
  media/ImageTransformsTest.kt   + YUYV tests; media/SnapshotWriterTest.kt + YUYV test
```

---

### Task 14: Core model — FrameData, control transfers, interface layout, driver hooks

**Files:**
- Modify: `core/src/main/kotlin/com/technicallyvu/scope/core/usb/UsbTransport.kt`
- Modify: `core/src/main/kotlin/com/technicallyvu/scope/core/driver/DeviceDriver.kt`
- Modify: `core/src/main/kotlin/com/technicallyvu/scope/core/useeplus/FrameReassembler.kt`
- Modify: `core/src/main/kotlin/com/technicallyvu/scope/core/useeplus/UseeplusDriver.kt`
- Modify: `core/src/main/kotlin/com/technicallyvu/scope/core/fixture/ReplayTransport.kt`
- Modify: `core/src/main/kotlin/com/technicallyvu/scope/core/fixture/ReplayDeviceSource.kt`
- Modify: `core/src/test/kotlin/com/technicallyvu/scope/core/TestPackets.kt`
- Modify tests that use `frame.jpeg`: `useeplus/FrameReassemblerTest.kt`, `useeplus/UseeplusDriverTest.kt`
- Modify: `core/src/test/kotlin/com/technicallyvu/scope/core/fixture/PacketLogTest.kt` (add control tests)

**Interfaces:**
- Produces: `FrameData.Jpeg(bytes)`, `FrameData.Yuyv422(width, height, bytes)`, `Frame(data, timestampNanos, buttonPressed, cameraNumber, sensorValue)`, `UsbInterfaceInfo(number, usbClass, subclass, protocol)`, `UsbDeviceInfo(vendorId, productId, usbClass, interfaces = emptyList())`, `UsbTransport.controlTransfer(requestType, request, value, index, data, timeoutMs): Int`, `DeviceDriver.defaultRotation: Int`, `DeviceDriver.withPacketSink(sink): DeviceDriver`, `ReplayTransport.controlResponses: MutableMap<Pair<Int, Int>, ByteArray>`, `ReplayDeviceSource(log, info)`, test helper `Frame.jpegBytes()`.

- [ ] **Step 1: Write the failing tests first**

Append to `core/src/test/kotlin/com/technicallyvu/scope/core/TestPackets.kt` (inside the object, or as a top-level function in the same file):
```kotlin
    /** JPEG bytes of a frame that must carry JPEG data. */
    fun com.technicallyvu.scope.core.driver.Frame.jpegBytes(): ByteArray =
        (data as com.technicallyvu.scope.core.driver.FrameData.Jpeg).bytes
```
(Place it as a top-level extension function after the `object TestPackets { ... }` block: `fun Frame.jpegBytes(): ByteArray = (data as FrameData.Jpeg).bytes` with imports.)

In `FrameReassemblerTest.kt` and `UseeplusDriverTest.kt`, replace every `frame.jpeg` / `it.jpeg` / `frames[0].jpeg` with `frame.jpegBytes()` / `it.jpegBytes()` / `frames[0].jpegBytes()` (import `com.technicallyvu.scope.core.jpegBytes`). Assertions stay identical.

Add to `PacketLogTest.kt`:
```kotlin
    @Test
    fun `replay transport records control transfers and answers IN requests from canned replies`() {
        val t = ReplayTransport(emptyList())
        val out = ByteArray(64)
        assertEquals(64, t.controlTransfer(0x20, 1, 5, 0, out, 1000))
        val buf = ByteArray(512)
        assertEquals(0, t.controlTransfer(0xA0, 0, 5, 0, buf, 1000))           // nothing canned -> 0 bytes
        t.controlResponses[0xA0 to 0x00] = byteArrayOf(1, 2, 3)
        assertEquals(3, t.controlTransfer(0xA0, 0, 5, 0, buf, 1000))
        assertArrayEquals(byteArrayOf(1, 2, 3), buf.copyOf(3))
        assertEquals(listOf("ctrl 20 01 0005 0000 64", "ctrl A0 00 0005 0000 512", "ctrl A0 00 0005 0000 512"), t.calls)
    }
```

Add to `UseeplusDriverTest.kt`:
```kotlin
    @Test
    fun `matches on interface layout when it is known`() {
        val d = driver()
        val iap = UsbInterfaceInfo(0, 0xFF, 0xF0, 0)
        val video = UsbInterfaceInfo(1, 0xFF, 0xF0, 1)
        assertTrue(d.matches(UsbDeviceInfo(0x2CE3, 0x3828, 0xEF, listOf(iap, video))))
        assertFalse(d.matches(UsbDeviceInfo(0x2CE3, 0x3828, 0xEF, listOf(video))))   // lone protocol-1 = YUV type
        assertEquals(90, d.defaultRotation)
    }

    @Test
    fun `withPacketSink keeps the same identity but delivers packets`() {
        var count = 0
        val d = driver().withPacketSink { _, _, _ -> count++ }
        assertEquals("useeplus", d.id)
        val source = d.open(ReplayTransport(stream(3)))
        runCatching { runBlocking { source.frames.toList() } }
        assertTrue(count > 0)
    }
```
(import `com.technicallyvu.scope.core.usb.UsbInterfaceInfo`.)

- [ ] **Step 2: Run to verify failure**

Run: `.\gradlew.bat :core:test`
Expected: compilation FAILS (`jpegBytes`, `controlTransfer`, `UsbInterfaceInfo`, `defaultRotation`, `withPacketSink`, `controlResponses` unresolved).

- [ ] **Step 3: Implement the model changes**

`core/src/main/kotlin/com/technicallyvu/scope/core/usb/UsbTransport.kt` (full file):
```kotlin
package com.technicallyvu.scope.core.usb

class UsbException(message: String, val code: Int = 0, cause: Throwable? = null) : Exception(message, cause)

/** One interface of the active configuration (alternate setting 0). */
data class UsbInterfaceInfo(val number: Int, val usbClass: Int, val subclass: Int, val protocol: Int)

data class UsbDeviceInfo(
    val vendorId: Int,
    val productId: Int,
    val usbClass: Int,
    /** Interfaces of the active configuration, or empty when the platform could not read them. */
    val interfaces: List<UsbInterfaceInfo> = emptyList(),
) {
    val idString: String get() = "%04X:%04X".format(vendorId, productId)
}

/** The USB primitives a driver needs. One implementation per platform. */
interface UsbTransport : AutoCloseable {
    fun claimInterface(iface: Int)
    fun releaseInterface(iface: Int)
    fun setAltSetting(iface: Int, alt: Int)
    fun clearHalt(endpoint: Int)
    /** Returns bytes written. Throws [UsbException] on error. */
    fun bulkWrite(endpoint: Int, data: ByteArray, timeoutMs: Int): Int
    /** Returns bytes read; 0 on timeout. Throws [UsbException] on any other error, including device removal. */
    fun bulkRead(endpoint: Int, buffer: ByteArray, timeoutMs: Int): Int
    /**
     * Control transfer on endpoint 0. For device-to-host requests (bit 7 of [requestType] set) [data]
     * is filled and the number of bytes received is returned; for host-to-device requests [data] is
     * sent and the number of bytes sent is returned. Throws [UsbException] on error (including STALL).
     */
    fun controlTransfer(requestType: Int, request: Int, value: Int, index: Int, data: ByteArray, timeoutMs: Int): Int
    fun resetDevice()
}
```

`core/src/main/kotlin/com/technicallyvu/scope/core/driver/DeviceDriver.kt` (full file):
```kotlin
package com.technicallyvu.scope.core.driver

import com.technicallyvu.scope.core.usb.UsbDeviceInfo
import com.technicallyvu.scope.core.usb.UsbTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Pixel payload of one frame. Shells decode whichever variant a driver produces. */
sealed interface FrameData {
    class Jpeg(val bytes: ByteArray) : FrameData

    /** Packed 4:2:2, byte order Y0 U Y1 V, exactly width*height*2 bytes. */
    class Yuyv422(val width: Int, val height: Int, val bytes: ByteArray) : FrameData {
        init {
            require(bytes.size == width * height * 2) { "YUYV payload ${bytes.size} != ${width}x${height}x2" }
        }
    }
}

class Frame(
    val data: FrameData,
    val timestampNanos: Long,
    val buttonPressed: Boolean,
    val cameraNumber: Int,
    val sensorValue: Long,
)

data class StreamStats(
    val fps: Double = 0.0,
    val framesEmitted: Long = 0,
    val framesDropped: Long = 0,
    val bytesReceived: Long = 0,
)

/** Receives every raw bulk-IN packet before parsing. Used for fixture capture. */
fun interface PacketSink {
    fun onPacket(packet: ByteArray, len: Int, timestampNanos: Long)
}

interface FrameSource : AutoCloseable {
    val frames: Flow<Frame>
    val stats: StateFlow<StreamStats>
}

interface DeviceDriver {
    val id: String
    val displayName: String
    /** Clockwise rotation that shows this device's picture upright by default. */
    val defaultRotation: Int get() = 0
    fun matches(info: UsbDeviceInfo): Boolean
    /** Performs the device handshake. Throws [com.technicallyvu.scope.core.usb.UsbException] if the device cannot be started. */
    fun open(transport: UsbTransport): FrameSource
    /** A copy of this driver that also feeds raw packets to [sink] (fixture capture). */
    fun withPacketSink(sink: PacketSink): DeviceDriver
}
```

`FrameReassembler.finish(...)`: replace `jpeg = bytes` with `data = FrameData.Jpeg(bytes)` and import `com.technicallyvu.scope.core.driver.FrameData`.

`UseeplusDriver.kt` changes:
```kotlin
    override val defaultRotation: Int get() = 90

    override fun matches(info: UsbDeviceInfo): Boolean {
        if ((info.vendorId to info.productId) !in SUPPORTED_IDS) return false
        if (info.interfaces.isEmpty()) return true   // layout unknown: assume the documented two-interface variant
        val vendorF0 = info.interfaces.filter { it.usbClass == 0xFF && it.subclass == 0xF0 }
        return vendorF0.any { it.protocol == 0 } && vendorF0.any { it.protocol == 1 }
    }

    override fun withPacketSink(sink: PacketSink): DeviceDriver =
        UseeplusDriver(clock, sleep, ioDispatcher, sink)
```
(`clock`, `sleep`, `ioDispatcher` are already constructor properties; keep them `private val`.)

`ReplayTransport.kt` additions:
```kotlin
    /** Canned replies for device-to-host control requests, keyed by (requestType, request). */
    val controlResponses = mutableMapOf<Pair<Int, Int>, ByteArray>()

    override fun controlTransfer(requestType: Int, request: Int, value: Int, index: Int, data: ByteArray, timeoutMs: Int): Int {
        calls += "ctrl %02X %02X %04X %04X %d".format(requestType and 0xFF, request and 0xFF, value and 0xFFFF, index and 0xFFFF, data.size)
        if (requestType and 0x80 == 0) return data.size
        val reply = controlResponses[(requestType and 0xFF) to (request and 0xFF)] ?: return 0
        val n = minOf(reply.size, data.size)
        reply.copyInto(data, 0, 0, n)
        return n
    }
```

`ReplayDeviceSource.kt`:
```kotlin
class ReplayDeviceSource(
    private val log: Path,
    info: UsbDeviceInfo = UsbDeviceInfo(0x2CE3, 0x3828, 0xEF),
) : DeviceSource {
    private val ref = DeviceRef(info, bus = 0, address = 0)
    // list() and open() unchanged
}
```

- [ ] **Step 4: Run all core tests**

Run: `.\gradlew.bat :core:test`
Expected: all PASS (existing suites plus the 3 new tests). The `desktop` module will not compile until Task 16; do not run it here.

- [ ] **Step 5: Commit**

```bash
git add core/src
git commit -m "feat(core): FrameData model, control transfers, interface layout, driver hooks"
```

---

### Task 15: i4season YUV driver

**Files:**
- Create: `core/src/main/kotlin/com/technicallyvu/scope/core/i4season/I4seasonInfoBlock.kt`
- Create: `core/src/main/kotlin/com/technicallyvu/scope/core/i4season/I4seasonFrameParser.kt`
- Create: `core/src/main/kotlin/com/technicallyvu/scope/core/i4season/I4seasonYuvDriver.kt`
- Modify: `core/src/main/kotlin/com/technicallyvu/scope/core/driver/DriverRegistry.kt`
- Test: `core/src/test/kotlin/com/technicallyvu/scope/core/i4season/I4seasonTestFrames.kt`, `I4seasonInfoBlockTest.kt`, `I4seasonFrameParserTest.kt`, `I4seasonYuvDriverTest.kt`

**Interfaces:**
- Consumes: Task 14 types.
- Produces: `I4seasonInfoBlock.parse(bytes, len): CameraInfo(vendor, product, firmware, width, height)`, `I4seasonFrameParser(width, height).accept(chunk, len, nowNanos): List<Frame>` with `framesEmitted/framesDropped/bytesReceived`, `I4seasonYuvDriver(clock, sleep, ioDispatcher, packetSink)` with constants `INTERFACE=0`, `EP_IN=0x82`, `CHUNK_SIZE=16384`, `HEADER_SIZE=511`, `BUTTON_OFFSET=7`, `REQTYPE_IN=0xA0`, `REQTYPE_OUT=0x20`, `REQ_INFO=0`, `REQ_START=1`, `REQ_STOP=2`, `WVALUE=5`, `DEFAULT_WIDTH=320`, `DEFAULT_HEIGHT=240`; `DriverRegistry.all = [I4seasonYuvDriver(), UseeplusDriver()]`.

- [ ] **Step 1: Test helpers and failing tests**

`core/src/test/kotlin/com/technicallyvu/scope/core/i4season/I4seasonTestFrames.kt`:
```kotlin
package com.technicallyvu.scope.core.i4season

import com.technicallyvu.scope.core.fixture.LoggedPacket

object I4seasonTestFrames {
    /** The real 56 leading bytes of Anthony's unit's info block (rest of the 480 bytes is zero). */
    val REAL_INFO_PREFIX: ByteArray = (
        "01 69 34 73 65 61 73 6f 6e 00 00 00 00 00 00 00 00 73 75 34 70 2d 30 30 32 00 00 00 00 00 00 00 " +
        "00 35 2e 30 2e 31 33 00 00 02 00 58 02 00 40 01 f0 00 00 00 00 00 00 00"
    ).split(" ").map { it.toInt(16).toByte() }.toByteArray()

    fun realInfo(): ByteArray = REAL_INFO_PREFIX + ByteArray(480 - REAL_INFO_PREFIX.size)

    /** An info block advertising a tiny [width]x[height] picture, for small synthetic streams. */
    fun info(width: Int, height: Int): ByteArray = realInfo().also {
        it[46] = (width and 0xFF).toByte(); it[47] = ((width shr 8) and 0xFF).toByte()
        it[48] = (height and 0xFF).toByte(); it[49] = ((height shr 8) and 0xFF).toByte()
    }

    /** One stream frame: 511-byte header (DD CC 01 00 58 02 00 flags 00 ...) + YUYV payload filled with [fill]. */
    fun frame(width: Int, height: Int, flags: Int = 0, fill: Byte = 0x40): ByteArray {
        val header = ByteArray(511)
        header[0] = 0xDD.toByte(); header[1] = 0xCC.toByte(); header[2] = 0x01
        header[4] = 0x58; header[5] = 0x02; header[7] = flags.toByte()
        for (i in 9 until 511) header[i] = (i * 7).toByte()          // constant vendor blob
        return header + ByteArray(width * height * 2) { fill }
    }

    /** Splits a byte stream into bulk reads of [size] bytes, 1 ms apart. */
    fun chunked(stream: ByteArray, size: Int): List<LoggedPacket> =
        stream.toList().chunked(size).mapIndexed { i, c -> LoggedPacket(i * 1_000_000L, c.toByteArray()) }
}
```

`I4seasonInfoBlockTest.kt`:
```kotlin
package com.technicallyvu.scope.core.i4season

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class I4seasonInfoBlockTest {
    @Test
    fun `parses the real info block`() {
        val info = I4seasonInfoBlock.parse(I4seasonTestFrames.realInfo(), 480)
        assertEquals("i4season", info.vendor)
        assertEquals("su4p-002", info.product)
        assertEquals("5.0.13", info.firmware)
        assertEquals(320, info.width)
        assertEquals(240, info.height)
    }

    @Test
    fun `falls back to 320x240 when the block is short or implausible`() {
        val short = I4seasonInfoBlock.parse(ByteArray(10), 10)
        assertEquals(320, short.width); assertEquals(240, short.height); assertEquals("", short.vendor)
        val zero = I4seasonInfoBlock.parse(I4seasonTestFrames.info(0, 0), 480)
        assertEquals(320, zero.width); assertEquals(240, zero.height)
        val huge = I4seasonInfoBlock.parse(I4seasonTestFrames.info(9000, 9000), 480)
        assertEquals(320, huge.width); assertEquals(240, huge.height)
    }
}
```

`I4seasonFrameParserTest.kt`:
```kotlin
package com.technicallyvu.scope.core.i4season

import com.technicallyvu.scope.core.driver.FrameData
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class I4seasonFrameParserTest {
    private val w = 4
    private val h = 2   // payload 16 bytes, frame 527 bytes

    private fun feed(p: I4seasonFrameParser, stream: ByteArray, chunk: Int) =
        stream.toList().chunked(chunk).flatMap { c -> p.accept(c.toByteArray(), c.size, 5L) }

    @Test
    fun `reassembles frames across arbitrary read boundaries`() {
        val a = I4seasonTestFrames.frame(w, h, fill = 0x11)
        val b = I4seasonTestFrames.frame(w, h, fill = 0x22)
        val p = I4seasonFrameParser(w, h)
        val frames = feed(p, a + b, 100)
        assertEquals(2, frames.size)
        val d0 = frames[0].data as FrameData.Yuyv422
        assertEquals(w, d0.width); assertEquals(h, d0.height)
        assertArrayEquals(ByteArray(16) { 0x11 }, d0.bytes)
        assertArrayEquals(ByteArray(16) { 0x22 }, (frames[1].data as FrameData.Yuyv422).bytes)
        assertEquals(5L, frames[0].timestampNanos)
        assertEquals(2, p.framesEmitted); assertEquals(0, p.framesDropped)
        assertEquals((a.size + b.size).toLong(), p.bytesReceived)
    }

    @Test
    fun `button flag comes from header byte 7`() {
        val p = I4seasonFrameParser(w, h)
        val frames = feed(p, I4seasonTestFrames.frame(w, h, flags = 0) + I4seasonTestFrames.frame(w, h, flags = 0x02), 64)
        assertFalse(frames[0].buttonPressed)
        assertTrue(frames[1].buttonPressed)
    }

    @Test
    fun `resynchronises after garbage and counts one drop`() {
        val p = I4seasonFrameParser(w, h)
        val garbage = ByteArray(300) { 0x55 }
        val frames = feed(p, garbage + I4seasonTestFrames.frame(w, h, fill = 0x33), 50)
        assertEquals(1, frames.size)
        assertArrayEquals(ByteArray(16) { 0x33 }, (frames[0].data as FrameData.Yuyv422).bytes)
        assertEquals(1, p.framesDropped)
    }

    @Test
    fun `a magic split across two reads is still found`() {
        val p = I4seasonFrameParser(w, h)
        val stream = ByteArray(7) { 0x01 } + I4seasonTestFrames.frame(w, h, fill = 0x44)
        val frames = feed(p, stream, 8)   // first read ends with DD, second starts with CC
        assertEquals(1, frames.size)
    }

    @Test
    fun `payload bytes that look like the magic do not break framing`() {
        val p = I4seasonFrameParser(w, h)
        val f = I4seasonTestFrames.frame(w, h, fill = 0xDD.toByte())
        f[511 + 1] = 0xCC.toByte()   // DD CC inside the payload
        val frames = feed(p, f + I4seasonTestFrames.frame(w, h, fill = 0x66), 33)
        assertEquals(2, frames.size)
        assertEquals(0, p.framesDropped)
    }
}
```

`I4seasonYuvDriverTest.kt`:
```kotlin
package com.technicallyvu.scope.core.i4season

import com.technicallyvu.scope.core.driver.FrameData
import com.technicallyvu.scope.core.driver.PacketSink
import com.technicallyvu.scope.core.fixture.ReplayTransport
import com.technicallyvu.scope.core.usb.UsbDeviceInfo
import com.technicallyvu.scope.core.usb.UsbException
import com.technicallyvu.scope.core.usb.UsbInterfaceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class I4seasonYuvDriverTest {
    private val slept = mutableListOf<Long>()
    private fun driver(sink: PacketSink? = null) =
        I4seasonYuvDriver(clock = { 42L }, sleep = { slept += it }, ioDispatcher = Dispatchers.Default, packetSink = sink)

    private fun transport(frames: Int, w: Int = 4, h: Int = 2, buttonOn: Set<Int> = emptySet()): ReplayTransport {
        val stream = (1..frames).fold(ByteArray(0)) { acc, i ->
            acc + I4seasonTestFrames.frame(w, h, flags = if (i in buttonOn) 0x02 else 0, fill = i.toByte())
        }
        return ReplayTransport(I4seasonTestFrames.chunked(stream, 100)).apply {
            controlResponses[0xA0 to 0x00] = I4seasonTestFrames.info(w, h)
        }
    }

    @Test
    fun `matches only the lone protocol-1 layout with a known id`() {
        val d = driver()
        val yuv = UsbInterfaceInfo(0, 0xFF, 0xF0, 1)
        val iap = UsbInterfaceInfo(0, 0xFF, 0xF0, 0)
        assertTrue(d.matches(UsbDeviceInfo(0x2CE3, 0x3828, 0xEF, listOf(yuv))))
        assertTrue(d.matches(UsbDeviceInfo(0x0329, 0x2022, 0xEF, listOf(yuv))))
        assertFalse(d.matches(UsbDeviceInfo(0x2CE3, 0x3828, 0xEF, listOf(iap, UsbInterfaceInfo(1, 0xFF, 0xF0, 1)))))
        assertFalse(d.matches(UsbDeviceInfo(0x2CE3, 0x3828, 0xEF)))              // layout unknown -> not ours
        assertFalse(d.matches(UsbDeviceInfo(0x1234, 0x0001, 0xEF, listOf(yuv))))
        assertEquals(0, d.defaultRotation)
    }

    @Test
    fun `handshake is claim, info, start`() {
        val t = transport(0)
        driver().open(t)
        assertEquals(listOf("claim 0", "ctrl A0 00 0005 0000 512", "ctrl 20 01 0005 0000 64"), t.calls)
    }

    @Test
    fun `frames carry YUYV data at the advertised size and the button flag`() {
        val t = transport(3, buttonOn = setOf(2))
        val frames = runBlocking { driver().open(t).frames.take(3).toList() }
        val d = frames[0].data as FrameData.Yuyv422
        assertEquals(4, d.width); assertEquals(2, d.height); assertEquals(16, d.bytes.size)
        assertEquals(listOf(false, true, false), frames.map { it.buttonPressed })
        assertEquals(42L, frames[0].timestampNanos)
    }

    @Test
    fun `stats count frames and bytes`() {
        val t = transport(3)
        val source = driver().open(t)
        runBlocking { source.frames.take(3).toList() }
        assertEquals(3, source.stats.value.framesEmitted)
        assertEquals(3L * (511 + 16), source.stats.value.bytesReceived)
    }

    @Test
    fun `missing info block falls back to 320x240`() {
        val t = ReplayTransport(I4seasonTestFrames.chunked(I4seasonTestFrames.frame(320, 240), 16384))
        val frame = runBlocking { driver().open(t).frames.take(1).toList() }.single()
        val d = frame.data as FrameData.Yuyv422
        assertEquals(320, d.width); assertEquals(240, d.height)
    }

    @Test
    fun `open retries with reset and gives up after three failures`() {
        val t = transport(0).apply { failuresBeforeSuccess = 3 }
        assertThrows(UsbException::class.java) { driver().open(t) }
        assertEquals(3, t.resets)
        assertEquals(listOf(1500L, 1500L, 1500L), slept)
    }

    @Test
    fun `stream ends with UsbException when the device goes away`() {
        val source = driver().open(transport(2))
        assertThrows(UsbException::class.java) { runBlocking { source.frames.toList() } }
    }

    @Test
    fun `close sends stop then releases and closes`() {
        val t = transport(0)
        driver().open(t).close()
        assertEquals(listOf("ctrl 20 02 0005 0000 0", "release 0", "close"), t.calls.takeLast(3))
    }

    @Test
    fun `packet sink sees raw reads`() {
        var n = 0
        val t = transport(2)
        runCatching { runBlocking { driver { _, _, _ -> n++ }.open(t).frames.toList() } }
        assertTrue(n > 0)
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `.\gradlew.bat :core:test --tests "com.technicallyvu.scope.core.i4season.*"`
Expected: compilation FAILS with unresolved `I4seasonInfoBlock`, `I4seasonFrameParser`, `I4seasonYuvDriver`.

- [ ] **Step 3: Implement**

`core/src/main/kotlin/com/technicallyvu/scope/core/i4season/I4seasonInfoBlock.kt`:
```kotlin
package com.technicallyvu.scope.core.i4season

/** The 480-byte reply to the info request. Layout verified on hardware (spec §12). */
data class CameraInfo(val vendor: String, val product: String, val firmware: String, val width: Int, val height: Int)

object I4seasonInfoBlock {
    const val DEFAULT_WIDTH = 320
    const val DEFAULT_HEIGHT = 240
    private const val MAX_DIMENSION = 4096

    fun parse(bytes: ByteArray, len: Int): CameraInfo {
        val vendor = cString(bytes, len, 1, 16)
        val product = cString(bytes, len, 17, 16)
        val firmware = cString(bytes, len, 33, 8)
        var width = le16(bytes, len, 46)
        var height = le16(bytes, len, 48)
        if (width !in 1..MAX_DIMENSION || height !in 1..MAX_DIMENSION) {
            width = DEFAULT_WIDTH
            height = DEFAULT_HEIGHT
        }
        return CameraInfo(vendor, product, firmware, width, height)
    }

    private fun cString(b: ByteArray, len: Int, offset: Int, max: Int): String {
        if (offset >= len) return ""
        val end = minOf(len, offset + max)
        var stop = offset
        while (stop < end && b[stop] != 0.toByte()) stop++
        return String(b, offset, stop - offset, Charsets.US_ASCII)
    }

    private fun le16(b: ByteArray, len: Int, offset: Int): Int =
        if (offset + 1 >= len) 0 else (b[offset].toInt() and 0xFF) or ((b[offset + 1].toInt() and 0xFF) shl 8)
}
```

`core/src/main/kotlin/com/technicallyvu/scope/core/i4season/I4seasonFrameParser.kt`:
```kotlin
package com.technicallyvu.scope.core.i4season

import com.technicallyvu.scope.core.driver.Frame
import com.technicallyvu.scope.core.driver.FrameData

/**
 * Splits the bulk stream into frames: [511-byte header][width*height*2 bytes YUYV].
 * The header starts with DD CC; byte 7 carries the button flags. Once aligned, frames are consumed
 * by size, so magic-like bytes inside pixel data cannot break framing. Not thread-safe.
 */
class I4seasonFrameParser(val width: Int, val height: Int) {
    private val payloadSize = width * height * 2
    private val frameSize = HEADER_SIZE + payloadSize
    private var buf = ByteArray(frameSize + 2 * MAX_READ)
    private var used = 0

    var framesEmitted: Long = 0; private set
    var framesDropped: Long = 0; private set
    var bytesReceived: Long = 0; private set

    fun accept(chunk: ByteArray, len: Int, nowNanos: Long): List<Frame> {
        bytesReceived += len
        if (used + len > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, used + len))
        System.arraycopy(chunk, 0, buf, used, len)
        used += len
        val out = ArrayList<Frame>(2)
        while (true) {
            if (!alignedAtMagic()) {
                val i = indexOfMagic()
                if (i < 0) {
                    if (used > 1) discard(used - 1)   // keep a possible leading DD of a split magic
                    break
                }
                framesDropped++
                discard(i)
            }
            if (used < frameSize) break
            val flags = buf[BUTTON_OFFSET].toInt() and 0xFF
            val payload = buf.copyOfRange(HEADER_SIZE, frameSize)
            out += Frame(FrameData.Yuyv422(width, height, payload), nowNanos, buttonPressed = flags != 0, cameraNumber = 0, sensorValue = 0)
            framesEmitted++
            discard(frameSize)
        }
        return out
    }

    private fun alignedAtMagic() = used >= 2 && buf[0] == MAGIC0 && buf[1] == MAGIC1

    private fun indexOfMagic(): Int {
        for (i in 0 until used - 1) if (buf[i] == MAGIC0 && buf[i + 1] == MAGIC1) return i
        return -1
    }

    private fun discard(n: Int) {
        System.arraycopy(buf, n, buf, 0, used - n)
        used -= n
    }

    companion object {
        const val HEADER_SIZE = 511
        const val BUTTON_OFFSET = 7
        const val MAX_READ = 16384
        const val MAGIC0 = 0xDD.toByte()
        const val MAGIC1 = 0xCC.toByte()
    }
}
```

`core/src/main/kotlin/com/technicallyvu/scope/core/i4season/I4seasonYuvDriver.kt`:
```kotlin
package com.technicallyvu.scope.core.i4season

import com.technicallyvu.scope.core.driver.DeviceDriver
import com.technicallyvu.scope.core.driver.Frame
import com.technicallyvu.scope.core.driver.FrameSource
import com.technicallyvu.scope.core.driver.PacketSink
import com.technicallyvu.scope.core.driver.StreamStats
import com.technicallyvu.scope.core.usb.UsbDeviceInfo
import com.technicallyvu.scope.core.usb.UsbException
import com.technicallyvu.scope.core.usb.UsbTransport
import com.technicallyvu.scope.core.useeplus.FpsMeter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/**
 * Driver for the single-interface "YUV" personality of the 2CE3:3828 / 0329:2022 endoscopes
 * (i4season firmware). Protocol per spec §12: class control requests to start/stop, raw YUYV frames
 * with a 511-byte DD CC header on bulk IN 0x82.
 */
class I4seasonYuvDriver(
    private val clock: () -> Long = System::nanoTime,
    private val sleep: (millis: Long) -> Unit = Thread::sleep,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val packetSink: PacketSink? = null,
) : DeviceDriver {

    override val id = "i4season-yuv"
    override val displayName = "i4season YUV endoscope"
    override val defaultRotation: Int get() = 0

    override fun matches(info: UsbDeviceInfo): Boolean {
        if ((info.vendorId to info.productId) !in SUPPORTED_IDS) return false
        val only = info.interfaces.singleOrNull() ?: return false
        return only.usbClass == 0xFF && only.subclass == 0xF0 && only.protocol == 1
    }

    override fun withPacketSink(sink: PacketSink): DeviceDriver = I4seasonYuvDriver(clock, sleep, ioDispatcher, sink)

    override fun open(transport: UsbTransport): FrameSource {
        var lastError: UsbException? = null
        repeat(OPEN_ATTEMPTS) {
            try {
                val info = handshake(transport)
                return I4seasonFrameSource(transport, info, clock, ioDispatcher, packetSink)
            } catch (e: UsbException) {
                lastError = e
                runCatching { transport.resetDevice() }
                sleep(RESET_WAIT_MS)
            }
        }
        throw UsbException("i4season handshake failed after $OPEN_ATTEMPTS attempts", cause = lastError)
    }

    internal fun handshake(t: UsbTransport): CameraInfo {
        t.claimInterface(INTERFACE)
        val raw = ByteArray(INFO_LENGTH)
        val n = t.controlTransfer(REQTYPE_IN, REQ_INFO, WVALUE, 0, raw, CMD_TIMEOUT_MS)
        val info = I4seasonInfoBlock.parse(raw, n)
        t.controlTransfer(REQTYPE_OUT, REQ_START, WVALUE, 0, ByteArray(START_LENGTH), CMD_TIMEOUT_MS)
        return info
    }

    companion object {
        val SUPPORTED_IDS = setOf(0x2CE3 to 0x3828, 0x0329 to 0x2022)
        const val INTERFACE = 0
        const val EP_IN = 0x82
        const val CHUNK_SIZE = 16384
        const val READ_TIMEOUT_MS = 5000
        const val REQTYPE_IN = 0xA0
        const val REQTYPE_OUT = 0x20
        const val REQ_INFO = 0
        const val REQ_START = 1
        const val REQ_STOP = 2
        const val WVALUE = 5
        const val INFO_LENGTH = 512
        const val START_LENGTH = 64
        const val CMD_TIMEOUT_MS = 1000
        const val OPEN_ATTEMPTS = 3
        const val RESET_WAIT_MS = 1500L
    }
}

class I4seasonFrameSource(
    private val transport: UsbTransport,
    val info: CameraInfo,
    private val clock: () -> Long,
    private val dispatcher: CoroutineDispatcher,
    private val packetSink: PacketSink?,
) : FrameSource {
    private val _stats = MutableStateFlow(StreamStats())
    override val stats: StateFlow<StreamStats> = _stats

    @Volatile private var closed = false

    override val frames: Flow<Frame> = flow {
        val parser = I4seasonFrameParser(info.width, info.height)
        val fps = FpsMeter()
        val buf = ByteArray(I4seasonYuvDriver.CHUNK_SIZE)
        while (!closed) {
            // Blocking USB read on the IO dispatcher; emit on the collector's context (no internal buffer).
            val n = withContext(dispatcher) { transport.bulkRead(I4seasonYuvDriver.EP_IN, buf, I4seasonYuvDriver.READ_TIMEOUT_MS) }
            if (n == 0) continue
            val now = clock()
            packetSink?.onPacket(buf, n, now)
            for (frame in parser.accept(buf, n, now)) {
                _stats.value = StreamStats(fps.tick(now), parser.framesEmitted, parser.framesDropped, parser.bytesReceived)
                emit(frame)
            }
        }
    }

    override fun close() {
        closed = true
        runCatching { transport.controlTransfer(I4seasonYuvDriver.REQTYPE_OUT, I4seasonYuvDriver.REQ_STOP, I4seasonYuvDriver.WVALUE, 0, ByteArray(0), I4seasonYuvDriver.CMD_TIMEOUT_MS) }
        runCatching { transport.releaseInterface(I4seasonYuvDriver.INTERFACE) }
        runCatching { transport.close() }
    }
}
```

`DriverRegistry.kt`: `val all: List<DeviceDriver> = listOf(I4seasonYuvDriver(), UseeplusDriver())` (import `com.technicallyvu.scope.core.i4season.I4seasonYuvDriver`). Add a test to `UseeplusDriverTest.kt` or a new `DriverRegistryTest.kt`:
```kotlin
class DriverRegistryTest {
    @Test
    fun `layout decides which driver claims the shared usb id`() {
        val yuv = UsbDeviceInfo(0x2CE3, 0x3828, 0xEF, listOf(UsbInterfaceInfo(0, 0xFF, 0xF0, 1)))
        val jpeg = UsbDeviceInfo(0x2CE3, 0x3828, 0xEF, listOf(UsbInterfaceInfo(0, 0xFF, 0xF0, 0), UsbInterfaceInfo(1, 0xFF, 0xF0, 1)))
        assertEquals("i4season-yuv", DriverRegistry.find(yuv)?.id)
        assertEquals("useeplus", DriverRegistry.find(jpeg)?.id)
        assertEquals("useeplus", DriverRegistry.find(UsbDeviceInfo(0x2CE3, 0x3828, 0xEF))?.id)   // unknown layout
        assertNull(DriverRegistry.find(UsbDeviceInfo(0x046D, 0x0825, 0xEF)))
    }
}
```

- [ ] **Step 4: Run all core tests**

Run: `.\gradlew.bat :core:test`
Expected: all PASS.

- [ ] **Step 5: Commit**

```bash
git add core/src
git commit -m "feat(core): i4season YUV driver with control-transfer handshake and DD CC framing"
```

---

### Task 16: Desktop — control transfers, interface enumeration, YUYV decode, FrameData plumbing

**Files:**
- Modify: `desktop/src/main/kotlin/com/technicallyvu/scope/desktop/usb/LibUsbTransport.kt`
- Modify: `desktop/src/main/kotlin/com/technicallyvu/scope/desktop/usb/LibUsbDevices.kt`
- Modify: `desktop/src/main/kotlin/com/technicallyvu/scope/desktop/media/ImageTransforms.kt`
- Modify: `desktop/src/main/kotlin/com/technicallyvu/scope/desktop/media/SnapshotWriter.kt`
- Modify: `desktop/src/main/kotlin/com/technicallyvu/scope/desktop/ui/ScopeViewModel.kt`
- Modify: `desktop/src/main/kotlin/com/technicallyvu/scope/desktop/Probe.kt`
- Modify: `desktop/src/main/kotlin/com/technicallyvu/scope/desktop/Main.kt`
- Test: `desktop/src/test/kotlin/com/technicallyvu/scope/desktop/media/ImageTransformsTest.kt`, `SnapshotWriterTest.kt` (add cases); `ui/ScopeViewModelTest.kt` (adjust to FrameData)

**Interfaces:**
- Produces: `ImageTransforms.decode(data: FrameData): BufferedImage?`, `ImageTransforms.yuyvToImage(data: FrameData.Yuyv422): BufferedImage`, `SnapshotWriter.write(data: FrameData, rotationDegrees, mirror, dir, now): Path` (the existing `write(jpeg: ByteArray, ...)` stays as the JPEG path), `Main` flags `--replay <file> [--layout yuv|jpeg]`.

- [ ] **Step 1: Failing tests**

Add to `ImageTransformsTest.kt`:
```kotlin
    private fun yuyv(w: Int, h: Int, y: Int, u: Int, v: Int): FrameData.Yuyv422 {
        val b = ByteArray(w * h * 2)
        for (i in b.indices step 4) { b[i] = y.toByte(); b[i + 1] = u.toByte(); b[i + 2] = y.toByte(); b[i + 3] = v.toByte() }
        return FrameData.Yuyv422(w, h, b)
    }

    @Test
    fun `yuyv white and black`() {
        val white = ImageTransforms.yuyvToImage(yuyv(4, 2, 235, 128, 128))
        assertEquals(4, white.width); assertEquals(2, white.height)
        assertEquals(0xFFFFFF, rgb(white, 3, 1))
        val black = ImageTransforms.yuyvToImage(yuyv(4, 2, 16, 128, 128))
        assertEquals(0x000000, rgb(black, 0, 0))
    }

    @Test
    fun `yuyv red is red`() {
        val img = ImageTransforms.yuyvToImage(yuyv(2, 1, 81, 90, 240))
        val p = rgb(img, 0, 0)
        val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
        assertTrue(r > 200 && g < 40 && b < 40, "expected red, got %06X".format(p))
    }

    @Test
    fun `decode dispatches on frame data type`() {
        val jpegBytes = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "jpg", it) }.toByteArray()
        assertEquals(8, ImageTransforms.decode(FrameData.Jpeg(jpegBytes))!!.width)
        assertEquals(4, ImageTransforms.decode(yuyv(4, 2, 128, 128, 128))!!.width)
        assertNull(ImageTransforms.decode(FrameData.Jpeg(byteArrayOf(1, 2))))
    }
```
(import `com.technicallyvu.scope.core.driver.FrameData`, `org.junit.jupiter.api.Assertions.assertTrue`.)

Add to `SnapshotWriterTest.kt`:
```kotlin
    @Test
    fun `yuyv frames are jpeg-encoded and tagged`(@TempDir dir: Path) {
        val b = ByteArray(16 * 8 * 2)
        for (i in b.indices step 4) { b[i] = 235.toByte(); b[i + 1] = 128.toByte(); b[i + 2] = 235.toByte(); b[i + 3] = 128.toByte() }
        val path = SnapshotWriter.write(FrameData.Yuyv422(16, 8, b), 90, false, dir, LocalDateTime.of(2026, 9, 8, 15, 0, 0))
        assertEquals("SCOPE_20260908_150000.jpg", path.fileName.toString())
        val img = requireNotNull(ImageIO.read(path.toFile()))
        assertEquals(16, img.width)
        assertTrue((img.getRGB(3, 3) and 0xFF) > 240, "should be near white")
        val meta = requireNotNull(Imaging.getMetadata(Files.readAllBytes(path)) as? JpegImageMetadata)
        assertEquals(6, meta.findExifValue(TiffTagConstants.TIFF_TAG_ORIENTATION).intValue)
    }
```

- [ ] **Step 2: Run to verify failure**

Run: `.\gradlew.bat :desktop:compileKotlin`
Expected: FAILS (desktop still uses `frame.jpeg`, `UsbTransport` implementation lacks `controlTransfer`, new test symbols unresolved).

- [ ] **Step 3: Implement**

`LibUsbTransport.kt` add:
```kotlin
    override fun controlTransfer(requestType: Int, request: Int, value: Int, index: Int, data: ByteArray, timeoutMs: Int): Int {
        val buf = ByteBuffer.allocateDirect(data.size)
        if (requestType and 0x80 == 0) {
            buf.put(data)
            buf.rewind()
        }
        val r = LibUsb.controlTransfer(handle, requestType.toByte(), request.toByte(), value.toShort(), index.toShort(), buf, timeoutMs.toLong())
        if (r < 0) throw UsbException("controlTransfer %02X/%02X failed: %s".format(requestType, request, LibUsb.strError(r)), r)
        if (requestType and 0x80 != 0 && r > 0) {
            buf.rewind()
            buf.get(data, 0, r)
        }
        return r
    }
```

`LibUsbDevices.describe(dev)` becomes:
```kotlin
    private fun describe(dev: Device): UsbDeviceInfo {
        val d = DeviceDescriptor()
        LibUsb.getDeviceDescriptor(dev, d)
        return UsbDeviceInfo(
            vendorId = d.idVendor().toInt() and 0xFFFF,
            productId = d.idProduct().toInt() and 0xFFFF,
            usbClass = d.bDeviceClass().toInt() and 0xFF,
            interfaces = interfaces(dev),
        )
    }

    /** Interfaces (alt setting 0) of the active configuration; empty if libusb cannot read it. */
    private fun interfaces(dev: Device): List<UsbInterfaceInfo> {
        val cfg = ConfigDescriptor()
        var r = LibUsb.getActiveConfigDescriptor(dev, cfg)
        if (r != LibUsb.SUCCESS) r = LibUsb.getConfigDescriptor(dev, 0, cfg)
        if (r != LibUsb.SUCCESS) return emptyList()
        try {
            return cfg.iface().mapNotNull { i ->
                i.altsetting().firstOrNull()?.let { a ->
                    UsbInterfaceInfo(
                        number = a.bInterfaceNumber().toInt() and 0xFF,
                        usbClass = a.bInterfaceClass().toInt() and 0xFF,
                        subclass = a.bInterfaceSubClass().toInt() and 0xFF,
                        protocol = a.bInterfaceProtocol().toInt() and 0xFF,
                    )
                }
            }
        } finally {
            LibUsb.freeConfigDescriptor(cfg)
        }
    }
```
(imports `org.usb4java.ConfigDescriptor`, `com.technicallyvu.scope.core.usb.UsbInterfaceInfo`.)

`ImageTransforms.kt` add:
```kotlin
    fun decode(data: FrameData): BufferedImage? = when (data) {
        is FrameData.Jpeg -> decodeJpeg(data.bytes)
        is FrameData.Yuyv422 -> yuyvToImage(data)
    }

    /** Packed Y0 U Y1 V (BT.601 limited range) to TYPE_3BYTE_BGR. */
    fun yuyvToImage(f: FrameData.Yuyv422): BufferedImage {
        val img = BufferedImage(f.width, f.height, BufferedImage.TYPE_3BYTE_BGR)
        val out = (img.raster.dataBuffer as DataBufferByte).data
        val src = f.bytes
        var si = 0
        var di = 0
        while (si + 3 < src.size) {
            val y0 = src[si].toInt() and 0xFF
            val u = src[si + 1].toInt() and 0xFF
            val y1 = src[si + 2].toInt() and 0xFF
            val v = src[si + 3].toInt() and 0xFF
            di = putPixel(out, di, y0, u, v)
            di = putPixel(out, di, y1, u, v)
            si += 4
        }
        return img
    }

    private fun putPixel(out: ByteArray, di: Int, y: Int, u: Int, v: Int): Int {
        val c = y - 16
        val d = u - 128
        val e = v - 128
        out[di] = clamp((298 * c + 516 * d + 128) shr 8).toByte()          // B
        out[di + 1] = clamp((298 * c - 100 * d - 208 * e + 128) shr 8).toByte()   // G
        out[di + 2] = clamp((298 * c + 409 * e + 128) shr 8).toByte()      // R
        return di + 3
    }

    private fun clamp(v: Int) = if (v < 0) 0 else if (v > 255) 255 else v
```
(imports `com.technicallyvu.scope.core.driver.FrameData`, `java.awt.image.DataBufferByte`.)

`SnapshotWriter.kt` add:
```kotlin
    fun write(data: FrameData, rotationDegrees: Int, mirror: Boolean, dir: Path, now: LocalDateTime = LocalDateTime.now()): Path {
        val jpeg = when (data) {
            is FrameData.Jpeg -> data.bytes
            is FrameData.Yuyv422 -> encodeJpeg(ImageTransforms.yuyvToImage(data))
        }
        return write(jpeg, rotationDegrees, mirror, dir, now)
    }

    private fun encodeJpeg(img: BufferedImage, quality: Float = 0.92f): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val params = writer.defaultWriteParam.apply {
            compressionMode = ImageWriteParam.MODE_EXPLICIT
            compressionQuality = quality
        }
        val out = ByteArrayOutputStream()
        ImageIO.createImageOutputStream(out).use { ios ->
            writer.output = ios
            writer.write(null, IIOImage(img, null, null), params)
        }
        writer.dispose()
        return out.toByteArray()
    }
```
(imports `com.technicallyvu.scope.core.driver.FrameData`, `java.awt.image.BufferedImage`, `java.io.ByteArrayOutputStream`, `javax.imageio.IIOImage`, `javax.imageio.ImageIO`, `javax.imageio.ImageWriteParam`.)

`ScopeViewModel.kt` changes:
- `@Volatile private var lastJpeg: ByteArray?` → `@Volatile private var lastFrame: FrameData?`.
- `onFrame`: `val decoded = ImageTransforms.decode(frame.data) ?: return` and `lastFrame = frame.data`.
- `snapshot()`: `val data = lastFrame ?: return` … `SnapshotWriter.write(data, s.rotation, s.mirror, s.outputDir)`.
- `session()`: after `Connecting` is set and before `open`, `_state.update { it.copy(rotation = driver.defaultRotation) }`.
- `UiState.rotation` default becomes `0` (the driver sets it per session); update the KDoc.
- `finally`: `lastFrame = null`.

`ScopeViewModelTest.kt`: the recording test's `assertEquals(90, vm.state.value.rotation)` and `assertEquals(180, ...)` remain valid because the fake device matches `UseeplusDriver` (layout unknown → useeplus, rotation 90). No change required unless compilation demands it.

`Probe.kt` changes:
- Replace `val driver = UseeplusDriver(packetSink = sink)` with `val driver = DriverRegistry.find(ref.info)!!.withPacketSink(sink)`; drop the unused `UseeplusDriver` import if no longer referenced (keep `UseeplusDriver.SUPPORTED_IDS` use for the hint, or switch to `I4seasonYuvDriver.SUPPORTED_IDS`; both sets are identical).
- In the `--list` line print interfaces: `r.info.interfaces.joinToString(" ") { "if%d:%02X/%02X/%02X".format(it.number, it.usbClass, it.subclass, it.protocol) }`.
- Per-second line: replace `jpeg=%d B` with a frame description:
```kotlin
val kind = when (val d = f.data) { is FrameData.Jpeg -> "jpeg ${d.bytes.size} B"; is FrameData.Yuyv422 -> "yuyv ${d.width}x${d.height}" }
```
- Remove the flags histogram block (it was useeplus-specific) and instead print `buttonFrames` as now.

`Main.kt`: parse `--layout yuv|jpeg` (default `yuv`) and build
```kotlin
val replayInfo = if (layout == "jpeg")
    UsbDeviceInfo(0x2CE3, 0x3828, 0xEF, listOf(UsbInterfaceInfo(0, 0xFF, 0xF0, 0), UsbInterfaceInfo(1, 0xFF, 0xF0, 1)))
else UsbDeviceInfo(0x2CE3, 0x3828, 0xEF, listOf(UsbInterfaceInfo(0, 0xFF, 0xF0, 1)))
val devices: DeviceSource = replay?.let { ReplayDeviceSource(it, replayInfo) } ?: LibUsbDevices()
```
and `driverHintCheck` uses `I4seasonYuvDriver.SUPPORTED_IDS`.

- [ ] **Step 4: Run everything**

Run: `.\gradlew.bat test`
Expected: core and desktop all PASS (ImageTransforms 9, SnapshotWriter 4, view model 8, others unchanged).

Run: `.\gradlew.bat :desktop:probe --args="--list"`
Expected: the endoscope line now shows `if0:FF/F0/01  <- i4season-yuv`.

- [ ] **Step 5: Commit**

```bash
git add desktop/src
git commit -m "feat(desktop): control transfers, interface layout, YUYV decode, FrameData plumbing"
```

---

### Task 17: Hardware run, fixture capture, real-capture tests

**Files:**
- Create: `core/src/test/resources/fixtures/i4season-yuv-320x240.upkt`
- Test: `core/src/test/kotlin/com/technicallyvu/scope/core/i4season/I4seasonRealCaptureTest.kt`

- [ ] **Step 1: Stream from the real device**

Run: `.\gradlew.bat :desktop:probe --args="--seconds 5"`
Expected: `Opening 2CE3:3828 with i4season-yuv ...`, per-second lines around 10–11 fps, `yuyv 320x240`, dropped 0 or 1 (the initial resync). If `controlTransfer A0/00 failed: Pipe error`, the device is in a stuck state: unplug/replug and retry.

- [ ] **Step 2: Capture the fixture (Anthony presses the cable button 2–3 times)**

Run: `.\gradlew.bat :desktop:probe --args="--seconds 3 --record core/src/test/resources/fixtures/i4season-yuv-320x240.upkt"`
Expected: file of roughly 5 MB (3 s × 11 fps × 154 KB), `buttonFrames` ≥ 1. If `buttonFrames` is 0, recapture.

- [ ] **Step 3: Real-capture tests**

`core/src/test/kotlin/com/technicallyvu/scope/core/i4season/I4seasonRealCaptureTest.kt`:
```kotlin
package com.technicallyvu.scope.core.i4season

import com.technicallyvu.scope.core.driver.FrameData
import com.technicallyvu.scope.core.fixture.LoggedPacket
import com.technicallyvu.scope.core.fixture.PacketLogReader
import com.technicallyvu.scope.core.fixture.ReplayTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class I4seasonRealCaptureTest {
    private lateinit var packets: List<LoggedPacket>

    @BeforeAll
    fun load() {
        val stream = requireNotNull(javaClass.getResourceAsStream("/fixtures/i4season-yuv-320x240.upkt")) { "fixture missing; run Task 17" }
        packets = stream.use { PacketLogReader.read(it) }
        assertTrue(packets.size > 50, "capture too short: ${packets.size} reads")
    }

    private fun frames() = runBlocking {
        val t = ReplayTransport(packets).apply { controlResponses[0xA0 to 0x00] = I4seasonTestFrames.realInfo() }
        val source = I4seasonYuvDriver(ioDispatcher = Dispatchers.Default).open(t)
        runCatching { source.frames.toList() }.getOrElse { emptyList() }.also { source.close() }
    }

    @Test
    fun `at least 20 frames of 320x240 yuyv`() {
        val frames = frames()
        assertTrue(frames.size >= 20, "only ${frames.size} frames")
        for (f in frames) {
            val d = f.data as FrameData.Yuyv422
            assertEquals(320, d.width); assertEquals(240, d.height); assertEquals(153600, d.bytes.size)
        }
    }

    @Test
    fun `chroma bytes cluster around 128 and luma is not constant`() {
        val d = frames().last().data as FrameData.Yuyv422
        val chroma = (1 until d.bytes.size step 2).map { d.bytes[it].toInt() and 0xFF }
        val chromaMean = chroma.average()
        assertTrue(chromaMean in 100.0..156.0, "chroma mean $chromaMean; wrong byte order?")
        val luma = (0 until d.bytes.size step 2).map { d.bytes[it].toInt() and 0xFF }
        assertTrue(luma.distinct().size > 16, "luma looks constant; not a picture")
    }

    @Test
    fun `frame rate from timestamps is between 5 and 30 fps`() {
        val frames = frames()
        val span = (frames.last().timestampNanos - frames.first().timestampNanos) / 1e9
        val fps = (frames.size - 1) / span
        assertTrue(fps in 5.0..30.0, "fps $fps")
    }

    @Test
    fun `a button press appears in the capture`() {
        assertTrue(frames().any { it.buttonPressed }, "no buttonPressed frame; recapture while pressing the button")
    }
}
```

- [ ] **Step 4: Run and commit**

Run: `.\gradlew.bat :core:test --tests "com.technicallyvu.scope.core.i4season.I4seasonRealCaptureTest"`
Expected: 4 PASS.

```bash
git add core/src/test
git commit -m "test(core): real i4season capture from Anthony's unit with button press"
```

---

### Task 18: Phase 1 acceptance (original Task 12 Steps 3–5) and wrap-up (original Task 13)

- [ ] **Step 1: Replay run**

Run: `.\gradlew.bat :desktop:run --args="--replay core/src/test/resources/fixtures/i4season-yuv-320x240.upkt"`
Expected: window shows the looping capture as live video at ~11 fps; Snapshot writes a `.jpg`; Record/Stop writes a playable `.mp4`.

- [ ] **Step 2: Real-device acceptance with Anthony**

Follow the original Task 12 Step 4 checklist (plug in → streaming within ~2 s; rotate/mirror to taste and record the chosen default; snapshot opens upright in Windows Photos; 5 s clip plays; cable button saves a snapshot; unplug mid-stream → "No device" within ~5 s and re-plug resumes; unplug during recording → clip still plays). Record observations in `docs/phase1-notes.md`.

- [ ] **Step 3: Full test run, README, tag** — as original Task 13.

```bash
.\gradlew.bat test
git add -A
git commit -m "docs: phase 1 notes and README status"
git tag -a v0.1.0-bench -m "Phase 1: Windows dev bench accepted (i4season YUV unit)"
```
