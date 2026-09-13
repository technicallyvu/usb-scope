# Phase 3b: Android polish — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the Android app feel finished: Material 3 theme, full-screen live view with auto-hiding controls, glanceable status, snapshot/recording feedback, a settings screen backed by SharedPreferences, first-launch tips, developer controls hidden behind About, all strings in resources. Verified on an emulator in replay mode; Anthony judges on the phone later.

**Architecture:** Core gains settings hooks on `ScopeSession` (denoise strength, double-press window, per-driver default rotation/mirror). Android gains `settings/AppSettings` (SharedPreferences + `StateFlow`), `ui/theme/ScopeTheme`, a restructured `ScopeScreen` (full-screen `Box`, `StatusChip`, `RecBadge`, `ControlsSheet`, `TipsCard`, `SnapshotToast`, `ShutterFlash`), `ui/SettingsScreen`, a `DevSheet` reachable from `TrustScreen`. `ScopeViewModel` applies settings and exposes new UI state (`recordingStarting`, `lastSnapshotThumb`, `flashTick`, `screen` navigation). Desktop unchanged.

**Tech Stack:** unchanged plus `androidx.compose.material:material-icons-core` (version from the existing Compose BOM 2026.08.00; OSV-check before adding). No other new dependencies. SharedPreferences for persistence.

## Global Constraints

- Spec §14. Existing constraints hold: no INTERNET/no permissions (the gate stays), vendor-neutral strings, `core` pure Kotlin, JUnit 5 with TDD evidence for core, commits end with `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`. Run from `C:\Projects\usb-endoscope-app`, `$env:ANDROID_HOME = "C:\Android\sdk"`.
- The phone is NOT available. Verification is `.\gradlew.bat test :android:testDebugUnitTest :android:assembleDebug` plus, in Task 9, the emulator (`C:\Android\sdk\emulator\emulator.exe`, AVD `scope36` once created) running the debug build in replay mode.
- Keep the existing behaviours intact: session loop, recording lock discipline, replay, button handling, About content, `WindowSizeClass` wide layout.
- Every user-visible string in `android/src/main/res/values/strings.xml` (`stringResource(R.string.x)`); every icon button has a `contentDescription`; minimum touch target 48 dp.
- Do not change the desktop module in this phase.

---

## File Structure

```
core/src/main/kotlin/com/technicallyvu/scope/core/session/ScopeSession.kt   + setDenoise(enabled, strength), doublePressWindowNanos, defaultOverrides
core/src/test/kotlin/com/technicallyvu/scope/core/session/ScopeSessionTest.kt (+3 tests)
gradle/libs.versions.toml                       + androidx-compose-material-icons-core
android/build.gradle.kts                        + icons dependency
android/src/main/res/values/strings.xml         all strings
android/src/main/res/values/themes.xml          Theme.Scope (windowBackground black, no action bar)
android/src/main/res/mipmap-anydpi-v26/ic_launcher.xml, ic_launcher_round.xml
android/src/main/res/drawable/ic_launcher_foreground.xml, values/ic_launcher_background.xml   placeholder icon
android/src/main/AndroidManifest.xml            icon + theme references
android/src/main/kotlin/com/technicallyvu/scope/
  settings/AppSettings.kt                       SharedPreferences-backed settings with StateFlow<Settings>
  ui/theme/ScopeTheme.kt                        Material 3 dynamic/dark theme
  ui/ScopeScreen.kt                             restructured full-screen layout
  ui/components/StatusChip.kt, RecBadge.kt, ControlsSheet.kt, TipsCard.kt, SnapshotToast.kt, ShutterFlash.kt
  ui/SettingsScreen.kt
  ui/DevSheet.kt                                debug-only developer controls
  ui/TrustScreen.kt                             + long-press on version opens DevSheet (debug)
  ui/ScopeViewModel.kt                          + settings application, screen navigation, feedback state
  MainActivity.kt                               + edge-to-edge, keep-screen-on from settings
android/src/test/kotlin/com/technicallyvu/scope/settings/AppSettingsTest.kt   (pure logic: defaults, clamping, serialization)
docs/phase3b-notes.md
```

---

### Task 1: Core settings hooks (TDD)

