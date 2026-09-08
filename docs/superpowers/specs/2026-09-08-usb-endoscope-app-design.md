# USB Endoscope App — Design Spec

**Date:** 2026-09-08
**Author:** Anthony Vu (TechnicallyVu), with Claude
**Status:** Draft for review
**Working name:** `usb-endoscope-app` (repo/folder name only). The product name is Anthony's
decision and gets a USPTO trademark search before launch. It must not contain "Anesok",
"Sup-Anesok", "Usee", or any vendor mark.

## 1. Goal

A privacy-first, open-source replacement for the vendor apps bundled with cheap USB endoscopes,
publishable on Google Play and later the Microsoft Store. First supported device is Anthony's
own unit: a Geek szitman "supercamera" (USB `2CE3:3828`, sibling ID `0329:2022`) speaking the
proprietary **useeplus** protocol. The architecture is a pluggable driver layer so other
untrusted USB devices can be added later.

**Out of scope:** iOS. The device's control endpoints are Apple iAP accessory endpoints and
`com.useeplus.protocol` is an External Accessory protocol string; Apple only approves apps
declaring it if the accessory maker adds the app to their MFi plan. Revisit only if a vendor
partners with us.

## 2. Device facts (verified 2026-09-08 on Anthony's Windows 11 PC)

| Fact | Value |
|---|---|
| USB ID | `VID_2CE3 PID_3828 REV_0111`, bus-reported name "supercamera" |
| USB class | `FF` (vendor-specific), subclass `F0`, protocol `01` — **not UVC** |
| Windows state out of the box | Problem code 28 (no driver). Needs WinUSB bound (Zadig) |
| Sensor | 640×480 MJPEG, ~13–16 fps, native orientation sideways |
| Interfaces | 0 (iAP control: OUT `0x02`, IN `0x82`), 1 (video: bulk OUT `0x01`, bulk IN `0x81`) |
| Cable button | Visible as a bit in the per-packet flags byte |
| LED brightness | Not in the protocol (physical dial on the cable) |

Protocol details are documented in the open-source projects listed in §10 and reproduced in
§5.1. We reimplement from that documentation; we do not copy GPL code.

## 3. Platforms and phases

| Phase | Target | Deliverable | Done means |
|---|---|---|---|
| 1 | Windows 11 (dev bench) | Compose for Desktop viewer over libusb | Live video from Anthony's unit; save a photo and a clip; packet fixtures + parser tests committed |
| 2 | Android 10+ (min SDK 29, target SDK 36) | Native Kotlin/Compose app on Google Play | Same on Anthony's Galaxy Z Fold 7 (Android 16), app auto-launches on plug-in |
| 3 | Launch | MIT source on GitHub, F-Droid listing, paid Play build, privacy policy, store listing | Listed and installable |
| Later | Windows Store | Attestation-signed WinUSB driver via Windows Update + MSIX Store app | Plug in, it works, no Zadig |
| Later | Generic UVC driver | Second driver implementing the same interface | Any UVC endoscope streams |

Test devices: Anthony's Windows 11 workstation (JDK 17 present; Android SDK not yet installed)
and Samsung Galaxy Z Fold 7 on Android 16 (foldable: UI must handle cover and inner screens via
window size classes).

## 4. Architecture

Single Gradle workspace, Kotlin throughout, three modules:

```
usb-endoscope-app/
  core/      pure Kotlin/JVM — driver interface, useeplus driver, parser, reassembler,
             UsbTransport interface, fixtures + tests. No Android/desktop deps.
  desktop/   Compose for Desktop UI + usb4java (libusb) UsbTransport. Windows bench/product.
  android/   Compose UI + Android USB Host API UsbTransport, device_filter for auto-launch.
```

**Why one Kotlin core instead of a Python bench:** the bytes-to-frames code is written and
tested once, and the Windows bench validates the exact code the Android app ships.

### 4.1 Core interfaces

