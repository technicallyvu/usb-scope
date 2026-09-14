# Phase 3b notes (Android polish: live view, settings, tips)

Completed 2026-09-13, Android only — `desktop` is untouched this phase and `core` gained nothing but
settings hooks. Everything below was exercised on an API 36 emulator in replay mode; no endoscope and
no phone were available, so every layout and legibility judgement still wants Anthony's look on the
Fold 7. Version bumped to 0.3.1 (`versionCode` 3). No new permission, and the "no INTERNET" build gate
still passes on debug and release.

## What shipped

- **Live-view chrome.** `ScopeScreen` is a full-screen black `Box`: the picture fits and letterboxes,
  a status chip (connection dot, state text, fps when stats are on) sits top-start, a REC badge with
  an elapsed timer top-end, the stats overlay under the chip, and a bottom column with the snapshot
  toast, the permission button, the snackbar host and the controls sheet. A shutter flash draws over
  the lot.
- **Controls sheet.** A translucent surface of eight 56 dp icon buttons in two fixed rows —
  Snapshot / Record / Rotate / Mirror, then Denoise / Stats / Settings / About — that fades out after
  4 s without interaction while streaming. On a wide (Fold-open) window it becomes a scrolling
  vertical rail at the end edge. Five glyphs (camera, record, stop, mirror, denoise) are hand-drawn
  with `Canvas`; material-icons-core supplies the other three.
- **Settings screen and persistence.** A Material 3 `Scaffold` with five groups (Image, Cable button,
  Feedback, Devices, About) over `settings/AppSettings` — SharedPreferences plus a
  `StateFlow<Settings>`, clamped on both load and write. Denoise and strength, the double-press
  window, haptics, keep-screen-on, the stats overlay and the tips flag all survive a restart, and
  they reach the running session through one `SettingsApplier` over a narrow `SettingsTarget`, so an
  on-screen toggle and the settings screen cannot disagree.
- **Per-device orientation memory.** Rotating or mirroring the live view writes
  `defaults[driverId]`; the Devices group lists what is remembered and can forget any of it.
- **First-launch tips card.** Four lines over the live view (plug in and tap Allow; hold the cable
  button about half a second; once for a photo, twice for a clip; where the files land) until
  "Got it".
- **Developer sheet** (debug builds only): replay fixture start/stop, a "Log frame timing" switch
  naming its logcat tag, and "Copy diagnostics". Reached by a long-press on the About screen's
  version line. The old "Replay fixture" button left the controls sheet with it.
- **Strings.** Every user-visible string is in `res/values/strings.xml`, with a `driverNameRes(id)`
  mapping so no driver id or protocol label — one of them vendor-derived — can reach the UI. A script
  over `android/src` reports zero unused string resources.
- **Material 3 theme.** Dynamic colour on API 31+, a teal-seeded static scheme below it, light and
  dark, edge-to-edge with a black live-view surface.
- **Placeholder launcher icon.** An adaptive icon: a lens ring, a highlight and a probe line on dark
  teal. Superseded after 3b by the real all-vector icon; see `.superpowers/sdd/icon-report.md`.

## Decisions

- **Auto-hide is 4 s of no interaction, and three things are exempt from it.** A single
  `LaunchedEffect(streaming, interactions, showTips)` drives one `chromeVisible` flag. The **REC
  badge** is outside the fade entirely — it is the only on-screen sign that a clip is still being
  written, and a clip being written must never be invisible. An **undismissed tips card** holds the
  timer off completely, so advice cannot vanish before it has been read; "Got it" restores normal
  behaviour for good. And the timer only runs **while Streaming** — with no device there is nothing
  to get out of the way of. What counts as an interaction is deliberately wide: a tap anywhere on the
  root box, any control in the sheet, the "Allow USB access" button, and *a snapshot from any
  source*. That last one is why a cable-button snapshot brings the whole chrome and its "Saved" toast
  back for four seconds instead of flashing once against an empty screen.

