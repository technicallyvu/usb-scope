# Next steps (updated 2026-09-13 after Phase 3b)

State: branch `phase3b-polish` at v0.3.1 (Android polish), awaiting Anthony's whole-branch review and merge.
Master is v0.3.0 + cable-button double-press. All accepted on hardware except UVC (needs any webcam), the phone's
look at denoise/About, and the whole of Phase 3b — none of which has run on a device. Install the 3b build the
next time the Fold 7 is on USB.

## Look and feel (polish)
1. ~~Material 3 theme with dynamic colour and dark mode~~ — done in 3b. ~~Real app icon~~ — done 2026-09-13: Anthony's design (phone + USB-C cable + lit probe on a
   teal-to-blue gradient), lifted from his render by `art/tools/make_icon.py` into bitmap foreground and
   monochrome layers over a vector gradient background. Still open: a splash screen.
2. ~~Full-screen live view with controls as a translucent sheet that auto-hides; tap to reveal; wide/Fold-open
   rail; 56 dp touch targets.~~ Done in 3b — the wide rail has not been seen on a real fold.
3. ~~Status as icons: connection dot, fps chip, REC timer, "Saved" toast with thumbnail.~~ Done in 3b.
4. ~~Recording start indicator (encoder spin-up), snapshot shutter flash + thumbnail.~~ Done in 3b. Still open:
   making the thumbnail tappable so it opens the Gallery item.
5. ~~Settings screen: denoise strength, double-press window, default rotation per device, haptics on/off,
   keep screen on, stats overlay.~~ Done in 3b. Still open: a configurable **output folder**.
6. ~~Rename/relocate the debug "Replay fixture" control.~~ Done in 3b — it lives in a developer sheet behind a
   long-press on the version line in About, debug builds only.
7. ~~Onboarding card on first launch.~~ Done in 3b (four lines, dismissable, "Show tips again" in Settings).
8. ~~Strings to resources, accessibility labels.~~ Done in 3b. Still open: **localisation** itself (no
   translations exist, and `core`'s trust text — licence, attributions, no-network statement — is not in
   `strings.xml` at all), and the `DevSheet` switch row's TalkBack pattern.

## Carried forward from the Phase 3b reviews
- Tips card is bottom-aligned over the live view rather than centred on the "no device" screen — Anthony's call.
- A snackbar raised while Settings or About is showing is never consumed; decide where a snackbar lives across
  destinations. The permission button and snackbar host also sit outside the auto-hide fade.
- R8/minify pass, or a debug source set, so the replay-fixture asset path and the `DevSheet` class bytes stop
  shipping in the release DEX.
- Verify the wide (Fold-open) rail on real hardware.
- Cosmetic: 8 dp gap left by a dismissed tips card; ripple and "button" role on the version line (debug only);
  `keepScreenOn` recomputes per frame before `stateIn`; `serializeDefaults` does not validate what
  `parseDefaults` accepts.
- Decide whether the stats toggle belongs in the controls sheet, in Settings only, or both.

## Features (in rough value order)
0. Resolution question settled — see `image-quality.md` (native 320x240, never upscaled, optional
   sharpening); the PC-bench probe for hidden info requests remains optional.
1. Honest 2x upscale on save (labelled), and digital zoom with pan on the live view.
2. Freeze frame; brightness/contrast/gamma sliders; black-and-white mode.
3. Auto-orientation from the phone's sensors (the scope has no gyro); volume buttons as shutter.
4. Narrated recording (phone mic) — note: adds RECORD_AUDIO permission, must be opt-in and explained on the trust screen.
5. Jobs/folders, timestamp overlay, annotations (arrows, notes), size estimate from a known probe diameter.
6. PDF inspection report export (photos + notes). Candidate headline for a paid Pro tier.
7. Picture-in-picture; burst and timed capture; time-lapse.
8. Isochronous UVC via native libusb/libuvc (most webcams); stream picker for dual-camera devices.
9. Desktop: adopt core ScopeSession; libusb async reads (fixes the 1-in-6 short frames on the bench); Linux support.
10. Reproducible builds + a verification page, so the Play APK can be matched to the GitHub source.

## Hardware verification still pending
Phase 3a's list (UVC on any webcam, denoise tuning, denoise cost against the 25 ms/frame threshold, the trust
screen) plus the whole of Phase 3b on the Fold 7: live-view chrome and auto-hide, the controls sheet at real
density, settings persistence across a restart, per-device orientation memory across a replug, the tips card,
dark mode against a real picture, and the launcher icon on a real launcher (only the emulator's circle mask has
been seen; the Fold 7 uses a different mask, and Android 13 themed icons are still unverified).

## Launch prep (Anthony)
Name + USPTO check ("USB Scope" is a placeholder and appears in the media folder name, so changing it after
release orphans saved media), D-U-N-S, Play developer account, privacy policy page, GitHub repository (then
replace source_url, push tags v0.1.0-bench, v0.2.0-android, v0.3.0-features, v0.3.1-polish), one-hour legal
review. The launcher icon is done; the 512x512 Play listing icon is rendered at `art/icon-play-512.png` from
`art/icon-play-512.svg`, so swap it only if the artwork changes.

## Launch prep (Claude)
Release signing, minify rules, store listing text and screenshots, F-Droid metadata, About screen links, help text.
</content>