```kotlin
interface UsbTransport {
    fun claimInterface(iface: Int)
    fun releaseInterface(iface: Int)
    fun setAltSetting(iface: Int, alt: Int)
    fun clearHalt(endpoint: Int)
    fun bulkWrite(endpoint: Int, data: ByteArray, timeoutMs: Int): Int
    fun bulkRead(endpoint: Int, buffer: ByteArray, timeoutMs: Int): Int   // bytes read, 0 on timeout
    fun resetDevice()
}

data class UsbDeviceInfo(val vendorId: Int, val productId: Int, val usbClass: Int)

interface DeviceDriver {
    val id: String                                 // "useeplus", "uvc"
    fun matches(info: UsbDeviceInfo): Boolean
    fun open(transport: UsbTransport): FrameSource
}

interface FrameSource : AutoCloseable {
    val frames: Flow<Frame>
    val stats: StateFlow<StreamStats>              // fps, dropped, bytes
}

data class Frame(val jpeg: ByteArray, val timestampNanos: Long, val buttonPressed: Boolean,
                 val cameraNumber: Int, val sensorValue: Long)
```

UI code never touches USB. Adding a device = one new `DeviceDriver` in `core`.

## 5. Components

### 5.1 useeplus driver (`core`)

Open sequence (retry up to 3× with `resetDevice()` + 1.5 s wait between attempts):
1. Claim interfaces 0 and 1.
2. Drain iAP heartbeat: read `0x82` up to 30× with 100 ms timeout until a read times out.
3. Set interface 1 alt setting 1.
4. Clear halt on `0x01`.
5. Write `FF 55 FF 55 EE 10` to `0x02`.
6. Write `BB AA 05 00 00` to `0x01`.
7. Loop: `bulkRead(0x81, 1024 bytes, 5000 ms)` → parser → reassembler.
8. Discard the first 2 reassembled frames (device sends partials on start).

Close: cancel read loop, release interfaces 1 and 0.

Packet layout (each 1 KB bulk read):

| Offset | Size | Field |
|---|---|---|
| 0–1 | 2 | Magic `AA BB` (i.e. `0xBBAA` little-endian) |
| 2 | 1 | Channel ID — accept only 7 (frame head) or 11 (frame tail) |
| 3–4 | 2 | Length, little-endian, counts camera header + payload |
| 5 | 1 | Frame ID |
| 6 | 1 | Camera number |
| 7 | 1 | Flags (button bit) |
| 8–11 | 4 | G-sensor value, uint32 LE |
| 12… | length−7 | JPEG payload chunk |

### 5.2 Parser (`core`, pure function)
`parsePacket(ByteArray, len): Chunk?` validates magic and channel ID, extracts header fields and
the payload slice. Returns `null` for anything malformed. No state.

### 5.3 Reassembler (`core`, state machine)
Accumulates chunks while the frame ID is unchanged. On frame ID change: emit if the buffer starts
with `FF D8` and ends with `FF D9`, else drop and increment the drop counter. Exposes `StreamStats`.

### 5.4 View model (same shape in each shell)
Collects `frames`, decodes JPEG off the main thread, applies rotation (0/90/180/270) and mirror,
publishes a bitmap. Actions: snapshot, start/stop recording, rotate, mirror, toggle debug
overlay. Cable button = debounced rising edge on `buttonPressed` → same snapshot action.

### 5.5 Snapshot and recording
- **Snapshot** writes the original JPEG bytes untouched plus an EXIF orientation tag reflecting
  the chosen rotation/mirror. Filename `SCOPE_yyyyMMdd_HHmmss.jpg`.
- **Recording** encodes decoded frames to MP4 (H.264): Android via `MediaCodec` + `MediaMuxer`;
  desktop via JavaCV/FFmpeg. Rotation/mirror are baked into the encoded frames. Filename
  `SCOPE_yyyyMMdd_HHmmss.mp4`. Variable frame rate is handled by presentation timestamps.