- **Snapshot and recording feedback are separated by what they can promise.** A snapshot flashes the
  shutter *immediately* (before the IO hop, because the shutter is feedback for the press, not for
  the write) and only then, on success, shows a ≤ 96 px thumbnail with the file name for three
  seconds. The thumbnail is held behind a generation ticket, so a second snapshot gets its own full
  three seconds rather than inheriting the remainder of the first. Recording cannot promise anything
  until the encoder is up, so the Record control shows a spinner and is disabled while
  `recordingStarting`, and the REC timer starts from the nanosecond the session actually accepted the
  recording, not from the tap.

- **The double-press slider ships 0.5–3.0 s, not the plan's 1.0–2.5 s.** The session already accepts
  500 ms–5 s, and both ends of the narrower range are reachable in practice: a fast double-tap lands
  under 1.0 s, and a deliberate or gloved one can run past 2.5 s. A slider whose ends you can hit
  without meaning to is a slider with the wrong ends. Documented on `WINDOW_MIN_MS` in
  `ui/SettingsScreen.kt`.

- **Per-driver overrides are sticky, and "Forget" really forgets.** An override reapplies on *every*
  open of its driver, not only when the driver changes — otherwise an orientation you set once would
  survive the replug it was set for and then quietly revert. A plain driver default still applies on
  a driver change only, and a null flag inside an override keeps the driver default on a driver
  change and the current value on a same-driver reconnect, so the "keep the user's rotation across a
  reconnect" rule is intact where no override exists. `clearDefaultOverride` therefore has to drop
  the map entry *and* clear `lastDriverId` when it matches: without that second half the next open
  takes the same-driver branch and keeps whatever rotation state held, which is not what a button
  labelled "Forget" implies. It does not snap the *currently streaming* picture back — the session
  only consults overrides at open.

- **About's back returns wherever About was opened from.** Navigation is still a single flat
  `Screen` enum — a general back stack for three destinations would be more machinery than the case
  is worth — but the one pair that needs it carries its origin instead: `navigate()` records
  `UiState.returnTo` when it moves to `Trust`, and the About screen's back arrow and back gesture
  both route to that. Opened from the controls sheet it returns to Live; opened from Settings' "About
  this app" row it returns to Settings, which is what a one-way door out of a settings list reads as
  a bug for.

- **Any cable-button event brings the live view forward.** The button keeps working behind Settings
  and About — the session never stops — so before this the reverse of the `enabled = !recording`
  guards was wide open: a double-press from the settings screen started writing a clip with nothing
  on screen saying so, and a single press saved a photo with no flash, no toast and no haptic. The
  rule is one pure function, `screenAfterButtonEvent(current)`, pinned by a unit test: whatever is
  showing, a button event lands on Live, because every sign that the press happened — REC badge,
  shutter flash, "Saved" toast, snackbar — lives there. Navigating also drops any pending message,
  so a snackbar raised while the user was away is not replayed, stale, on their return.

  The feedback state this needs (the haptic tick already answered, the flash animation and its tick,
  the auto-hide interaction counter) is hoisted **above** `ScopeScreen`'s destination switch. Held
  inside the live subtree it would be re-seeded by the very navigation the press performs, and the
  press that brought the user back would arrive with its buzz and its flash already spent; seeded
  once on first entry instead, it would replay the last snapshot's flash on every return from
  Settings. So `ShutterFlash` is now a pure drawing composable over an alpha from
  `rememberShutterFlashAlpha`, which tracks the tick it has *answered* rather than the one it
  entered on.

- **"Permissions requested: 0" now excludes app-private signature permissions.**
  `PackageInfo.requestedPermissions` carries the merged manifest, which includes AndroidX's injected
  `com.technicallyvu.scope.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` — defined by the app, granted
  only to the app, invisible to everything else — so the trust screen was reading "1" and calling the
  app's central privacy claim into question over a permission that grants nothing. `countSystemPermissions`
  excludes anything prefixed with the app's own package id plus a dot: a prefix rule rather than a
  hard-coded AndroidX name that a library upgrade could rename, and `com.technicallyvu.scopeX.FOO`
  stays counted because it is a different app. A footnote saying so appears only when something was
  actually excluded, so it never disclaims a thing that did not happen.

