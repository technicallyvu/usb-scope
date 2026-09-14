# Building a release

## Signing

Release builds are signed with the **Play upload key**. The keystore and its passwords live outside
the repository, in a folder only Anthony's Windows user can read:

```
C:\Projects\usb-endoscope-app-secrets\usb-scope-upload.jks
C:\Projects\usb-endoscope-app-secrets\keystore.properties
```

`android/build.gradle.kts` reads `keystore.properties` from that path (or from the path in the
`USB_SCOPE_KEYSTORE_PROPERTIES` environment variable). When the file is absent the release build is
simply unsigned, so the public repository builds for anyone without any secret.

The properties file has four lines: `storeFile`, `storePassword`, `keyAlias` (`upload`), `keyPassword`.

Key facts (generated 2026-09-14):

- RSA 4096, valid until 2054, alias `upload`, subject `CN=USB Scope upload key, O=TechnicallyVu, C=US`.
- Certificate SHA-256: `C5:83:5A:08:07:E1:2F:FF:B6:F6:8E:83:B2:D9:92:23:90:0D:D2:F3:A6:D5:5B:F5:3B:CD:DE:36:EF:68:92:E2`

**Back it up.** Copy the `.jks` and the properties file to the password manager (or another
encrypted place) now. With Play App Signing, Google holds the *app* signing key and this key only
authorises uploads, so a lost upload key can be reset through Play Console support, but that takes
days and needs identity checks. Never commit either file; `.gitignore` refuses `*.jks` and
`keystore.properties` as a second line of defence.

## Commands

From the repository root, PowerShell, with `$env:ANDROID_HOME = "C:\Android\sdk"`:

```powershell
.\gradlew.bat test :android:testDebugUnitTest      # all unit tests
.\gradlew.bat :android:bundleRelease               # signed AAB for Play
.\gradlew.bat :android:assembleRelease             # signed APK for GitHub Releases / sideloading
```

Outputs:

- `android/build/outputs/bundle/release/android-release.aab` — upload this to Play.
- `android/build/outputs/apk/release/android-release.apk` — attach this to a GitHub Release.

Both tasks run the privacy gate; the build fails if the INTERNET permission ever appears in the merged
manifest, and prints `OK: no INTERNET permission in the release manifest` when it passes.

Verify the signature if in doubt:

```powershell
& "C:\Android\sdk\build-tools\36.0.0\apksigner.bat" verify --print-certs android\build\outputs\apk\release\android-release.apk
```

## Version bump checklist

1. `android/build.gradle.kts`: increment `versionCode` (Play rejects a reused one) and set `versionName`.
2. Update the release notes in `docs/play-listing.md`.
3. Tag: `git tag -a vX.Y.Z -m "..."` and `git push origin --tags`.
4. Build the bundle and the APK as above.
5. Play Console: create a release on the chosen track, upload the AAB, paste the release notes.
6. GitHub: `gh release create vX.Y.Z android/build/outputs/apk/release/android-release.apk --title "USB Scope X.Y.Z" --notes-file <notes>`.

## First upload (0.3.1, versionCode 3)

Play App Signing is mandatory for new apps: on the first upload Play Console offers to generate the
app signing key itself and register this keystore's certificate as the upload key. Accept that; do not
export or upload the `.jks`.
