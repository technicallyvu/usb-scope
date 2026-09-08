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

Re-run before every release:
    curl -s -X POST https://api.osv.dev/v1/query -H "content-type: application/json" \
      -d "{\"version\":\"<ver>\",\"package\":{\"name\":\"<group>:<artifact>\",\"ecosystem\":\"Maven\"}}"
