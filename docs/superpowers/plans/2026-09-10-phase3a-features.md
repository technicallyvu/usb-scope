# Phase 3a: UVC bulk driver, temporal denoise, trust screen — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Three features on the accepted Phase 2 app: a generic UVC (bulk-transport) driver in `core`, an adaptive temporal denoiser shared by both shells, and an About/trust screen (Android) plus About dialog (desktop).

**Architecture:** All new logic lives in `core` (pure Kotlin, fixture-tested): `image/TemporalDenoiser`, `TrustInfo`, and the `uvc` package (descriptor parser, probe/commit negotiation, payload reassembly, `UvcBulkDriver`). `UsbTransport` gains a default `readConfigDescriptor()`; Android overrides it with `rawDescriptors`. `ScopeSession` applies the denoiser to YUV frames; each shell applies the ARGB variant after decoding JPEG frames and adds a toggle. The trust screen is a second Compose screen on Android and a dialog on desktop.

**Tech Stack:** unchanged (Kotlin 2.4.10, Gradle 9.7.1, Compose 1.12 / BOM 2026.08.00, AGP 9.4.0). No new dependencies.

## Global Constraints

- Spec §13 (`docs/superpowers/specs/2026-09-08-usb-endoscope-app-design.md`). Backlog context in `docs/phase2-notes.md`.
- No hardware is available for UVC in this phase: the driver must be complete and unit-tested against synthetic descriptors and payload streams; hardware verification is recorded as pending in `docs/phase3a-notes.md`.
- No new dependencies; no network; vendor-neutral strings; `core` stays pure Kotlin. Tests JUnit 5; TDD with RED/GREEN evidence. JUnit Jupiter's `assertNotNull(x)` is void in Kotlin: use `requireNotNull(x)`.
- Android: `$env:ANDROID_HOME = "C:\Android\sdk"`; the phone is not connected; do not attempt device steps.
- Run from `C:\Projects\usb-endoscope-app` in PowerShell with `.\gradlew.bat ...`. Commits end with `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.
- UVC constants (spec 1.1): CS_INTERFACE 0x24; VC subclass 0x01 (VC_HEADER subtype 0x01 with `bcdUVC` at bytes 3–4); VS subclass 0x02; VS subtypes: INPUT_HEADER 0x01 (`bNumFormats` byte 3, `wTotalLength` 4–5, `bEndpointAddress` byte 6), FORMAT_UNCOMPRESSED 0x04 (`bFormatIndex` 3, `bNumFrameDescriptors` 4, `guidFormat` 5–20, `bBitsPerPixel` 21, `bDefaultFrameIndex` 22), FRAME_UNCOMPRESSED 0x05 and FRAME_MJPEG 0x07 (`bFrameIndex` 3, `bmCapabilities` 4, `wWidth` 5–6, `wHeight` 7–8, `dwMinBitRate` 9–12, `dwMaxBitRate` 13–16, `dwMaxVideoFrameBufferSize` 17–20, `dwDefaultFrameInterval` 21–24, `bFrameIntervalType` 25), FORMAT_MJPEG 0x06 (`bFormatIndex` 3, `bNumFrameDescriptors` 4, `bmFlags` 5, `bDefaultFrameIndex` 6). YUY2 GUID = `32 59 55 59 00 00 10 00 80 00 00 AA 00 38 9B 71`. Requests: SET_CUR 0x01 / GET_CUR 0x81, bmRequestType 0x21 (OUT) / 0xA1 (IN), wValue VS_PROBE_CONTROL 0x0100 / VS_COMMIT_CONTROL 0x0200, wIndex = VS interface number. Probe struct length: 26 (bcdUVC < 0x0110), 34 (< 0x0150), 48 otherwise. Payload header: byte 0 `bHeaderLength`, byte 1 `bmHeaderInfo` (bit0 FID, bit1 EOF, bit6 ERR).

---

## File Structure

```
core/src/main/kotlin/com/technicallyvu/scope/core/
  image/TemporalDenoiser.kt        YUYV + ARGB adaptive recursive denoise
  TrustInfo.kt                     license, attributions, statements (pure data)
  session/ScopeSession.kt          + denoise flag/denoiser for YUV frames, SessionState.denoise
  usb/UsbTransport.kt              + readConfigDescriptor() default via GET_DESCRIPTOR
  uvc/UvcDescriptors.kt            parser -> UvcDevice model
  uvc/UvcProbe.kt                  probe/commit struct encode/decode
  uvc/UvcPayloadParser.kt          payload headers -> frames
  uvc/UvcBulkDriver.kt             driver + frame source
  driver/DriverRegistry.kt         + UvcBulkDriver (last)
