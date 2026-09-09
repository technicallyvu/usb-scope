# USB Scope (working name)

Privacy-first, open-source viewer for cheap USB endoscopes that ship with untrusted vendor apps.
First supported device: Geek szitman "supercamera" (USB 2CE3:3828 / 0329:2022, useeplus protocol).

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
Next: Phase 2, native Android app (see docs/superpowers/specs/).
