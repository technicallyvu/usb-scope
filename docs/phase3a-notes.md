# Phase 3a notes (UVC driver, temporal denoise, trust screen)

Completed 2026-09-10. Denoise and the trust screen are built and unit-tested; UVC is unit-tested only (no
hardware). Denoise and the trust screen await Anthony's look on the phone the next time it is connected.

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
  bar sliding across a block boundary. Each block therefore also counts the samples whose per-sample
  luma difference reaches `2 x motionThreshold` (48/255) and passes the current frame straight through
  once **two or more** of them do. Two, not one: a lone sample that far out is as likely to be a
  Gaussian noise outlier as motion, while anything that actually moved shows up at both the position
  it left and the one it arrived at. Noise of +-20 cannot reach 48, so immunity on a static scene is
  unchanged.

- **`FrameSink.onStreamStarted()`** (default no-op, called by `ScopeSession` on entering `Streaming`)
  is how a shell resets per-stream state it owns. The Android sink resets its ARGB denoiser there;
  without it, the first decoded JPEG frame of a new device would blend with the last frame of the
  previous one. The YUV denoiser lives in `ScopeSession` and resets itself.

- **The About screen is disabled while recording.** It replaces the live view, and with it the REC
  badge — the only on-screen sign that a clip is still being written.

- **Link taps are guarded.** A device with no browser throws `ActivityNotFoundException` from
  `startActivity`; the tap does nothing and the URL stays on screen as copyable text.

- **UVC devices trigger the open-app prompt.** `device_filter.xml` matches USB class 14 / subclass 1
  (VideoControl) alongside the two known endoscope VID:PIDs. No `protocol` attribute: UVC 1.0/1.1 use
  protocol 0 but 1.5 uses 1, so pinning it would quietly exclude every 1.5 camera.

- **`UvcBulkDriver.matches` looks only for a VideoControl interface** (class 0x0E, subclass 0x01;
  spec §13.1). The streaming interfaces are read from the configuration descriptor, which is the
  authority; the platform's interface summary is not always complete. A device that really has no
  streaming interface is therefore refused by `open`, with a message, rather than silently not
  matching.

- **The in-progress UVC frame is bounded.** The parser caps it at twice the larger of the negotiated
  (or declared) `dwMaxVideoFrameSize` and a 64 KiB floor — the floor is applied before the doubling,
  so the smallest cap that can result is 128 KiB. A stream that never sets EOF and never toggles the
  frame id would otherwise buffer until the process died. Past the cap the frame is counted as
  dropped once and its bytes discarded until the next EOF or FID toggle resynchronises the stream.
  Malformed payload headers are counted separately (`badHeaders`) and mark a frame already under way
  as damaged, so it is dropped at its end instead of emitted short.

## Known limits (pending hardware)

- **Only the first VideoStreaming interface is used, and VC/VS are not paired by function.** A
  dual-camera device (two VC/VS pairs behind one IAD) would always open the first stream, and a
  configuration whose VS interfaces belong to a *different* video function than the VC header we
  parsed would be mis-paired. Correct handling means walking the Interface Association Descriptors
  and offering the user a choice of stream; no such device is available to test against.

- **On Android only the first CONFIGURATION record is parsed.** `UsbDevice.getRawDescriptors()`
  returns the descriptors for the active configuration; a multi-configuration device whose video
  function lives in another configuration is not reachable, and switching configurations is not
  exposed by the Android USB API.

- **The denoiser's max-difference override is calibrated on uniform synthetic noise** (+-20 on a
  gradient). Real sensor noise is neither uniform nor uncorrelated, and gain rises in the dark, so
  the `2 x motionThreshold` / two-sample rule needs a look on the phone against a real scope before
  it can be called tuned rather than reasoned.

- **Isochronous streaming is unsupported.** `UvcBulkDriver` refuses an isochronous-only camera with
  an explanation. Isochronous transfers need packet-scheduling APIs that neither backend (libusb's
  async transfer API, Android's `UsbRequest` queueing) is wired up for yet; most webcams are
  isochronous, so this is the main reason the driver is billed as endoscope-oriented rather than
  universal.

- **No UVC hardware was available in this phase.** Everything above is verified against synthetic
  descriptors and payload streams only; the first real UVC camera is expected to shake out
  quirks (short packets, header lengths, devices that ignore probe/commit).

## Hardware verification still pending
- **UVC:** plug any UVC camera (a plain USB webcam counts) into the PC with WinUSB bound via Zadig, run
  `.\gradlew.bat :desktop:probe --args="--list"` and expect `<- uvc-bulk`; then `--seconds 5`. A camera that only
  offers isochronous endpoints will fail `open` with the "isochronous" message, which is the expected outcome for
  most webcams; a bulk-mode endoscope is the real target. On the phone: plug the camera in, accept the prompt, and
  check the Stats overlay. Record the first `.upkt` fixture as `core/src/test/resources/fixtures/uvc-<name>.upkt`.
- **Denoise:** on the phone, toggle Denoise on/off while pointing at a dim, textured surface; on should be visibly
  cleaner with no smear when the probe moves. The max-difference override was calibrated on uniform +-20 noise;
  if real sensor noise switches the filter off too often (grainy picture with denoise on), raise the override to
  3x the threshold.
- **Denoise cost:** with the debug timing overlay up (`Stats` on a debug build), read `avgProcess` with denoise
  **on** at 640x480 MJPEG. Record the number here. Above **25 ms/frame** the filter is eating the frame budget on
  that phone, so ship with denoise defaulted **off** (`SessionState.denoise = false`) and leave it as an opt-in
  toggle; at or below 25 ms leave the default on. 25 ms is the threshold because a 30 fps stream has a 33 ms
  budget and decode plus draw need the rest.
- **Trust screen:** open About & privacy on the phone; "Permissions requested: 0" and the build id should show.
  `source_url` is a placeholder until the GitHub repository exists.

## Phase 3 launch backlog (carried)
See `docs/phase2-notes.md` "Deferred to Phase 3": icon and theme, release signing and minify, Play assets and
privacy policy, rename the debug replay control, strings to resources, recording-start indicator, per-frame
recomposition, desktop adoption of `ScopeSession` and libusb async reads, bitmap ring, `RealUsbConnection`
request-pool tests, an isochronous UVC path (native libusb/libuvc), a stream picker for dual-camera devices.