- **The MIT licence is reflowed before it is drawn.** `reflowParagraphs` collapses single newlines
  inside a paragraph to spaces and keeps blank lines as paragraph breaks; the text also lost
  `FontFamily.Monospace` for `bodySmall`. The licence lives in `core` as a hard-wrapped 80-column
  block, and a fixed-pitch 80-column block re-wraps a second time on a phone — it is prose, not code.

- **The About screen's "Support this project" block is gone on Android (2026-09-14).** The Ko-fi row
  under the source link was removed for Google Play's Payments policy (`docs/play-policy-audit.md`
  fix 1), along with the `about_support_title`, `about_support_body` and `donate_url` strings. The
  desktop About dialog keeps it — the Windows build is not distributed through Play.

- **The developer sheet is gated by a null, not by an `if`.** `TrustScreen` takes
  `devControls: DevControls?`, and only attaches the long-press when it is non-null *and*
  `BuildConfig.DEBUG`; release builds pass null, so no gesture exists and `DevSheet` is never called.
  A gesture that silently does nothing is a gesture someone will eventually find.

- **`ConnStatus` exists for Compose, not for the domain.** `ConnectionState` is a sealed class from
  `core`, which Compose has no stability metadata for, so a `StatusChip` taking it directly could
  never skip. The chip and the controls sheet take stable primitives plus a `ConnStatus` enum
  (`ConnectionState.toConnStatus()`), which keeps nine buttons and five `Canvas` glyphs off the
  frame-rate recomposition path. `Settings` and `DriverDefault` are `@Immutable` for the same reason
  — a `Map` field would otherwise make the whole settings subtree unskippable.

## Emulator verification

AVD `scope36` (API 36, `google_apis;x86_64`, `pixel_7`), created once with `avdmanager` and run
headless: `emulator.exe -avd scope36 -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect`,
waiting on `adb shell getprop sys.boot_completed`, then `adb install -r` and `adb shell am start`.
There is no USB on an emulator, so the app was driven in **replay mode** off the bundled fixture,
with `adb shell input` for taps and a 1200 ms `input swipe` for the version-line long-press. Two
widths were checked: the stock 411 × 914 dp and `wm density 480` → 360 × 800 dp.

States checked: fresh install with the tips card; idle with no device; the settings screen; About
(permission count 0 with the footnote, and the reflowed licence); the developer sheet, including
scrolled to its Close button; streaming with the controls up; streaming after the 4 s auto-hide;
the stats overlay on (and confirmed absent with no device, where it used to read a permanent
all-zero line); recording with the REC timer; and the whole live view in dark mode
(`adb shell cmd uimode night yes`).

Frames live in the git-ignored `.superpowers/re/frames/final/` (`01-fresh-tips` through
`11-stream-360dp`), with the fix-pass set beside them in `.superpowers/re/frames/`. They are not in
the repository, so they are described rather than linked. Note for whoever runs the next pass:
`adb exec-out screencap -p > file.png` through PowerShell corrupts the bytes — use
`adb shell screencap -p /sdcard/x.png` and `adb pull`.

Four defects came out of the pass and were fixed in it: the controls sheet's `FlowRow` stranded About
on a second line (now two fixed rows of four), the "Plug in your endoscope" prompt drew under the tips
card (now laid out against the measured bottom-chrome height), the stats overlay showed an all-zero
line with no stream, and the About screen was near-black text on black in dark mode (`TrustScreen`
drew into a bare `Column`, which paints no background and sets no content colour; it is a
`Scaffold` + `TopAppBar` now, like Settings).

Tests: 44 Android unit tests green (`AppSettingsTest` 5, `SettingsWiringTest` 6, `DriverNamesTest` 5,
`RecBadgeTest` 6, `AndroidUsbTransportTest` 10, `CountSystemPermissionsTest` 6, `ReflowParagraphsTest`
6), plus the core suite with the new session-hook and override tests.

## Known limits and open nits (carried forward)

- **The tips card is bottom-aligned over the live view, not centred on the "no device" screen.** The
  spec and plan both said centred; it stayed over the live view because the two tips that matter most
  — the cable button and where files land — are only actionable once a picture is up. Anthony's call
  on the phone.
