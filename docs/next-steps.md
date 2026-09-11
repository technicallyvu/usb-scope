# Next steps (written 2026-09-10 for the session after Anthony's reset)

State: master 05bbf94 = v0.3.0 + cable-button double-press. All accepted on hardware except UVC (needs any webcam)
and the phone's look at denoise/About. Phone still runs the pre-fix button build; install master next time it is on USB.

## Look and feel (polish)
1. Real app icon + Material 3 theme with dynamic colour and dark mode; splash screen. (Icon is Anthony's call.)
2. Full-screen live view with controls as a translucent bottom sheet that auto-hides; tap to reveal; landscape and
   Fold-open layouts tuned; larger touch targets for gloved hands.
3. Status as icons, not text: connection dot, fps chip, REC timer with elapsed time, "Saved" toast with thumbnail.
4. Recording start indicator (encoder spin-up), snapshot shutter flash + thumbnail that opens the Gallery item.
5. Settings screen: denoise strength, double-press window, default rotation per device, output folder, haptics on/off.
6. Rename/relocate the debug "Replay fixture" control (dev menu behind a long-press on the version in About).
7. Onboarding card on first launch: plug in, Allow, the ~0.5 s button press rule, where files go.
8. Strings to resources, accessibility labels, localisation scaffolding.

## Features (in rough value order)
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

## Launch prep (Anthony)
Name + USPTO check, D-U-N-S, Play developer account, privacy policy page, GitHub repository (then replace source_url,
push tags v0.1.0-bench, v0.2.0-android, v0.3.0-features), one-hour legal review.

## Launch prep (Claude)
Release signing, minify rules, store listing text and screenshots, F-Droid metadata, About screen links, help text.