core/src/test/kotlin/com/technicallyvu/scope/core/
  image/TemporalDenoiserTest.kt, TrustInfoTest.kt, session/ScopeSessionTest.kt (+1 test)
  uvc/UvcTestDescriptors.kt, uvc/UvcDescriptorsTest.kt, uvc/UvcProbeTest.kt, uvc/UvcPayloadParserTest.kt, uvc/UvcBulkDriverTest.kt
android/build.gradle.kts           + BuildConfig GIT_SHA
android/src/main/kotlin/com/technicallyvu/scope/
  usb/UsbConnection.kt, usb/RealUsbConnection.kt, usb/AndroidUsbTransport.kt   + rawDescriptors / readConfigDescriptor override
  media/FrameBitmaps.kt            + ARGB denoise on JPEG frames
  ui/ScopeViewModel.kt             + toggleDenoise, showTrust
  ui/ScopeScreen.kt                + Denoise and About buttons
  ui/TrustScreen.kt                About & privacy screen
android/src/main/res/values/strings.xml   + source_url
desktop/src/main/kotlin/com/technicallyvu/scope/desktop/
  ui/ScopeViewModel.kt             + denoise
  ui/ScopeScreen.kt                + Denoise, About buttons; ui/AboutDialog.kt
  media/ImageTransforms.kt         + decode with denoiser
docs/phase3a-notes.md
```

---

### Task 1: TemporalDenoiser (core)

**Files:**
- Create: `core/src/main/kotlin/com/technicallyvu/scope/core/image/TemporalDenoiser.kt`
- Test: `core/src/test/kotlin/com/technicallyvu/scope/core/image/TemporalDenoiserTest.kt`

**Interfaces:** `TemporalDenoiser(strength: Float = 0.6f, motionThreshold: Int = 24, blockSize: Int = 4)`, `var strength`, `fun apply(frame: FrameData.Yuyv422): FrameData.Yuyv422` (returns a new frame; input untouched), `fun applyArgb(pixels: IntArray, width: Int, height: Int)` (in place), `fun reset()`.

- [ ] **Step 1: Failing tests**

```kotlin
package com.technicallyvu.scope.core.image

import com.technicallyvu.scope.core.driver.FrameData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.random.Random

class TemporalDenoiserTest {
    private val w = 32
    private val h = 16

    /** Gradient luma, neutral chroma. */
    private fun base(): ByteArray {
        val b = ByteArray(w * h * 2)
        for (y in 0 until h) for (x in 0 until w) {
            val i = (y * w + x) * 2
            b[i] = (40 + x * 4).toByte(); b[i + 1] = 128.toByte()
        }
        return b
    }

    private fun noisy(rnd: Random, amp: Int): FrameData.Yuyv422 {
        val b = base()
        for (i in b.indices step 2) b[i] = ((b[i].toInt() and 0xFF) + rnd.nextInt(-amp, amp + 1)).coerceIn(0, 255).toByte()
        return FrameData.Yuyv422(w, h, b)
    }

    private fun lumaError(f: FrameData.Yuyv422, ref: ByteArray): Double {
        var sum = 0L; var n = 0
        for (i in f.bytes.indices step 2) { sum += abs((f.bytes[i].toInt() and 0xFF) - (ref[i].toInt() and 0xFF)); n++ }
        return sum.toDouble() / n
    }

    @Test
    fun `static noisy scene converges toward the clean image`() {
        val d = TemporalDenoiser(strength = 0.6f)
        val rnd = Random(7)
        var out: FrameData.Yuyv422? = null
        var rawErr = 0.0
        repeat(8) { val f = noisy(rnd, 20); rawErr = lumaError(f, base()); out = d.apply(f) }
        val err = lumaError(requireNotNull(out), base())
        assertTrue(err < rawErr * 0.6, "denoised error $err vs raw $rawErr")
        assertEquals(w * h * 2, out!!.bytes.size)
    }

    @Test
    fun `first frame passes through unchanged and input is never modified`() {
        val d = TemporalDenoiser()
        val f = noisy(Random(1), 20)
        val copy = f.bytes.copyOf()
        val out = d.apply(f)
        assertTrue(out.bytes.contentEquals(copy))
        d.apply(noisy(Random(2), 20))
        assertTrue(f.bytes.contentEquals(copy))
    }

    @Test
    fun `a moving bright bar is not smeared`() {
        val d = TemporalDenoiser(strength = 0.9f)
        var last: FrameData.Yuyv422? = null
        for (k in 0 until 6) {
            val b = ByteArray(w * h * 2) { i -> if (i % 2 == 0) 20 else 128.toByte() }
            val x0 = k * 4
            for (y in 0 until h) for (x in x0 until x0 + 4) b[(y * w + x) * 2] = 235.toByte()
            last = d.apply(FrameData.Yuyv422(w, h, b))
        }
        val out = requireNotNull(last).bytes
        val barX = 5 * 4 + 1
        val oldX = 4 * 4 + 1
        val atBar = out[(3 * w + barX) * 2].toInt() and 0xFF
        val atOld = out[(3 * w + oldX) * 2].toInt() and 0xFF
        assertTrue(atBar > 220, "bar position should be bright, got $atBar")
        assertTrue(atOld < 40, "old bar position should have no ghost, got $atOld")
    }