- **`DevSheet`'s switch row is two accessibility nodes.** TalkBack announces the label and the
  control separately. `SettingsScreen`'s rows were converted to a single `toggleable` node with
  `Role.Switch`; the developer sheet's was left, since it is debug-only — but if it is ever fixed,
  both should already match.
- **The replay-fixture asset *path* string is compiled into `src/main`,** so it lands in the release
  DEX even though the asset itself is `src/debug` only; the unreachable `DevSheet` class bytes ship
  for the same reason (`isMinifyEnabled = false`). An R8 pass or a debug source set clears both.
- **A snackbar raised while Settings or About is showing is still not *shown* there.** The host
  lives on the live screen. It is no longer replayed stale on the way back — `navigate()` drops the
  message, and a cable-button event returns to Live before raising one — but a message from some
  future off-screen source would simply be lost. Where a snackbar lives across destinations wants a
  real decision, not a patch. The permission button and the snackbar host also sit outside the
  auto-hide fade.
- **The wide-layout rail has not been seen on a real tablet or fold.** Both emulator widths were
  portrait phones, so the Fold-open rail is verified by construction and by the scroll modifier only.
- **Smaller nits:** a dismissed tips card leaves an 8 dp gap in the bottom column's `spacedBy`; the
  version line has a ripple and a "button" role for a tap that does nothing (debug only).
  (`keepScreenOn` no longer recomputes per frame — it combines the preference with a
  `distinctUntilChanged` "is streaming" boolean — and `serializeDefaults` now writes only what
  `parseDefaults` will accept, with a round-trip test.)

- **Landscape is capped and scrolled, not redesigned.** On a short window (a landscape phone at
  ~360 dp tall, still Medium-width and so still on the compact sheet) the bottom column no longer
  overflows upward over the status chip: it is capped at 60 % of the window height and scrolls in
  reverse, so the controls sheet is what stays put and the tips card is what you scroll to. Verified
  at 2400 × 1080 (`.superpowers/re/frames/final/12-landscape.png`). A landscape layout that actually
  uses the width — the rail, say, at Medium as well as Expanded — is a design question for the phone.
- **Verified on the Fold 7 (2026-09-13, v0.3.1 debug build).** Installed over adb (USB, then
  adb-over-Wi-Fi because the phone has a single USB-C port and the scope needs it): zero requested
  system permissions; live view at 10.9 fps with 0 partial frames; the saved orientation for the
  YUV scope (rotation 180, no mirror) was picked up from the per-device defaults; the controls
  auto-hid and came back on a tap; a cable-button hold produced the shutter flash, the "Saved" toast
  and a JPEG in the Gallery folder; a cable-button double-press while the Settings screen was open
  returned to the live view with the REC badge and started a clip, and a second double-press stopped
  it (the F1 fix from the branch review, which the emulator could not exercise). No crash in logcat.
  Still pending on hardware: dark-mode and dynamic-colour judgement is Anthony's; the items in
  `docs/phase3a-notes.md` under "Hardware verification still pending" (UVC, denoise tuning, denoise
  cost) are unchanged.

## Anthony's decisions pending

- ~~**The product name.**~~ Decided 2026-09-13: **USB Scope**, the name already used for `app_name`,
  the media folder (`translatable="false"`, because a translated album name would orphan saved media),
  the tips card and the README. Checked the same day: no Google Play app carries that exact title, the
  term appears on hardware listings only as a description (so it is a weak mark, which is acceptable for
  an open-source app), usbscope.com is taken, usbscope.app had no DNS record. "Scope Cam" was rejected
  because ScopeCam is already a camera product name (Futudent, RunCam). Suggested store title:
  "USB Scope: Endoscope Camera".
- ~~**The launcher icon.**~~ Decided 2026-09-13: Anthony's own render, see `art/tools/make_icon.py`.
- **Where the stats toggle belongs.** It is currently both a button in the controls sheet and a
  switch in Settings, backed by the same preference. That is one of eight sheet buttons spent on a
  debug-flavoured overlay; if it moves to Settings only, the sheet drops to seven and the two-row
  layout wants a rethink.
</content>
</invoke>

- **The icon — decided 2026-09-13.** See `art/tools/make_icon.py`; source `art/icon-source.png`.
