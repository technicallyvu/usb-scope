# Dependency pins and vulnerability checks

Checked 2026-09-08 against OSV (https://api.osv.dev/v1/query, ecosystem Maven). All zero known vulns.

| Artifact | Version |
|---|---|
| org.jetbrains.kotlin:* | 2.4.10 |
| org.jetbrains.compose (gradle plugin + libs) | 1.12.0 |
| org.jetbrains.kotlinx:kotlinx-coroutines-* | 1.11.0 |
| org.junit:junit-bom | 6.1.3 |
| org.usb4java:usb4java / libusb4java | 1.3.0 |
| org.bytedeco:javacv, javacpp | 1.5.14 |
| org.bytedeco:ffmpeg | 8.1.2-1.5.14 (windows-x86_64) |
| org.apache.commons:commons-imaging | 1.0.0-alpha6 |

Checked 2026-09-09 against OSV (https://api.osv.dev/v1/query, ecosystem Maven). All zero known vulns.

| Artifact | Version |
|---|---|
| com.android.tools.build:gradle (AGP) | 9.4.0 |
| androidx.compose:compose-bom | 2026.08.00 |
| androidx.compose.ui:ui / androidx.compose.foundation:foundation | 1.12.0 (via BOM) |
| androidx.compose.material3:material3 | 1.4.0 (via BOM) |
| androidx.compose.material3:material3-window-size-class | 1.4.0 (via BOM) |
| androidx.activity:activity-compose | 1.13.0 |
| androidx.lifecycle:lifecycle-viewmodel-compose | 2.11.0 |
| androidx.core:core-ktx | 1.19.0 |
| androidx.exifinterface:exifinterface | 1.4.2 |
| org.jetbrains.kotlinx:kotlinx-coroutines-android | 1.11.0 |

Android SDK levels: compileSdk 37, required by the pinned AndroidX artifacts (Compose BOM 2026.08.00); targetSdk 36, minSdk 29.

Re-run before every release:
    curl -s -X POST https://api.osv.dev/v1/query -H "content-type: application/json" \
      -d "{\"version\":\"<ver>\",\"package\":{\"name\":\"<group>:<artifact>\",\"ecosystem\":\"Maven\"}}"
