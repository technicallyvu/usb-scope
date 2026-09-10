# Phase 3a notes (UVC driver, temporal denoise, trust screen)

Work in progress. Task 6 completes this file; what follows is the decision record so far.

## Decisions

- **Snapshots save what the user sees.** With denoise on, the picture on screen is the filtered one and
  exists only as decoded pixels, so the snapshot is that picture re-encoded as JPEG at quality 92, with
  the rotation and mirror already baked into it and no EXIF orientation tag (Android:
  `saveBitmapJpeg(lastImage)`; desktop: `SnapshotWriter.write(BufferedImage, dir)`). With denoise off
  the old behaviour stands: a JPEG frame keeps its original sensor bytes and gets an EXIF orientation
  tag for the view transform (no re-encode, no quality loss), and a YUYV frame is encoded as before.
  The rule matters because the alternative — always keeping the original bytes — hands the user a photo
  visibly noisier than the live view they took it from.

- **Denoiser motion metric: block mean plus a max-difference override.** The per-block (4x4) motion
  value is the difference of the block's *mean* luma, which suppresses per-pixel sensor noise, but it
  is blind to a small high-contrast feature moving inside one block (the mean does not change) and to a
  bar sliding across a block boundary. Each block therefore also tracks its largest per-sample luma
  difference and passes the current frame straight through when that reaches `2 x motionThreshold`
  (48/255). Noise of +-20 cannot reach 48, so immunity on a static scene is unchanged.

- **`FrameSink.onStreamStarted()`** (default no-op, called by `ScopeSession` on entering `Streaming`)
  is how a shell resets per-stream state it owns. The Android sink resets its ARGB denoiser there;
  without it, the first decoded JPEG frame of a new device would blend with the last frame of the
  previous one. The YUV denoiser lives in `ScopeSession` and resets itself.

- **The About screen is disabled while recording.** It replaces the live view, and with it the REC
  badge — the only on-screen sign that a clip is still being written.

- **Link taps are guarded.** A device with no browser throws `ActivityNotFoundException` from
  `startActivity`; the tap does nothing and the URL stays on screen as copyable text.

- **UVC devices trigger the open-app prompt.** `device_filter.xml` matches USB class 14 / subclass 1
  (VideoControl) alongside the two known endoscope VID:PIDs.