    @Test
    fun `size change resets history`() {
        val d = TemporalDenoiser()
        d.apply(noisy(Random(3), 20))
        val small = FrameData.Yuyv422(8, 4, ByteArray(64) { 100 })
        val out = d.apply(small)
        assertTrue(out.bytes.contentEquals(small.bytes))
    }

    @Test
    fun `argb variant reduces noise and keeps alpha opaque`() {
        val d = TemporalDenoiser(strength = 0.6f)
        val rnd = Random(9)
        val ref = IntArray(w * h) { i -> 0xFF000000.toInt() or ((60 + (i % w) * 3) * 0x010101) }
        var px = IntArray(0)
        var rawErr = 0.0
        repeat(8) {
            px = IntArray(w * h) { i ->
                val v = ((ref[i] and 0xFF) + rnd.nextInt(-20, 21)).coerceIn(0, 255)
                0xFF000000.toInt() or (v * 0x010101)
            }
            rawErr = px.indices.sumOf { abs((px[it] and 0xFF) - (ref[it] and 0xFF)) }.toDouble() / px.size
            d.applyArgb(px, w, h)
        }
        val err = px.indices.sumOf { abs((px[it] and 0xFF) - (ref[it] and 0xFF)) }.toDouble() / px.size
        assertTrue(err < rawErr * 0.6, "argb denoised error $err vs raw $rawErr")
        assertTrue(px.all { (it ushr 24) == 0xFF })
    }
}
```

- [ ] **Step 2: Run to verify failure** — `.\gradlew.bat :core:test --tests "com.technicallyvu.scope.core.image.TemporalDenoiserTest"` → compilation FAILS.

- [ ] **Step 3: Implement**

```kotlin
package com.technicallyvu.scope.core.image

import com.technicallyvu.scope.core.driver.FrameData
import kotlin.math.abs

/**
 * Adaptive recursive temporal filter. Each frame is blended into the previous *output*; the weight
 * of the current frame is 1.0 where a 4x4 block moved (mean luma change >= [motionThreshold]) and
 * `1 - 0.75*strength` where it is static, linear in between. Not thread-safe: one instance per stream.
 */
class TemporalDenoiser(strength: Float = 0.6f, val motionThreshold: Int = 24, val blockSize: Int = 4) {
    var strength: Float = strength.coerceIn(0f, 1f)
        set(value) { field = value.coerceIn(0f, 1f) }

    private var prevYuyv: ByteArray? = null
    private var prevArgb: IntArray? = null
    private var prevW = 0
    private var prevH = 0

    fun reset() { prevYuyv = null; prevArgb = null; prevW = 0; prevH = 0 }

    private fun staticWeight256(): Int = ((1f - 0.75f * strength) * 256f).toInt().coerceIn(32, 256)

    /** Returns a new, denoised frame. The input is never modified. */
    fun apply(frame: FrameData.Yuyv422): FrameData.Yuyv422 {
        val w = frame.width; val h = frame.height; val src = frame.bytes
        val prev = prevYuyv
        if (prev == null || prevW != w || prevH != h || prev.size != src.size) {
            prevYuyv = src.copyOf(); prevArgb = null; prevW = w; prevH = h
            return FrameData.Yuyv422(w, h, src.copyOf())
        }
        val out = ByteArray(src.size)
        val aStatic = staticWeight256()
        val rowBytes = w * 2
        var by = 0
        while (by < h) {
            val bh = minOf(blockSize, h - by)
            var bx = 0
            while (bx < w) {
                val bw = minOf(blockSize, w - bx)
                var sum = 0; var count = 0
                for (y in by until by + bh) {
                    var i = y * rowBytes + bx * 2
                    for (x in 0 until bw) { sum += abs((src[i].toInt() and 0xFF) - (prev[i].toInt() and 0xFF)); i += 2; count++ }
                }
                val motion = sum / count
                val a = if (motion >= motionThreshold) 256 else aStatic + (256 - aStatic) * motion / motionThreshold
                for (y in by until by + bh) {
                    val start = y * rowBytes + bx * 2
                    val end = start + bw * 2
                    for (i in start until end) {
                        val c = src[i].toInt() and 0xFF
                        val p = prev[i].toInt() and 0xFF
                        out[i] = (p + (((c - p) * a + 128) shr 8)).coerceIn(0, 255).toByte()
                    }
                }
                bx += blockSize
            }
            by += blockSize
        }
        prevYuyv = out
        return FrameData.Yuyv422(w, h, out)
    }

