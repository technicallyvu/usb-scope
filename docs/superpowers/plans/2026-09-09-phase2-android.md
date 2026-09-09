# Phase 2: Android App — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A native Android app (Kotlin + Jetpack Compose) that auto-launches when the endoscope is plugged into Anthony's Galaxy Z Fold 7, streams live video through the same `core` drivers proven on the Windows bench, saves photos and clips to the gallery, and requests no network permission.

**Architecture:** The session loop that the desktop view model carries (poll → open → stream → reconnect, rotation/mirror, button debounce) moves into `core` as a platform-free `ScopeSession`, so Android only supplies a USB transport over `UsbDeviceConnection`, a bitmap converter, MediaStore output, a `MediaRecorder`-backed clip recorder, and a Compose screen. The desktop shell is left untouched in this phase (it can adopt `ScopeSession` later). Android's single `-1` return for both timeout and error is bridged by a detach flag plus a consecutive-timeout limit in the transport.

**Tech Stack:** Android Gradle Plugin 9.4.0 (built-in Kotlin; Gradle 9.7.1 and JDK 17 already in place), Kotlin 2.4.10, Compose BOM 2026.08.00 (ui/foundation 1.12.0, material3 1.4.0, window-size-class 1.4.0), activity-compose 1.13.0, lifecycle-viewmodel-compose 2.11.0, core-ktx 1.19.0, exifinterface 1.4.2, kotlinx-coroutines-android 1.11.0. compileSdk 37 (forced by the pinned AndroidX AARs), targetSdk 36, minSdk 29. All checked against OSV on 2026-09-09: zero known vulnerabilities.

## Global Constraints

