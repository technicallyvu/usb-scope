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