    /** In-place ARGB_8888 variant (for decoded JPEG frames). */
    fun applyArgb(pixels: IntArray, width: Int, height: Int) {
        val prev = prevArgb
        if (prev == null || prevW != width || prevH != height || prev.size != pixels.size) {
            prevArgb = pixels.copyOf(); prevYuyv = null; prevW = width; prevH = height
            return
        }
        val aStatic = staticWeight256()
        var by = 0
        while (by < height) {
            val bh = minOf(blockSize, height - by)
            var bx = 0
            while (bx < width) {
                val bw = minOf(blockSize, width - bx)
                var sum = 0; var count = 0
                for (y in by until by + bh) for (x in bx until bx + bw) {
                    val i = y * width + x
                    sum += abs(luma(pixels[i]) - luma(prev[i])); count++
                }
                val motion = sum / count
                val a = if (motion >= motionThreshold) 256 else aStatic + (256 - aStatic) * motion / motionThreshold
                for (y in by until by + bh) for (x in bx until bx + bw) {
                    val i = y * width + x
                    val c = pixels[i]; val p = prev[i]
                    val r = blend((c shr 16) and 0xFF, (p shr 16) and 0xFF, a)
                    val g = blend((c shr 8) and 0xFF, (p shr 8) and 0xFF, a)
                    val b = blend(c and 0xFF, p and 0xFF, a)
                    val v = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    pixels[i] = v; prev[i] = v
                }
                bx += blockSize
            }
            by += blockSize
        }
    }