- Spec: `docs/superpowers/specs/2026-09-08-usb-endoscope-app-design.md` §3 (Phase 2 row), §5.4–§5.6, §6, §12. Phase 1 notes and backlog: `docs/phase1-notes.md`.
- Package root `com.technicallyvu.scope`; Android `applicationId = "com.technicallyvu.scope"`; module name `android`. App label is the working name `USB Scope` (final name is Anthony's decision, Phase 3).
- **No `INTERNET` permission** in the merged manifest, enforced by a Gradle check that runs on every `assemble`. No telemetry, analytics, crash reporters, ads, or SDKs that open sockets.
- Versions pinned exactly as listed above and recorded in `docs/dependencies.md` with the OSV check date.
- `core` stays pure Kotlin (no Android or desktop types). `ReplayDeviceSource` loses its dependency on `java.nio.file` for the primary constructor.
- No vendor trademarks in identifiers, resources, or UI strings ("Anesok", "Sup-Anesok", "Usee"); protocol/OEM identifiers already in `core` (`useeplus`, `i4season`) stay per the recorded naming decision.
- USB IDs for auto-launch: `2CE3:3828` (decimal 11491/14376) and `0329:2022` (decimal 809/8226).
- Test device: Samsung Galaxy Z Fold 7, Android 16, USB debugging on, adb at `C:\platform-tools\adb.exe` (serial `<phone-serial>`). The endoscope's cable is USB-C, so it plugs straight into the phone; the phone cannot be connected to the PC at the same time — hardware checks on the phone are done with the phone detached from the PC, results read back afterwards (screenshots via `adb exec-out screencap`, logs via `adb logcat`) once the phone is reconnected.
- Known compile pattern: JUnit Jupiter's `assertNotNull(x)` is void in Kotlin; use `requireNotNull(x)`.
- Run from `C:\Projects\usb-endoscope-app` in PowerShell with `.\gradlew.bat ...`. Commits end with `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.
- Human steps (Anthony): Task 1 SDK download and license acceptance; Task 8 acceptance on the phone.

---

## File Structure

```
gradle/libs.versions.toml            + agp, compose bom, androidx libs, coroutines-android
settings.gradle.kts                  include(":android")
build.gradle.kts                     + android application plugin apply false
local.properties                     sdk.dir (git-ignored)
core/src/main/kotlin/com/technicallyvu/scope/core/
  image/YuyvConverter.kt             YUYV -> ARGB ints (pure Kotlin)
  image/ExifOrientation.kt           rotation/mirror -> EXIF orientation 1..8
  fixture/ReplayDeviceSource.kt      primary ctor takes () -> InputStream; packets cached
  session/ScopeSession.kt            platform-free session loop, state, actions, FrameSink
core/src/test/kotlin/com/technicallyvu/scope/core/
  image/YuyvConverterTest.kt, image/ExifOrientationTest.kt, fixture/ReplayDeviceSourceTest.kt, session/ScopeSessionTest.kt
android/build.gradle.kts
android/src/main/AndroidManifest.xml
android/src/main/res/values/strings.xml, res/xml/device_filter.xml
android/src/debug/assets/fixtures/i4season-yuv-320x240.upkt   (copied from core test resources)
android/src/main/kotlin/com/technicallyvu/scope/
  MainActivity.kt                    intents, permission + detach receivers, keep-screen-on, setContent
  usb/UsbConnection.kt               small interface over UsbDeviceConnection (testable)
  usb/RealUsbConnection.kt           UsbDevice/UsbDeviceConnection-backed implementation
  usb/AndroidUsbTransport.kt         UsbTransport over UsbConnection; -1 handling; detach flag
  usb/AndroidDeviceSource.kt         DeviceSource over UsbManager; permission exception; detach hook
  media/FrameBitmaps.kt              FrameData -> Bitmap; rotate/mirror
  media/MediaStoreSaver.kt           photos to Pictures/USB Scope, videos to Movies/USB Scope
  media/SurfaceRecorder.kt           MediaRecorder(H.264 MP4) fed through a Surface canvas
  ui/ScopeViewModel.kt               AndroidViewModel: ScopeSession + bitmaps + saver + recorder + permission state
  ui/ScopeScreen.kt                  Compose UI, window-size-class aware
android/src/test/kotlin/com/technicallyvu/scope/usb/AndroidUsbTransportTest.kt
```

---

### Task 1: Android SDK on the workstation (HUMAN + agent)

**Files:** `local.properties` (new, git-ignored), environment variable `ANDROID_HOME`.

Anthony must approve the download (155.7 MB from Google) and accept the Android SDK licenses; the agent runs the commands after approval.

- [ ] **Step 1: Download and verify the command-line tools**

```powershell
$sdk = "C:\Android\sdk"; New-Item -ItemType Directory -Force "$sdk\cmdline-tools" | Out-Null
$zip = "$env:TEMP\commandlinetools-win.zip"
Invoke-WebRequest "https://dl.google.com/android/repository/commandlinetools-win-15859902_latest.zip" -OutFile $zip
(Get-FileHash $zip -Algorithm SHA256).Hash.ToLower() -eq "90ae805d20434428bffcb699c290860f19bb5f66a67e6b330067e3de801fb04a"   # must print True
Expand-Archive $zip -DestinationPath "$env:TEMP\cmdline-tools-extract" -Force
Move-Item "$env:TEMP\cmdline-tools-extract\cmdline-tools" "$sdk\cmdline-tools\latest"
```

- [ ] **Step 2: Accept licenses and install components (Anthony approves the license step)**

```powershell
$sdkm = "C:\Android\sdk\cmdline-tools\latest\bin\sdkmanager.bat"
& $sdkm --sdk_root=C:\Android\sdk --licenses          # answer y to each prompt (Anthony)
& $sdkm --sdk_root=C:\Android\sdk "platform-tools" "platforms;android-36" "build-tools;36.0.0"
& $sdkm --sdk_root=C:\Android\sdk --list_installed
```
Expected: the three packages listed.

- [ ] **Step 3: Point Gradle at the SDK**

Create `C:\Projects\usb-endoscope-app\local.properties` containing `sdk.dir=C\:\\Android\\sdk`. Set the user environment variable `ANDROID_HOME=C:\Android\sdk` (`[Environment]::SetEnvironmentVariable("ANDROID_HOME", "C:\Android\sdk", "User")`). `.gitignore` already lists `local.properties`.

- [ ] **Step 4: Confirm the phone is reachable**

Run: `C:\platform-tools\adb.exe devices` → `<phone-serial> device`.

No commit (nothing tracked changes).

---

### Task 2: Core additions — YUYV converter, EXIF orientation, replay from a stream, ScopeSession

**Files:**
- Create: `core/src/main/kotlin/com/technicallyvu/scope/core/image/YuyvConverter.kt`
- Create: `core/src/main/kotlin/com/technicallyvu/scope/core/image/ExifOrientation.kt`
- Modify: `core/src/main/kotlin/com/technicallyvu/scope/core/fixture/ReplayDeviceSource.kt`
- Create: `core/src/main/kotlin/com/technicallyvu/scope/core/session/ScopeSession.kt`
- Modify: `desktop/src/main/kotlin/com/technicallyvu/scope/desktop/Main.kt` (only if the `ReplayDeviceSource(Path, ...)` secondary constructor is not kept; the plan keeps it, so no change expected)
- Tests: `core/src/test/kotlin/com/technicallyvu/scope/core/image/YuyvConverterTest.kt`, `image/ExifOrientationTest.kt`, `fixture/ReplayDeviceSourceTest.kt`, `session/ScopeSessionTest.kt`

**Interfaces:**
- Produces: `YuyvConverter.toArgb(frame: FrameData.Yuyv422, out: IntArray)`; `ExifOrientation.of(rotationDegrees: Int, mirror: Boolean): Int`; `ReplayDeviceSource(openLog: () -> InputStream, info, videoEndpoint)` with the `Path` secondary constructor retained; `ScopeSession(devices, drivers, scope, sink, driverHintCheck, pollMillis)` with `state: StateFlow<SessionState>`, `start()`, `stop(timeoutMillis)`, `rotate()`, `toggleMirror()`, `setRecording(Boolean)`, `markSaved(String)`; `SessionState(connection, rotation, mirror, stats, recording, lastSaved)`; `ConnectionState` (`NoDevice(needsDriverHint)`, `Connecting(name)`, `Streaming(name)`, `Failed(message)`); `FrameSink { onFrame(frame, state); onButtonSnapshot() }`.

- [ ] **Step 1: Failing tests**

`core/src/test/kotlin/com/technicallyvu/scope/core/image/YuyvConverterTest.kt`:
```kotlin
package com.technicallyvu.scope.core.image

import com.technicallyvu.scope.core.driver.FrameData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class YuyvConverterTest {
    private fun yuyv(w: Int, h: Int, y: Int, u: Int, v: Int): FrameData.Yuyv422 {
        val b = ByteArray(w * h * 2)
        for (i in b.indices step 4) { b[i] = y.toByte(); b[i + 1] = u.toByte(); b[i + 2] = y.toByte(); b[i + 3] = v.toByte() }
        return FrameData.Yuyv422(w, h, b)
    }

    @Test
    fun `white black and red`() {
        val out = IntArray(8)
        YuyvConverter.toArgb(yuyv(4, 2, 235, 128, 128), out)
        assertEquals(0xFFFFFFFF.toInt(), out[7])
        YuyvConverter.toArgb(yuyv(4, 2, 16, 128, 128), out)
        assertEquals(0xFF000000.toInt(), out[0])
        YuyvConverter.toArgb(yuyv(2, 1, 81, 90, 240), out)
        val p = out[0]
        val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
        assertTrue(r > 200 && g < 40 && b < 40, "expected red, got %08X".format(p))
        assertEquals(0xFF, (p ushr 24))
    }

    @Test
    fun `output must fit the frame`() {
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> { YuyvConverter.toArgb(yuyv(4, 2, 128, 128, 128), IntArray(7)) }
    }
}
```

`core/src/test/kotlin/com/technicallyvu/scope/core/image/ExifOrientationTest.kt`:
```kotlin
package com.technicallyvu.scope.core.image

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ExifOrientationTest {
    @Test
    fun `table matches the EXIF spec`() {
        assertEquals(1, ExifOrientation.of(0, false)); assertEquals(2, ExifOrientation.of(0, true))
        assertEquals(6, ExifOrientation.of(90, false)); assertEquals(7, ExifOrientation.of(90, true))
        assertEquals(3, ExifOrientation.of(180, false)); assertEquals(4, ExifOrientation.of(180, true))
        assertEquals(8, ExifOrientation.of(270, false)); assertEquals(5, ExifOrientation.of(270, true))
        assertEquals(6, ExifOrientation.of(450, false)); assertEquals(8, ExifOrientation.of(-90, false))
    }
}
```

`core/src/test/kotlin/com/technicallyvu/scope/core/fixture/ReplayDeviceSourceTest.kt`:
```kotlin
package com.technicallyvu.scope.core.fixture

import com.technicallyvu.scope.core.TestPackets.packet
import com.technicallyvu.scope.core.usb.UsbDeviceInfo
import com.technicallyvu.scope.core.usb.UsbInterfaceInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class ReplayDeviceSourceTest {
    @Test
    fun `serves the log from a stream factory and parses it once`() {
        val out = ByteArrayOutputStream()
        PacketLogWriter(out).use { w -> val p = packet(7, 1, byteArrayOf(1, 2)); w.onPacket(p, p.size, 0) }
        var opens = 0
        val info = UsbDeviceInfo(0x2CE3, 0x3828, 0xEF, listOf(UsbInterfaceInfo(0, 0xFF, 0xF0, 1)))
        val src = ReplayDeviceSource({ opens++; ByteArrayInputStream(out.toByteArray()) }, info, videoEndpoint = 0x82)
        assertEquals(info, src.list().single().info)
        val buf = ByteArray(64)
        val t1 = src.open(src.list().single()); assertEquals(14, t1.bulkRead(0x82, buf, 100))
        val t2 = src.open(src.list().single()); assertEquals(14, t2.bulkRead(0x82, buf, 100))
        assertEquals(1, opens)
    }
}
```

`core/src/test/kotlin/com/technicallyvu/scope/core/session/ScopeSessionTest.kt`:
```kotlin
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
```

- [ ] **Step 2: Run to verify failure**

Run: `.\gradlew.bat :core:test --tests "com.technicallyvu.scope.core.image.*" --tests "com.technicallyvu.scope.core.session.*" --tests "com.technicallyvu.scope.core.fixture.ReplayDeviceSourceTest"`
Expected: compilation FAILS (unresolved `YuyvConverter`, `ExifOrientation`, `ScopeSession`, new `ReplayDeviceSource` constructor).

- [ ] **Step 3: Implement**

`core/src/main/kotlin/com/technicallyvu/scope/core/image/YuyvConverter.kt`:
```kotlin
package com.technicallyvu.scope.core.image

import com.technicallyvu.scope.core.driver.FrameData

/** Packed Y0 U Y1 V (BT.601 limited range) to ARGB_8888 ints, one per pixel. Pure Kotlin; shared by shells. */
object YuyvConverter {
    fun toArgb(frame: FrameData.Yuyv422, out: IntArray) {
        val pixels = frame.width * frame.height
        require(out.size >= pixels) { "out has ${out.size} ints, need $pixels" }
        val src = frame.bytes
        var si = 0
        var di = 0
        while (si + 3 < src.size) {
            val y0 = src[si].toInt() and 0xFF
            val u = src[si + 1].toInt() and 0xFF
            val y1 = src[si + 2].toInt() and 0xFF
            val v = src[si + 3].toInt() and 0xFF
            out[di++] = argb(y0, u, v)
            out[di++] = argb(y1, u, v)
            si += 4
        }
    }

    private fun argb(y: Int, u: Int, v: Int): Int {
        val c = y - 16
        val d = u - 128
        val e = v - 128
        val r = clamp((298 * c + 409 * e + 128) shr 8)
        val g = clamp((298 * c - 100 * d - 208 * e + 128) shr 8)
        val b = clamp((298 * c + 516 * d + 128) shr 8)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun clamp(v: Int) = if (v < 0) 0 else if (v > 255) 255 else v
}
```

`core/src/main/kotlin/com/technicallyvu/scope/core/image/ExifOrientation.kt`:
```kotlin
package com.technicallyvu.scope.core.image

/** EXIF Orientation (1..8) for "mirror horizontally, then rotate clockwise by N degrees". */
object ExifOrientation {
    fun of(rotationDegrees: Int, mirror: Boolean): Int = when (((rotationDegrees % 360) + 360) % 360) {
        0 -> if (mirror) 2 else 1
        90 -> if (mirror) 7 else 6
        180 -> if (mirror) 4 else 3
        270 -> if (mirror) 5 else 8
        else -> 1
    }
}
```

`core/src/main/kotlin/com/technicallyvu/scope/core/fixture/ReplayDeviceSource.kt` (full file):
```kotlin
package com.technicallyvu.scope.core.fixture

import com.technicallyvu.scope.core.usb.DeviceRef
import com.technicallyvu.scope.core.usb.DeviceSource
import com.technicallyvu.scope.core.usb.UsbDeviceInfo
import com.technicallyvu.scope.core.usb.UsbTransport
import com.technicallyvu.scope.core.useeplus.UseeplusDriver
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * Presents one fake device whose video stream is a looping `.upkt` capture. The log is read through
 * [openLog] (a file on desktop, an asset on Android) and parsed once.
 */
class ReplayDeviceSource(
    private val openLog: () -> InputStream,
    info: UsbDeviceInfo = UsbDeviceInfo(0x2CE3, 0x3828, 0xEF),
    private val videoEndpoint: Int = UseeplusDriver.EP_VIDEO_IN,
) : DeviceSource {
    constructor(log: Path, info: UsbDeviceInfo = UsbDeviceInfo(0x2CE3, 0x3828, 0xEF), videoEndpoint: Int = UseeplusDriver.EP_VIDEO_IN) :
        this({ Files.newInputStream(log) }, info, videoEndpoint)

    private val ref = DeviceRef(info, bus = 0, address = 0)
    private val packets: List<LoggedPacket> by lazy { openLog().use { PacketLogReader.read(it) } }

    override fun list(): List<DeviceRef> = listOf(ref)

    override fun open(ref: DeviceRef): UsbTransport =
        ReplayTransport(packets, loop = true, sleep = Thread::sleep, videoEndpoint = videoEndpoint)
}
```
(Check `desktop/.../Main.kt` still compiles: it calls the `Path` constructor with named arguments; keep parameter names identical.)

`core/src/main/kotlin/com/technicallyvu/scope/core/session/ScopeSession.kt`:
```kotlin
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
```
Note: `setRecording(false)` in the `finally` tells the shell nothing; the shell must observe `state.recording` flipping to false and finalize its recorder (Task 6 does).

- [ ] **Step 4: Run all core tests (and desktop, since `ReplayDeviceSource` changed)**

Run: `.\gradlew.bat test` — all PASS.

- [ ] **Step 5: Commit**

```bash
git add core/src
git commit -m "feat(core): YUYV->ARGB converter, EXIF orientation, stream-based replay source, shared ScopeSession"
```

---

### Task 3: Android module scaffold, manifest, no-INTERNET check, first install

**Files:**
- Modify: `gradle/libs.versions.toml`, `settings.gradle.kts`, `build.gradle.kts`, `docs/dependencies.md`
- Create: `android/build.gradle.kts`, `android/src/main/AndroidManifest.xml`, `android/src/main/res/values/strings.xml`, `android/src/main/res/xml/device_filter.xml`, `android/src/main/kotlin/com/technicallyvu/scope/MainActivity.kt` (placeholder), `android/src/debug/assets/fixtures/i4season-yuv-320x240.upkt` (copy of the core fixture)

- [ ] **Step 1: Version catalog additions**

Append to `[versions]`:
```toml
agp = "9.4.0"
composeBom = "2026.08.00"
activityCompose = "1.13.0"
lifecycle = "2.11.0"
coreKtx = "1.19.0"
exifinterface = "1.4.2"
```
Append to `[libraries]`:
```toml
androidx-compose-bom = { module = "androidx.compose:compose-bom", version.ref = "composeBom" }
androidx-compose-ui = { module = "androidx.compose.ui:ui" }
androidx-compose-foundation = { module = "androidx.compose.foundation:foundation" }
androidx-compose-material3 = { module = "androidx.compose.material3:material3" }
androidx-compose-material3-window = { module = "androidx.compose.material3:material3-window-size-class" }
androidx-activity-compose = { module = "androidx.activity:activity-compose", version.ref = "activityCompose" }
androidx-lifecycle-viewmodel-compose = { module = "androidx.lifecycle:lifecycle-viewmodel-compose", version.ref = "lifecycle" }
androidx-core-ktx = { module = "androidx.core:core-ktx", version.ref = "coreKtx" }
androidx-exifinterface = { module = "androidx.exifinterface:exifinterface", version.ref = "exifinterface" }
kotlinx-coroutines-android = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-android", version.ref = "coroutines" }
```
Append to `[plugins]`:
```toml
android-application = { id = "com.android.application", version.ref = "agp" }
```
`settings.gradle.kts`: `include(":core", ":desktop", ":android")`. Root `build.gradle.kts`: add `alias(libs.plugins.android.application) apply false`.

- [ ] **Step 2: Module build file**

`android/build.gradle.kts`:
```kotlin
import com.android.build.api.artifact.SingleArtifact

plugins {
    alias(libs.plugins.android.application)   // AGP 9: Kotlin is built in; do not apply org.jetbrains.kotlin.android
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.technicallyvu.scope"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.technicallyvu.scope"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.2.0"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

// Built-in Kotlin (AGP 9): the Kotlin JVM target must match compileOptions.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.window)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.exifinterface)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

// Privacy gate: fail the build if any dependency drags in the INTERNET permission.
androidComponents {
    onVariants { variant ->
        val cap = variant.name.replaceFirstChar { it.uppercase() }
        val check = tasks.register("checkNoInternetPermission$cap") {
            val manifest = variant.artifacts.get(SingleArtifact.MERGED_MANIFEST)
            inputs.file(manifest)
            doLast {
                val text = manifest.get().asFile.readText()
                check(!text.contains("android.permission.INTERNET")) { "INTERNET permission found in the merged ${variant.name} manifest" }
                println("OK: no INTERNET permission in the ${variant.name} manifest")
            }
        }
        tasks.matching { it.name == "assemble$cap" }.configureEach { dependsOn(check) }
    }
}
```
If the `kotlin { compilerOptions { ... } }` block is not accepted at the top level under AGP 9's built-in Kotlin, use `android { kotlinOptions { jvmTarget = "17" } }` (or whatever the AGP 9.4 DSL exposes) and record it in the report; the requirement is JVM target 17 for the module.

- [ ] **Step 3: Manifest and resources**

`android/src/main/AndroidManifest.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-feature android:name="android.hardware.usb.host" android:required="true" />

    <application
        android:label="@string/app_name"
        android:icon="@android:drawable/ic_menu_camera"
        android:allowBackup="false"
        android:supportsRtl="true"
        android:theme="@android:style/Theme.Material.NoActionBar">

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:launchMode="singleTop"
            android:configChanges="orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden|uiMode">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
            <intent-filter>
                <action android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED" />
            </intent-filter>
            <meta-data
                android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED"
                android:resource="@xml/device_filter" />
        </activity>
    </application>
</manifest>
```

`android/src/main/res/values/strings.xml`:
```xml
<resources>
    <string name="app_name">USB Scope</string>
</resources>
```

`android/src/main/res/xml/device_filter.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!-- 2CE3:3828 -->
    <usb-device vendor-id="11491" product-id="14376" />
    <!-- 0329:2022 -->
    <usb-device vendor-id="809" product-id="8226" />
</resources>
```

Placeholder `android/src/main/kotlin/com/technicallyvu/scope/MainActivity.kt`:
```kotlin
package com.technicallyvu.scope

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Text("USB Scope: UI arrives in Task 7") } }
    }
}
```

Copy the fixture: `Copy-Item core\src\test\resources\fixtures\i4season-yuv-320x240.upkt android\src\debug\assets\fixtures\` (create the folder).

- [ ] **Step 4: Build, gate, install, auto-launch check**

Run: `.\gradlew.bat :android:assembleDebug`
Expected: `BUILD SUCCESSFUL` and the line `OK: no INTERNET permission in the debug manifest`. (First run downloads the Android dependencies; allow up to 10 minutes.)

Negative check of the gate: temporarily add `<uses-permission android:name="android.permission.INTERNET"/>` to the manifest, run `assembleDebug`, expect the build to FAIL with the gate's message, then revert.

Install: `C:\platform-tools\adb.exe install -r android\build\outputs\apk\debug\android-debug.apk` → `Success`. Launch: `adb shell am start -n com.technicallyvu.scope/.MainActivity`; screenshot `adb exec-out screencap -p > .superpowers\re\frames\android-task3.png` shows the placeholder text.

Auto-launch (Anthony): disconnect the phone from the PC, plug the endoscope into the phone. Expected: Android shows "Open USB Scope to handle supercamera?" (or launches directly if "always" was chosen). Note the outcome in the report; it is re-verified in Task 8.

Update `docs/dependencies.md` with the Android rows and the 2026-09-09 OSV check.

- [ ] **Step 5: Commit**

```bash
git add gradle settings.gradle.kts build.gradle.kts android docs/dependencies.md
git commit -m "feat(android): module scaffold, USB auto-launch manifest, no-INTERNET build gate"
```

---

### Task 4: Android USB layer

**Files:**
- Create: `android/src/main/kotlin/com/technicallyvu/scope/usb/UsbConnection.kt`
- Create: `android/src/main/kotlin/com/technicallyvu/scope/usb/RealUsbConnection.kt`
- Create: `android/src/main/kotlin/com/technicallyvu/scope/usb/AndroidUsbTransport.kt`
- Create: `android/src/main/kotlin/com/technicallyvu/scope/usb/AndroidDeviceSource.kt`
- Test: `android/src/test/kotlin/com/technicallyvu/scope/usb/AndroidUsbTransportTest.kt`

**Interfaces:**
- Produces: `UsbConnection` (claim/release/setInterface/bulkTransfer/controlTransfer/close), `RealUsbConnection(device, connection)`, `AndroidUsbTransport(conn, maxConsecutiveTimeouts = 20)` with `@Volatile var detached`, `AndroidDeviceSource(usbManager)` with `onDetached(deviceName)` and `pendingPermission: UsbDevice?`, `UsbPermissionException(device)`.

- [ ] **Step 1: Failing transport tests**

`android/src/test/kotlin/com/technicallyvu/scope/usb/AndroidUsbTransportTest.kt`:
```kotlin
package com.technicallyvu.scope.usb

