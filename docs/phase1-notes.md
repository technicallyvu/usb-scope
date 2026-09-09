# Phase 1 notes (Windows dev bench)

Accepted by Anthony on 2026-09-09 against his unit (i4season su4p-002, USB 2CE3:3828, firmware 5.0.13).

## Acceptance checklist
All items passed: live view, snapshot opens upright, 5 s recording plays, cable button saves a
snapshot, unplug mid-stream returns to "No device" and re-plug resumes, unplug during a recording
leaves a playable clip.

## Observations and decisions
- **Orientation.** Observed: picture upside down at rotation 0. Decision: `I4seasonYuvDriver.defaultRotation = 180`,
  mirror off. Lands in Phase 1 (this commit).
- **Resolution.** Observed: the sensor delivers 320x240 YUYV at ~11 fps; the vendor app upscales photos to
  1920x1440. Decision: save native resolution. Phase 2 may offer an optional upscale toggle.
- **Short frames.** Observed: ~1 in 6 frames arrives 8-24 rows short. Decision: pad with the last complete row and
  count as `partial` (visible in the Stats overlay). Phase 2 backlog: consider hiding the padded band.
- **Second personality.** The `useeplus` (MJPEG, two-interface) driver is implemented from public documentation but
  could not be exercised on this hardware. Phase 2 backlog: find a unit of that variant for a fixture.
- **Windows driver.** Zadig/WinUSB bind is a one-time manual step; libusb lists the device even unbound, so the app
  shows a driver hint when `open` fails. Later phase: attestation-signed WinUSB package via Windows Update.
- **Compose rendering.** `BufferedImage.toComposeImageBitmap()` rendered TYPE_3BYTE_BGR frames as solid green;
  frames are converted to TYPE_INT_ARGB first.

- **Naming.** Package and class names keep their protocol/OEM identifiers (`useeplus`, `i4season`) because they
  name the wire protocol and are the terms the public documentation uses. User-visible strings stay
  vendor-neutral: the drivers report "MJPEG endoscope (dual interface)" and "YUV endoscope (single interface)".

## Deferred minor findings from the branch reviews
Carried into the Phase 2 backlog; none block Phase 1 acceptance.

- Drop counter double-counts when garbage precedes a false header.
- Stray zero-frame mp4 when a recording start loses the race with a stop.
- `Controls` recomposes on every frame.
- `JFileChooser` blocks the render loop and is opened without an owner window.
- `--replay` with a missing file loops in `Failed` and re-parses the log on every open attempt.
- Parser `MAX_READ` duplicates `CHUNK_SIZE`.
- The fps fixture test measures wall clock; assert against the recorded packet timestamps instead.
- `toggleRecording`'s stop-branch comment is misleading.
- `ReplayDeviceSource` uses `java.nio.file` in core `main`; move it to a testing source set before Android.
- `FrameData.Yuyv422` allocates a fresh array per frame; consider a buffer ring on Android.
- Enable the Gradle configuration cache.
- Turn on `allWarningsAsErrors` for `core` once it is warning-clean.