    private fun blend(c: Int, p: Int, a: Int): Int = (p + (((c - p) * a + 128) shr 8)).coerceIn(0, 255)
    private fun luma(argb: Int): Int = (((argb shr 16) and 0xFF) * 77 + ((argb shr 8) and 0xFF) * 150 + (argb and 0xFF) * 29) shr 8
}
```

- [ ] **Step 4: GREEN** — 5 tests pass. **Step 5: Commit** `feat(core): adaptive temporal denoiser for YUYV and ARGB frames`.

---

### Task 2: Denoise wiring — session, Android, desktop

**Files:**
- Modify: `core/.../session/ScopeSession.kt` (+ `denoise` in `SessionState`, `setDenoise(Boolean)`, apply to YUV frames, reset per session); `core/src/test/.../session/ScopeSessionTest.kt` (+1 test)
- Modify: `android/.../media/FrameBitmaps.kt`, `android/.../ui/ScopeViewModel.kt`, `android/.../ui/ScopeScreen.kt`
- Modify: `desktop/.../media/ImageTransforms.kt`, `desktop/.../ui/ScopeViewModel.kt`, `desktop/.../ui/ScopeScreen.kt`

- [ ] **Step 1: Session (TDD).** Add to `ScopeSessionTest`:
```kotlin
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
```
Implement: `SessionState.denoise: Boolean = true`; `@Volatile private var denoiser: TemporalDenoiser? = TemporalDenoiser()`; `fun setDenoise(enabled: Boolean)` sets state and swaps the denoiser (new instance when enabling; null when disabling); in `session()` before streaming call `denoiser?.reset()`; in `onFrame`, `val delivered = denoiser?.let { d -> (frame.data as? FrameData.Yuyv422)?.let { Frame(d.apply(it), frame.timestampNanos, frame.buttonPressed, frame.cameraNumber, frame.sensorValue) } } ?: frame` and pass `delivered` to the sink. Run `:core:test` green.

- [ ] **Step 2: Android.** `FrameBitmaps.toBitmap(data: FrameData, argbDenoiser: TemporalDenoiser?)`: for JPEG, decode with `BitmapFactory.Options().apply { inMutable = true; inPreferredConfig = ARGB_8888 }`, and when `argbDenoiser != null`: `getPixels` into a reused IntArray, `applyArgb`, `setPixels`. `ScopeViewModel`: per-session `argbDenoiser` inside `SessionSink` (created with the sink; used only for `FrameData.Jpeg`), `fun toggleDenoise() = session.setDenoise(!session.state.value.denoise)`; the sink reads `state.denoise` to decide whether to pass the ARGB denoiser. `ScopeScreen` Controls: `OutlinedButton(onClick = vm::toggleDenoise) { Text(if (s.denoise) "Denoise: on" else "Denoise: off") }`.
- [ ] **Step 3: Desktop.** `ImageTransforms.decode(data, yuvDenoiser: TemporalDenoiser?, argbDenoiser: TemporalDenoiser?)`: YUYV → `yuvDenoiser?.apply(data) ?: data` then convert; JPEG → decode, and if `argbDenoiser != null`: `getRGB` into an IntArray, `applyArgb`, `setRGB` (keep TYPE_3BYTE_BGR output by drawing/setRGB into it). Desktop `ScopeViewModel`: `UiState.denoise: Boolean = true`, `fun toggleDenoise()`, per-session `TemporalDenoiser` pair reset in `session()`; `ScopeScreen`: Denoise toggle button next to Mirror. Desktop tests: `ImageTransformsTest` gets one case that `decode` with a denoiser on two identical noisy JPEG-decoded frames returns an image (smoke), and `ScopeViewModelTest` asserts `toggleDenoise()` flips the flag.
- [ ] **Step 4:** `.\gradlew.bat test :android:testDebugUnitTest :android:assembleDebug` green. Commit `feat: temporal denoise wired into session and both shells with a toggle`.

---

### Task 3: Trust info and screens

**Files:**
- Create: `core/src/main/kotlin/com/technicallyvu/scope/core/TrustInfo.kt`; test `core/src/test/.../TrustInfoTest.kt`
- Modify: `android/build.gradle.kts` (BuildConfig `GIT_SHA`), `android/src/main/res/values/strings.xml` (`source_url`)
- Create: `android/src/main/kotlin/com/technicallyvu/scope/ui/TrustScreen.kt`; modify `ScopeViewModel.kt` (`showTrust` in `UiState`, `toggleTrust()`), `ScopeScreen.kt` (About button; render `TrustScreen` when `showTrust`)
- Create: `desktop/src/main/kotlin/com/technicallyvu/scope/desktop/ui/AboutDialog.kt`; modify desktop `ScopeScreen.kt` (About button + dialog state), `ScopeViewModel.kt` if state is kept there (a local `remember { mutableStateOf(false) }` in the screen is fine).

- [ ] **Step 1: Core (TDD).** Test: `TrustInfoTest` asserts `TrustInfo.attributions.size >= 6`, every attribution has non-blank `name`, `url` starting with `https://`, `license`; `TrustInfo.licenseText` contains "MIT License" and "Anthony Vu"; `TrustInfo.noNetworkStatement` mentions "INTERNET"; `TrustInfo.permissionsNotRequested` contains "android.permission.INTERNET", "android.permission.CAMERA", "android.permission.RECORD_AUDIO", "android.permission.ACCESS_FINE_LOCATION", "android.permission.READ_CONTACTS".
Implement `TrustInfo` as an `object` with `data class Attribution(val name: String, val url: String, val license: String, val note: String)`; attributions: hbens/geek-szitman-supercamera (CC0, original protocol PoC), echase/ProbeView (MIT, macOS viewer and protocol write-up), MAkcanca/useeplus-linux-driver (GPL-3, reference only), ollyoid/useeplus-linux-v4l2-driver (GPL, reference only), jmz3/EndoscopeCamera (reference), NinesLastGoal/supercamera_WIN10 (reference); plus `protocolNote`: "The single-interface YUV protocol was recovered by observing the device and reading the publicly distributed vendor app; no vendor code is included." `licenseText` = full MIT text with `Copyright (c) 2026 Anthony Vu (TechnicallyVu)`. `noNetworkStatement` = "This app does not request the INTERNET permission, so it cannot send anything anywhere. It has no analytics, no crash reporting, and no accounts."
- [ ] **Step 2: Android.** In `android/build.gradle.kts` `defaultConfig`: `buildConfigField("String", "GIT_SHA", "\"${gitShortSha()}\"")` where `fun gitShortSha(): String = runCatching { providers.exec { commandLine("git", "rev-parse", "--short", "HEAD") }.standardOutput.asText.get().trim() }.getOrDefault("unknown")`. `strings.xml`: `<string name="source_url">https://technicallyvu.com/usb-scope</string>` (placeholder until the repository exists; noted in phase3a-notes). `TrustScreen(onBack: () -> Unit)`: reads `PackageManager.getPackageInfo(packageName, GET_PERMISSIONS).requestedPermissions` (size shown as "Permissions requested: N", expected 0), shows version name/code and `BuildConfig.GIT_SHA`, the no-network statement, the "does not have" list from `TrustInfo.permissionsNotRequested` (strip the `android.permission.` prefix for display), the source URL as a clickable `Text` that opens `Intent(Intent.ACTION_VIEW, Uri.parse(url))`, then attributions and the license in a `LazyColumn`. `ScopeScreen`: when `ui.showTrust` render `TrustScreen(onBack = vm::toggleTrust)` instead of the main content; add an `OutlinedButton("About")` to Controls. Handle system back: `BackHandler(enabled = ui.showTrust) { vm.toggleTrust() }` (activity-compose).
- [ ] **Step 3: Desktop.** `AboutDialog(onClose)` using Compose Desktop `DialogWindow` (or `Dialog`) listing version ("0.2.0 dev bench"), the no-network statement, attributions, license (scrollable). Button "About" in Controls; state local to the screen.
- [ ] **Step 4:** `.\gradlew.bat test :android:testDebugUnitTest :android:assembleDebug` green (gate line). Commit `feat: trust/about screen (Android) and About dialog (desktop) with attribution`.