- `ScopeSession`: `fun setDenoise(enabled: Boolean, strength: Float = current)`; `@Volatile var doublePressWindowNanos: Long` (default 1.5 s, clamp 500 ms..5 s); `fun setDefaultOverride(driverId: String, rotation: Int?, mirror: Boolean?)` stored in a map consulted when a session starts (override wins over `driver.defaultRotation`; mirror default false unless overridden). `SessionState` gains `denoiseStrength: Float`.
- Tests: strength change reaches the denoiser (observe blended bytes differ between strength 0.2 and 0.9 on the same alternating stream); window change alters double-press detection (presses 2 s apart toggle when the window is 2.5 s, snapshot twice when 1.5 s); override rotation/mirror applied at session start and preserved on reconnect.
- Commit `feat(core): session settings hooks (denoise strength, double-press window, per-driver defaults)`.

### Task 2: AppSettings + theme + icon + strings scaffold

- `settings/AppSettings.kt`: `data class Settings(denoise: Boolean = true, denoiseStrength: Float = 0.6f, doublePressWindowMs: Int = 1500, haptics: Boolean = true, keepScreenOn: Boolean = true, showStats: Boolean = false, tipsDismissed: Boolean = false, defaults: Map<String, DriverDefault> = emptyMap())` with `DriverDefault(rotation: Int, mirror: Boolean)`; `class AppSettings(prefs: SharedPreferences)` exposing `flow: StateFlow<Settings>` and `update { }`; serialization of the per-driver map as `driverId:rotation:mirror;...`; clamping. Unit tests with an in-memory `SharedPreferences` fake (write a minimal fake implementing the interface in the test) covering defaults, round trip, clamping, malformed map string.
- `ui/theme/ScopeTheme.kt`: `@Composable fun ScopeTheme(content)` using `dynamicDarkColorScheme/dynamicLightColorScheme` on API ≥ 31 else `darkColorScheme()/lightColorScheme()` with a neutral teal seed; `isSystemInDarkTheme()`.
- `values/themes.xml`: `Theme.Scope` parent `android:Theme.Material.NoActionBar` with `android:windowBackground` black, `android:statusBarColor` transparent handled via `enableEdgeToEdge()`.
- Placeholder adaptive icon: vector foreground (a circle lens with a small highlight and a probe line; two colours) on a dark teal background; `ic_launcher.xml`/`ic_launcher_round.xml` in `mipmap-anydpi-v26`. Note in strings/README that it is a placeholder.
- Add `androidx-compose-material-icons-core = { module = "androidx.compose.material:material-icons-core" }` to the catalog (BOM-managed) and the dependency; record the OSV check in `docs/dependencies.md` (run `curl -s -X POST https://api.osv.dev/v1/query ...` for `androidx.compose.material:material-icons-core` at the BOM's resolved version, printed by `.\gradlew.bat :android:dependencies --configuration debugRuntimeClasspath | Select-String icons`).
- `strings.xml`: add every string the later tasks need (list them in the task report); existing hardcoded strings in `ScopeScreen`/`TrustScreen`/`MainActivity` migrate in Task 3/5.
- Manifest: `android:icon="@mipmap/ic_launcher"`, `android:roundIcon`, `android:theme="@style/Theme.Scope"`. `MainActivity.onCreate`: `enableEdgeToEdge()` (activity-compose provides `androidx.activity.enableEdgeToEdge`).
- Build green; commit `feat(android): settings store, Material 3 theme, placeholder icon, string resources`.

### Task 3: Full-screen live view and controls sheet

- `ScopeScreen` becomes a `Box(Modifier.fillMaxSize().background(Color.Black))` with: the live `Image` (fit); `StatusChip` top-start (dot colour: grey NoDevice / amber Connecting / green Streaming / red Failed; text; fps when streaming and stats on); `RecBadge` top-end (red pill, "REC 00:12" elapsed from `recordingStartedAt`); `SnapshotToast` bottom-start above the sheet (thumbnail + "Saved"); `ShutterFlash` full-screen white overlay fading over 150 ms on `flashTick`; `TipsCard` centred when `NoDevice` and tips not dismissed; the `ControlsSheet` at the bottom (compact) or as a right rail (wide).
- `ControlsSheet`: translucent surface (`surface.copy(alpha = 0.85f)`, rounded top corners), icon buttons 56 dp: Snapshot (camera), Record (fiber_manual_record red / stop square; shows a small spinner while `recordingStarting`), Rotate, Mirror, Denoise (toggle tint), Stats, Settings, About. Permission button appears above the sheet when `permissionDevice != null`. Auto-hide: while streaming and no interaction for 4 s the sheet and chips fade out (`AnimatedVisibility`); any tap on the screen shows them and restarts the timer; not hidden when not streaming.
- View model: `UiState` gains `screen: Screen` (`Live | Settings | Trust`), `recordingStarting: Boolean`, `recordingStartedAtNanos: Long?`, `lastSnapshotThumb: Bitmap?` (a ≤ 96 px thumbnail of the saved image, set with the `lastSaved` name and cleared after 3 s), `flashTick: Long`. `snapshot()` bumps `flashTick` immediately and sets the thumb on success. `toggleRecording` sets `recordingStarting` true until published. Replace `showTrust` with `screen`.
- Tests: `AppSettingsTest` unaffected; add a view-model-free unit test for the elapsed-time formatter (`formatElapsed(nanos)` → "00:12").
- Build green; commit `feat(android): full-screen live view, auto-hiding controls, status chip, REC timer, snapshot feedback`.

### Task 4: Settings screen and settings application

- `SettingsScreen(settings: Settings, onChange: (Settings) -> Unit, currentDriverId: String?, onBack)`: Material 3 list with switches/sliders: Denoise (switch + strength slider 0.2–1.0 shown as %); Double-press window slider 1.0–2.5 s (step 0.1); "This device" section when a driver is connected: default rotation segmented buttons 0/90/180/270 and mirror switch (writes `defaults[driverId]`); Haptics; Keep screen on while streaming; Show stats overlay; "Show tips again" button. `BackHandler`.
- View model: collect `AppSettings.flow`; on each change apply to the session (`setDenoise`, `doublePressWindowNanos`, `setDefaultOverride` for every entry) and to `UiState.showStats`/haptics gating (`hapticTick` only bumps when haptics enabled); the current driver id comes from the session state's `Streaming(name)`? — add `driverId: String?` to core `SessionState` (set on open, cleared on disconnect) in this task (small core change + test).
- `MainActivity`: keep-screen-on flag follows `keepScreenOn && Streaming` (observe `ui`).
- Build green; commit `feat(android): settings screen wired to session and UI`.

### Task 5: Tips card, developer sheet, About long-press, string migration

- `TipsCard`: title, four short lines (plug in and tap Allow; hold the cable button about half a second; press once for a photo, twice within a second and a half to start or stop a clip; photos in Gallery › Pictures › USB Scope, clips in Movies), "Got it" sets `tipsDismissed`.
- `DevSheet` (debug only): Replay fixture start/stop, "Log frame timing" switch (drives the existing debug timing log via a view-model flag), build id. Opened from `TrustScreen` by a long-press on the version line (`combinedClickable`); in release builds the long-press does nothing.
- Remove the Replay button from the controls sheet. Migrate every remaining hardcoded string (`TrustScreen`, `MainActivity`, `ScopeScreen`) to resources; add `contentDescription`s.
- Build green; commit `feat(android): first-launch tips, developer sheet behind About, strings to resources`.

### Task 6: Emulator verification (controller + agent)

- Create the AVD once: `C:\Android\sdk\cmdline-tools\latest\bin\avdmanager.bat create avd -n scope36 -k "system-images;android-36;google_apis;x86_64" -d pixel_7 --force`; run `C:\Android\sdk\emulator\emulator.exe -avd scope36 -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect` in the background; wait for `adb shell getprop sys.boot_completed` = 1; `adb install -r`; `adb shell am start`; open the dev sheet via long-press (`adb shell input swipe x y x y 1200` on the version line) and start replay; screenshots (`adb exec-out screencap -p > file` from cmd, or `adb shell screencap -p /sdcard/s.png` + `adb pull`) of: no-device with tips card, live view with sheet visible, live view after auto-hide, recording with REC timer, snapshot toast, settings, About, dark and light (`adb shell cmd uimode night yes/no`). Fix whatever looks wrong; record the results and screenshot names in `docs/phase3b-notes.md`.

### Task 7: Docs, review, merge

- `docs/phase3b-notes.md` (what changed, emulator screenshots, what Anthony should look at on the phone, the placeholder-icon note), README feature list update, `docs/dependencies.md` icons row. Full run green. Commit, tag `v0.3.1-polish`, whole-branch review, fix pass, merge to master.
