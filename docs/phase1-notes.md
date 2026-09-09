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

## Deferred minor findings from task reviews
See `.superpowers/sdd/progress.md` (local) for the per-task list; notable ones: drop counter double-counts when
garbage precedes a false header; `WindowsDeviceCheck` has no read timeout; `--seconds` parsing is not validated;
stray zero-frame mp4 stub if a recording is discarded during encoder start-up.