---

### Task 4: UVC descriptor parser (core)

**Files:**
- Create: `core/src/main/kotlin/com/technicallyvu/scope/core/uvc/UvcDescriptors.kt`
- Test helper: `core/src/test/kotlin/com/technicallyvu/scope/core/uvc/UvcTestDescriptors.kt`; test `UvcDescriptorsTest.kt`

**Interfaces:**
```kotlin
enum class UvcFormatKind { MJPEG, YUY2, OTHER }
data class UvcFrame(val index: Int, val width: Int, val height: Int, val defaultInterval100ns: Int, val maxFrameBufferSize: Int)
data class UvcFormat(val index: Int, val kind: UvcFormatKind, val defaultFrameIndex: Int, val frames: List<UvcFrame>)
data class UvcEndpoint(val address: Int, val isBulk: Boolean, val maxPacketSize: Int)
data class UvcAltSetting(val alt: Int, val endpoints: List<UvcEndpoint>)
data class UvcStreamingInterface(val number: Int, val inputEndpoint: Int, val formats: List<UvcFormat>, val altSettings: List<UvcAltSetting>)
data class UvcDevice(val bcdUvc: Int, val controlInterface: Int, val streaming: List<UvcStreamingInterface>)
object UvcDescriptors { fun parse(config: ByteArray): UvcDevice?  /* null when no VC interface */ }
```

- [ ] **Step 1: Test helper** `UvcTestDescriptors.build(bulk: Boolean = true, bcdUvc: Int = 0x0110, includeYuy2: Boolean = true): ByteArray` assembling: config header (9 bytes, `wTotalLength` patched at the end), IAD (8 bytes, type 0x0B), VC interface (9 bytes: number 0, class 0x0E, sub 0x01), VC_HEADER (13 bytes: `0D 24 01 <bcdUvc LE> <wTotalLength LE = 13> <dwClockFrequency 4> 01 01`), VS interface alt 0 (9 bytes: number 1, class 0x0E, sub 0x02, numEndpoints = if bulk 1 else 0), VS_INPUT_HEADER (14 bytes: `0E 24 01 <bNumFormats> <wTotalLength LE> <bEndpointAddress 0x81> 00 00 00 00 01 00 00`), FORMAT_MJPEG (11 bytes: `0B 24 06 01 01 01 01 00 00 00 00`), FRAME_MJPEG for 640x480 (30 bytes: `1E 24 07 01 00 <80 02> <E0 01> <dwMinBitRate> <dwMaxBitRate> <dwMaxVideoFrameBufferSize = 614400> <dwDefaultFrameInterval = 333333> 01 <dwFrameInterval 333333>`), optionally FORMAT_UNCOMPRESSED (27 bytes: `1B 24 04 02 01 <YUY2 GUID 16> 10 01 00 00 00 00`) + FRAME_UNCOMPRESSED 320x240 (30 bytes, buffer 153600), then endpoint descriptors: if bulk, `07 05 81 02 <00 02> 00` in alt 0; if iso, an alt 1 interface (9 bytes, alt 1, numEndpoints 1) with endpoint `07 05 81 05 <00 04> 01`. Patch the input header's `wTotalLength` (input header + formats + frames) and the config `wTotalLength`. Provide `fun withoutVc(): ByteArray` (a plain HID-only config) for the negative test.
- [ ] **Step 2: Failing tests**: parse(bulk) → `bcdUvc == 0x0110`, `controlInterface == 0`, one streaming interface number 1, `inputEndpoint == 0x81`, formats [MJPEG idx1 default 1 with frame 640x480 interval 333333 buffer 614400, YUY2 idx2 with 320x240]; alt 0 has bulk endpoint 0x81 maxPacket 512. parse(iso) → alt 1 endpoint `isBulk == false`, alt 0 no endpoints. parse(withoutVc) → null. Truncated descriptor (cut mid-frame) → parse does not throw and returns what it could.
- [ ] **Step 3: Implement** a linear walk over `bLength`/`bDescriptorType` records tracking the current interface (number, alt, class, subclass); on CS_INTERFACE subtypes under a VS interface build formats/frames (frames attach to the last format; unknown format GUIDs → OTHER); endpoints attach to the current alt setting; stop cleanly on truncation (`if (i + len > size) break`). Alt settings are collected per streaming interface number (list in order encountered).
- [ ] **Step 4: GREEN; commit** `feat(core): UVC configuration descriptor parser`.

---

### Task 5: UVC probe/commit, payload reassembly, bulk driver, registry, transport descriptor read

