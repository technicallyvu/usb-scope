# Phase 1: Windows Dev Bench — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A Windows desktop viewer that streams live video from Anthony's useeplus endoscope (USB `2CE3:3828`), saves photos and MP4 clips, and leaves behind real packet fixtures plus parser/reassembler tests.

**Architecture:** One Gradle workspace. `core` is pure Kotlin/JVM: the `UsbTransport`/`DeviceDriver` interfaces, the useeplus packet parser, frame reassembler, driver, and a packet-log fixture format with a replay transport. `desktop` is Compose for Desktop plus a libusb (usb4java) transport, a probe CLI for first hardware contact and fixture capture, and the viewer UI. UI never touches USB.

**Tech Stack:** Kotlin 2.4.10, Gradle 9.7.1, Compose Multiplatform 1.12.0, kotlinx-coroutines 1.11.0, JUnit Jupiter 6.1.3, usb4java 1.3.0, JavaCV 1.5.14 + bytedeco ffmpeg 8.1.2-1.5.14 (windows-x86_64), Apache Commons Imaging 1.0.0-alpha6. JDK 17 (present on the workstation).

## Global Constraints

- Spec: `docs/superpowers/specs/2026-09-08-usb-endoscope-app-design.md`. Read §2, §4.1, §5.1 before starting.
- Package root: `com.technicallyvu.scope`. Modules: `core`, `desktop`.
- Every dependency pinned to the exact version above; all were checked against OSV (api.osv.dev) on 2026-09-08 with zero known vulnerabilities. Record any version change in `docs/dependencies.md` with a fresh OSV check.
- No network calls at runtime. No telemetry, analytics, or crash reporters.
- Reimplement the protocol from the spec's documentation; do not copy code from the GPL Linux drivers.
- No vendor trademarks ("Anesok", "Sup-Anesok", "Usee") in any UI string, file name, or package name. Window title is `USB Scope (dev bench)`.
- Deviations from the spec accepted for Phase 1 (Android will follow the spec exactly): desktop MP4 uses the MPEG-4 Part 2 encoder (always present in the LGPL ffmpeg build, universally playable) instead of H.264; desktop uses one output folder (default `~/Pictures/USB Scope`) for both photos and clips.
- Windows requires the one-time Zadig WinUSB bind before libusb can see the device (Task 7 is a human step for Anthony).
- Run commands from `C:\Projects\usb-endoscope-app` in PowerShell: `.\gradlew.bat ...`. All commits use the repo-local identity already configured.
- Tests: JUnit 5 API (Jupiter) under `src/test/kotlin`. Run a single class with `.\gradlew.bat :core:test --tests "com.technicallyvu.scope.core.useeplus.UseeplusPacketTest"`.

---

## File Structure

```
usb-endoscope-app/
  settings.gradle.kts, build.gradle.kts, gradle.properties, gradle/libs.versions.toml, gradlew(.bat)
  LICENSE (MIT), README.md, .gitignore, docs/dependencies.md, docs/windows-setup.md
  core/build.gradle.kts
  core/src/main/kotlin/com/technicallyvu/scope/core/
    usb/UsbTransport.kt          UsbTransport, UsbDeviceInfo, UsbException
    usb/DeviceSource.kt          DeviceRef, DeviceSource (enumerate + open; per-platform impls)
    driver/DeviceDriver.kt       Frame, StreamStats, PacketSink, FrameSource, DeviceDriver
    driver/DriverRegistry.kt     list of drivers, find(info)
    useeplus/UseeplusPacket.kt   UseeplusChunk + UseeplusPacket.parse (pure)
    useeplus/FrameReassembler.kt chunks -> JPEG frames, drop counting
    useeplus/FpsMeter.kt         sliding-window fps
    useeplus/UseeplusDriver.kt   handshake, retry, UseeplusFrameSource read loop
    fixture/PacketLog.kt         LoggedPacket, PacketLogWriter, PacketLogReader (.upkt format)
    fixture/ReplayTransport.kt   UsbTransport that replays a packet list (tests + --replay mode)
    fixture/ReplayDeviceSource.kt DeviceSource exposing one replayed device
  core/src/test/kotlin/com/technicallyvu/scope/core/
    TestPackets.kt               builder for synthetic useeplus packets
    useeplus/UseeplusPacketTest.kt, FrameReassemblerTest.kt, FpsMeterTest.kt, UseeplusDriverTest.kt, RealCaptureTest.kt
    fixture/PacketLogTest.kt
  core/src/test/resources/fixtures/useeplus-640x480.upkt   (captured in Task 7)
  desktop/build.gradle.kts
  desktop/src/main/kotlin/com/technicallyvu/scope/desktop/
    usb/LibUsbTransport.kt       usb4java UsbTransport
    usb/LibUsbDevices.kt         usb4java DeviceSource
    usb/WindowsDeviceCheck.kt    pnputil-based "present but no driver" detection
    media/ImageTransforms.kt     JPEG decode, rotate/mirror
    media/SnapshotWriter.kt      JPEG bytes + EXIF orientation via Commons Imaging
    media/Mp4Recorder.kt         JavaCV recorder
    ui/ScopeViewModel.kt         UiState, ConnectionState, session loop, actions
    ui/ScopeScreen.kt            Compose UI
    Probe.kt                     CLI: list devices, stream, capture fixture, flags histogram
    Main.kt                      Compose entry point (--replay <file> for hardware-free runs)
  desktop/src/test/kotlin/com/technicallyvu/scope/desktop/
    TestPackets.kt, usb/WindowsDeviceCheckTest.kt, media/ImageTransformsTest.kt,
    media/SnapshotWriterTest.kt, media/Mp4RecorderTest.kt, ui/ScopeViewModelTest.kt
```

---

### Task 1: Gradle workspace, core module skeleton, repo hygiene

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`, `core/build.gradle.kts`, `.gitignore`, `LICENSE`, `README.md`, `docs/dependencies.md`
- Create: `core/src/test/kotlin/com/technicallyvu/scope/core/SmokeTest.kt`

**Interfaces:**
- Produces: the Gradle version catalog aliases used by every later task (`libs.kotlinx.coroutines.core`, `libs.junit.bom`, `libs.junit.jupiter`, `libs.kotlinx.coroutines.test`, `libs.usb4java`, `libs.javacv`, `libs.ffmpeg`, `libs.javacpp`, `libs.commons.imaging`, `libs.kotlinx.coroutines.swing`; plugins `libs.plugins.kotlin.jvm`, `libs.plugins.kotlin.compose`, `libs.plugins.compose`).

- [ ] **Step 1: Bootstrap the Gradle wrapper (no Gradle is installed)**

Run in PowerShell:
```powershell
$tmp = "$env:TEMP\gradle-boot"; New-Item -ItemType Directory -Force $tmp | Out-Null
Invoke-WebRequest https://services.gradle.org/distributions/gradle-9.7.1-bin.zip -OutFile "$tmp\gradle.zip"
Expand-Archive "$tmp\gradle.zip" -DestinationPath $tmp -Force
& "$tmp\gradle-9.7.1\bin\gradle.bat" wrapper --gradle-version 9.7.1 --distribution-type bin
```
Expected: `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties` exist. (`gradle init` is not needed; the wrapper task works in an empty directory.)

- [ ] **Step 2: Write the settings, root build, properties, and version catalog**

`settings.gradle.kts`:
```kotlin
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "usb-endoscope-app"
include(":core")
```

`build.gradle.kts` (root):
```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.compose) apply false
}
```

`gradle.properties`:
```properties
org.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8
kotlin.code.style=official
```

`gradle/libs.versions.toml`:
```toml
[versions]
kotlin = "2.4.10"
compose = "1.12.0"
coroutines = "1.11.0"
junit = "6.1.3"
usb4java = "1.3.0"
javacv = "1.5.14"
ffmpeg = "8.1.2-1.5.14"
commonsImaging = "1.0.0-alpha6"

[libraries]
kotlinx-coroutines-core = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "coroutines" }
kotlinx-coroutines-swing = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-swing", version.ref = "coroutines" }
kotlinx-coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "coroutines" }
junit-bom = { module = "org.junit:junit-bom", version.ref = "junit" }
junit-jupiter = { module = "org.junit.jupiter:junit-jupiter" }
junit-platform-launcher = { module = "org.junit.platform:junit-platform-launcher" }
usb4java = { module = "org.usb4java:usb4java", version.ref = "usb4java" }
javacv = { module = "org.bytedeco:javacv", version.ref = "javacv" }
javacpp = { module = "org.bytedeco:javacpp", version.ref = "javacv" }
ffmpeg = { module = "org.bytedeco:ffmpeg", version.ref = "ffmpeg" }
commons-imaging = { module = "org.apache.commons:commons-imaging", version.ref = "commonsImaging" }

[plugins]
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
compose = { id = "org.jetbrains.compose", version.ref = "compose" }
```

- [ ] **Step 3: Write the core module build file**

`core/build.gradle.kts`:
```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(libs.kotlinx.coroutines.core)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "failed", "skipped") }
}
```

- [ ] **Step 4: Write a failing smoke test**

`core/src/test/kotlin/com/technicallyvu/scope/core/SmokeTest.kt`:
```kotlin
package com.technicallyvu.scope.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SmokeTest {
    @Test
    fun `toolchain runs kotlin tests`() {
        assertEquals(4, 2 + 2)
    }
}
```

- [ ] **Step 5: Run the build**

Run: `.\gradlew.bat :core:test`
Expected: `BUILD SUCCESSFUL`, one test passed. (First run downloads dependencies; allow several minutes.)

- [ ] **Step 6: Repo hygiene files**

`.gitignore`:
```
.gradle/
build/
.kotlin/
.idea/
*.iml
out/
local.properties
*.log
```

`LICENSE`: the MIT license text with `Copyright (c) 2026 Anthony Vu (TechnicallyVu)`.

`README.md`:
```markdown
# USB Scope (working name)

Privacy-first, open-source viewer for cheap USB endoscopes that ship with untrusted vendor apps.
First supported device: Geek szitman "supercamera" (USB 2CE3:3828 / 0329:2022, useeplus protocol).

Phase 1 = Windows dev bench. See `docs/superpowers/specs/` for the design and `docs/windows-setup.md`
for the one-time WinUSB driver step.

## Quick start (Windows)
    .\gradlew.bat :core:test                       # protocol tests
    .\gradlew.bat :desktop:probe --args="--list"   # what libusb can see
    .\gradlew.bat :desktop:run                     # the viewer

## Prior work
Protocol documentation reimplemented from: hbens/geek-szitman-supercamera (CC0),
echase/ProbeView (MIT), MAkcanca/useeplus-linux-driver (GPL-3, reference only),
jmz3/EndoscopeCamera, NinesLastGoal/supercamera_WIN10.
```

`docs/dependencies.md`:
```markdown
# Dependency pins and vulnerability checks