import com.technicallyvu.scope.core.usb.UsbException
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class AndroidUsbTransportTest {
    private class FakeConnection : UsbConnection {
        val calls = mutableListOf<String>()
        var bulkResults = ArrayDeque<Int>()
        var controlReply: ByteArray? = null
        override fun claimInterface(number: Int) = true.also { calls += "claim $number" }
        override fun releaseInterface(number: Int) = true.also { calls += "release $number" }
        override fun setInterface(number: Int, alt: Int) = true.also { calls += "alt $number $alt" }
        override fun bulkTransfer(endpoint: Int, buffer: ByteArray, length: Int, timeoutMs: Int): Int {
            calls += "bulk %02X %d".format(endpoint, length)
            val r = bulkResults.removeFirstOrNull() ?: -1
            if (r > 0) for (i in 0 until r) buffer[i] = 7
            return r
        }
        override fun controlTransfer(requestType: Int, request: Int, value: Int, index: Int, buffer: ByteArray?, length: Int, timeoutMs: Int): Int {
            calls += "ctrl %02X %02X %04X %04X %d".format(requestType, request, value, index, length)
            val reply = controlReply ?: return length
            reply.copyInto(buffer!!, 0, 0, minOf(reply.size, length)); return minOf(reply.size, length)
        }
        override fun close() { calls += "close" }
    }

    @Test
    fun `timeouts return zero until the limit then throw`() {
        val c = FakeConnection()
        val t = AndroidUsbTransport(c, maxConsecutiveTimeouts = 3)
        val buf = ByteArray(16)
        assertEquals(0, t.bulkRead(0x82, buf, 500))
        assertEquals(0, t.bulkRead(0x82, buf, 500))
        assertThrows(UsbException::class.java) { t.bulkRead(0x82, buf, 500) }
    }

    @Test
    fun `data resets the timeout counter`() {
        val c = FakeConnection().apply { bulkResults = ArrayDeque(listOf(-1, -1, 4, -1, -1)) }
        val t = AndroidUsbTransport(c, maxConsecutiveTimeouts = 3)
        val buf = ByteArray(16)
        t.bulkRead(0x82, buf, 500); t.bulkRead(0x82, buf, 500)
        assertEquals(4, t.bulkRead(0x82, buf, 500)); assertEquals(7, buf[3].toInt())
        assertEquals(0, t.bulkRead(0x82, buf, 500)); assertEquals(0, t.bulkRead(0x82, buf, 500))
    }

    @Test
    fun `detach makes the next read throw`() {
        val c = FakeConnection().apply { bulkResults = ArrayDeque(listOf(4)) }
        val t = AndroidUsbTransport(c)
        t.detached = true
        assertThrows(UsbException::class.java) { t.bulkRead(0x82, ByteArray(16), 500) }
    }

    @Test
    fun `control transfer fills IN buffers and reports lengths`() {
        val c = FakeConnection().apply { controlReply = byteArrayOf(1, 2, 3) }
        val t = AndroidUsbTransport(c)
        val buf = ByteArray(512)
        assertEquals(3, t.controlTransfer(0xA0, 0, 5, 0, buf, 1000))
        assertArrayEquals(byteArrayOf(1, 2, 3), buf.copyOf(3))
        c.controlReply = null
        assertEquals(64, t.controlTransfer(0x20, 1, 5, 0, ByteArray(64), 1000))
        assertEquals(listOf("ctrl A0 00 0005 0000 512", "ctrl 20 01 0005 0000 64"), c.calls)
    }

    @Test
    fun `clearHalt is a CLEAR_FEATURE ENDPOINT_HALT control request`() {
        val c = FakeConnection()
        AndroidUsbTransport(c).clearHalt(0x01)
        assertEquals(listOf("ctrl 02 01 0000 0001 0"), c.calls)
    }

    @Test
    fun `claim failure and reset are UsbExceptions`() {
        val c = object : UsbConnection by FakeConnection() { override fun claimInterface(number: Int) = false }
        val t = AndroidUsbTransport(c)
        assertThrows(UsbException::class.java) { t.claimInterface(0) }
        assertThrows(UsbException::class.java) { t.resetDevice() }
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `.\gradlew.bat :android:testDebugUnitTest --tests "com.technicallyvu.scope.usb.AndroidUsbTransportTest"`
Expected: compilation FAILS (unresolved `UsbConnection`, `AndroidUsbTransport`).

- [ ] **Step 3: Implement**

`android/src/main/kotlin/com/technicallyvu/scope/usb/UsbConnection.kt`:
```kotlin
package com.technicallyvu.scope.usb

/** The slice of android.hardware.usb.UsbDeviceConnection the transport uses, kept as an interface so it can be faked in JVM tests. */
interface UsbConnection : AutoCloseable {
    fun claimInterface(number: Int): Boolean
    fun releaseInterface(number: Int): Boolean
    fun setInterface(number: Int, alt: Int): Boolean
    /** Android semantics: bytes transferred, or -1 on timeout OR error. */
    fun bulkTransfer(endpoint: Int, buffer: ByteArray, length: Int, timeoutMs: Int): Int
    fun controlTransfer(requestType: Int, request: Int, value: Int, index: Int, buffer: ByteArray?, length: Int, timeoutMs: Int): Int
}
```

`android/src/main/kotlin/com/technicallyvu/scope/usb/RealUsbConnection.kt`:
```kotlin
package com.technicallyvu.scope.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface

class RealUsbConnection(private val device: UsbDevice, private val connection: UsbDeviceConnection) : UsbConnection {
    private val endpoints: Map<Int, UsbEndpoint> = buildMap {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            for (e in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(e)
                putIfAbsent(ep.address, ep)
            }
        }
    }

    private fun iface(number: Int, alt: Int): UsbInterface? {
        for (i in 0 until device.interfaceCount) {
            val it = device.getInterface(i)
            if (it.id == number && it.alternateSetting == alt) return it
        }
        return null
    }

    override fun claimInterface(number: Int): Boolean = iface(number, 0)?.let { connection.claimInterface(it, true) } ?: false
    override fun releaseInterface(number: Int): Boolean = iface(number, 0)?.let { connection.releaseInterface(it) } ?: false
    override fun setInterface(number: Int, alt: Int): Boolean = iface(number, alt)?.let { connection.setInterface(it) } ?: false

    override fun bulkTransfer(endpoint: Int, buffer: ByteArray, length: Int, timeoutMs: Int): Int {
        val ep = endpoints[endpoint] ?: return -1
        return connection.bulkTransfer(ep, buffer, length, timeoutMs)
    }

    override fun controlTransfer(requestType: Int, request: Int, value: Int, index: Int, buffer: ByteArray?, length: Int, timeoutMs: Int): Int =
        connection.controlTransfer(requestType, request, value, index, buffer, length, timeoutMs)

    override fun close() = connection.close()
}
```

`android/src/main/kotlin/com/technicallyvu/scope/usb/AndroidUsbTransport.kt`:
```kotlin
package com.technicallyvu.scope.usb

import com.technicallyvu.scope.core.usb.UsbException
import com.technicallyvu.scope.core.usb.UsbTransport

/**
 * [UsbTransport] over the Android USB Host API. Android reports both timeout and error as -1 from
 * bulkTransfer, so "device gone" is derived from the [detached] flag (set from ACTION_USB_DEVICE_DETACHED)
 * plus a limit on consecutive empty reads.
 */
class AndroidUsbTransport(
    private val conn: UsbConnection,
    private val maxConsecutiveTimeouts: Int = 20,
) : UsbTransport {
    @Volatile var detached: Boolean = false
    private var consecutiveTimeouts = 0

    override fun claimInterface(iface: Int) {
        if (!conn.claimInterface(iface)) throw UsbException("claimInterface $iface failed")
    }

    override fun releaseInterface(iface: Int) {
        conn.releaseInterface(iface)
    }

    override fun setAltSetting(iface: Int, alt: Int) {
        if (!conn.setInterface(iface, alt)) throw UsbException("setAltSetting $iface/$alt failed")
    }

    override fun clearHalt(endpoint: Int) {
        // Standard request: bmRequestType 0x02 (host-to-device, standard, endpoint), CLEAR_FEATURE (1), ENDPOINT_HALT (0), wIndex = endpoint
        val r = conn.controlTransfer(0x02, 0x01, 0x00, endpoint, null, 0, 1000)
        if (r < 0) throw UsbException("clearHalt %02X failed".format(endpoint))
    }

    override fun bulkWrite(endpoint: Int, data: ByteArray, timeoutMs: Int): Int {
        val n = conn.bulkTransfer(endpoint, data, data.size, timeoutMs)
        if (n < 0) throw UsbException("bulkWrite %02X failed".format(endpoint))
        return n
    }

    override fun bulkRead(endpoint: Int, buffer: ByteArray, timeoutMs: Int): Int {
        if (detached) throw UsbException("device detached")
        val n = conn.bulkTransfer(endpoint, buffer, buffer.size, timeoutMs)
        if (n >= 0) {
            consecutiveTimeouts = 0
            return n
        }
        if (detached) throw UsbException("device detached")
        consecutiveTimeouts++
        if (consecutiveTimeouts >= maxConsecutiveTimeouts) {
            throw UsbException("no data after $maxConsecutiveTimeouts reads; assuming the device is gone")
        }
        return 0
    }

    override fun controlTransfer(requestType: Int, request: Int, value: Int, index: Int, data: ByteArray, timeoutMs: Int): Int {
        val r = conn.controlTransfer(requestType, request, value, index, data, data.size, timeoutMs)
        if (r < 0) throw UsbException("controlTransfer %02X/%02X failed".format(requestType, request))
        return r
    }

    override fun resetDevice() {
        throw UsbException("resetDevice is not available on Android")
    }

    override fun close() = conn.close()
}
```

`android/src/main/kotlin/com/technicallyvu/scope/usb/AndroidDeviceSource.kt`:
```kotlin
package com.technicallyvu.scope.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.technicallyvu.scope.core.usb.DeviceRef
import com.technicallyvu.scope.core.usb.DeviceSource
import com.technicallyvu.scope.core.usb.UsbDeviceInfo
import com.technicallyvu.scope.core.usb.UsbException
import com.technicallyvu.scope.core.usb.UsbInterfaceInfo
import com.technicallyvu.scope.core.usb.UsbTransport
import java.util.concurrent.ConcurrentHashMap

class UsbPermissionException(val device: UsbDevice) : UsbException("USB permission not granted for ${device.deviceName}")

/** [DeviceSource] over [UsbManager]. `DeviceRef.address` carries Android's deviceId. */
class AndroidDeviceSource(private val usbManager: UsbManager) : DeviceSource {
    private val openTransports = ConcurrentHashMap<String, AndroidUsbTransport>()

    /** Set when [open] found a device we lack permission for; the shell requests permission and clears it. */
    @Volatile var pendingPermission: UsbDevice? = null

    override fun list(): List<DeviceRef> = usbManager.deviceList.values.map { d -> DeviceRef(info(d), bus = 0, address = d.deviceId) }

    override fun open(ref: DeviceRef): UsbTransport {
        val device = usbManager.deviceList.values.firstOrNull { it.deviceId == ref.address } ?: throw UsbException("device ${ref.info.idString} is gone")
        if (!usbManager.hasPermission(device)) {
            pendingPermission = device
            throw UsbPermissionException(device)
        }
        pendingPermission = null
        val connection = usbManager.openDevice(device) ?: throw UsbException("openDevice returned null for ${device.deviceName}")
        val transport = AndroidUsbTransport(RealUsbConnection(device, connection))
        openTransports[device.deviceName] = transport
        return transport
    }

    /** Called from the ACTION_USB_DEVICE_DETACHED receiver so an in-flight read fails fast. */
    fun onDetached(deviceName: String) {
        openTransports.remove(deviceName)?.detached = true
    }

    companion object {
        fun info(d: UsbDevice): UsbDeviceInfo {
            val interfaces = (0 until d.interfaceCount).map { d.getInterface(it) }
                .filter { it.alternateSetting == 0 }
                .map { UsbInterfaceInfo(it.id, it.interfaceClass, it.interfaceSubclass, it.interfaceProtocol) }
            return UsbDeviceInfo(d.vendorId, d.productId, d.deviceClass, interfaces)
        }
    }
}
```

- [ ] **Step 4: Run tests and build**

Run: `.\gradlew.bat :android:testDebugUnitTest` → 6 PASS. `.\gradlew.bat :android:assembleDebug` → BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add android/src
git commit -m "feat(android): USB transport and device source over the Android USB Host API"
```

---

### Task 5: Media — bitmaps, MediaStore output, surface recorder

**Files:**
- Create: `android/src/main/kotlin/com/technicallyvu/scope/media/FrameBitmaps.kt`
- Create: `android/src/main/kotlin/com/technicallyvu/scope/media/MediaStoreSaver.kt`
- Create: `android/src/main/kotlin/com/technicallyvu/scope/media/SurfaceRecorder.kt`

These use Android framework classes and are verified on the device in Tasks 7–8 (no JVM tests).

- [ ] **Step 1: Implement**

`FrameBitmaps.kt`:
```kotlin
package com.technicallyvu.scope.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import com.technicallyvu.scope.core.driver.FrameData
import com.technicallyvu.scope.core.image.YuyvConverter

/** Converts frames to bitmaps and applies the view transform. Reuses its pixel buffer; use from one thread. */
class FrameBitmaps {
    private var argb = IntArray(0)

    fun toBitmap(data: FrameData): Bitmap? = when (data) {
        is FrameData.Jpeg -> BitmapFactory.decodeByteArray(data.bytes, 0, data.bytes.size)
        is FrameData.Yuyv422 -> {
            val n = data.width * data.height
            if (argb.size < n) argb = IntArray(n)
            YuyvConverter.toArgb(data, argb)
            Bitmap.createBitmap(argb, data.width, data.height, Bitmap.Config.ARGB_8888)
        }
    }

    /** Mirror horizontally (if asked), then rotate clockwise. Returns [src] itself when nothing changes. */
    fun transform(src: Bitmap, rotationDegrees: Int, mirror: Boolean): Bitmap {
        val rot = ((rotationDegrees % 360) + 360) % 360
        if (rot == 0 && !mirror) return src
        val m = Matrix()
        if (mirror) m.preScale(-1f, 1f)
        m.postRotate(rot.toFloat())
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }
}
```

`MediaStoreSaver.kt`:
```kotlin
package com.technicallyvu.scope.media

import android.content.ContentResolver
import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.IOException

/** Writes photos to Pictures/<folder> and clips to Movies/<folder> through MediaStore (no storage permission needed on API 29+). */
class MediaStoreSaver(private val resolver: ContentResolver, private val folder: String = "USB Scope") {

    fun saveJpeg(bytes: ByteArray, exifOrientation: Int?, displayName: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/$folder")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: throw IOException("MediaStore insert failed")
        try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: throw IOException("cannot open $uri")
            if (exifOrientation != null) {
                resolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                    ExifInterface(pfd.fileDescriptor).apply {
                        setAttribute(ExifInterface.TAG_ORIENTATION, exifOrientation.toString())
                        saveAttributes()
                    }
                }
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            return uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    fun saveBitmapJpeg(bitmap: Bitmap, displayName: String, quality: Int = 92): Uri {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return saveJpeg(out.toByteArray(), null, displayName)
    }

    class PendingVideo(val uri: Uri, val fd: ParcelFileDescriptor)

    fun createVideo(displayName: String): PendingVideo {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/$folder")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: throw IOException("MediaStore insert failed")
        val fd = resolver.openFileDescriptor(uri, "rw") ?: run { resolver.delete(uri, null, null); throw IOException("cannot open $uri") }
        return PendingVideo(uri, fd)
    }

    fun finishVideo(video: PendingVideo, keep: Boolean) {
        runCatching { video.fd.close() }
        if (keep) resolver.update(video.uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
        else resolver.delete(video.uri, null, null)
    }
}
```

`SurfaceRecorder.kt`:
```kotlin
package com.technicallyvu.scope.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.media.MediaRecorder
import android.os.Build
import android.os.ParcelFileDescriptor
import android.view.Surface

/**
 * H.264/MP4 clip recorder fed by drawing bitmaps onto the encoder's input surface. Frame timing
 * comes from the surface (variable frame rate is fine). Not thread-safe; callers serialize.
 */
class SurfaceRecorder(context: Context, fd: ParcelFileDescriptor, width: Int, height: Int) : AutoCloseable {
    private val w = width and 1.inv()    // H.264 needs even dimensions
    private val h = height and 1.inv()
    private val recorder: MediaRecorder = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
    private val surface: Surface

    var framesWritten: Int = 0
        private set

    init {
        recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        recorder.setOutputFile(fd.fileDescriptor)
        recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        recorder.setVideoSize(w, h)
        recorder.setVideoFrameRate(15)
        recorder.setVideoEncodingBitRate(4_000_000)
        try {
            recorder.prepare()
            surface = recorder.surface
            recorder.start()
        } catch (e: Exception) {
            recorder.release()
            throw e
        }
    }

    fun record(bitmap: Bitmap) {
        val canvas = surface.lockCanvas(null)
        try {
            canvas.drawBitmap(bitmap, null, Rect(0, 0, w, h), null)
        } finally {
            surface.unlockCanvasAndPost(canvas)
        }
        framesWritten++
    }

    override fun close() {
        try {
            recorder.stop()          // throws RuntimeException when no frame was ever written
        } catch (e: RuntimeException) {
            // nothing usable was recorded; the caller discards the file when framesWritten == 0
        } finally {
            surface.release()
            recorder.release()
        }
    }
}
```

- [ ] **Step 2: Build and commit**

Run: `.\gradlew.bat :android:assembleDebug` → BUILD SUCCESSFUL.
```bash
git add android/src
git commit -m "feat(android): frame bitmaps, MediaStore saver, surface-fed MP4 recorder"
```

---

### Task 6: View model and activity wiring

**Files:**
- Create: `android/src/main/kotlin/com/technicallyvu/scope/ui/ScopeViewModel.kt`
- Modify: `android/src/main/kotlin/com/technicallyvu/scope/MainActivity.kt`

**Interfaces:**
- Produces: `ScopeViewModel(app)` with `ui: StateFlow<UiState>` (`session: SessionState`, `image: Bitmap?`, `permissionDevice: UsbDevice?`, `message: String?`, `replaying: Boolean`), actions `snapshot()`, `toggleRecording()`, `rotate()`, `toggleMirror()`, `toggleStats()`, `onDeviceDetached(name)`, `onPermissionResult(granted)`, `startReplay()` (debug), `stopReplay()`.

- [ ] **Step 1: Implement the view model**

`android/src/main/kotlin/com/technicallyvu/scope/ui/ScopeViewModel.kt`:
```kotlin
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

    private var session: ScopeSession = newSession(usbDevices)
    @Volatile private var lastFrame: FrameData? = null
    @Volatile private var lastImage: Bitmap? = null
    private val recorderLock = Any()
    private var recorder: SurfaceRecorder? = null
    private var pendingVideo: MediaStoreSaver.PendingVideo? = null
    private var recorderGeneration = 0L

    init {
        session.start()
        viewModelScope.launch {
            session.state.collect { s ->
                _ui.update { it.copy(session = s, permissionDevice = if (s.connection is ConnectionState.Failed) usbDevices.pendingPermission else null) }
                if (!s.recording) stopRecordingIfActive()
            }
        }
    }

    private fun newSession(devices: DeviceSource) = ScopeSession(devices, DriverRegistry.all, viewModelScope, sink = this)

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
        if (!BuildConfig.DEBUG) return
        session.stop()
        val app = getApplication<Application>()
        val info = UsbDeviceInfo(0x2CE3, 0x3828, 0xEF, listOf(UsbInterfaceInfo(0, 0xFF, 0xF0, 1)))
        val replay = ReplayDeviceSource({ app.assets.open("fixtures/i4season-yuv-320x240.upkt") }, info, videoEndpoint = I4seasonYuvDriver.EP_IN)
        session = newSession(replay).also { it.start() }
        _ui.update { it.copy(replaying = true) }
        viewModelScope.launch { session.state.collect { s -> _ui.update { it.copy(session = s) } } }
    }

    fun stopReplay() {
        if (!_ui.value.replaying) return
        session.stop()
        session = newSession(usbDevices).also { it.start() }
        _ui.update { it.copy(replaying = false, image = null) }
        viewModelScope.launch { session.state.collect { s -> _ui.update { it.copy(session = s) } } }
    }

    override fun onCleared() {
        session.stop()
        stopRecordingIfActive()
    }

    companion object {
        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.ROOT)
    }
}
```
Note for the implementer: the `init` collector and the replay collectors each collect a different session; when swapping sessions, cancel the previous collector (hold its `Job` in a field and cancel before starting the next). Apply that refinement while implementing; the shape above shows intent.

- [ ] **Step 2: Activity wiring**

`android/src/main/kotlin/com/technicallyvu/scope/MainActivity.kt`:
```kotlin
package com.technicallyvu.scope

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.core.content.ContextCompat
import com.technicallyvu.scope.ui.ScopeScreen
import com.technicallyvu.scope.ui.ScopeViewModel

class MainActivity : ComponentActivity() {
    private val vm: ScopeViewModel by viewModels()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    device?.let { vm.onDeviceDetached(it.deviceName) }
                }
                ACTION_USB_PERMISSION -> vm.onPermissionResult(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_USB_PERMISSION)
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        setContent {
            MaterialTheme {
                ScopeScreen(vm, onRequestPermission = ::requestUsbPermission)
            }
        }
    }

    private fun requestUsbPermission(device: UsbDevice) {
        val usb = getSystemService(UsbManager::class.java)
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(packageName)
        val pi = PendingIntent.getBroadcast(this, 0, intent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        usb.requestPermission(device, pi)
    }

    override fun onDestroy() {
        unregisterReceiver(receiver)
        super.onDestroy()
    }

    companion object {
        const val ACTION_USB_PERMISSION = "com.technicallyvu.scope.USB_PERMISSION"
    }
}
```

- [ ] **Step 3: Build and commit** (the screen composable arrives in Task 7; to compile now, add a temporary `ScopeScreen` stub in `ui/ScopeScreen.kt` showing the connection state as text, replaced in Task 7).

Run: `.\gradlew.bat :android:assembleDebug` → BUILD SUCCESSFUL.
```bash
git add android/src
git commit -m "feat(android): view model over the shared session; USB permission and detach wiring"
```

---

### Task 7: Compose screen, window-size-class layout, replay run on the phone

**Files:**
- Create/replace: `android/src/main/kotlin/com/technicallyvu/scope/ui/ScopeScreen.kt`

- [ ] **Step 1: Implement**

```kotlin
package com.technicallyvu.scope.ui