**Files:**
- Modify: `core/.../usb/UsbTransport.kt` (+ `readConfigDescriptor()` default)
- Create: `core/.../uvc/UvcProbe.kt`, `UvcPayloadParser.kt`, `UvcBulkDriver.kt`; modify `driver/DriverRegistry.kt`
- Tests: `UvcProbeTest.kt`, `UvcPayloadParserTest.kt`, `UvcBulkDriverTest.kt`
- Android: `usb/UsbConnection.kt` (+ `fun rawDescriptors(): ByteArray?`), `RealUsbConnection.kt` (`connection.rawDescriptors`), `AndroidUsbTransport.kt` (override `readConfigDescriptor()`: locate the first descriptor of type 2 in `rawDescriptors` and return `wTotalLength` bytes from it; fall back to `super`). Test in `AndroidUsbTransportTest`: fake returns raw bytes = device descriptor (18 bytes) + config → override returns the config slice.

**Interfaces:**
```kotlin
// UsbTransport
/** Full configuration descriptor (all interfaces, endpoints, class-specific blocks). Default: two GET_DESCRIPTOR control requests. */
fun readConfigDescriptor(): ByteArray { ... }

data class UvcProbeControl(val formatIndex: Int, val frameIndex: Int, val frameInterval100ns: Int, val maxVideoFrameSize: Int, val maxPayloadTransferSize: Int)
object UvcProbe {
    fun lengthFor(bcdUvc: Int): Int = if (bcdUvc < 0x0110) 26 else if (bcdUvc < 0x0150) 34 else 48
    fun encode(p: UvcProbeControl, length: Int): ByteArray   // bmHint=0x0001, fields LE, rest zero
    fun decode(bytes: ByteArray, n: Int): UvcProbeControl     // reads the same offsets
    /** SET_CUR probe -> GET_CUR probe -> SET_CUR commit. Returns the negotiated control. */
    fun negotiate(t: UsbTransport, vsInterface: Int, bcdUvc: Int, request: UvcProbeControl): UvcProbeControl
}
class UvcPayloadParser(private val kind: UvcFormatKind, private val width: Int, private val height: Int, private val maxPayloadSize: Int) {
    var framesEmitted/framesDropped/bytesReceived
    fun accept(chunk: ByteArray, n: Int, chunkCapacity: Int, nowNanos: Long): List<Frame>
}
class UvcBulkDriver(clock, sleep, ioDispatcher, packetSink) : DeviceDriver { id = "uvc-bulk"; displayName = "UVC camera"; defaultRotation = 0 }
```
Probe struct offsets: `bmHint` 0–1, `bFormatIndex` 2, `bFrameIndex` 3, `dwFrameInterval` 4–7, `dwMaxVideoFrameSize` 18–21, `dwMaxPayloadTransferSize` 22–25.

- [ ] **Step 1: Failing tests**
  - `UvcProbeTest`: `lengthFor` table; `encode`/`decode` round trip at 26/34/48; `negotiate` with a `ReplayTransport` whose `controlResponses[0xA1 to 0x81]` returns an encoded struct with `maxPayloadTransferSize = 16384`, asserting `calls == ["ctrl 21 01 0100 0001 34", "ctrl A1 81 0100 0001 34", "ctrl 21 01 0200 0001 34"]` and the returned control echoes the device's values.
  - `UvcPayloadParserTest` (MJPEG, 4x4): payloads built as `header(len=12, flags) + data`; two payloads for frame A (FID 0, second has EOF) then frame B (FID 1): expect 2 frames with the right JPEG bytes (use `TestPackets.fakeJpeg()`-style SOI..EOI content); ERR bit → frame dropped and counted; a FID toggle without EOF still emits the previous frame; continuation: `maxPayloadSize = 4096`, chunk capacity 1024, one payload of 3000 bytes delivered as reads of 1024, 1024, 952 → the header is parsed once and the frame carries 2988 data bytes; YUY2 kind with a wrong size → dropped; correct size → `FrameData.Yuyv422`.
  - `UvcBulkDriverTest`: `matches` true for `UsbDeviceInfo(..., interfaces = [UsbInterfaceInfo(0, 0x0E, 0x01, 0), UsbInterfaceInfo(1, 0x0E, 0x02, 0)])`, false without the VC interface; `open` against a `ReplayTransport` with `controlResponses[0x80 to 0x06] = UvcTestDescriptors.build(bulk = true)` and the GET_CUR reply → `calls` contains, in order, the two GET_DESCRIPTOR reads, `claim 0`, `claim 1`, the three probe/commit requests, and `alt 1 0`; streaming: video packets (endpoint 0x81, so `ReplayTransport(..., videoEndpoint = 0x81)`) = MJPEG payloads → frames are `FrameData.Jpeg`; `open` against `build(bulk = false)` throws `UsbException` whose message contains "isochronous"; `close` releases 1 then 0. `DriverRegistryTest`: a UVC layout resolves to `uvc-bulk`; the existing i4season/useeplus cases still hold (UVC is last in the list).
