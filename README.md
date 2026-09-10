# USB Scope (working name)

Privacy-first, open-source viewer for cheap USB endoscopes that ship with untrusted vendor apps.
First supported device: USB 2CE3:3828 / 0329:2022 endoscopes. Verified on hardware in the `i4season-yuv`
personality (single interface, YUYV); the `useeplus` personality (dual interface, MJPEG) is implemented
from public protocol documentation and has not been exercised on a device here.

Phase 1 = Windows dev bench. See `docs/superpowers/specs/` for the design and `docs/windows-setup.md`
for the one-time WinUSB driver step.

## Quick start (Windows)
    .\gradlew.bat :core:test                       # protocol tests
    .\gradlew.bat :desktop:probe --args="--list"   # what libusb can see
    .\gradlew.bat :desktop:run                     # the viewer

## Prior work
Protocol documentation reimplemented from: hbens/geek-szitman-supercamera (CC0),
echase/ProbeView (MIT), MAkcanca/useeplus-linux-driver (GPL-3, reference only),
jmz3/EndoscopeCamera, NinesLastGoal/supercamera_WIN10.

## Hardware-free development
    .\gradlew.bat :desktop:run --args="--replay core/src/test/resources/fixtures/i4season-yuv-320x240.upkt"

## Supported devices
| Driver | USB IDs | Layout | Picture |
|---|---|---|---|
| `i4season-yuv` | 2CE3:3828, 0329:2022 | one interface FF/F0/01 | 320x240 YUYV, ~11 fps (verified on hardware) |
| `useeplus` | 2CE3:3828, 0329:2022 | interfaces FF/F0/00 + FF/F0/01 | 640x480 MJPEG (from public docs; untested here) |

## Status
Phase 1 (Windows dev bench) accepted 2026-09-09 against an i4season "su4p-002" (2CE3:3828, firmware 5.0.13).
Next: Phase 3, launch prep (see docs/phase2-notes.md).

## Android (Phase 2)
    .\gradlew.bat :android:assembleDebug            # builds; fails if any dependency adds the INTERNET permission
    C:\platform-tools\adb.exe install -r android\build\outputs\apk\debug\android-debug.apk

Plug the endoscope into the phone; Android offers to open USB Scope. Photos go to Pictures/USB Scope and clips to
Movies/USB Scope through MediaStore, so the app requests no permissions at all. Debug builds add a "Replay fixture"
control that streams the bundled capture without hardware, and log per-second frame timing under the tag
`ScopeViewModel`. Requires the Android SDK (compileSdk 37, targetSdk 36, minSdk 29); see `docs/dependencies.md`.

Phase 2 accepted 2026-09-10 on a Galaxy Z Fold 7. Notes: `docs/phase2-notes.md`.