import android.hardware.usb.UsbDevice
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.technicallyvu.scope.BuildConfig
import com.technicallyvu.scope.core.driver.StreamStats
import com.technicallyvu.scope.core.session.ConnectionState
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScopeScreen(vm: ScopeViewModel, onRequestPermission: (UsbDevice) -> Unit) {
    val ui by vm.ui.collectAsState()
    val activity = LocalContext.current as android.app.Activity
    val wide = calculateWindowSizeClass(activity).widthSizeClass == WindowWidthSizeClass.Expanded

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        StatusBar(ui)
        if (wide) {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                LiveView(ui, Modifier.weight(1f).fillMaxHeight())
                Column(Modifier.width(220.dp).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Controls(ui, vm, onRequestPermission) }
            }
        } else {
            LiveView(ui, Modifier.weight(1f).fillMaxWidth())
            FlowRow(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Controls(ui, vm, onRequestPermission) }
        }
        ui.message?.let { msg -> Snackbar(action = { OutlinedButton(onClick = vm::clearMessage) { Text("OK") } }) { Text(msg) } }
    }
}

@Composable
private fun StatusBar(ui: UiState) {
    val text = when (val c = ui.session.connection) {
        is ConnectionState.NoDevice -> "No device"
        is ConnectionState.Connecting -> "Connecting…"
        is ConnectionState.Streaming -> if (ui.replaying) "Replaying fixture" else "Streaming"
        is ConnectionState.Failed -> "Error: ${c.message}"
    }
    Surface(tonalElevation = 2.dp) {
        Text(text, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun LiveView(ui: UiState, modifier: Modifier) {
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        val img = ui.image
        if (img != null) {
            Image(bitmap = img.asImageBitmap(), contentDescription = "Live view", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        } else {
            val text = when (val c = ui.session.connection) {
                is ConnectionState.NoDevice -> "Plug in your endoscope."
                is ConnectionState.Connecting -> "Connecting…"
                is ConnectionState.Streaming -> "Waiting for the first frame…"
                is ConnectionState.Failed -> if (ui.permissionDevice != null) "USB permission needed." else "Could not start the device.\n${c.message}\nRetrying automatically."
            }
            Text(text, color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(24.dp))
        }
        if (ui.showStats) StatsOverlay(ui.session.stats, Modifier.align(Alignment.TopStart).padding(8.dp))
        if (ui.session.recording) Text("REC", color = Color.White, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).background(Color(0xFFD32F2F)).padding(horizontal = 10.dp, vertical = 4.dp))
    }
}

@Composable
private fun StatsOverlay(st: StreamStats, modifier: Modifier) {
    Text(
        String.format(Locale.ROOT, "%.1f fps  frames %d  partial %d  dropped %d  %.1f MB", st.fps, st.framesEmitted, st.framesPartial, st.framesDropped, st.bytesReceived / 1e6),
        color = Color(0xFF00FF66), style = MaterialTheme.typography.bodySmall,
        modifier = modifier.background(Color(0x99000000)).padding(6.dp),
    )
}

@Composable
private fun Controls(ui: UiState, vm: ScopeViewModel, onRequestPermission: (UsbDevice) -> Unit) {
    val streaming = ui.image != null
    val s = ui.session
    ui.permissionDevice?.let { dev -> Button(onClick = { onRequestPermission(dev) }) { Text("Allow USB access") } }
    Button(onClick = vm::snapshot, enabled = streaming) { Text("Snapshot") }
    Button(onClick = vm::toggleRecording, enabled = streaming) { Text(if (s.recording) "Stop" else "Record") }
    OutlinedButton(onClick = vm::rotate, enabled = !s.recording) { Text("Rotate (${s.rotation}°)") }
    OutlinedButton(onClick = vm::toggleMirror, enabled = !s.recording) { Text(if (s.mirror) "Mirror: on" else "Mirror: off") }
    OutlinedButton(onClick = vm::toggleStats) { Text(if (ui.showStats) "Hide stats" else "Stats") }
    if (BuildConfig.DEBUG) {
        OutlinedButton(onClick = { if (ui.replaying) vm.stopReplay() else vm.startReplay() }) { Text(if (ui.replaying) "Stop replay" else "Replay fixture") }
    }
    s.lastSaved?.let { Text("Saved: $it", style = MaterialTheme.typography.bodySmall) }
}
```

- [ ] **Step 2: Build, install, replay on the phone (phone connected to the PC)**

Run: `.\gradlew.bat :android:assembleDebug` then `C:\platform-tools\adb.exe install -r android\build\outputs\apk\debug\android-debug.apk`, `adb shell am start -n com.technicallyvu.scope/.MainActivity`, then tap "Replay fixture" via `adb shell input tap` (find coordinates from `adb exec-out screencap`), or ask Anthony to tap it. Expected: live picture of the fixture (the soda can), stats overlay ~11 fps, Snapshot creates `Pictures/USB Scope/SCOPE_*.jpg` visible in the Gallery (`adb shell ls /sdcard/Pictures/USB\ Scope`), Record/Stop creates a playable `Movies/USB Scope/SCOPE_*.mp4` (`adb pull` it and check with the desktop's ffmpeg-backed test or any player). Capture `adb exec-out screencap -p > .superpowers\re\frames\android-replay.png` for the record. Check `adb logcat -d | findstr /i "scope AndroidRuntime"` for exceptions.

- [ ] **Step 3: Commit**

```bash
git add android/src
git commit -m "feat(android): Compose screen with foldable-aware layout and debug replay"
```

---

### Task 8: Hardware acceptance on the Fold 7 (HUMAN)

Anthony, with the phone detached from the PC:

1. Plug the endoscope into the phone. Expected: Android offers to open USB Scope (tick "Always" to make it automatic), the app opens and streams within a couple of seconds.
2. Picture upright by default (rotation 180 for this unit). Rotate/Mirror work; both lock while recording.
3. Snapshot → appears in the Gallery under "USB Scope". Cable button → same.
4. Record 5 s → Stop → clip plays in the Gallery. Unplug during a recording → the clip still plays.
5. Unplug → "No device" within ~10 s (the transport gives up after 20 empty reads of 500 ms, or immediately if the detach broadcast arrives); re-plug → streaming resumes (permission may be asked again unless "Always" was ticked).
6. Fold/unfold: the layout switches between the stacked and side-by-side arrangements without losing the stream.
7. Settings → Apps → USB Scope → Permissions shows no network-related permission; App info shows "No permissions requested" or only the USB-related entries.

Record outcomes and any UX notes in `docs/phase2-notes.md`. Then reconnect the phone and pull `adb logcat -d` for any warnings.

---

### Task 9: Wrap-up

- `README.md`: Android section (build, install, replay button, what the app does and does not request). `docs/dependencies.md` already updated in Task 3. `docs/phase2-notes.md` from Task 8.
- Full run: `.\gradlew.bat test :android:testDebugUnitTest :android:assembleDebug` — green, and the no-INTERNET gate line printed.
- Commit `docs: phase 2 notes and README Android section`; tag `v0.2.0-android`.
- Whole-branch review (subagent-driven-development final review), fix pass, merge to master.

---

## Self-review against the spec

- §3 Phase 2 row: native Kotlin/Compose app, min SDK 29, target SDK 36, auto-launch on plug-in — Tasks 3, 6, 7, 8.
- §5.4 view model behaviours — `ScopeSession` (Task 2) + `ScopeViewModel` (Task 6).
- §5.5 snapshot (JPEG bytes + EXIF for JPEG frames; YUV baked) and H.264 MP4 recording via MediaRecorder (spec named MediaCodec+MediaMuxer; MediaRecorder with a Surface input is the same encoder stack with less code; recorded as a deviation) — Tasks 5, 6.
- §5.6 discovery via `device_filter.xml`, MediaStore storage under Pictures/Movies — Tasks 3, 5.
- §6 privacy: no INTERNET permission enforced by the build gate; no network SDKs; pinned, OSV-checked versions — Tasks 3, 9.
- §12 protocol: unchanged `core` drivers; Android transport contract for -1 — Task 4.
- Backlog items settled: `ReplayDeviceSource` stream constructor (Task 2), `bulkRead` contract on Android (Task 4). Remaining backlog stays in `docs/phase1-notes.md`.
- Type consistency: `ScopeSession(devices, drivers, scope, sink, driverHintCheck, pollMillis, workDispatcher)`, `FrameSink.onFrame(frame, state)`/`onButtonSnapshot()`, `SessionState` fields, `AndroidUsbTransport(conn, maxConsecutiveTimeouts)`, `AndroidDeviceSource.onDetached(name)`/`pendingPermission`, `MediaStoreSaver.saveJpeg/saveBitmapJpeg/createVideo/finishVideo`, `SurfaceRecorder(context, fd, width, height)` are used with these exact shapes across Tasks 2–7.