- [ ] **Step 2: Implement.**
  - `UsbTransport.readConfigDescriptor()` default: `val head = ByteArray(9); val n = controlTransfer(0x80, 0x06, 0x0200, 0, head, 1000); if (n < 4) throw UsbException("config descriptor header short"); val total = le16(head, 2); val full = ByteArray(total); val m = controlTransfer(0x80, 0x06, 0x0200, 0, full, 1000); return full.copyOf(m)`.
  - `UvcPayloadParser.accept`: state `remainingInPayload` (0 = expecting a header), `frameBuf` (ByteArrayOutputStream), `currentFid`, `frameErrored`. For a new payload: `hdrLen = chunk[0]`, `flags = chunk[1]`; if `hdrLen < 2 || hdrLen > n` → count drop, resync (treat as a payload without data). `fid = flags and 1`; if `fid != currentFid && frameBuf.size() > 0` → finish frame (emit unless errored), then start new; append `chunk[hdrLen until n]`; `remainingInPayload = maxPayloadSize - n`; if `n < chunkCapacity` → `remainingInPayload = 0` (short packet ends the payload); if `flags and 0x40 != 0` → `frameErrored = true`; if `flags and 0x02 != 0` → finish frame, `remainingInPayload = 0`. For a continuation chunk: append all `n` bytes, `remainingInPayload -= n`, and if `n < chunkCapacity` or `remainingInPayload <= 0` → payload ends. `finish`: MJPEG → require SOI/EOI (`FrameReassembler.isJpeg`) else drop; YUY2 → require size `width*height*2` else drop; emit `Frame(data, nowNanos, buttonPressed = false, cameraNumber = 0, sensorValue = 0)`.
  - `UvcBulkDriver.open`: `handshake` = `val cfg = t.readConfigDescriptor(); val dev = UvcDescriptors.parse(cfg) ?: throw UsbException("not a UVC device")`; pick the first streaming interface; find `(alt, endpoint)` with `isBulk` (prefer alt 0); if none → `throw UsbException("This camera streams over isochronous endpoints, which is not supported yet")`; choose format (MJPEG else YUY2 else throw "no MJPEG/YUY2 format"), frame (default index if present else first); `t.claimInterface(dev.controlInterface); t.claimInterface(vs.number)`; `val negotiated = UvcProbe.negotiate(t, vs.number, dev.bcdUvc, UvcProbeControl(format.index, frame.index, frame.defaultInterval100ns, frame.maxFrameBufferSize, 0))`; `t.setAltSetting(vs.number, alt)` (always call it, even for alt 0); return `UvcFrameSource(t, endpoint, format.kind, frame.width, frame.height, maxPayload = negotiated.maxPayloadTransferSize.takeIf { it > 0 } ?: 16384, ...)` reading with `CHUNK_SIZE = 16384`, `READ_TIMEOUT_MS = 500`, the same `ioLock`/`withContext` pattern and `close()` (set alt 0 best-effort, release VS then VC, close transport). Retry: 3 attempts, 1500 ms between, no reset. `packetSink` supported. Stats via the parser + `FpsMeter`.
  - Registry: `listOf(I4seasonYuvDriver(), UseeplusDriver(), UvcBulkDriver())`.
  - Android: `UsbConnection.rawDescriptors(): ByteArray?`; `RealUsbConnection` returns `connection.rawDescriptors`; `AndroidUsbTransport.readConfigDescriptor()` override as described.
- [ ] **Step 3:** `.\gradlew.bat test :android:testDebugUnitTest :android:assembleDebug` green. Commit `feat(core): UVC bulk driver with probe/commit negotiation and payload reassembly`.

---

### Task 6: Notes, README, review, merge

- `docs/phase3a-notes.md`: what shipped; UVC hardware verification pending (steps to run when a UVC device is available: `probe --list` should show `<- uvc-bulk`; expected behaviours); denoise defaults; trust screen source URL placeholder; Phase 3 launch backlog carried forward.
- README: "Supported devices" table gains `uvc-bulk` (untested on hardware); feature list mentions denoise and the About screen.
- Full run: `.\gradlew.bat test :android:testDebugUnitTest :android:assembleDebug` green with the gate line.
- Commit, tag `v0.3.0-features`, whole-branch review, fix pass, merge.

---

## Self-review

- §13.1: Tasks 4–5 (parser, probe, payload, driver, transport descriptor read, registry, iso message). §13.2: Tasks 1–2. §13.3: Task 3. Docs: Task 6.
- Type consistency: `TemporalDenoiser.apply/applyArgb/reset`, `SessionState.denoise`, `ScopeSession.setDenoise`, `UvcDescriptors.parse → UvcDevice`, `UvcProbe.negotiate(t, vsInterface, bcdUvc, request)`, `UvcPayloadParser(kind, width, height, maxPayloadSize).accept(chunk, n, chunkCapacity, nowNanos)`, `UsbTransport.readConfigDescriptor()` used with these shapes throughout.