### 5.6 Discovery and storage
- **Android:** `device_filter.xml` lists both USB IDs so the app launches on attach; request USB
  permission; wrap `UsbDeviceConnection` in `UsbTransport`. Files go to
  `Pictures/<AppName>/` and `Movies/<AppName>/` via MediaStore (survive uninstall, appear in gallery).
- **Desktop:** enumerate libusb devices, match drivers by ID; picker if more than one candidate.
  Files go to a user-chosen folder, default `Pictures/<AppName>` and `Videos/<AppName>`. Requires
  a one-time WinUSB bind via Zadig to the composite device (documented in README with screenshots).

## 6. Privacy and trust requirements (non-negotiable)

- Android manifest requests **no** `INTERNET` permission. Play's permission list is the proof.
- No telemetry, analytics, crash reporters, ads, or any SDK that opens a socket.
- Core and app source under **MIT** on GitHub. F-Droid listing. The paid Play build is the
  signed convenience; source stays free.
- Dependencies pinned to exact versions and checked against live CVE data before the first build
  and before each release.
- Attribution to prior reverse-engineering work in README and in-app About screen.

## 7. Error handling (user-visible behavior)

| Situation | Behavior |
|---|---|
| Unplug mid-stream | "Device disconnected" state, return to waiting screen; re-attach reconnects without restart |
| Open sequence fails | Reset + retry 3× silently, then error screen with Try Again |
| Recording in progress on disconnect | Muxer finalized so the file is playable |
| Corrupt/partial frame | Dropped, never shown, counted in debug overlay (fps, drops, bytes) |
| USB permission denied (Android) | Explanatory screen with re-request button |
| No driver bound (Windows) | Device present but unopenable → link to Zadig instructions |

## 8. Testing

- **Fixtures first:** on the Windows bench, record raw bulk-IN packet captures from Anthony's
  unit (a few seconds, including a button press) into `core/src/test/resources/fixtures/`.
- **Unit tests (`core`):** parser against fixtures and hand-built malformed packets; reassembler
  against fixtures (frame count, every emitted frame is valid JPEG, drop count), button
  edge detection, discard-first-two-frames rule.
- **Transport fakes:** a scripted `UsbTransport` replaying fixtures lets the full driver run
  headless in tests on both CI and Android instrumentation.
- **Manual (Anthony's call):** image quality, orientation defaults, button feel, UI, folding
  behavior on the Fold 7. Claude hands over builds; Anthony has final say on UX and naming.

## 9. Legal footing (not legal advice; one-hour counsel review before launch)

- Interoperability reverse engineering; no encryption or copy protection circumvented.
- Vendor trademarks only in descriptive "compatible with…" text, never in name/icon.
- Prior work licenses: hbens PoC (CC0), ProbeView (MIT), Linux drivers (GPL-3, reference only,
  no code copied).

## 10. Prior work referenced

- https://github.com/hbens/geek-szitman-supercamera (CC0), original protocol PoC
- https://github.com/echase/ProbeView (MIT), macOS libusb viewer; most complete protocol write-up
- https://github.com/MAkcanca/useeplus-linux-driver (GPL-3), Linux kernel driver
- https://github.com/ollyoid/useeplus-linux-v4l2-driver (GPL), V4L2 fork
- https://github.com/jmz3/EndoscopeCamera, C++/Python viewer, descriptors folder
- https://github.com/NinesLastGoal/supercamera_WIN10, Windows attempt (notes ~3% partial frames)

## 11. Launch checklist (Phase 3)

- Google Play developer account ($25). Register as an organization with a free D-U-N-S number
  under the TechnicallyVu DBA to skip the 20-tester/14-day closed-test requirement.
- Hosted privacy policy on technicallyvu.com ("collects nothing"), data safety form, screenshots
  (phone + foldable), feature graphic, neutral trademark-checked name.
- GitHub repo public with README, LICENSE (MIT), Zadig guide, attribution.
- F-Droid metadata submission.
- Windows Store track: Hardware Dev Center account, EV code-signing certificate (~$300/yr),
  attestation-signed WinUSB INF for both USB IDs published to Windows Update; MSIX via jpackage.
