# Phase 2 notes (Android)

Accepted by Anthony on 2026-09-10 on a Samsung Galaxy Z Fold 7 (Android 16) with his i4season su4p-002 unit.

## Acceptance
- Plugging the endoscope into the phone prompts to open USB Scope; the app opens and streams within seconds.
- Picture upright by default (rotation 180 for this unit). Snapshot and the cable button save photos to
  Gallery > Pictures > USB Scope; Record/Stop saves clips to Movies > USB Scope (verified: 2 MB and 0.6 MB clips).
- App info shows no requested permissions at all (`dumpsys package` lists an empty set).
- Anthony's verdict after the pipelined-read fix: "everything looks right".

## Findings and decisions
- **Stutter root cause (fixed 01edb85).** With synchronous `UsbDeviceConnection.bulkTransfer`, every frame arrived
  truncated (`framesPartial` +11/s at 10.9 fps): the device's small USB FIFO overflows in the gap between one
  read completing and the next being submitted, and Android's per-read overhead is far larger than libusb's.
  Fix: keep 8 `UsbRequest`s queued on the bulk IN endpoint (`RealUsbConnection`). Measured after: `partial 0`
  over 40+ s, 10.9 fps, ~9 ms processing per frame. The same mechanism explains the ~1-in-6 short frames on the
  desktop; a libusb async path there is a Phase 3 candidate.
- **Debug timing log.** Debug builds log one line per second (`ScopeViewModel: timing: ...`) with fps, max frame
  gap, processing cost, partial/dropped counts. Not present in release builds.
- **"Replay fixture" button confused the owner** (he expected it to play his own recording). It is a debug-only
  developer control that replays the bundled capture. Phase 3: rename to "Dev: play test capture" or move it
  behind a long-press, and make it obvious that recordings live in the Gallery.
- **Recording** uses `MediaRecorder` fed through a Surface (H.264 MP4), a deviation from the spec's
  MediaCodec+MediaMuxer wording; same encoder stack, less code.
- **compileSdk 37** is required by the pinned AndroidX artifacts (Compose BOM 2026.08.00); targetSdk stays 36.
- **Phone lock.** The screen locking with a PIN interrupted two remote verification rounds; keep the screen
  awake during bench sessions (the app keeps it on only while in the foreground).

## Deferred to Phase 3
- Real launcher icon and Material theme (currently a framework drawable and the platform theme).
- Release signing config, `isMinifyEnabled` with rules, Play listing assets, privacy policy URL.
- Rename/relocate the debug replay control (above). Localize strings into `res/values/strings.xml`.
- Recording start still constructs the encoder on IO but the UI has no "starting" indicator; add one.
- `Controls` recomposes per frame (pass narrower state). Snackbar via `Scaffold`/`SnackbarHost`.
- Desktop: adopt core `ScopeSession` (currently duplicated logic in the desktop view model) and libusb async reads.
- `openTransports` entries are removed only on detach; `releaseInterface` now clears the alt map but transports
  that close normally stay referenced until the next detach of the same device name.
- Consider a bitmap ring to cut the two-bitmaps-per-frame allocation on Android.
- In-app About screen with attribution to the prior reverse-engineering projects and the MIT notice (spec §6; dropped from the Phase 2 plan).
- Unit coverage for RealUsbConnection's request-pool bookkeeping behind a small seam; FrameBitmaps/MediaStoreSaver/SurfaceRecorder/ScopeViewModel are device-verified only.