Checked 2026-09-08 against OSV (https://api.osv.dev/v1/query, ecosystem Maven). All zero known vulns.

| Artifact | Version |
|---|---|
| org.jetbrains.kotlin:* | 2.4.10 |
| org.jetbrains.compose (gradle plugin + libs) | 1.12.0 |
| org.jetbrains.kotlinx:kotlinx-coroutines-* | 1.11.0 |
| org.junit:junit-bom | 6.1.3 |
| org.usb4java:usb4java / libusb4java | 1.3.0 |
| org.bytedeco:javacv, javacpp | 1.5.14 |
| org.bytedeco:ffmpeg | 8.1.2-1.5.14 (windows-x86_64) |
| org.apache.commons:commons-imaging | 1.0.0-alpha6 |

Re-run before every release:
    curl -s -X POST https://api.osv.dev/v1/query -H "content-type: application/json" \
      -d "{\"version\":\"<ver>\",\"package\":{\"name\":\"<group>:<artifact>\",\"ecosystem\":\"Maven\"}}"
```

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "build: gradle workspace, core module skeleton, license, dependency pins"
```

---

### Task 2: Core interfaces and the useeplus packet parser

**Files:**
- Create: `core/src/main/kotlin/com/technicallyvu/scope/core/usb/UsbTransport.kt`
- Create: `core/src/main/kotlin/com/technicallyvu/scope/core/usb/DeviceSource.kt`
- Create: `core/src/main/kotlin/com/technicallyvu/scope/core/driver/DeviceDriver.kt`
- Create: `core/src/main/kotlin/com/technicallyvu/scope/core/useeplus/UseeplusPacket.kt`
- Create: `core/src/test/kotlin/com/technicallyvu/scope/core/TestPackets.kt`
- Test: `core/src/test/kotlin/com/technicallyvu/scope/core/useeplus/UseeplusPacketTest.kt`

**Interfaces:**
- Produces: `UsbTransport`, `UsbDeviceInfo`, `UsbException`, `DeviceRef`, `DeviceSource`, `Frame`, `StreamStats`, `PacketSink`, `FrameSource`, `DeviceDriver`, `UseeplusChunk`, `UseeplusPacket.parse(buf, len): UseeplusChunk?`, `TestPackets.packet(...)`.

- [ ] **Step 1: Write the interfaces (no tests; they are contracts)**

`core/src/main/kotlin/com/technicallyvu/scope/core/usb/UsbTransport.kt`:
```kotlin
package com.technicallyvu.scope.core.usb

class UsbException(message: String, val code: Int = 0, cause: Throwable? = null) : Exception(message, cause)

data class UsbDeviceInfo(val vendorId: Int, val productId: Int, val usbClass: Int) {
    val idString: String get() = "%04X:%04X".format(vendorId, productId)
}

/** The five USB primitives a driver needs. One implementation per platform. */
interface UsbTransport : AutoCloseable {
    fun claimInterface(iface: Int)
    fun releaseInterface(iface: Int)
    fun setAltSetting(iface: Int, alt: Int)
    fun clearHalt(endpoint: Int)
    /** Returns bytes written. Throws [UsbException] on error. */
    fun bulkWrite(endpoint: Int, data: ByteArray, timeoutMs: Int): Int
    /** Returns bytes read; 0 on timeout. Throws [UsbException] on any other error, including device removal. */
    fun bulkRead(endpoint: Int, buffer: ByteArray, timeoutMs: Int): Int
    fun resetDevice()
}
```

`core/src/main/kotlin/com/technicallyvu/scope/core/usb/DeviceSource.kt`:
```kotlin
package com.technicallyvu.scope.core.usb

data class DeviceRef(val info: UsbDeviceInfo, val bus: Int, val address: Int)

/** Enumerates attached USB devices and opens one. One implementation per platform. */
interface DeviceSource {
    fun list(): List<DeviceRef>
    fun open(ref: DeviceRef): UsbTransport
}
```

`core/src/main/kotlin/com/technicallyvu/scope/core/driver/DeviceDriver.kt`:
```kotlin
package com.technicallyvu.scope.core.driver

import com.technicallyvu.scope.core.usb.UsbDeviceInfo
import com.technicallyvu.scope.core.usb.UsbTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

class Frame(
    val jpeg: ByteArray,
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
    fun matches(info: UsbDeviceInfo): Boolean
    /** Performs the device handshake. Throws [com.technicallyvu.scope.core.usb.UsbException] if the device cannot be started. */
    fun open(transport: UsbTransport): FrameSource
}
```

- [ ] **Step 2: Write the synthetic packet builder for tests**

`core/src/test/kotlin/com/technicallyvu/scope/core/TestPackets.kt`:
```kotlin
package com.technicallyvu.scope.core

object TestPackets {
    /** Builds one useeplus bulk packet: 5-byte USB header + 7-byte camera header + payload. */
    fun packet(
        channelId: Int,
        frameId: Int,
        payload: ByteArray,
        cameraNumber: Int = 0,
        flags: Int = 0,
        sensor: Long = 0,
    ): ByteArray {
        val length = 7 + payload.size
        val out = ByteArray(12 + payload.size)
        out[0] = 0xAA.toByte(); out[1] = 0xBB.toByte()
        out[2] = channelId.toByte()
        out[3] = (length and 0xFF).toByte(); out[4] = ((length shr 8) and 0xFF).toByte()
        out[5] = frameId.toByte(); out[6] = cameraNumber.toByte(); out[7] = flags.toByte()
        out[8] = (sensor and 0xFF).toByte(); out[9] = ((sensor shr 8) and 0xFF).toByte()
        out[10] = ((sensor shr 16) and 0xFF).toByte(); out[11] = ((sensor shr 24) and 0xFF).toByte()
        payload.copyInto(out, 12)
        return out
    }

    val SOI = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
    val EOI = byteArrayOf(0xFF.toByte(), 0xD9.toByte())

    /** A fake "JPEG": SOI + filler + EOI. Not decodable, but passes marker checks. */
    fun fakeJpeg(fillerBytes: Int = 20): ByteArray = SOI + ByteArray(fillerBytes) { 0x11 } + EOI

    /** Splits [frame] into a head (channel 7) and tail (channel 11) packet for [frameId]. */
    fun framePackets(frameId: Int, frame: ByteArray, flags: Int = 0): List<ByteArray> {
        val half = frame.size / 2
        return listOf(
            packet(7, frameId, frame.copyOfRange(0, half), flags = flags),
            packet(11, frameId, frame.copyOfRange(half, frame.size), flags = flags),
        )
    }
}
```

- [ ] **Step 3: Write the failing parser tests**

`core/src/test/kotlin/com/technicallyvu/scope/core/useeplus/UseeplusPacketTest.kt`:
```kotlin
package com.technicallyvu.scope.core.useeplus

import com.technicallyvu.scope.core.TestPackets.packet
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class UseeplusPacketTest {
    private val payload = byteArrayOf(1, 2, 3, 4, 5)

    @Test
    fun `parses a head packet`() {
        val pkt = packet(channelId = 7, frameId = 42, payload = payload, cameraNumber = 1, flags = 0x02, sensor = 0x01020304)
        val chunk = assertNotNull(UseeplusPacket.parse(pkt, pkt.size))
        assertEquals(7, chunk.channelId)
        assertEquals(42, chunk.frameId)
        assertEquals(1, chunk.cameraNumber)
        assertEquals(0x02, chunk.flags)
        assertEquals(0x01020304L, chunk.sensorValue)
        assertArrayEquals(payload, chunk.payload)
    }

    @Test
    fun `accepts tail channel 11`() {
        val pkt = packet(channelId = 11, frameId = 1, payload = payload)
        assertEquals(11, UseeplusPacket.parse(pkt, pkt.size)?.channelId)
    }

    @Test
    fun `rejects other channels`() {
        val pkt = packet(channelId = 3, frameId = 1, payload = payload)
        assertNull(UseeplusPacket.parse(pkt, pkt.size))
    }

    @Test
    fun `rejects bad magic`() {
        val pkt = packet(channelId = 7, frameId = 1, payload = payload)
        pkt[0] = 0x00
        assertNull(UseeplusPacket.parse(pkt, pkt.size))
    }

    @Test
    fun `rejects buffers shorter than the headers`() {
        assertNull(UseeplusPacket.parse(ByteArray(11), 11))
    }

    @Test
    fun `rejects declared length beyond the buffer`() {
        val pkt = packet(channelId = 7, frameId = 1, payload = payload)
        pkt[3] = 0xFF.toByte(); pkt[4] = 0x03  // length 1023 > buffer
        assertNull(UseeplusPacket.parse(pkt, pkt.size))
    }

    @Test
    fun `payload stops at declared length even when the buffer is larger`() {
        val pkt = packet(channelId = 7, frameId = 1, payload = payload)
        val padded = pkt + ByteArray(100) { 0x77 }   // a 1 KB bulk read has trailing garbage
        assertArrayEquals(payload, UseeplusPacket.parse(padded, padded.size)?.payload)
    }

    @Test
    fun `frame id and sensor are unsigned`() {
        val pkt = packet(channelId = 7, frameId = 0xFE, payload = payload, sensor = 0xFFFFFFFFL)
        val chunk = assertNotNull(UseeplusPacket.parse(pkt, pkt.size))
        assertEquals(0xFE, chunk.frameId)
        assertEquals(0xFFFFFFFFL, chunk.sensorValue)
    }
}
```

- [ ] **Step 4: Run the tests to verify they fail**

Run: `.\gradlew.bat :core:test --tests "com.technicallyvu.scope.core.useeplus.UseeplusPacketTest"`
Expected: compilation FAILS with `Unresolved reference: UseeplusPacket`.

- [ ] **Step 5: Implement the parser**

`core/src/main/kotlin/com/technicallyvu/scope/core/useeplus/UseeplusPacket.kt`:
```kotlin
package com.technicallyvu.scope.core.useeplus

/** One parsed bulk-IN packet: the camera header fields plus the JPEG payload slice. */
class UseeplusChunk(
    val channelId: Int,
    val frameId: Int,
    val cameraNumber: Int,
    val flags: Int,
    val sensorValue: Long,
    val payload: ByteArray,
)

/**
 * useeplus packet layout (spec §5.1):
 *   0-1  magic AA BB        2 channel id (7 head / 11 tail)   3-4 length LE (camera header + payload)
 *   5    frame id           6 camera number                   7   flags
 *   8-11 g-sensor uint32 LE 12.. JPEG payload
 */
object UseeplusPacket {
    const val USB_HEADER_LEN = 5
    const val CAMERA_HEADER_LEN = 7
    const val PAYLOAD_OFFSET = USB_HEADER_LEN + CAMERA_HEADER_LEN
    const val MAGIC0 = 0xAA
    const val MAGIC1 = 0xBB
    const val CHANNEL_HEAD = 7
    const val CHANNEL_TAIL = 11

    /** Which bit of the flags byte means "cable button pressed". Verified against a real capture in Task 8. */
    const val BUTTON_MASK = 0x02

    fun parse(buf: ByteArray, len: Int = buf.size): UseeplusChunk? {
        if (len < PAYLOAD_OFFSET) return null
        if (u8(buf, 0) != MAGIC0 || u8(buf, 1) != MAGIC1) return null
        val channel = u8(buf, 2)
        if (channel != CHANNEL_HEAD && channel != CHANNEL_TAIL) return null
        val length = u8(buf, 3) or (u8(buf, 4) shl 8)
        if (length < CAMERA_HEADER_LEN) return null
        val end = USB_HEADER_LEN + length
        if (end > len) return null
        val sensor = u8(buf, 8).toLong() or
            (u8(buf, 9).toLong() shl 8) or
            (u8(buf, 10).toLong() shl 16) or
            (u8(buf, 11).toLong() shl 24)
        return UseeplusChunk(
            channelId = channel,
            frameId = u8(buf, 5),
            cameraNumber = u8(buf, 6),
            flags = u8(buf, 7),
            sensorValue = sensor,
            payload = buf.copyOfRange(PAYLOAD_OFFSET, end),
        )
    }

    private fun u8(buf: ByteArray, i: Int): Int = buf[i].toInt() and 0xFF
}
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `.\gradlew.bat :core:test --tests "com.technicallyvu.scope.core.useeplus.UseeplusPacketTest"`
Expected: 8 tests PASS.

- [ ] **Step 7: Commit**

```bash
git add core/src
git commit -m "feat(core): transport/driver interfaces and useeplus packet parser"
```

---

### Task 3: Frame reassembler and FPS meter

**Files:**
- Create: `core/src/main/kotlin/com/technicallyvu/scope/core/useeplus/FrameReassembler.kt`
- Create: `core/src/main/kotlin/com/technicallyvu/scope/core/useeplus/FpsMeter.kt`
- Test: `core/src/test/kotlin/com/technicallyvu/scope/core/useeplus/FrameReassemblerTest.kt`
- Test: `core/src/test/kotlin/com/technicallyvu/scope/core/useeplus/FpsMeterTest.kt`

**Interfaces:**
- Consumes: `UseeplusChunk`, `UseeplusPacket.BUTTON_MASK`, `Frame`.
- Produces: `FrameReassembler.accept(chunk, nowNanos): Frame?`, properties `framesEmitted`, `framesDropped`, `bytesReceived`; `FrameReassembler.isJpeg(bytes)`; `FpsMeter.tick(nowNanos): Double`.

- [ ] **Step 1: Write the failing reassembler tests**

`core/src/test/kotlin/com/technicallyvu/scope/core/useeplus/FrameReassemblerTest.kt`:
```kotlin
package com.technicallyvu.scope.core.useeplus

import com.technicallyvu.scope.core.TestPackets
import com.technicallyvu.scope.core.TestPackets.packet
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FrameReassemblerTest {
    private fun chunk(bytes: ByteArray) = UseeplusPacket.parse(bytes)!!

    @Test
    fun `emits the previous frame when the frame id changes`() {
        val r = FrameReassembler()
        val jpeg = TestPackets.fakeJpeg()
        val (head, tail) = TestPackets.framePackets(frameId = 1, frame = jpeg)
        assertNull(r.accept(chunk(head), 100))
        assertNull(r.accept(chunk(tail), 200))
        val next = packet(7, frameId = 2, payload = TestPackets.SOI)
        val frame = assertNotNull(r.accept(chunk(next), 300))
        assertArrayEquals(jpeg, frame.jpeg)
        assertEquals(300, frame.timestampNanos)
        assertEquals(1, r.framesEmitted)
        assertEquals(0, r.framesDropped)
    }

    @Test
    fun `drops a frame without an end marker and counts it`() {
        val r = FrameReassembler()
        r.accept(chunk(packet(7, frameId = 1, payload = TestPackets.SOI + byteArrayOf(1, 2, 3))), 0)
        assertNull(r.accept(chunk(packet(7, frameId = 2, payload = TestPackets.SOI)), 0))
        assertEquals(0, r.framesEmitted)
        assertEquals(1, r.framesDropped)
    }

    @Test
    fun `drops a frame without a start marker`() {
        val r = FrameReassembler()
        r.accept(chunk(packet(11, frameId = 1, payload = byteArrayOf(1, 2) + TestPackets.EOI)), 0)
        assertNull(r.accept(chunk(packet(7, frameId = 2, payload = TestPackets.SOI)), 0))
        assertEquals(1, r.framesDropped)
    }

    @Test
    fun `button flag on any chunk marks the frame`() {
        val r = FrameReassembler()
        val jpeg = TestPackets.fakeJpeg()
        val half = jpeg.size / 2
        r.accept(chunk(packet(7, 1, jpeg.copyOfRange(0, half), flags = 0)), 0)
        r.accept(chunk(packet(11, 1, jpeg.copyOfRange(half, jpeg.size), flags = UseeplusPacket.BUTTON_MASK)), 0)
        val frame = assertNotNull(r.accept(chunk(packet(7, 2, TestPackets.SOI)), 0))
        assertTrue(frame.buttonPressed)

        r.accept(chunk(packet(11, 2, jpeg.copyOfRange(2, jpeg.size))), 0)
        val second = assertNotNull(r.accept(chunk(packet(7, 3, TestPackets.SOI)), 0))
        assertFalse(second.buttonPressed)
    }

    @Test
    fun `carries camera number and sensor value from the frame`() {
        val r = FrameReassembler()
        val jpeg = TestPackets.fakeJpeg()
        r.accept(chunk(packet(7, 1, jpeg, cameraNumber = 3, sensor = 0xABCD)), 0)
        val frame = assertNotNull(r.accept(chunk(packet(7, 2, TestPackets.SOI)), 0))
        assertEquals(3, frame.cameraNumber)
        assertEquals(0xABCDL, frame.sensorValue)
    }

    @Test
    fun `counts payload bytes`() {
        val r = FrameReassembler()
        r.accept(chunk(packet(7, 1, ByteArray(10))), 0)
        r.accept(chunk(packet(11, 1, ByteArray(5))), 0)
        assertEquals(15, r.bytesReceived)
    }

    @Test
    fun `isJpeg requires both markers`() {
        assertTrue(FrameReassembler.isJpeg(TestPackets.fakeJpeg()))
        assertFalse(FrameReassembler.isJpeg(TestPackets.SOI + byteArrayOf(0)))
        assertFalse(FrameReassembler.isJpeg(byteArrayOf(0) + TestPackets.EOI))
        assertFalse(FrameReassembler.isJpeg(ByteArray(0)))
    }
}
```

- [ ] **Step 2: Write the failing FPS meter test**

`core/src/test/kotlin/com/technicallyvu/scope/core/useeplus/FpsMeterTest.kt`:
```kotlin
package com.technicallyvu.scope.core.useeplus

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FpsMeterTest {
    private val ms = 1_000_000L

    @Test
    fun `zero until two samples`() {
        val m = FpsMeter()
        assertEquals(0.0, m.tick(0))
    }

    @Test
    fun `ten frames per second`() {
        val m = FpsMeter(windowNanos = 2_000 * ms)
        var fps = 0.0
        for (i in 0..10) fps = m.tick(i * 100 * ms)
        assertEquals(10.0, fps, 0.01)
    }

    @Test
    fun `old samples fall out of the window`() {
        val m = FpsMeter(windowNanos = 1_000 * ms)
        m.tick(0)
        m.tick(100 * ms)
        val fps = m.tick(5_000 * ms)   // only the last two samples remain: 4.9 s apart
        assertEquals(1.0 / 4.9, fps, 0.01)
    }
}
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `.\gradlew.bat :core:test --tests "com.technicallyvu.scope.core.useeplus.*"`
Expected: compilation FAILS with `Unresolved reference: FrameReassembler` / `FpsMeter`.

- [ ] **Step 4: Implement the reassembler**

`core/src/main/kotlin/com/technicallyvu/scope/core/useeplus/FrameReassembler.kt`:
```kotlin
package com.technicallyvu.scope.core.useeplus

import com.technicallyvu.scope.core.driver.Frame
import java.io.ByteArrayOutputStream

/**
 * Accumulates payload chunks while the frame id is unchanged. When the id changes, the
 * accumulated bytes are emitted as a [Frame] if they carry JPEG SOI/EOI markers, else dropped.
 * Not thread-safe; owned by one read loop.
 */
class FrameReassembler {
    private val buffer = ByteArrayOutputStream(64 * 1024)
    private var currentFrameId = -1
    private var flagsOr = 0
    private var cameraNumber = 0
    private var sensorValue = 0L

    var framesEmitted: Long = 0; private set
    var framesDropped: Long = 0; private set
    var bytesReceived: Long = 0; private set

    /** Returns the completed previous frame, if this chunk started a new one and the previous was valid. */
    fun accept(chunk: UseeplusChunk, nowNanos: Long): Frame? {
        bytesReceived += chunk.payload.size
        var completed: Frame? = null
        if (chunk.frameId != currentFrameId) {
            if (buffer.size() > 0) completed = finish(nowNanos)
            currentFrameId = chunk.frameId
            flagsOr = 0
        }
        buffer.write(chunk.payload)
        flagsOr = flagsOr or chunk.flags
        cameraNumber = chunk.cameraNumber
        sensorValue = chunk.sensorValue
        return completed
    }

    private fun finish(nowNanos: Long): Frame? {
        val bytes = buffer.toByteArray()
        buffer.reset()
        return if (isJpeg(bytes)) {
            framesEmitted++
            Frame(
                jpeg = bytes,
                timestampNanos = nowNanos,
                buttonPressed = (flagsOr and UseeplusPacket.BUTTON_MASK) != 0,
                cameraNumber = cameraNumber,
                sensorValue = sensorValue,
            )
        } else {
            framesDropped++
            null
        }
    }

    companion object {
        fun isJpeg(b: ByteArray): Boolean =
            b.size >= 4 &&
                b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() &&
                b[b.size - 2] == 0xFF.toByte() && b[b.size - 1] == 0xD9.toByte()
    }
}
```

- [ ] **Step 5: Implement the FPS meter**

`core/src/main/kotlin/com/technicallyvu/scope/core/useeplus/FpsMeter.kt`:
```kotlin
package com.technicallyvu.scope.core.useeplus

/** Frames per second over a sliding time window of frame timestamps. */
class FpsMeter(private val windowNanos: Long = 2_000_000_000L) {
    private val stamps = ArrayDeque<Long>()

    fun tick(nowNanos: Long): Double {
        stamps.addLast(nowNanos)
        while (stamps.size > 1 && nowNanos - stamps.first() > windowNanos) stamps.removeFirst()
        if (stamps.size < 2) return 0.0
        val span = stamps.last() - stamps.first()
        return if (span <= 0) 0.0 else (stamps.size - 1) * 1e9 / span
    }
}
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `.\gradlew.bat :core:test --tests "com.technicallyvu.scope.core.useeplus.*"`
Expected: all PASS (8 parser + 7 reassembler + 3 fps).

- [ ] **Step 7: Commit**

```bash
git add core/src
git commit -m "feat(core): frame reassembler with JPEG validation and fps meter"
```

---

### Task 4: Packet-log fixture format and replay transport

**Files:**
- Create: `core/src/main/kotlin/com/technicallyvu/scope/core/fixture/PacketLog.kt`
- Create: `core/src/main/kotlin/com/technicallyvu/scope/core/fixture/ReplayTransport.kt`
- Create: `core/src/main/kotlin/com/technicallyvu/scope/core/fixture/ReplayDeviceSource.kt`
- Test: `core/src/test/kotlin/com/technicallyvu/scope/core/fixture/PacketLogTest.kt`

**Interfaces:**
- Consumes: `PacketSink`, `UsbTransport`, `UsbException`, `DeviceSource`, `DeviceRef`, `UsbDeviceInfo`.
- Produces: `LoggedPacket(timestampNanos, bytes)`, `PacketLogWriter(OutputStream) : PacketSink, AutoCloseable`, `PacketLogReader.read(InputStream): List<LoggedPacket>`, `ReplayTransport(packets, loop, sleep)` with `calls: MutableList<String>`, `failuresBeforeSuccess`, `resets`; `ReplayDeviceSource(path)`.

`.upkt` format: bytes `55 50 4B 54` ("UPKT"), one version byte `01`, then repeated records of `int64 LE timestampNanos`, `uint16 LE length`, `length` bytes.

- [ ] **Step 1: Write the failing round-trip test**

`core/src/test/kotlin/com/technicallyvu/scope/core/fixture/PacketLogTest.kt`:
```kotlin
package com.technicallyvu.scope.core.fixture

import com.technicallyvu.scope.core.TestPackets.packet
import com.technicallyvu.scope.core.usb.UsbException
import com.technicallyvu.scope.core.useeplus.UseeplusDriver
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class PacketLogTest {
    @Test
    fun `writer and reader round trip`() {
        val p1 = packet(7, 1, byteArrayOf(1, 2, 3))
        val p2 = packet(11, 1, byteArrayOf(4, 5))
        val out = ByteArrayOutputStream()
        PacketLogWriter(out).use { w ->
            w.onPacket(p1, p1.size, 1_000)
            w.onPacket(p2 + ByteArray(50), p2.size, 2_000)   // len < buffer: only len bytes stored
        }
        val bytes = out.toByteArray()
        assertEquals('U'.code.toByte(), bytes[0])
        val packets = PacketLogReader.read(ByteArrayInputStream(bytes))
        assertEquals(2, packets.size)
        assertEquals(1_000, packets[0].timestampNanos)
        assertArrayEquals(p1, packets[0].bytes)
        assertEquals(2_000, packets[1].timestampNanos)
        assertArrayEquals(p2, packets[1].bytes)
    }

    @Test
    fun `reader rejects a file without the magic`() {
        assertThrows(IllegalArgumentException::class.java) {
            PacketLogReader.read(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5)))
        }
    }

    @Test
    fun `replay transport serves video packets in order and ends`() {
        val p1 = packet(7, 1, byteArrayOf(1))
        val p2 = packet(11, 1, byteArrayOf(2))
        val t = ReplayTransport(listOf(LoggedPacket(0, p1), LoggedPacket(10, p2)))
        val buf = ByteArray(1024)
        assertEquals(p1.size, t.bulkRead(UseeplusDriver.EP_VIDEO_IN, buf, 100))
        assertArrayEquals(p1, buf.copyOf(p1.size))
        assertEquals(p2.size, t.bulkRead(UseeplusDriver.EP_VIDEO_IN, buf, 100))
        assertThrows(UsbException::class.java) { t.bulkRead(UseeplusDriver.EP_VIDEO_IN, buf, 100) }
    }

    @Test
    fun `replay transport loops when asked and paces by timestamp`() {
        val p1 = packet(7, 1, byteArrayOf(1))
        val slept = mutableListOf<Long>()
        val t = ReplayTransport(listOf(LoggedPacket(0, p1), LoggedPacket(50_000_000, p1)), loop = true, sleep = { slept += it })
        val buf = ByteArray(1024)
        repeat(3) { t.bulkRead(UseeplusDriver.EP_VIDEO_IN, buf, 100) }
        assertEquals(listOf(50L), slept)   // one 50 ms gap; loop restart does not sleep
    }

    @Test
    fun `replay transport control-in reads time out and calls are recorded`() {
        val t = ReplayTransport(emptyList())
        assertEquals(0, t.bulkRead(UseeplusDriver.EP_CONTROL_IN, ByteArray(64), 100))
        t.claimInterface(0)
        t.setAltSetting(1, 1)
        t.clearHalt(0x01)
        t.bulkWrite(0x02, byteArrayOf(0xFF.toByte(), 0x55), 100)
        assertEquals(listOf("claim 0", "alt 1 1", "clearHalt 01", "write 02 FF 55"), t.calls)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `.\gradlew.bat :core:test --tests "com.technicallyvu.scope.core.fixture.PacketLogTest"`
Expected: compilation FAILS with unresolved references (`PacketLogWriter`, `ReplayTransport`, `UseeplusDriver`). `UseeplusDriver` arrives in Task 5; to compile now, create the constants-only stub below and fill it in during Task 5.

`core/src/main/kotlin/com/technicallyvu/scope/core/useeplus/UseeplusDriver.kt` (stub, replaced in Task 5):
```kotlin
package com.technicallyvu.scope.core.useeplus

class UseeplusDriver {
    companion object {
        val SUPPORTED_IDS = setOf(0x2CE3 to 0x3828, 0x0329 to 0x2022)
        const val IFACE_CONTROL = 0
        const val IFACE_VIDEO = 1
        const val EP_VIDEO_OUT = 0x01
        const val EP_VIDEO_IN = 0x81
        const val EP_CONTROL_OUT = 0x02
        const val EP_CONTROL_IN = 0x82
    }
}
```

- [ ] **Step 3: Implement the packet log**

`core/src/main/kotlin/com/technicallyvu/scope/core/fixture/PacketLog.kt`:
```kotlin
package com.technicallyvu.scope.core.fixture

import com.technicallyvu.scope.core.driver.PacketSink
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class LoggedPacket(val timestampNanos: Long, val bytes: ByteArray)

private val MAGIC = byteArrayOf(0x55, 0x50, 0x4B, 0x54) // "UPKT"
private const val VERSION = 1

/** Appends raw bulk-IN packets to a `.upkt` stream. Thread-confined to the read loop. */
class PacketLogWriter(out: OutputStream) : PacketSink, AutoCloseable {
    private val out = BufferedOutputStream(out, 1 shl 16)
    private val header = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN)

    init {
        this.out.write(MAGIC)
        this.out.write(VERSION)
    }

    override fun onPacket(packet: ByteArray, len: Int, timestampNanos: Long) {
        header.clear()
        header.putLong(timestampNanos).putShort(len.toShort())
        out.write(header.array(), 0, 10)
        out.write(packet, 0, len)
    }

    override fun close() = out.close()
}

object PacketLogReader {
    fun read(input: InputStream): List<LoggedPacket> {
        val data = DataInputStream(input.buffered())
        val magic = ByteArray(4)
        data.readFully(magic)
        require(magic.contentEquals(MAGIC)) { "not a .upkt packet log" }
        val version = data.read()
        require(version == VERSION) { "unsupported .upkt version $version" }
        val result = ArrayList<LoggedPacket>()
        val header = ByteArray(10)
        while (true) {
            try {
                data.readFully(header)
            } catch (e: EOFException) {
                return result
            }
            val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val ts = bb.long
            val len = bb.short.toInt() and 0xFFFF
            val bytes = ByteArray(len)
            data.readFully(bytes)
            result += LoggedPacket(ts, bytes)
        }
    }
}
```

- [ ] **Step 4: Implement the replay transport and replay device source**

`core/src/main/kotlin/com/technicallyvu/scope/core/fixture/ReplayTransport.kt`:
```kotlin
package com.technicallyvu.scope.core.fixture

import com.technicallyvu.scope.core.usb.UsbException
import com.technicallyvu.scope.core.usb.UsbTransport
import com.technicallyvu.scope.core.useeplus.UseeplusDriver

/**
 * A [UsbTransport] that answers video bulk reads from a recorded packet list. Control-in reads
 * report "nothing pending" (0). Every other call is recorded in [calls] so tests can assert the
 * exact handshake. Doubles as the hardware-free `--replay` mode for the desktop app.
 */
class ReplayTransport(
    private val packets: List<LoggedPacket>,
    private val loop: Boolean = false,
    private val sleep: (millis: Long) -> Unit = {},
) : UsbTransport {
    val calls = mutableListOf<String>()

    /** Number of times [claimInterface] should throw before succeeding (retry tests). */
    var failuresBeforeSuccess = 0
    var resets = 0
        private set

    private var index = 0
    private var lastTimestamp = -1L

    override fun claimInterface(iface: Int) {
        calls += "claim $iface"
        if (failuresBeforeSuccess > 0) {
            failuresBeforeSuccess--
            throw UsbException("simulated claim failure")
        }
    }

    override fun releaseInterface(iface: Int) { calls += "release $iface" }
    override fun setAltSetting(iface: Int, alt: Int) { calls += "alt $iface $alt" }
    override fun clearHalt(endpoint: Int) { calls += "clearHalt ${hex(endpoint)}" }

    override fun bulkWrite(endpoint: Int, data: ByteArray, timeoutMs: Int): Int {
        calls += "write ${hex(endpoint)} " + data.joinToString(" ") { "%02X".format(it) }
        return data.size
    }

    override fun bulkRead(endpoint: Int, buffer: ByteArray, timeoutMs: Int): Int {
        if (endpoint != UseeplusDriver.EP_VIDEO_IN) return 0
        if (index >= packets.size) {
            if (!loop || packets.isEmpty()) throw UsbException("replay ended")
            index = 0
            lastTimestamp = -1
        }
        val p = packets[index++]
        if (lastTimestamp >= 0 && p.timestampNanos > lastTimestamp) {
            sleep((p.timestampNanos - lastTimestamp) / 1_000_000)
        }
        lastTimestamp = p.timestampNanos
        val n = minOf(p.bytes.size, buffer.size)
        p.bytes.copyInto(buffer, 0, 0, n)
        return n
    }

    override fun resetDevice() { resets++; calls += "reset" }
    override fun close() { calls += "close" }

    private fun hex(v: Int) = "%02X".format(v and 0xFF)
}
```

`core/src/main/kotlin/com/technicallyvu/scope/core/fixture/ReplayDeviceSource.kt`:
```kotlin
package com.technicallyvu.scope.core.fixture

import com.technicallyvu.scope.core.usb.DeviceRef
import com.technicallyvu.scope.core.usb.DeviceSource
import com.technicallyvu.scope.core.usb.UsbDeviceInfo
import com.technicallyvu.scope.core.usb.UsbTransport
import java.nio.file.Files
import java.nio.file.Path

/** Presents one fake useeplus device whose video stream is a looping `.upkt` capture. */
class ReplayDeviceSource(private val log: Path) : DeviceSource {
    private val ref = DeviceRef(UsbDeviceInfo(0x2CE3, 0x3828, 0xFF), bus = 0, address = 0)

    override fun list(): List<DeviceRef> = listOf(ref)

    override fun open(ref: DeviceRef): UsbTransport {
        val packets = Files.newInputStream(log).use { PacketLogReader.read(it) }
        return ReplayTransport(packets, loop = true, sleep = Thread::sleep)
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `.\gradlew.bat :core:test --tests "com.technicallyvu.scope.core.fixture.PacketLogTest"`
Expected: 5 tests PASS.

- [ ] **Step 6: Commit**

```bash
git add core/src
git commit -m "feat(core): .upkt packet log, replay transport and replay device source"
```

---

### Task 5: useeplus driver (handshake, retry, frame flow) and driver registry

**Files:**
- Modify: `core/src/main/kotlin/com/technicallyvu/scope/core/useeplus/UseeplusDriver.kt` (replace the stub)
- Create: `core/src/main/kotlin/com/technicallyvu/scope/core/driver/DriverRegistry.kt`
- Test: `core/src/test/kotlin/com/technicallyvu/scope/core/useeplus/UseeplusDriverTest.kt`

**Interfaces:**
- Consumes: `UsbTransport`, `UsbException`, `DeviceDriver`, `FrameSource`, `Frame`, `StreamStats`, `PacketSink`, `UseeplusPacket.parse`, `FrameReassembler`, `FpsMeter`, `ReplayTransport`, `LoggedPacket`.
- Produces: `UseeplusDriver(clock, sleep, ioDispatcher, packetSink) : DeviceDriver`, constants listed in the stub plus `MAGIC_INIT`, `CMD_CONNECT`, `PACKET_SIZE`, `READ_TIMEOUT_MS`, `OPEN_ATTEMPTS`, `RESET_WAIT_MS`, `DISCARD_FRAMES`; `UseeplusFrameSource`; `DriverRegistry.all: List<DeviceDriver>`, `DriverRegistry.find(info): DeviceDriver?`.

- [ ] **Step 1: Write the failing driver tests**

`core/src/test/kotlin/com/technicallyvu/scope/core/useeplus/UseeplusDriverTest.kt`:
```kotlin
package com.technicallyvu.scope.core.useeplus

import com.technicallyvu.scope.core.TestPackets
import com.technicallyvu.scope.core.driver.PacketSink
import com.technicallyvu.scope.core.fixture.LoggedPacket
import com.technicallyvu.scope.core.fixture.ReplayTransport
import com.technicallyvu.scope.core.usb.UsbDeviceInfo
import com.technicallyvu.scope.core.usb.UsbException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UseeplusDriverTest {
    private val slept = mutableListOf<Long>()
    private var now = 0L
    private fun driver(sink: PacketSink? = null) = UseeplusDriver(
        clock = { now },
        sleep = { slept += it },
        ioDispatcher = Dispatchers.Default,
        packetSink = sink,
    )

    /** N synthetic frames as head+tail packets, each frame's bytes distinct, plus a trailing head to flush the last. */
    private fun stream(frameCount: Int, buttonOn: Set<Int> = emptySet()): List<LoggedPacket> {
        val packets = ArrayList<LoggedPacket>()
        var ts = 0L
        for (f in 1..frameCount) {
            val jpeg = TestPackets.SOI + ByteArray(30) { f.toByte() } + TestPackets.EOI
            val flags = if (f in buttonOn) UseeplusPacket.BUTTON_MASK else 0
            for (p in TestPackets.framePackets(f, jpeg, flags)) { packets += LoggedPacket(ts, p); ts += 1_000_000 }
        }
        packets += LoggedPacket(ts, TestPackets.packet(7, frameCount + 1, TestPackets.SOI))
        return packets
    }

    @Test
    fun `matches both supported ids and nothing else`() {
        val d = driver()
        assertTrue(d.matches(UsbDeviceInfo(0x2CE3, 0x3828, 0xFF)))
        assertTrue(d.matches(UsbDeviceInfo(0x0329, 0x2022, 0xFF)))
        assertFalse(d.matches(UsbDeviceInfo(0x2CE3, 0x0001, 0xFF)))
    }

    @Test
    fun `handshake issues the documented sequence`() {
        val t = ReplayTransport(emptyList())
        driver().open(t)
        assertEquals(
            listOf(
                "claim 0", "claim 1",
                "alt 1 1",
                "clearHalt 01",
                "write 02 FF 55 FF 55 EE 10",
                "write 01 BB AA 05 00 00",
            ),
            t.calls,
        )
    }

    @Test
    fun `open retries with a reset after a failure`() {
        val t = ReplayTransport(emptyList()).apply { failuresBeforeSuccess = 1 }
        driver().open(t)
        assertEquals(1, t.resets)
        assertEquals(listOf(UseeplusDriver.RESET_WAIT_MS), slept)
    }

    @Test
    fun `open gives up after three failures`() {
        val t = ReplayTransport(emptyList()).apply { failuresBeforeSuccess = 3 }
        assertThrows(UsbException::class.java) { driver().open(t) }
        assertEquals(3, t.resets)
    }

    @Test
    fun `discards the first two frames then emits the rest`() {
        val t = ReplayTransport(stream(5))
        val source = driver().open(t)
        val frames = runBlocking { source.frames.take(3).toList() }
        assertEquals(listOf<Byte>(3, 4, 5), frames.map { it.jpeg[2] })
        assertArrayEquals(TestPackets.SOI + ByteArray(30) { 3 } + TestPackets.EOI, frames[0].jpeg)
        assertEquals(3, source.stats.value.framesEmitted)
        assertEquals(0, source.stats.value.framesDropped)
    }

    @Test
    fun `button flag reaches the frame`() {
        val t = ReplayTransport(stream(4, buttonOn = setOf(4)))
        val frames = runBlocking { driver().open(t).frames.take(2).toList() }
        assertFalse(frames[0].buttonPressed)
        assertTrue(frames[1].buttonPressed)
    }

    @Test
    fun `stream ends with a UsbException when the device goes away`() {
        val t = ReplayTransport(stream(3))
        val source = driver().open(t)
        assertThrows(UsbException::class.java) { runBlocking { source.frames.toList() } }
    }

    @Test
    fun `packet sink sees every raw packet`() {
        var count = 0
        val packets = stream(3)
        val t = ReplayTransport(packets)
        val source = driver(sink = PacketSink { _, _, _ -> count++ }).open(t)
        runCatching { runBlocking { source.frames.toList() } }
        assertEquals(packets.size, count)
    }

    @Test
    fun `close releases both interfaces and the transport`() {
        val t = ReplayTransport(emptyList())
        driver().open(t).close()
        assertEquals(listOf("release 1", "release 0", "close"), t.calls.takeLast(3))
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `.\gradlew.bat :core:test --tests "com.technicallyvu.scope.core.useeplus.UseeplusDriverTest"`
Expected: compilation FAILS (stub has no constructor parameters, no `open`, no `matches`).

- [ ] **Step 3: Implement the driver (replace the stub entirely)**

`core/src/main/kotlin/com/technicallyvu/scope/core/useeplus/UseeplusDriver.kt`:
```kotlin
package com.technicallyvu.scope.core.useeplus

import com.technicallyvu.scope.core.driver.DeviceDriver
import com.technicallyvu.scope.core.driver.Frame
import com.technicallyvu.scope.core.driver.FrameSource
import com.technicallyvu.scope.core.driver.PacketSink
import com.technicallyvu.scope.core.driver.StreamStats
import com.technicallyvu.scope.core.usb.UsbDeviceInfo
import com.technicallyvu.scope.core.usb.UsbException
import com.technicallyvu.scope.core.usb.UsbTransport
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * Driver for the Geek szitman "supercamera" family (useeplus protocol). Spec §5.1.
 * Reimplemented from public protocol documentation; see README "Prior work".
 */
class UseeplusDriver(
    private val clock: () -> Long = System::nanoTime,
    private val sleep: (millis: Long) -> Unit = Thread::sleep,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val packetSink: PacketSink? = null,
) : DeviceDriver {

    override val id = "useeplus"
    override val displayName = "useeplus endoscope"

    override fun matches(info: UsbDeviceInfo): Boolean = (info.vendorId to info.productId) in SUPPORTED_IDS

    override fun open(transport: UsbTransport): FrameSource {
        var lastError: UsbException? = null
        repeat(OPEN_ATTEMPTS) {
            try {
                handshake(transport)
                return UseeplusFrameSource(transport, clock, ioDispatcher, packetSink)
            } catch (e: UsbException) {
                lastError = e
                runCatching { transport.resetDevice() }
                sleep(RESET_WAIT_MS)
            }
        }
        throw UsbException("useeplus handshake failed after $OPEN_ATTEMPTS attempts", cause = lastError)
    }

    internal fun handshake(t: UsbTransport) {
        t.claimInterface(IFACE_CONTROL)
        t.claimInterface(IFACE_VIDEO)
        val scratch = ByteArray(64)
        for (i in 0 until HEARTBEAT_DRAIN_MAX) {
            if (t.bulkRead(EP_CONTROL_IN, scratch, HEARTBEAT_TIMEOUT_MS) == 0) break
        }
        t.setAltSetting(IFACE_VIDEO, 1)
        t.clearHalt(EP_VIDEO_OUT)
        t.bulkWrite(EP_CONTROL_OUT, MAGIC_INIT, CMD_TIMEOUT_MS)
        t.bulkWrite(EP_VIDEO_OUT, CMD_CONNECT, CMD_TIMEOUT_MS)
    }

    companion object {
        val SUPPORTED_IDS = setOf(0x2CE3 to 0x3828, 0x0329 to 0x2022)
        const val IFACE_CONTROL = 0
        const val IFACE_VIDEO = 1
        const val EP_VIDEO_OUT = 0x01
        const val EP_VIDEO_IN = 0x81
        const val EP_CONTROL_OUT = 0x02
        const val EP_CONTROL_IN = 0x82
        val MAGIC_INIT = byteArrayOf(0xFF.toByte(), 0x55, 0xFF.toByte(), 0x55, 0xEE.toByte(), 0x10)
        val CMD_CONNECT = byteArrayOf(0xBB.toByte(), 0xAA.toByte(), 0x05, 0x00, 0x00)
        const val PACKET_SIZE = 1024
        const val READ_TIMEOUT_MS = 5000
        const val HEARTBEAT_DRAIN_MAX = 30
        const val HEARTBEAT_TIMEOUT_MS = 100
        const val CMD_TIMEOUT_MS = 1000
        const val OPEN_ATTEMPTS = 3
        const val RESET_WAIT_MS = 1500L
        const val DISCARD_FRAMES = 2
    }
}

class UseeplusFrameSource(
    private val transport: UsbTransport,
    private val clock: () -> Long,
    dispatcher: CoroutineDispatcher,
    private val packetSink: PacketSink?,
) : FrameSource {
    private val _stats = MutableStateFlow(StreamStats())
    override val stats: StateFlow<StreamStats> = _stats

    @Volatile private var closed = false

    override val frames: Flow<Frame> = flow {
        val reassembler = FrameReassembler()
        val fps = FpsMeter()
        val buf = ByteArray(UseeplusDriver.PACKET_SIZE)
        var discarded = 0
        while (!closed) {
            val n = transport.bulkRead(UseeplusDriver.EP_VIDEO_IN, buf, UseeplusDriver.READ_TIMEOUT_MS)
            if (n == 0) continue
            val now = clock()
            packetSink?.onPacket(buf, n, now)
            val chunk = UseeplusPacket.parse(buf, n) ?: continue
            val frame = reassembler.accept(chunk, now) ?: continue
            if (discarded < UseeplusDriver.DISCARD_FRAMES) { discarded++; continue }
            _stats.value = StreamStats(
                fps = fps.tick(now),
                framesEmitted = reassembler.framesEmitted - UseeplusDriver.DISCARD_FRAMES,
                framesDropped = reassembler.framesDropped,
                bytesReceived = reassembler.bytesReceived,
            )
            emit(frame)
        }
    }.flowOn(dispatcher)

    override fun close() {
        closed = true
        runCatching { transport.releaseInterface(UseeplusDriver.IFACE_VIDEO) }
        runCatching { transport.releaseInterface(UseeplusDriver.IFACE_CONTROL) }
        runCatching { transport.close() }
    }
}
```

- [ ] **Step 4: Add the driver registry**

`core/src/main/kotlin/com/technicallyvu/scope/core/driver/DriverRegistry.kt`:
```kotlin
package com.technicallyvu.scope.core.driver

import com.technicallyvu.scope.core.usb.UsbDeviceInfo
import com.technicallyvu.scope.core.useeplus.UseeplusDriver

/** Every driver the app knows. Add a new device by adding a DeviceDriver here. */
object DriverRegistry {
    val all: List<DeviceDriver> = listOf(UseeplusDriver())

    fun find(info: UsbDeviceInfo): DeviceDriver? = all.firstOrNull { it.matches(info) }
}
```

- [ ] **Step 5: Run all core tests**

Run: `.\gradlew.bat :core:test`
Expected: all PASS (smoke + parser + reassembler + fps + packet log + 9 driver tests).

- [ ] **Step 6: Commit**

```bash
git add core/src
git commit -m "feat(core): useeplus driver with handshake retry, frame flow, and driver registry"
```

---

### Task 6: Desktop module — libusb transport, device source, Windows driver check, probe CLI

**Files:**
- Modify: `settings.gradle.kts` (add `":desktop"`)
- Create: `desktop/build.gradle.kts`
- Create: `desktop/src/main/kotlin/com/technicallyvu/scope/desktop/usb/LibUsbTransport.kt`
- Create: `desktop/src/main/kotlin/com/technicallyvu/scope/desktop/usb/LibUsbDevices.kt`
- Create: `desktop/src/main/kotlin/com/technicallyvu/scope/desktop/usb/WindowsDeviceCheck.kt`
- Create: `desktop/src/main/kotlin/com/technicallyvu/scope/desktop/Probe.kt`
- Create: `desktop/src/main/kotlin/com/technicallyvu/scope/desktop/Main.kt` (placeholder main so the Compose plugin is satisfied; real UI in Task 12)
- Test: `desktop/src/test/kotlin/com/technicallyvu/scope/desktop/usb/WindowsDeviceCheckTest.kt`

**Interfaces:**
- Consumes: `UsbTransport`, `UsbException`, `DeviceSource`, `DeviceRef`, `UsbDeviceInfo`, `DriverRegistry`, `UseeplusDriver`, `PacketSink`, `PacketLogWriter`, `UseeplusPacket.parse`.
- Produces: `LibUsbTransport(handle)`, `LibUsbDevices() : DeviceSource, AutoCloseable`, `WindowsDeviceCheck.isPresentWithoutDriver(ids)`, `WindowsDeviceCheck.parse(output, ids)`, Gradle task `:desktop:probe`.

- [ ] **Step 1: Register the module and write its build file**

In `settings.gradle.kts` change the last line to `include(":core", ":desktop")`.

`desktop/build.gradle.kts`:
```kotlin
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.usb4java)
    implementation(libs.javacv)
    implementation(libs.ffmpeg)
    implementation(variantOf(libs.ffmpeg) { classifier("windows-x86_64") })
    implementation(variantOf(libs.javacpp) { classifier("windows-x86_64") })
    implementation(libs.commons.imaging)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "failed", "skipped") }
}

compose.desktop {
    application {
        mainClass = "com.technicallyvu.scope.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "USB Scope"
            packageVersion = "0.1.0"
        }
    }
}

tasks.register<JavaExec>("probe") {
    group = "tools"
    description = "List USB devices, stream from the endoscope, optionally record a .upkt fixture"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.technicallyvu.scope.desktop.ProbeKt")
}
```

- [ ] **Step 2: Write the failing Windows device-check parse test**

`desktop/src/test/kotlin/com/technicallyvu/scope/desktop/usb/WindowsDeviceCheckTest.kt`:
```kotlin
package com.technicallyvu.scope.desktop.usb

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WindowsDeviceCheckTest {
    private val sample = """
        Microsoft PnP Utility

        Instance ID:                USB\VID_2CE3&PID_3828\202402062300000
        Device Description:         supercamera
        Class Name:                 
        Class GUID:                 
        Manufacturer Name:          
        Status:                     Problem
        Problem Code:               28
        Driver Name:                
    """.trimIndent()

    private val ids = setOf(0x2CE3 to 0x3828, 0x0329 to 0x2022)

    @Test
    fun `finds a known device in pnputil output`() {
        assertTrue(WindowsDeviceCheck.parse(sample, ids))
    }

    @Test
    fun `ignores output without the device`() {
        assertFalse(WindowsDeviceCheck.parse("Instance ID: USB\\VID_0B05&PID_1A27\\5&F34C116&0&7", ids))
    }

    @Test
    fun `matching is case-insensitive`() {
        assertTrue(WindowsDeviceCheck.parse("usb\\vid_0329&pid_2022\\x", ids))
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `.\gradlew.bat :desktop:test --tests "com.technicallyvu.scope.desktop.usb.WindowsDeviceCheckTest"`
Expected: compilation FAILS with `Unresolved reference: WindowsDeviceCheck` (and a missing `MainKt`; add the placeholder in Step 4 first if the Compose plugin complains).

- [ ] **Step 4: Implement the transport, device source, device check, and placeholder main**

`desktop/src/main/kotlin/com/technicallyvu/scope/desktop/usb/LibUsbTransport.kt`:
```kotlin
package com.technicallyvu.scope.desktop.usb

import com.technicallyvu.scope.core.usb.UsbException
import com.technicallyvu.scope.core.usb.UsbTransport
import org.usb4java.DeviceHandle
import org.usb4java.LibUsb
import java.nio.ByteBuffer
import java.nio.IntBuffer

/** libusb-backed transport. Requires WinUSB bound to the device on Windows (docs/windows-setup.md). */
class LibUsbTransport(private val handle: DeviceHandle) : UsbTransport {
    private val readBuffer = ByteBuffer.allocateDirect(64 * 1024)
    private val transferred = IntBuffer.allocate(1)

    override fun claimInterface(iface: Int) = check(LibUsb.claimInterface(handle, iface), "claimInterface $iface")
    override fun releaseInterface(iface: Int) = check(LibUsb.releaseInterface(handle, iface), "releaseInterface $iface")
    override fun setAltSetting(iface: Int, alt: Int) = check(LibUsb.setInterfaceAltSetting(handle, iface, alt), "setAltSetting $iface/$alt")
    override fun clearHalt(endpoint: Int) = check(LibUsb.clearHalt(handle, endpoint.toByte()), "clearHalt %02X".format(endpoint))

    override fun bulkWrite(endpoint: Int, data: ByteArray, timeoutMs: Int): Int {
        val buf = ByteBuffer.allocateDirect(data.size)
        buf.put(data)
        buf.rewind()
        transferred.clear()
        check(LibUsb.bulkTransfer(handle, endpoint.toByte(), buf, transferred, timeoutMs.toLong()), "bulkWrite %02X".format(endpoint))
        return transferred.get(0)
    }

    override fun bulkRead(endpoint: Int, buffer: ByteArray, timeoutMs: Int): Int {
        require(buffer.size <= readBuffer.capacity()) { "read larger than ${readBuffer.capacity()} bytes" }
        readBuffer.clear()
        readBuffer.limit(buffer.size)
        transferred.clear()
        val r = LibUsb.bulkTransfer(handle, endpoint.toByte(), readBuffer, transferred, timeoutMs.toLong())
        if (r != LibUsb.SUCCESS && r != LibUsb.ERROR_TIMEOUT) {
            throw UsbException("bulkRead %02X failed: %s".format(endpoint, LibUsb.strError(r)), r)
        }
        val n = transferred.get(0)
        if (n > 0) {
            readBuffer.rewind()
            readBuffer.get(buffer, 0, n)
        }
        return n
    }

    override fun resetDevice() = check(LibUsb.resetDevice(handle), "resetDevice")

    override fun close() {
        LibUsb.close(handle)
    }

    private fun check(code: Int, op: String) {
        if (code != LibUsb.SUCCESS) throw UsbException("$op failed: ${LibUsb.strError(code)}", code)
    }
}
```

`desktop/src/main/kotlin/com/technicallyvu/scope/desktop/usb/LibUsbDevices.kt`:
```kotlin
package com.technicallyvu.scope.desktop.usb

import com.technicallyvu.scope.core.usb.DeviceRef
import com.technicallyvu.scope.core.usb.DeviceSource
import com.technicallyvu.scope.core.usb.UsbDeviceInfo
import com.technicallyvu.scope.core.usb.UsbException
import com.technicallyvu.scope.core.usb.UsbTransport
import org.usb4java.Context
import org.usb4java.Device
import org.usb4java.DeviceDescriptor
import org.usb4java.DeviceHandle
import org.usb4java.DeviceList
import org.usb4java.LibUsb

/**
 * Enumerates devices via libusb. On Windows libusb only lists devices with WinUSB/libusbK bound,
 * so an unbound endoscope is simply absent here (see [WindowsDeviceCheck]).
 */
class LibUsbDevices : DeviceSource, AutoCloseable {
    private val context = Context()

    init {
        val r = LibUsb.init(context)
        if (r != LibUsb.SUCCESS) throw UsbException("libusb init failed: ${LibUsb.strError(r)}", r)
    }

    override fun list(): List<DeviceRef> = withDeviceList { list ->
        list.map { dev -> DeviceRef(describe(dev), LibUsb.getBusNumber(dev).toInt(), LibUsb.getDeviceAddress(dev).toInt()) }
    }

    override fun open(ref: DeviceRef): UsbTransport = withDeviceList { list ->
        val dev = list.firstOrNull {
            LibUsb.getBusNumber(it).toInt() == ref.bus && LibUsb.getDeviceAddress(it).toInt() == ref.address
        } ?: throw UsbException("device ${ref.info.idString} is no longer attached")
        val handle = DeviceHandle()
        val r = LibUsb.open(dev, handle)
        if (r != LibUsb.SUCCESS) throw UsbException("open ${ref.info.idString} failed: ${LibUsb.strError(r)}", r)
        LibUsbTransport(handle)
    }

    override fun close() {
        LibUsb.exit(context)
    }

    private fun describe(dev: Device): UsbDeviceInfo {
        val d = DeviceDescriptor()
        LibUsb.getDeviceDescriptor(dev, d)
        return UsbDeviceInfo(
            vendorId = d.idVendor().toInt() and 0xFFFF,
            productId = d.idProduct().toInt() and 0xFFFF,
            usbClass = d.bDeviceClass().toInt() and 0xFF,
        )
    }

    private fun <T> withDeviceList(block: (DeviceList) -> T): T {
        val list = DeviceList()
        val r = LibUsb.getDeviceList(context, list)
        if (r < 0) throw UsbException("getDeviceList failed: ${LibUsb.strError(r)}", r)
        try {
            return block(list)
        } finally {
            LibUsb.freeDeviceList(list, true)   // an open handle keeps its device alive
        }
    }
}
```

`desktop/src/main/kotlin/com/technicallyvu/scope/desktop/usb/WindowsDeviceCheck.kt`:
```kotlin
package com.technicallyvu.scope.desktop.usb

import java.util.concurrent.TimeUnit

/** Detects "endoscope is plugged in but Windows has no driver bound" via pnputil. */
object WindowsDeviceCheck {
    fun isWindows(): Boolean = System.getProperty("os.name", "").startsWith("Windows")

    /** True when pnputil lists a connected, problem-state device with one of [ids]. Never throws. */
    fun isPresentWithoutDriver(ids: Set<Pair<Int, Int>>): Boolean {
        if (!isWindows()) return false
        return try {
            val proc = ProcessBuilder("pnputil", "/enum-devices", "/connected", "/problem")
                .redirectErrorStream(true)
                .start()
            val output = proc.inputStream.bufferedReader().readText()
            proc.waitFor(5, TimeUnit.SECONDS)
            parse(output, ids)
        } catch (e: Exception) {
            false
        }
    }

    fun parse(pnputilOutput: String, ids: Set<Pair<Int, Int>>): Boolean =
        ids.any { (vid, pid) -> pnputilOutput.contains("VID_%04X&PID_%04X".format(vid, pid), ignoreCase = true) }
}
```

`desktop/src/main/kotlin/com/technicallyvu/scope/desktop/Main.kt` (placeholder; replaced in Task 12):
```kotlin
package com.technicallyvu.scope.desktop

fun main(args: Array<String>) {
    println("USB Scope (dev bench) - UI arrives in Task 12. Use :desktop:probe for now.")
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `.\gradlew.bat :desktop:test --tests "com.technicallyvu.scope.desktop.usb.WindowsDeviceCheckTest"`
Expected: 3 tests PASS. (First run downloads Compose, JavaCV and the ffmpeg natives; allow several minutes.)

- [ ] **Step 6: Write the probe CLI**

`desktop/src/main/kotlin/com/technicallyvu/scope/desktop/Probe.kt`:
```kotlin
package com.technicallyvu.scope.desktop

import com.technicallyvu.scope.core.driver.DriverRegistry
import com.technicallyvu.scope.core.driver.PacketSink
import com.technicallyvu.scope.core.fixture.PacketLogWriter
import com.technicallyvu.scope.core.useeplus.UseeplusDriver
import com.technicallyvu.scope.core.useeplus.UseeplusPacket
import com.technicallyvu.scope.desktop.usb.LibUsbDevices
import com.technicallyvu.scope.desktop.usb.WindowsDeviceCheck
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.system.exitProcess

/**
 * Usage:
 *   probe --list                                   show what libusb can see
 *   probe [--seconds N] [--record path.upkt]       stream from the first supported device
 */
fun main(args: Array<String>) {
    val seconds = argValue(args, "--seconds")?.toLong() ?: 10L
    val record = argValue(args, "--record")?.let { Paths.get(it) }

    LibUsbDevices().use { devices ->
        val refs = devices.list()
        println("USB devices visible to libusb (${refs.size}):")
        for (r in refs) {
            val driver = DriverRegistry.find(r.info)
            println("  %s  bus %d addr %d  class 0x%02X  %s".format(r.info.idString, r.bus, r.address, r.info.usbClass, driver?.let { "<- ${it.id}" } ?: ""))
        }
        if (args.contains("--list")) return

        val ref = refs.firstOrNull { DriverRegistry.find(it.info) != null }
        if (ref == null) {
            val hint = if (WindowsDeviceCheck.isPresentWithoutDriver(UseeplusDriver.SUPPORTED_IDS))
                "The endoscope is plugged in but has no WinUSB driver. Follow docs/windows-setup.md (Zadig)."
            else "No supported device found. Plug in the endoscope and try again."
            System.err.println(hint)
            exitProcess(2)
        }

        val flagsHistogram = sortedMapOf<Int, Int>()
        var packets = 0L
        val writer = record?.let { path ->
            path.parent?.let { Files.createDirectories(it) }
            PacketLogWriter(Files.newOutputStream(path))
        }
        val sink = PacketSink { p, len, ts ->
            packets++
            UseeplusPacket.parse(p, len)?.let { c -> flagsHistogram.merge(c.flags, 1, Int::plus) }
            writer?.onPacket(p, len, ts)
        }

        val driver = UseeplusDriver(packetSink = sink)
        println("Opening ${ref.info.idString} with ${driver.id} ...")
        val source = driver.open(devices.open(ref))
        println("Streaming for $seconds s" + (record?.let { ", recording raw packets to $it" } ?: "") + ". Press the cable button a few times.")

        var frames = 0L
        var buttonFrames = 0L
        runBlocking {
            withTimeoutOrNull(seconds * 1000) {
                var lastReport = System.nanoTime()
                source.frames.collect { f ->
                    frames++
                    if (f.buttonPressed) buttonFrames++
                    val now = System.nanoTime()
                    if (now - lastReport >= 1_000_000_000L) {
                        val st = source.stats.value
                        println("  %.1f fps  frames=%d dropped=%d bytes=%d  jpeg=%d B".format(st.fps, st.framesEmitted, st.framesDropped, st.bytesReceived, f.jpeg.size))
                        lastReport = now
                    }
                }
            }
        }
        source.close()
        writer?.close()
        println("Done. frames=$frames buttonFrames=$buttonFrames packets=$packets")
        println("flags histogram (flags value -> packet count): $flagsHistogram")
    }
}

private fun argValue(args: Array<String>, name: String): String? {
    val i = args.indexOf(name)
    return if (i >= 0 && i + 1 < args.size) args[i + 1] else null
}
```

- [ ] **Step 7: Run the probe in list mode (no hardware binding yet)**

Run: `.\gradlew.bat :desktop:probe --args="--list"`
Expected: `BUILD SUCCESSFUL` and a list of whatever libusb can see (likely a few HID/WinUSB devices, and NOT `2CE3:3828` yet, because no driver is bound). No stack traces.

- [ ] **Step 8: Commit**

```bash
git add settings.gradle.kts desktop
git commit -m "feat(desktop): libusb transport/device source, Windows driver check, probe CLI"
```

---

### Task 7: HUMAN STEP — Zadig WinUSB bind, first hardware contact, fixture capture

This task needs Anthony at the keyboard. The agent writes the guide, then stops and asks him to do the bind, then runs the probe.

**Files:**
- Create: `docs/windows-setup.md`
- Create: `core/src/test/resources/fixtures/useeplus-640x480.upkt` (captured)

- [ ] **Step 1: Write the Windows setup guide**

`docs/windows-setup.md`:
```markdown
# Windows one-time setup: bind WinUSB to the endoscope

The endoscope is a vendor-class USB device (not a webcam), so Windows installs no driver for it.
libusb needs the generic **WinUSB** driver bound to it once. This is per device model and survives
unplugging. It does not affect any other device.

1. Download Zadig from https://zadig.akeo.ie (Zadig 2.9, part of the libwdi 1.5.1 release). It is a
   single portable .exe by the author of Rufus. No install.
2. Plug in the endoscope.
3. Run Zadig. If Windows asks, allow it to run as administrator.
4. Menu **Options → List All Devices**.
5. In the dropdown pick **supercamera** (USB ID 2CE3 3828). Make sure it is the composite parent
   entry, not an "(Interface 0)" / "(Interface 1)" child.
6. The right-hand target driver box should read **WinUSB (v6.1.7600.16385)**. Use the arrows if not.
7. Click **Install Driver** (or **Replace Driver**). Wait for "The driver was installed successfully."
8. Close Zadig. Unplug and re-plug the endoscope.

Verify (PowerShell):

    Get-PnpDevice -InstanceId 'USB\VID_2CE3&PID_3828*' | Select Status, Class, FriendlyName

Expected: `Status OK`, `Class USBDevice`. Then:

    .\gradlew.bat :desktop:probe --args="--list"

should list `2CE3:3828 ... <- useeplus`.

## Undo
Device Manager → Universal Serial Bus devices → supercamera → Uninstall device → tick
"Delete the driver software for this device".
```

- [ ] **Step 2: Commit the guide**

```bash
git add docs/windows-setup.md
git commit -m "docs: Zadig WinUSB setup guide for Windows"
```

- [ ] **Step 3: STOP and ask Anthony to perform the Zadig bind**

Tell him: the device is plugged in, follow `docs/windows-setup.md` steps 1–8, then say "done". Do not attempt to install the driver for him.

- [ ] **Step 4: Verify the bind and libusb visibility**

Run: `Get-PnpDevice -InstanceId 'USB\VID_2CE3&PID_3828*' | Select Status, Class, FriendlyName`
Expected: `OK  USBDevice  supercamera`.

Run: `.\gradlew.bat :desktop:probe --args="--list"`
Expected: a line `2CE3:3828  bus N addr M  class 0xFF  <- useeplus`. If absent, re-plug and retry; if still absent, redo Zadig checking step 5 (composite parent).

- [ ] **Step 5: First stream, no recording**

Run: `.\gradlew.bat :desktop:probe --args="--seconds 5"`
Expected: per-second lines with fps roughly 10–16, `dropped` small (a few percent at most), `jpeg` sizes in the 10–40 KB range. If `open` fails with `LIBUSB_ERROR_NOT_SUPPORTED`, the driver bind is on the wrong node; redo Zadig. If fps is 0 and no frames arrive, unplug/replug and retry once (the device sometimes needs a fresh enumeration after being probed).

- [ ] **Step 6: Capture the fixture, with button presses**

Ask Anthony to press the cable button 2–3 times during the capture. Run:
`.\gradlew.bat :desktop:probe --args="--seconds 8 --record core/src/test/resources/fixtures/useeplus-640x480.upkt"`
Expected: the file exists (a few MB), `buttonFrames` > 0 if `BUTTON_MASK` is right, and a `flags histogram` printed. Save the histogram output into the commit message.

- [ ] **Step 7: Commit the fixture**

```bash
git add core/src/test/resources/fixtures/useeplus-640x480.upkt
git commit -m "test(core): real useeplus capture from Anthony's unit (8 s, button presses)

flags histogram: <paste>"
```

---

### Task 8: Real-capture tests and button-mask verification

**Files:**
- Test: `core/src/test/kotlin/com/technicallyvu/scope/core/useeplus/RealCaptureTest.kt`
- Possibly modify: `core/src/main/kotlin/com/technicallyvu/scope/core/useeplus/UseeplusPacket.kt` (`BUTTON_MASK`)

**Interfaces:**
- Consumes: `PacketLogReader.read`, `ReplayTransport`, `UseeplusDriver`, `UseeplusPacket.parse`.

- [ ] **Step 1: Write the real-capture tests**

`core/src/test/kotlin/com/technicallyvu/scope/core/useeplus/RealCaptureTest.kt`:
```kotlin
package com.technicallyvu.scope.core.useeplus

import com.technicallyvu.scope.core.fixture.LoggedPacket
import com.technicallyvu.scope.core.fixture.PacketLogReader
import com.technicallyvu.scope.core.fixture.ReplayTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RealCaptureTest {
    private lateinit var packets: List<LoggedPacket>

    @BeforeAll
    fun load() {
        val stream = assertNotNull(javaClass.getResourceAsStream("/fixtures/useeplus-640x480.upkt"), "fixture missing; run Task 7")
        packets = stream.use { PacketLogReader.read(it) }
        assertTrue(packets.size > 500, "capture too short: ${packets.size} packets")
    }

    private fun frames() = runBlocking {
        val driver = UseeplusDriver(ioDispatcher = Dispatchers.Default)
        val source = driver.open(ReplayTransport(packets))
        runCatching { source.frames.toList() }.getOrElse { emptyList() }.also { source.close() }
    }

    @Test
    fun `every packet parses`() {
        val unparsed = packets.count { UseeplusPacket.parse(it.bytes, it.bytes.size) == null }
        assertEquals(0, unparsed, "unparsed packets")
    }

    @Test
    fun `decodes at least 40 frames of 640x480 with under 10 percent drops`() {
        val frames = frames()
        assertTrue(frames.size >= 40, "only ${frames.size} frames")
        for (f in frames) {
            val img = assertNotNull(ImageIO.read(ByteArrayInputStream(f.jpeg)), "undecodable frame")
            assertEquals(setOf(640, 480), setOf(img.width, img.height), "unexpected size ${img.width}x${img.height}")
        }
        val reassembler = FrameReassembler()
        var dropped = 0L
        for (p in packets) UseeplusPacket.parse(p.bytes)?.let { reassembler.accept(it, p.timestampNanos) }
        dropped = reassembler.framesDropped
        assertTrue(dropped * 10 < reassembler.framesEmitted, "too many drops: $dropped of ${reassembler.framesEmitted}")
    }

    @Test
    fun `frame rate from timestamps is between 5 and 30 fps`() {
        val frames = frames()
        val span = (frames.last().timestampNanos - frames.first().timestampNanos) / 1e9
        val fps = (frames.size - 1) / span
        assertTrue(fps in 5.0..30.0, "fps $fps")
    }

    @Test
    fun `button press appears in the capture`() {
        assertTrue(frames().any { it.buttonPressed }, "no frame has buttonPressed; check BUTTON_MASK against the flags histogram")
    }
}
```

- [ ] **Step 2: Run the tests**

Run: `.\gradlew.bat :core:test --tests "com.technicallyvu.scope.core.useeplus.RealCaptureTest"`
Expected: the first three PASS. If `button press appears in the capture` FAILS, continue to Step 3; otherwise skip to Step 4.

- [ ] **Step 3: Determine the button bit empirically (only if Step 2's button test failed)**

The flags histogram from Task 7 shows which flags values occurred. The dominant value is "idle"; a rarer value that differs in one bit is "pressed". Set `BUTTON_MASK` in `UseeplusPacket.kt` to that differing bit (idle XOR pressed). Re-run Step 2; all four must PASS. If no second flags value exists at all, recapture (Task 7 Step 6) while Anthony holds the button for a full second.

- [ ] **Step 4: Commit**

```bash
git add core/src
git commit -m "test(core): real-capture tests; button mask verified against hardware"
```

---

### Task 9: Image transforms and snapshot writer

**Files:**
- Create: `desktop/src/main/kotlin/com/technicallyvu/scope/desktop/media/ImageTransforms.kt`
- Create: `desktop/src/main/kotlin/com/technicallyvu/scope/desktop/media/SnapshotWriter.kt`
- Test: `desktop/src/test/kotlin/com/technicallyvu/scope/desktop/media/ImageTransformsTest.kt`
- Test: `desktop/src/test/kotlin/com/technicallyvu/scope/desktop/media/SnapshotWriterTest.kt`

**Interfaces:**
- Produces: `ImageTransforms.decodeJpeg(bytes): BufferedImage?`, `ImageTransforms.apply(src, rotationDegrees, mirror): BufferedImage`, `SnapshotWriter.write(jpeg, rotationDegrees, mirror, dir, now): Path`, `SnapshotWriter.exifOrientation(rotationDegrees, mirror): Int`.

Semantics: `mirror` flips the sensor image horizontally first; `rotationDegrees` (0/90/180/270) then rotates clockwise. The stored snapshot keeps the original JPEG bytes and records the same transform as an EXIF orientation tag.

- [ ] **Step 1: Write the failing tests**

`desktop/src/test/kotlin/com/technicallyvu/scope/desktop/media/ImageTransformsTest.kt`:
```kotlin
package com.technicallyvu.scope.desktop.media

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

class ImageTransformsTest {
    private val red = 0xFF0000
    private val blue = 0x0000FF

    /** 2x1 image: red on the left, blue on the right. */
    private fun twoPixels(): BufferedImage = BufferedImage(2, 1, BufferedImage.TYPE_INT_RGB).apply {
        setRGB(0, 0, red); setRGB(1, 0, blue)
    }

    private fun rgb(img: BufferedImage, x: Int, y: Int) = img.getRGB(x, y) and 0xFFFFFF

    @Test
    fun `no-op transform keeps pixels`() {
        val out = ImageTransforms.apply(twoPixels(), 0, false)
        assertEquals(red, rgb(out, 0, 0)); assertEquals(blue, rgb(out, 1, 0))
    }

    @Test
    fun `mirror swaps left and right`() {
        val out = ImageTransforms.apply(twoPixels(), 0, true)
        assertEquals(blue, rgb(out, 0, 0)); assertEquals(red, rgb(out, 1, 0))
    }

    @Test
    fun `rotate 90 clockwise puts the left pixel on top`() {
        val out = ImageTransforms.apply(twoPixels(), 90, false)
        assertEquals(1, out.width); assertEquals(2, out.height)
        assertEquals(red, rgb(out, 0, 0)); assertEquals(blue, rgb(out, 0, 1))
    }

    @Test
    fun `rotate 270 clockwise puts the left pixel at the bottom`() {
        val out = ImageTransforms.apply(twoPixels(), 270, false)
        assertEquals(blue, rgb(out, 0, 0)); assertEquals(red, rgb(out, 0, 1))
    }

    @Test
    fun `rotate 180 reverses`() {
        val out = ImageTransforms.apply(twoPixels(), 180, false)
        assertEquals(blue, rgb(out, 0, 0)); assertEquals(red, rgb(out, 1, 0))
    }

    @Test
    fun `decodes a jpeg and rejects garbage`() {
        val bytes = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "jpg", it) }.toByteArray()
        val img = assertNotNull(ImageTransforms.decodeJpeg(bytes))
        assertEquals(8, img.width)
        assertNull(ImageTransforms.decodeJpeg(byteArrayOf(1, 2, 3)))
    }
}
```

`desktop/src/test/kotlin/com/technicallyvu/scope/desktop/media/SnapshotWriterTest.kt`:
```kotlin
package com.technicallyvu.scope.desktop.media

import org.apache.commons.imaging.Imaging
import org.apache.commons.imaging.formats.jpeg.JpegImageMetadata
import org.apache.commons.imaging.formats.tiff.constants.TiffTagConstants
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import javax.imageio.ImageIO

class SnapshotWriterTest {
    private fun jpeg(): ByteArray = ByteArrayOutputStream().also {
        ImageIO.write(BufferedImage(16, 8, BufferedImage.TYPE_INT_RGB), "jpg", it)
    }.toByteArray()

    @Test
    fun `exif orientation table`() {
        assertEquals(1, SnapshotWriter.exifOrientation(0, false))
        assertEquals(2, SnapshotWriter.exifOrientation(0, true))
        assertEquals(6, SnapshotWriter.exifOrientation(90, false))
        assertEquals(7, SnapshotWriter.exifOrientation(90, true))
        assertEquals(3, SnapshotWriter.exifOrientation(180, false))
        assertEquals(4, SnapshotWriter.exifOrientation(180, true))
        assertEquals(8, SnapshotWriter.exifOrientation(270, false))
        assertEquals(5, SnapshotWriter.exifOrientation(270, true))
    }

    @Test
    fun `writes original pixels with the orientation tag and a timestamped name`(@TempDir dir: Path) {
        val path = SnapshotWriter.write(jpeg(), 90, false, dir, LocalDateTime.of(2026, 9, 8, 14, 5, 9))
        assertEquals("SCOPE_20260908_140509.jpg", path.fileName.toString())
        val bytes = Files.readAllBytes(path)
        val img = assertNotNull(ImageIO.read(path.toFile()))
        assertEquals(16, img.width)   // pixels untouched; viewer applies the tag
        val meta = assertNotNull(Imaging.getMetadata(bytes) as? JpegImageMetadata)
        assertEquals(6, meta.findExifValue(TiffTagConstants.TIFF_TAG_ORIENTATION).intValue)
    }

    @Test
    fun `two snapshots in the same second get distinct names`(@TempDir dir: Path) {
        val t = LocalDateTime.of(2026, 9, 8, 14, 5, 9)
        val a = SnapshotWriter.write(jpeg(), 0, false, dir, t)
        val b = SnapshotWriter.write(jpeg(), 0, false, dir, t)
        assertTrue(a != b)
        assertTrue(Files.exists(a) && Files.exists(b))
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `.\gradlew.bat :desktop:test --tests "com.technicallyvu.scope.desktop.media.*"`
Expected: compilation FAILS with unresolved `ImageTransforms` / `SnapshotWriter`.

- [ ] **Step 3: Implement**

`desktop/src/main/kotlin/com/technicallyvu/scope/desktop/media/ImageTransforms.kt`:
```kotlin
package com.technicallyvu.scope.desktop.media

import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

object ImageTransforms {
    fun decodeJpeg(bytes: ByteArray): BufferedImage? =
        try { ImageIO.read(ByteArrayInputStream(bytes)) } catch (e: Exception) { null }

    fun normalize(rotationDegrees: Int): Int = ((rotationDegrees % 360) + 360) % 360

    /** Mirror horizontally (if asked), then rotate clockwise by 0/90/180/270. Output is TYPE_3BYTE_BGR for the encoder. */
    fun apply(src: BufferedImage, rotationDegrees: Int, mirror: Boolean): BufferedImage {
        val rot = normalize(rotationDegrees)
        val w = src.width
        val h = src.height
        val (ow, oh) = if (rot == 90 || rot == 270) h to w else w to h
        val out = BufferedImage(ow, oh, BufferedImage.TYPE_3BYTE_BGR)
        val t = AffineTransform()
        t.translate(ow / 2.0, oh / 2.0)
        t.rotate(Math.toRadians(rot.toDouble()))
        if (mirror) t.scale(-1.0, 1.0)
        t.translate(-w / 2.0, -h / 2.0)
        val g = out.createGraphics()
        try {
            g.drawImage(src, t, null)
        } finally {
            g.dispose()
        }
        return out
    }
}
```

`desktop/src/main/kotlin/com/technicallyvu/scope/desktop/media/SnapshotWriter.kt`:
```kotlin
package com.technicallyvu.scope.desktop.media

import org.apache.commons.imaging.formats.jpeg.exif.ExifRewriter
import org.apache.commons.imaging.formats.tiff.constants.TiffTagConstants
import org.apache.commons.imaging.formats.tiff.write.TiffOutputSet
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Saves the sensor's original JPEG bytes plus an EXIF orientation tag for the chosen view transform. */
object SnapshotWriter {
    private val stamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")

    fun write(jpeg: ByteArray, rotationDegrees: Int, mirror: Boolean, dir: Path, now: LocalDateTime = LocalDateTime.now()): Path {
        Files.createDirectories(dir)
        val file = uniquePath(dir, "SCOPE_" + now.format(stamp), ".jpg")
        val outputSet = TiffOutputSet()
        outputSet.getOrCreateRootDirectory().add(
            TiffTagConstants.TIFF_TAG_ORIENTATION,
            exifOrientation(rotationDegrees, mirror).toShort(),
        )
        Files.newOutputStream(file).use { os -> ExifRewriter().updateExifMetadataLossless(jpeg, os, outputSet) }
        return file
    }

    /** EXIF Orientation values 1-8 for "mirror horizontally, then rotate clockwise by N". */
    fun exifOrientation(rotationDegrees: Int, mirror: Boolean): Int = when (ImageTransforms.normalize(rotationDegrees)) {
        0 -> if (mirror) 2 else 1
        90 -> if (mirror) 7 else 6
        180 -> if (mirror) 4 else 3
        270 -> if (mirror) 5 else 8
        else -> 1
    }

    internal fun uniquePath(dir: Path, base: String, ext: String): Path {
        var candidate = dir.resolve(base + ext)
        var n = 1
        while (Files.exists(candidate)) candidate = dir.resolve("${base}_$n$ext").also { n++ }
        return candidate
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `.\gradlew.bat :desktop:test --tests "com.technicallyvu.scope.desktop.media.*"`
Expected: 9 tests PASS. If `updateExifMetadataLossless` fails to compile against Commons Imaging alpha6, the alternative overload is `updateExifMetadataLossless(byte[] src, OutputStream os, TiffOutputSet outputSet)`; check the exact signature in the IDE and adjust the call, keeping the test unchanged.

- [ ] **Step 5: Commit**

```bash
git add desktop/src
git commit -m "feat(desktop): rotate/mirror transforms and EXIF-tagged snapshot writer"
```

---

### Task 10: MP4 recorder

**Files:**
- Create: `desktop/src/main/kotlin/com/technicallyvu/scope/desktop/media/Mp4Recorder.kt`
- Test: `desktop/src/test/kotlin/com/technicallyvu/scope/desktop/media/Mp4RecorderTest.kt`

**Interfaces:**
- Produces: `Mp4Recorder(file: Path, width: Int, height: Int) : AutoCloseable` with `record(image: BufferedImage, timestampNanos: Long)`, `framesWritten: Int`. Constructor starts the encoder; `close()` finalizes a playable file.

- [ ] **Step 1: Write the failing test**

`desktop/src/test/kotlin/com/technicallyvu/scope/desktop/media/Mp4RecorderTest.kt`:
```kotlin
package com.technicallyvu.scope.desktop.media

import org.bytedeco.javacv.FFmpegFrameGrabber
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path

class Mp4RecorderTest {
    @Test
    fun `writes a playable mp4 with the recorded frame count`(@TempDir dir: Path) {
        val file = dir.resolve("clip.mp4")
        Mp4Recorder(file, 64, 48).use { rec ->
            for (i in 0 until 30) {
                val img = BufferedImage(64, 48, BufferedImage.TYPE_3BYTE_BGR)
                val g = img.createGraphics(); g.color = if (i % 2 == 0) Color.RED else Color.BLUE; g.fillRect(0, 0, 64, 48); g.dispose()
                rec.record(img, i * 66_000_000L)   // ~15 fps
            }
            assertEquals(30, rec.framesWritten)
        }
        assertTrue(Files.size(file) > 1_000, "file too small")
        FFmpegFrameGrabber(file.toFile()).use { g ->
            g.start()
            assertEquals(64, g.imageWidth)
            assertEquals(48, g.imageHeight)
            var n = 0
            while (g.grabImage() != null) n++
            assertTrue(n >= 28, "decoded only $n frames")
        }
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `.\gradlew.bat :desktop:test --tests "com.technicallyvu.scope.desktop.media.Mp4RecorderTest"`
Expected: compilation FAILS with `Unresolved reference: Mp4Recorder`.

- [ ] **Step 3: Implement**

`desktop/src/main/kotlin/com/technicallyvu/scope/desktop/media/Mp4Recorder.kt`:
```kotlin
package com.technicallyvu.scope.desktop.media

import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameRecorder
import org.bytedeco.javacv.Java2DFrameConverter
import java.awt.image.BufferedImage
import java.nio.file.Path

/**
 * Encodes frames to an MP4 file. Uses the MPEG-4 Part 2 encoder, which is always present in the
 * LGPL ffmpeg build and plays in Windows Media Player, VLC and browsers. Frame timing follows the
 * capture timestamps (variable frame rate).
 */
class Mp4Recorder(file: Path, width: Int, height: Int, nominalFps: Double = 15.0) : AutoCloseable {
    private val recorder = FFmpegFrameRecorder(file.toFile(), width, height).apply {
        format = "mp4"
        videoCodec = avcodec.AV_CODEC_ID_MPEG4
        pixelFormat = avutil.AV_PIX_FMT_YUV420P
        frameRate = nominalFps
        videoBitrate = 4_000_000
    }
    private val converter = Java2DFrameConverter()
    private var firstTimestampNanos = -1L

    var framesWritten: Int = 0
        private set

    init {
        recorder.start()
    }

    fun record(image: BufferedImage, timestampNanos: Long) {
        if (firstTimestampNanos < 0) firstTimestampNanos = timestampNanos
        val micros = (timestampNanos - firstTimestampNanos) / 1_000
        if (micros > recorder.timestamp) recorder.timestamp = micros
        recorder.record(converter.convert(image))
        framesWritten++
    }

    override fun close() {
        try {
            recorder.stop()
        } finally {
            recorder.release()
            converter.close()
        }
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `.\gradlew.bat :desktop:test --tests "com.technicallyvu.scope.desktop.media.Mp4RecorderTest"`
Expected: PASS. (Loads the ffmpeg natives; first run is slow.)

- [ ] **Step 5: Commit**

```bash
git add desktop/src
git commit -m "feat(desktop): MP4 recorder over JavaCV with timestamp-driven frame timing"
```

---

### Task 11: View model (session loop, actions, button snapshot)

**Files:**
- Create: `desktop/src/main/kotlin/com/technicallyvu/scope/desktop/ui/ScopeViewModel.kt`
- Create: `desktop/src/test/kotlin/com/technicallyvu/scope/desktop/TestPackets.kt`
- Test: `desktop/src/test/kotlin/com/technicallyvu/scope/desktop/ui/ScopeViewModelTest.kt`

**Interfaces:**
- Consumes: `DeviceSource`, `DeviceRef`, `DeviceDriver`, `FrameSource`, `Frame`, `StreamStats`, `UsbException`, `ImageTransforms`, `SnapshotWriter`, `Mp4Recorder`, `ReplayTransport`, `LoggedPacket`, `UseeplusDriver`.
- Produces: `ConnectionState` (`NoDevice(needsDriverHint)`, `Connecting(name)`, `Streaming(name)`, `Failed(message)`), `UiState`, `ScopeViewModel(devices, drivers, scope, outputDir, driverHintCheck, pollMillis, recorderFactory)` with `state: StateFlow<UiState>`, `start()`, `stop()`, `snapshot()`, `toggleRecording()`, `rotate()`, `toggleMirror()`, `toggleDebug()`, `setOutputDir(Path)`.

- [ ] **Step 1: Write the desktop test packet builder (same as core's; test sources are not shared)**

`desktop/src/test/kotlin/com/technicallyvu/scope/desktop/TestPackets.kt`:
```kotlin
package com.technicallyvu.scope.desktop

import com.technicallyvu.scope.core.fixture.LoggedPacket
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

object TestPackets {
    fun packet(channelId: Int, frameId: Int, payload: ByteArray, flags: Int = 0): ByteArray {
        val length = 7 + payload.size
        val out = ByteArray(12 + payload.size)
        out[0] = 0xAA.toByte(); out[1] = 0xBB.toByte(); out[2] = channelId.toByte()
        out[3] = (length and 0xFF).toByte(); out[4] = ((length shr 8) and 0xFF).toByte()
        out[5] = frameId.toByte(); out[6] = 0; out[7] = flags.toByte()
        payload.copyInto(out, 12)
        return out
    }

    /** A real, decodable 32x24 JPEG. */
    fun realJpeg(color: Color = Color.GREEN): ByteArray {
        val img = BufferedImage(32, 24, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics(); g.color = color; g.fillRect(0, 0, 32, 24); g.dispose()
        return ByteArrayOutputStream().also { ImageIO.write(img, "jpg", it) }.toByteArray()
    }

    /** [frameCount] real JPEG frames split head/tail, 66 ms apart, button flag on [buttonOn] frames, plus a flush packet. */
    fun stream(frameCount: Int, buttonOn: Set<Int> = emptySet(), buttonMask: Int): List<LoggedPacket> {
        val packets = ArrayList<LoggedPacket>()
        var ts = 0L
        for (f in 1..frameCount) {
            val jpeg = realJpeg()
            val half = jpeg.size / 2
            val flags = if (f in buttonOn) buttonMask else 0
            packets += LoggedPacket(ts, packet(7, f, jpeg.copyOfRange(0, half), flags)); ts += 33_000_000
            packets += LoggedPacket(ts, packet(11, f, jpeg.copyOfRange(half, jpeg.size), flags)); ts += 33_000_000
        }
        packets += LoggedPacket(ts, packet(7, frameCount + 1, byteArrayOf(0xFF.toByte(), 0xD8.toByte())))
        return packets
    }
}
```

- [ ] **Step 2: Write the failing view model tests**

`desktop/src/test/kotlin/com/technicallyvu/scope/desktop/ui/ScopeViewModelTest.kt`:
```kotlin
package com.technicallyvu.scope.desktop.ui

import com.technicallyvu.scope.core.fixture.ReplayTransport
import com.technicallyvu.scope.core.usb.DeviceRef
import com.technicallyvu.scope.core.usb.DeviceSource
import com.technicallyvu.scope.core.usb.UsbDeviceInfo
import com.technicallyvu.scope.core.usb.UsbTransport
import com.technicallyvu.scope.core.useeplus.UseeplusDriver
import com.technicallyvu.scope.core.useeplus.UseeplusPacket
import com.technicallyvu.scope.desktop.TestPackets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
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

    private fun vm(devices: DeviceSource, dir: Path, hint: () -> Boolean = { false }) =
        ScopeViewModel(devices, listOf(UseeplusDriver(ioDispatcher = Dispatchers.Default)), scope, dir, driverHintCheck = hint, pollMillis = 50)

    @AfterEach
    fun tearDown() = scope.cancel()

    @Test
    fun `no device shows waiting state with driver hint`(@TempDir dir: Path) = runBlocking {
        val vm = vm(FakeDevices(emptyList()) { error("unused") }, dir, hint = { true })
        vm.start()
        val s = withTimeout(2_000) { vm.state.first { it.connection is ConnectionState.NoDevice && (it.connection as ConnectionState.NoDevice).needsDriverHint } }
        assertTrue(s.image == null)
        vm.stop()
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
        assertEquals(24, streaming.image!!.width)
        assertEquals(32, streaming.image!!.height)
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
        val devices = FakeDevices(listOf(ref)) { ReplayTransport(packets, sleep = Thread::sleep) }
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
        val mp4 = assertNotNull(Files.list(dir).use { it.toList() }.firstOrNull { it.fileName.toString().endsWith(".mp4") }, "no mp4 in $names")
        assertTrue(Files.size(mp4) > 500)
        vm.rotate()
        assertEquals(180, vm.state.value.rotation)
        vm.stop()
    }
}
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `.\gradlew.bat :desktop:test --tests "com.technicallyvu.scope.desktop.ui.ScopeViewModelTest"`
Expected: compilation FAILS with unresolved `ScopeViewModel` / `ConnectionState`.

- [ ] **Step 4: Implement the view model**

`desktop/src/main/kotlin/com/technicallyvu/scope/desktop/ui/ScopeViewModel.kt`:
```kotlin
package com.technicallyvu.scope.desktop.ui

import com.technicallyvu.scope.core.driver.DeviceDriver
import com.technicallyvu.scope.core.driver.Frame
import com.technicallyvu.scope.core.driver.FrameSource
import com.technicallyvu.scope.core.driver.StreamStats
import com.technicallyvu.scope.core.usb.DeviceRef
import com.technicallyvu.scope.core.usb.DeviceSource
import com.technicallyvu.scope.core.usb.UsbException
import com.technicallyvu.scope.desktop.media.ImageTransforms
import com.technicallyvu.scope.desktop.media.Mp4Recorder
import com.technicallyvu.scope.desktop.media.SnapshotWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

sealed interface ConnectionState {
    data class NoDevice(val needsDriverHint: Boolean) : ConnectionState
    data class Connecting(val name: String) : ConnectionState
    data class Streaming(val name: String) : ConnectionState
    data class Failed(val message: String) : ConnectionState
}

data class UiState(
    val outputDir: Path,
    val connection: ConnectionState = ConnectionState.NoDevice(false),
    val image: BufferedImage? = null,
    /** Sensor is mounted sideways; 90 shows the picture upright for most units. */
    val rotation: Int = 90,
    val mirror: Boolean = false,
    val stats: StreamStats = StreamStats(),
    val recording: Boolean = false,
    val showDebug: Boolean = false,
    val lastSaved: String? = null,
)

/**
 * Owns the device session loop and every user action. UI-toolkit free so it is testable headless.
 * Runs on Dispatchers.Default; state is exposed as a StateFlow.
 */
class ScopeViewModel(
    private val devices: DeviceSource,
    private val drivers: List<DeviceDriver>,
    private val scope: CoroutineScope,
    outputDir: Path,
    private val driverHintCheck: () -> Boolean = { false },
    private val pollMillis: Long = 1000,
    private val recorderFactory: (Path, Int, Int) -> Mp4Recorder = { f, w, h -> Mp4Recorder(f, w, h) },
) {
    private val _state = MutableStateFlow(UiState(outputDir = outputDir))
    val state: StateFlow<UiState> = _state

    private var job: Job? = null
    private var lastJpeg: ByteArray? = null
    private var lastImage: BufferedImage? = null
    private var recorder: Mp4Recorder? = null
    private var prevButton = false
    private var lastButtonSnapNanos = Long.MIN_VALUE / 2

    fun start() {
        if (job != null) return
        job = scope.launch(Dispatchers.Default) {
            while (isActive) {
                val found = findDevice()
                if (found == null) {
                    _state.update { it.copy(connection = ConnectionState.NoDevice(driverHintCheck()), image = null) }
                } else {
                    session(found.first, found.second)
                }
                delay(pollMillis)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        stopRecording()
    }

    fun snapshot() {
        val jpeg = lastJpeg ?: return
        val s = _state.value
        val path = SnapshotWriter.write(jpeg, s.rotation, s.mirror, s.outputDir)
        _state.update { it.copy(lastSaved = path.fileName.toString()) }
    }

    fun toggleRecording() {
        if (recorder != null) stopRecording() else startRecording()
    }

    fun rotate() {
        if (_state.value.recording) return
        _state.update { it.copy(rotation = (it.rotation + 90) % 360) }
    }

    fun toggleMirror() {
        if (_state.value.recording) return
        _state.update { it.copy(mirror = !it.mirror) }
    }

    fun toggleDebug() = _state.update { it.copy(showDebug = !it.showDebug) }

    fun setOutputDir(dir: Path) = _state.update { it.copy(outputDir = dir) }

    private fun findDevice(): Pair<DeviceRef, DeviceDriver>? = try {
        devices.list().firstNotNullOfOrNull { ref -> drivers.firstOrNull { it.matches(ref.info) }?.let { ref to it } }
    } catch (e: UsbException) {
        null
    }

    private suspend fun session(ref: DeviceRef, driver: DeviceDriver) {
        _state.update { it.copy(connection = ConnectionState.Connecting(driver.displayName)) }
        var source: FrameSource? = null
        try {
            val transport = devices.open(ref)
            source = driver.open(transport)
            _state.update { it.copy(connection = ConnectionState.Streaming(driver.displayName)) }
            val src = source
            src.frames.collect { frame -> onFrame(frame, src.stats.value) }
        } catch (e: UsbException) {
            val wasStreaming = _state.value.connection is ConnectionState.Streaming
            _state.update {
                it.copy(
                    connection = if (wasStreaming) ConnectionState.NoDevice(false) else ConnectionState.Failed(e.message ?: "USB error"),
                    image = null,
                )
            }
        } finally {
            stopRecording()
            source?.close()
            lastJpeg = null
            lastImage = null
            prevButton = false
        }
    }

    private fun onFrame(frame: Frame, stats: StreamStats) {
        val decoded = ImageTransforms.decodeJpeg(frame.jpeg) ?: return
        val s = _state.value
        val shown = ImageTransforms.apply(decoded, s.rotation, s.mirror)
        lastJpeg = frame.jpeg
        lastImage = shown
        recorder?.record(shown, frame.timestampNanos)
        if (frame.buttonPressed && !prevButton && frame.timestampNanos - lastButtonSnapNanos > BUTTON_DEBOUNCE_NANOS) {
            lastButtonSnapNanos = frame.timestampNanos
            snapshot()
        }
        prevButton = frame.buttonPressed
        _state.update { it.copy(image = shown, stats = stats) }
    }

    private fun startRecording() {
        val img = lastImage ?: return
        val s = _state.value
        Files.createDirectories(s.outputDir)
        val file = s.outputDir.resolve("SCOPE_" + LocalDateTime.now().format(FILE_STAMP) + ".mp4")
        recorder = recorderFactory(file, img.width, img.height)
        _state.update { it.copy(recording = true, lastSaved = file.fileName.toString()) }
    }

    private fun stopRecording() {
        recorder?.let { runCatching { it.close() } }
        recorder = null
        _state.update { it.copy(recording = false) }
    }

    companion object {
        const val BUTTON_DEBOUNCE_NANOS = 300_000_000L
        val FILE_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `.\gradlew.bat :desktop:test --tests "com.technicallyvu.scope.desktop.ui.ScopeViewModelTest"`
Expected: 4 tests PASS.

- [ ] **Step 6: Commit**

```bash
git add desktop/src
git commit -m "feat(desktop): view model with session loop, snapshot, recording, button trigger"
```

---

### Task 12: Compose UI, entry point, and the Phase 1 acceptance run

**Files:**
- Create: `desktop/src/main/kotlin/com/technicallyvu/scope/desktop/ui/ScopeScreen.kt`
- Modify: `desktop/src/main/kotlin/com/technicallyvu/scope/desktop/Main.kt` (replace placeholder)

**Interfaces:**
- Consumes: `ScopeViewModel`, `UiState`, `ConnectionState`, `StreamStats`, `DriverRegistry`, `LibUsbDevices`, `ReplayDeviceSource`, `WindowsDeviceCheck`, `UseeplusDriver.SUPPORTED_IDS`.

- [ ] **Step 1: Write the screen**

`desktop/src/main/kotlin/com/technicallyvu/scope/desktop/ui/ScopeScreen.kt`:
```kotlin
package com.technicallyvu.scope.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.technicallyvu.scope.core.driver.StreamStats
import java.nio.file.Path
import javax.swing.JFileChooser

@Composable
fun ScopeScreen(vm: ScopeViewModel) {
    val s by vm.state.collectAsState()
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        StatusBar(s.connection)
        Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black), contentAlignment = Alignment.Center) {
            val img = s.image
            if (img != null) {
                val bitmap = remember(img) { img.toComposeImageBitmap() }
                Image(bitmap = bitmap, contentDescription = "Live view", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            } else {
                WaitingMessage(s.connection)
            }
            if (s.showDebug) DebugOverlay(s.stats, Modifier.align(Alignment.TopStart).padding(8.dp))
            if (s.recording) Badge("REC", Color(0xFFD32F2F), Modifier.align(Alignment.TopEnd).padding(8.dp))
        }
        Controls(s, vm)
    }
}

@Composable
private fun StatusBar(c: ConnectionState) {
    val text = when (c) {
        is ConnectionState.NoDevice -> "No device"
        is ConnectionState.Connecting -> "Connecting to ${c.name}…"
        is ConnectionState.Streaming -> "Streaming from ${c.name}"
        is ConnectionState.Failed -> "Error: ${c.message} (retrying)"
    }
    Surface(tonalElevation = 2.dp) {
        Text(text, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun WaitingMessage(c: ConnectionState) {
    val text = when (c) {
        is ConnectionState.NoDevice ->
            if (c.needsDriverHint) "The endoscope is plugged in, but Windows has no driver bound to it.\nSee docs/windows-setup.md for the one-time Zadig step."
            else "Plug in your endoscope."
        is ConnectionState.Connecting -> "Connecting…"
        is ConnectionState.Streaming -> "Waiting for the first frame…"
        is ConnectionState.Failed -> "Could not start the device.\n${c.message}\nRetrying automatically."
    }
    Text(text, color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(24.dp))
}

@Composable
private fun DebugOverlay(st: StreamStats, modifier: Modifier) {
    Text(
        "%.1f fps   frames %d   dropped %d   %.1f MB".format(st.fps, st.framesEmitted, st.framesDropped, st.bytesReceived / 1e6),
        color = Color(0xFF00FF66),
        style = MaterialTheme.typography.bodySmall,
        modifier = modifier.background(Color(0x99000000)).padding(6.dp),
    )
}

@Composable
private fun Badge(text: String, color: Color, modifier: Modifier) {
    Text(text, color = Color.White, style = MaterialTheme.typography.labelLarge, modifier = modifier.background(color).padding(horizontal = 10.dp, vertical = 4.dp))
}

@Composable
private fun Controls(s: UiState, vm: ScopeViewModel) {
    val streaming = s.image != null
    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = vm::snapshot, enabled = streaming) { Text("Snapshot") }
            Button(onClick = vm::toggleRecording, enabled = streaming) { Text(if (s.recording) "Stop" else "Record") }
            OutlinedButton(onClick = vm::rotate, enabled = !s.recording) { Text("Rotate (${s.rotation}°)") }
            OutlinedButton(onClick = vm::toggleMirror, enabled = !s.recording) { Text(if (s.mirror) "Mirror: on" else "Mirror: off") }
            OutlinedButton(onClick = vm::toggleDebug) { Text(if (s.showDebug) "Hide stats" else "Stats") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { chooseFolder(s.outputDir)?.let(vm::setOutputDir) }) { Text("Folder…") }
            Text(s.outputDir.toString(), style = MaterialTheme.typography.bodySmall)
            s.lastSaved?.let { Text("Saved: $it", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

private fun chooseFolder(current: Path): Path? {
    val chooser = JFileChooser(current.toFile()).apply {
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        dialogTitle = "Choose where to save photos and clips"
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile.toPath() else null
}
```

- [ ] **Step 2: Write the entry point (replace the placeholder)**

`desktop/src/main/kotlin/com/technicallyvu/scope/desktop/Main.kt`:
```kotlin
package com.technicallyvu.scope.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.unit.dp
import com.technicallyvu.scope.core.driver.DriverRegistry
import com.technicallyvu.scope.core.fixture.ReplayDeviceSource
import com.technicallyvu.scope.core.usb.DeviceSource
import com.technicallyvu.scope.core.useeplus.UseeplusDriver
import com.technicallyvu.scope.desktop.ui.ScopeScreen
import com.technicallyvu.scope.desktop.ui.ScopeViewModel
import com.technicallyvu.scope.desktop.usb.LibUsbDevices
import com.technicallyvu.scope.desktop.usb.WindowsDeviceCheck
import java.nio.file.Path
import java.nio.file.Paths

/**
 * USB Scope dev bench.
 *   run                      talk to real hardware via libusb
 *   run --replay <file.upkt> loop a recorded capture (no hardware needed)
 */
fun main(args: Array<String>) {
    val replay = args.indexOf("--replay").takeIf { it >= 0 && it + 1 < args.size }?.let { Paths.get(args[it + 1]) }
    val devices: DeviceSource = replay?.let { ReplayDeviceSource(it) } ?: LibUsbDevices()

    application {
        val scope = rememberCoroutineScope()
        val vm = remember {
            ScopeViewModel(
                devices = devices,
                drivers = DriverRegistry.all,
                scope = scope,
                outputDir = defaultOutputDir(),
                driverHintCheck = { replay == null && WindowsDeviceCheck.isPresentWithoutDriver(UseeplusDriver.SUPPORTED_IDS) },
            ).also { it.start() }
        }
        DisposableEffect(Unit) {
            onDispose {
                vm.stop()
                (devices as? AutoCloseable)?.close()
            }
        }
        Window(
            onCloseRequest = ::exitApplication,
            title = "USB Scope (dev bench)" + (replay?.let { "  —  replay: ${it.fileName}" } ?: ""),
            state = rememberWindowState(width = 900.dp, height = 760.dp),
        ) {
            MaterialTheme { ScopeScreen(vm) }
        }
    }
}

private fun defaultOutputDir(): Path = Paths.get(System.getProperty("user.home"), "Pictures", "USB Scope")
```

- [ ] **Step 3: Run hardware-free against the fixture**

Run: `.\gradlew.bat :desktop:run --args="--replay core/src/test/resources/fixtures/useeplus-640x480.upkt"`
Expected: a window opens, status shows "Streaming from useeplus endoscope", live video plays in a loop, "Stats" shows ~13–16 fps, Snapshot writes a `.jpg` into `~/Pictures/USB Scope`, Record then Stop writes an `.mp4` that plays in Windows Media Player. Close the window; the process exits cleanly.

- [ ] **Step 4: Run against the real device (Anthony present)**

Run: `.\gradlew.bat :desktop:run`
Acceptance, per spec §3 Phase 1 "done means":
1. Plug in → within ~2 s status flips to Streaming and live video appears.
2. Rotate until upright; Mirror if the image reads backwards. Anthony decides the right default; if it is not 90°/off, change `rotation`/`mirror` defaults in `UiState` and commit.
3. Snapshot → file appears; open it in Windows Photos, orientation is upright.
4. Record 5 s → Stop → file plays.
5. Press the cable button → a snapshot is saved (check "Saved:" changes).
6. Unplug mid-stream → status returns to "No device" within ~5 s, no crash. Re-plug → streaming resumes without restarting the app.
7. Unplug during a recording → the `.mp4` still plays.

Record anything Anthony flags as off (image quality, orientation, feel) as follow-up issues in `docs/phase1-notes.md`; do not silently change behavior beyond item 2.

- [ ] **Step 5: Full test run and commit**

Run: `.\gradlew.bat test`
Expected: all core and desktop tests PASS.

```bash
git add -A
git commit -m "feat(desktop): Compose viewer with live view, snapshot, recording, replay mode"
```

---

### Task 13: Wrap-up — README, notes, tag

**Files:**
- Modify: `README.md`
- Create: `docs/phase1-notes.md`

- [ ] **Step 1: Update the README quick start and add the replay mode and acceptance status**

Append to `README.md`:
```markdown
## Hardware-free development
    .\gradlew.bat :desktop:run --args="--replay core/src/test/resources/fixtures/useeplus-640x480.upkt"

## Status
Phase 1 (Windows dev bench) accepted on <date> against a Geek szitman supercamera 2CE3:3828.
Next: Phase 2, native Android app (see docs/superpowers/specs/).
```

- [ ] **Step 2: Write the notes file with Anthony's observations from Task 12 Step 4**

`docs/phase1-notes.md`: one bullet per observation, each with "observed", "decision", and "where it lands" (Phase 2 backlog, or fixed in Phase 1 with the commit hash).

- [ ] **Step 3: Commit and tag**

```bash
git add -A
git commit -m "docs: phase 1 notes and README status"
git tag -a v0.1.0-bench -m "Phase 1: Windows dev bench accepted"
```

---

## Self-review against the spec

- **§2 device facts / §5.1 protocol:** Tasks 2, 3, 5 implement the exact bytes and sequence; Task 8 validates against a real capture.
- **§3 Phase 1 done criteria:** Task 12 Step 4 is the acceptance checklist; fixtures + parser tests land in Tasks 7–8.
- **§4 architecture / §4.1 interfaces:** Task 2 defines them verbatim (plus `DeviceSource`, added so the view model is platform-free).
- **§5.2–5.6 components:** parser (T2), reassembler (T3), view model with rotate/mirror/debounced button (T11), snapshot with EXIF (T9), recording (T10, MPEG-4 deviation stated in Global Constraints), discovery + Zadig hint (T6, T12), storage (single default folder, deviation stated).
- **§6 privacy:** no network dependencies anywhere; attribution in README (T1). CVE record in `docs/dependencies.md` (T1).
- **§7 error handling:** disconnect → NoDevice, open failure → retry×3 then Failed with auto-retry, recording finalized in `finally`, drops counted in overlay, Windows no-driver hint. All in T5/T11/T12.
- **§8 testing:** fixtures first (T7), unit tests on fixtures (T8), replay transport as the "transport fake" (T4), manual acceptance is Anthony's (T12).
- **Type consistency check:** `UsbTransport.bulkRead(endpoint, buffer, timeoutMs): Int`, `DeviceDriver.open(transport): FrameSource`, `FrameReassembler.accept(chunk, nowNanos): Frame?`, `PacketSink.onPacket(packet, len, timestampNanos)`, `ReplayTransport(packets, loop, sleep)`, `ScopeViewModel(devices, drivers, scope, outputDir, driverHintCheck, pollMillis, recorderFactory)` are used with these exact shapes in every task that references them.
