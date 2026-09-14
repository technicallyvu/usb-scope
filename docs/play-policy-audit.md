# Google Play policy audit — USB Scope

**Audited:** 14 September 2026 against the live policy text (fetched that day, not from memory).
**Subject:** `com.technicallyvu.scope` v0.3.1 (versionCode 3), publisher TechnicallyVu (Anthony Vu).
**Scope:** the three declarations the Play Console asks a developer to accept before an app can be
created and released — (1) Developer Program Policies, (2) Play App Signing Terms of Service,
(3) US export laws — plus the policy areas that actually bite a free, offline, zero-permission
camera-viewer utility.

**Overall verdict:** all three boxes can be ticked truthfully today. The one exception that had to be
fixed first (the Ko-fi link) was **fixed on 2026-09-14** — see fix 1 below — leaving **two metadata
items that must be fixed before the listing goes live** (a stale screenshot and a dead URL). See
[Fixes](#fixes-ordered-by-severity).

---

## Policy sources relied on

| Topic | URL | Fetched |
|---|---|---|
| Developer Program Policies (policy centre index) | https://support.google.com/googleplay/android-developer/answer/9859348 | 2026-09-14 |
| Policy centre (public) | https://play.google.com/about/developer-content-policy/ | 2026-09-14 |
| User Data policy (privacy policy + Data safety) | https://support.google.com/googleplay/android-developer/answer/10144311 | 2026-09-14 |
| Permissions and APIs that Access Sensitive Information | https://support.google.com/googleplay/android-developer/answer/9888170 | 2026-09-14 |
| Store Listing and Promotion (metadata) | https://support.google.com/googleplay/android-developer/answer/9898842 | 2026-09-14 |
| Intellectual Property | https://support.google.com/googleplay/android-developer/answer/9888072 | 2026-09-14 |
| Payments policy | https://support.google.com/googleplay/android-developer/answer/9858738 | 2026-09-14 |
| Understanding Google Play's Payments policy | https://support.google.com/googleplay/android-developer/answer/10281818 | 2026-09-14 |
| Health apps | https://support.google.com/googleplay/android-developer/answer/12261419 | 2026-09-14 |
| Target audience and content (Families) | https://support.google.com/googleplay/android-developer/answer/9285070 | 2026-09-14 |
| Functionality, Content and User Experience (minimum functionality) | https://support.google.com/googleplay/android-developer/answer/9898783 | 2026-09-14 |
| Target API level requirements | https://support.google.com/googleplay/android-developer/answer/11926878 | 2026-09-14 |
| Android developer verification (Play Console) | https://support.google.com/googleplay/android-developer/answer/16471116 | 2026-09-14 |
| Android developer verification (rollout) | https://android-developers.googleblog.com/2026/06/android-developer-verification.html | 2026-09-14 |
| Graphic asset / screenshot specs | https://support.google.com/googleplay/android-developer/answer/9866151 | 2026-09-14 |
| Play App Signing Terms of Service | https://play.google/play-app-signing-terms/ (redirected from https://play.google.com/about/play-app-signing-terms/) | 2026-09-14 |
| Export compliance ("Learn more" from the console) | https://support.google.com/googleplay/android-developer/answer/113770 | 2026-09-14 |
| BIS — encryption annual self-classification | https://www.bis.gov/learn-support/encryption-controls/annual-self-classification | 2026-09-14 |
| 15 CFR 740.17 (License Exception ENC) | https://www.ecfr.gov/current/title-15/subtitle-B/chapter-VII/subchapter-C/part-740/section-740.17 | 2026-09-14 |
| Real-world enforcement precedent (donation link) | https://github.com/ankidroid/Anki-Android/issues/21656 | 2026-09-14 |

---

## Declaration 1 — Developer Program Policies

> "I confirm this app meets the Developer Program Policies."

### 1.1 Permissions, and consistency with Data safety

| Requirement | What the app does (evidence) | Verdict | Action |
|---|---|---|---|
| Request only permissions "necessary to implement current features or services" promoted in the listing (Permissions policy) | `android/src/main/AndroidManifest.xml` has **no `<uses-permission>` element at all** — lines 1–32; only `<uses-feature android:name="android.hardware.usb.host" android:required="true"/>` (line 4), which is a hardware feature declaration, not a permission | PASS | — |
| Merged manifest must match the claim | `android/build/intermediates/merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml:15-19` adds exactly one item: `com.technicallyvu.scope.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, `protectionLevel="signature"`, defined and used by the app itself (injected by AndroidX core). It is not a system permission and is invisible to other apps | PASS | — |
| The "zero permissions" claim must be truthful | `core/.../TrustInfo.kt:79-88` lists the sensitive permissions not held; the About screen prints "Permissions requested: 0" **plus** a footnote that app-internal signature permissions are excluded (`strings.xml` `about_permissions_internal_footnote`, rendered in `art/screenshots/play/06-about.png`). The exclusion is disclosed, not hidden | PASS | — |
| No network access | `android/build.gradle.kts:106-127` registers `checkNoInternetPermission<Variant>`, wired into `assemble`, `bundle` and `check`, which fails the build if `android.permission.INTERNET` appears in the merged manifest. Grep of `android/src/main` + `core/src/main` finds no `Socket`, `URLConnection`, OkHttp, Retrofit or `URL(` usage; the only `https://` literals are the two browser-intent links (`strings.xml:4`, `strings.xml:6`) and the attribution URLs in `TrustInfo.kt:11-48` | PASS | — |
| Exported components must be intentional | One exported activity, `MainActivity` (`AndroidManifest.xml:15-30`), exported because it carries LAUNCHER and `USB_DEVICE_ATTACHED` filters. `androidx.profileinstaller.ProfileInstallReceiver` (merged manifest lines 71-89) is exported but guarded by `android:permission="android.permission.DUMP"` — a signature-or-privileged permission; standard AndroidX, not app-authored | PASS | — |
| USB device filter must not over-claim | `android/src/main/res/xml/device_filter.xml` matches 2CE3:3828, 0329:2022, and USB class 14 subclass 1 (UVC VideoControl). The filter only decides which devices offer to open the app; it grants nothing | PASS | — |
| Data safety section must be accurate and consistent with the privacy policy | `docs/play-listing.md` § "Data safety form" answers "No, this app does not collect or share user data" for every category, with the supporting facts. Confirmed by code: `android/.../media/MediaStoreSaver.kt:28-56` writes JPEG/MP4 through `MediaStore` with `DISPLAY_NAME`, `MIME_TYPE`, `RELATIVE_PATH` and an optional EXIF orientation tag only — no location, no identifiers, and nothing is read back; `android/.../settings/AppSettings.kt` stores preferences in private `SharedPreferences`; no analytics, ads, crash-reporting or payment SDK appears in `android/build.gradle.kts:70-85` or `gradle/libs.versions.toml` | PASS | — |
| Prominent disclosure / runtime consent | Not triggered: no personal or sensitive data is accessed. The only runtime prompt is Android's own USB-device permission dialog, which is a platform dialog, not an app permission request | NOT APPLICABLE | — |
| No debug-only surface reachable in release | `BuildConfig.DEBUG` gates every dev path: `ui/ScopeScreen.kt:116`, `ui/TrustScreen.kt:121` (the long-press that opens the sheet is not even attached), `ui/TrustScreen.kt:248` (the sheet itself), `ui/ScopeViewModel.kt:261` and `:574` (timing log, replay fixture). The only clipboard use is a **write** in `ui/DevSheet.kt:152-157`, inside that debug-only sheet — no clipboard read anywhere | PASS | — |

### 1.2 Privacy policy

| Requirement (User Data policy, verbatim) | What we have | Verdict | Action |
|---|---|---|---|
| Must be on "an active, publicly accessible, non-geofenced URL (no PDFs)" | https://technicallyvu.com/usb-scope/privacy — fetched 2026-09-14, renders as HTML | PASS | — |
| Linked in the Play Console field **and** accessible from within the app | Console field listed in `docs/play-listing.md` § "Privacy policy URL". In-app: the About screen carries the policy content itself (`ui/TrustScreen.kt:160-180`) plus the source link | PASS | Optional: add a direct link to the policy URL on the About screen so the in-app requirement is met by a link, not only by equivalent text |
| "The entity … named in the app's Google Play store listing must appear in the privacy policy or the app must be named in the privacy policy" | Both. `docs/privacy-policy.md:4` — "Applies to: USB Scope for Android and USB Scope for Windows, published by TechnicallyVu (Anthony Vu)" | PASS | — |
| "Developer information and a privacy point of contact" | `docs/privacy-policy.md:6` and § Contact — anthony@technicallyvu.com | PASS | — |
| Types of data accessed/collected/used/shared, and any parties shared with | §§ "The camera picture", "Photos and clips", "Settings", "USB device information", "Nothing else" — states none is collected or shared | PASS | — |
| "Secure data handling procedures" | § "How you can verify this" plus the no-network/no-permission architecture. Stated as an architectural guarantee rather than a procedures paragraph | PASS | — |
| "The developer's data retention and deletion policy" | § Settings ("removed when you uninstall it") and § Photos and clips ("You can view, share or delete them with any other app") | PASS | — |
| Labelled as a privacy policy | Title "USB Scope privacy policy" | PASS | — |
| Consistent with the Data safety section | Both say: collects nothing, shares nothing, no transit encryption because there is no transit, user can delete | PASS | — |

### 1.3 Store listing and promotion (metadata)

| Requirement | What we have | Verdict | Action |
|---|---|---|---|
| Title ≤ 30 characters, no emoji/emoticons/repeated special characters, no ALL CAPS unless brand | `USB Scope: Endoscope Camera` — 27 chars, plain ASCII (`docs/play-listing.md`). "USB" is a standard acronym, not decorative caps | PASS | — |
| No "misleading, improperly formatted, non-descriptive, irrelevant, excessive, or inappropriate" metadata | Full description is descriptive prose about what the app does | PASS | — |
| No claims of store performance or ranking ("#1", "App of the year", "Best of Play 20XX", "Popular"), no Editor's-choice-alike | No such claim anywhere in `docs/play-listing.md` | PASS | — |
| No "unattributed or anonymous user testimonials" | None | PASS | — |
| No price/promotional text in graphic assets | `art/feature-graphic-1024x500.png` carries "No permissions. No internet. Open source." — factual product attributes, not price or promotion | PASS | — |
| Claims must be verifiable | "No permissions" — verifiable in the manifest and on-device. "No internet" — verifiable by the absent INTERNET permission. "No tracking" — verifiable by the zero-SDK dependency list. "Open source" — MIT, `LICENSE`, public at github.com/technicallyvu/usb-scope. "These cameras deliver 320×240 no matter what the box says" — backed by `docs/image-quality.md` and the hardware verification in `README.md` | PASS | — |
| Screenshots must represent the actual app | **`art/screenshots/play/06-about.png` shows the source link as `https://technicallyvu.com/usb-scope`.** That URL 404s (fetched 2026-09-14), and the shipping build shows a different URL: `android/src/main/res/values/strings.xml:4` is now `https://github.com/technicallyvu/usb-scope`, changed in commit `dd934f7` (2026-09-13 22:30) — 39 minutes *after* the screenshots were finalised in `ea2ee0c` (21:51) | **ACTION NEEDED** | Re-capture `06-about` from a build of the current `strings.xml`. Also decide whether `https://technicallyvu.com/usb-scope` should exist as a landing page — the privacy policy sits under it at `/privacy`, so a 404 parent is odd |
| Screenshot build id must not undermine the listing | `06-about` reads `build v0.3.1-polish-15-gc71e3ab-**dirty**`. Not a policy breach, but it advertises a build made from an uncommitted tree in an app whose whole pitch is reproducibility | ACTION NEEDED (cosmetic, same fix) | Re-capture from a clean tagged build |
| Screenshot specs: 2–8 per device type, 320–3840 px, "the maximum dimension of your screenshot can't be more than twice as long as the minimum dimension" | Six PNGs at 1180 × 2360 = exactly 2:1. At the limit but inside it. (Play's *recommended* portrait format is 9:16 ≥ 1080×1920; 1:2 is permitted but not recommended) | PASS | Optional: pad to 1180 × 2098 (≈9:16) to hit the recommended format |
| Feature graphic 1024 × 500, JPEG or 24-bit PNG no alpha | `art/feature-graphic-1024x500.png` | PASS | Verify at upload that the PNG has no alpha channel |
| Icon 512 × 512, 32-bit PNG | `art/icon-play-512.png` | PASS | — |

### 1.4 Intellectual property

| Requirement | What we have | Verdict | Action |
|---|---|---|---|
| "We don't allow apps that infringe on others' trademarks" — no third-party marks in title, icon, description | The listing and the app name contain **no vendor or product brand name**. The full description identifies hardware only by USB IDs: "identify themselves as USB 2CE3:3828 or 0329:2022". `strings.xml:52-55` deliberately renders driver names as "USB-C scope (YUV)" / "USB scope (MJPEG)" instead of the internal protocol ids, with a comment saying the raw id "carries a vendor-derived word" | PASS | — |
| Brand names inside the codebase | `useeplus` and `i4season` survive as package/class names (`core/.../useeplus/UseeplusDriver.kt:22` comments the "Geek szitman 'supercamera' family"). These are protocol identifiers in source, not user-facing or store-facing text, and `ui/DriverNames.kt:25` maps them to neutral display strings. Nominative use of a name to say which protocol was reimplemented is not source confusion | PASS | — |
| "the ones whose bundled app is a large download with a long permission list" (full description) | A factual, comparative statement about an unnamed third-party app. Play's metadata policy bars *deceptive* and *unverifiable* claims; this one names no one and is a description of an app anyone can download and check. Low risk, but it is the one line a reviewer could read as disparagement of a competitor | PASS (monitor) | Optional softening: "…than the app that ships in the box" without the size/permission comparison |
| "Obtain written documentation or a licence for any third-party intellectual property you use" | No third-party code is bundled. `core/.../TrustInfo.kt:63-65`: "The single-interface YUV protocol was recovered by observing the device and reading the publicly distributed vendor app; no vendor code is included" | PASS | — |
| Attribution licence compatibility with the app's MIT licence (`LICENSE`) | `TrustInfo.kt:11-48`: hbens/geek-szitman-supercamera **CC0** (public-domain dedication, no attribution required — ours is voluntary and correct); echase/ProbeView **MIT** (requires the copyright + permission notice with any *copied code*; none is copied — the entry credits a protocol write-up); MAkcanca/useeplus-linux-driver **GPL-3** and ollyoid/useeplus-linux-v4l2-driver **GPL**, both marked "Reference only; no code included" — GPL obligations attach to distributed derivative *code*, not to facts learned from reading it, so nothing is owed; jmz3/EndoscopeCamera and NinesLastGoal/supercamera_WIN10 **unspecified licence**, marked "Reference only" — no licence to comply with because nothing was taken | PASS | Keep "reference only; no code included" accurate: if any GPL-derived line is ever copied in, the whole app becomes GPL and the MIT `LICENSE` would be wrong |
| Screenshot content and third-party artwork | Screenshots 01–04 show a photographed retail box reading only the generic words "Industrial Endoscope" with unbranded pictograms; no legible brand name, logo or copyrighted artwork resolves at 320×240 | PASS | Optional: re-shoot against a neutral subject to remove the question entirely |
| Impersonation | Developer name TechnicallyVu, package `com.technicallyvu.scope`, icon is original work (`art/icon-source.png`, `README.md` § Features). No claim of affiliation with any vendor | PASS | — |

### 1.5 Monetisation and ads — the "buy the developer a coffee" link

**This was the one real finding. Fixed on 14 September 2026** — the in-app link is gone from the
Android app; the analysis below is kept as the record of why.

| Requirement | What we have | Verdict |
|---|---|---|
| Payments policy: "apps may not lead users to a payment method other than Google Play's billing system" except where Section 3, 8 or 9 applies. The "Understanding" page expands this: the prohibition "includes directly linking to a webpage that could lead to an alternate payment method or using language that encourages a user to purchase the digital item outside of the app" | **Fixed 2026-09-14 (option 1).** The "Support this project" block is gone from `ui/TrustScreen.kt`, and `about_support_title`, `about_support_body` and `donate_url` are gone from `android/src/main/res/values/strings.xml`. Grep of `android/src/main` for `ko-fi`, `donate`, `coffee` and "Support this project" returns zero hits. The About screen now carries one outbound link — the source repo — with a comment at that call site recording why no donation link may be added back. `docs/play-listing.md` no longer advertises a donation link (the "NO SUBSCRIPTION, NO UPSELL" paragraph states only that the app is free with nothing to buy), and the Data safety section lists one outbound link. Sponsorship stays on the GitHub repo via `.github/FUNDING.yml`, which Play explicitly permits: "outside of the app, you are free to communicate with your users about alternative purchase options." `TrustInfo.donateUrl` survives for the desktop build only (not distributed through Play), documented as such in its KDoc | **PASS** |

Which exemption could apply, and why neither clearly does:

- **Tax-exempt donations.** The Payments policy permits donations collected in-app only where "the donations are for a validated tax-exempt organization (for example, a validated 501(c)(3) charitable organization in the United States or the local equivalent), and the donations are collected through a secure payment system." TechnicallyVu is an individual consultancy, not a 501(c)(3). This exemption does not apply.
- **Peer-to-peer / tips.** "In cases where 100% of the tip or contribution from a user goes to the creator and the payment does not grant access to any digital content or services (including stickers, badges, special emojis etc.), then we regard this as a peer-to-peer payment and use of Google Play's billing system is not required." On the facts this fits well: Ko-fi takes 0% of donations, the app is free, and a coffee buys the user nothing — no feature, no unlock, no badge. The risk is that Google has historically read "peer-to-peer" as a *user-to-user* flow inside a social/creator app rather than a user-to-developer donation, and has enforced it that way.
- **Enforcement precedent (fresh, and against us).** In September 2026 Google Play required AnkiDroid — a free, open-source app — to remove its Open Collective donation link, citing exactly this policy and rejecting an IRS 501(c)(6) determination letter as insufficient. AnkiDroid removed the link under threat of removal effective 11 September 2026 (https://github.com/ankidroid/Anki-Android/issues/21656). That is a directly analogous app removed for a directly analogous link, three days before this audit.

Recommended handling, in descending order of safety:

1. **Safest — remove the in-app link and the listing sentence.** Keep the donation route on the GitHub repo and on technicallyvu.com only. Play explicitly permits this: "outside of the app, you are free to communicate with your users about alternative purchase options." The About screen still links to the source repo, where FUNDING.yml does the work.
2. **Middle — keep a bare source link, drop the money words.** Remove the Ko-fi URL and the "buy the developer a coffee" body from `TrustScreen.kt:196-213`, `strings.xml:6`, `TrustInfo.kt:61` and `docs/play-listing.md`. A link to the repo is not a link to a payment method.
3. **Riskiest — keep it as is** and argue the peer-to-peer exemption if challenged. Given the AnkiDroid outcome, expect to lose that argument and to lose review time or the release.

Whichever is chosen, `docs/play-listing.md` must not keep advertising the link in the full description — the listing itself is metadata Google reviews, and the sentence "the About screen has a link to buy the developer a coffee" is a signpost straight to the finding.

| Other monetisation checks | Status |
|---|---|
| Ads declaration "No, my app does not contain ads" — true: no ads SDK in `android/build.gradle.kts:70-85` | PASS |
| No in-app purchases, no subscriptions, no paid unlocks | PASS |
| No paid app price | PASS |

### 1.6 Health / medical content

| Requirement | What we have | Verdict |
|---|---|---|
| Health apps policy scope: "If your app offers health-related features or information as part of its functionality, or accesses health data to support non-health features, it must comply" | Grep of `android/src/main`, `core/src/main`, `docs/play-listing.md`, `docs/privacy-policy.md` and `README.md` for `medical`, `diagnos*`, `patient`, `doctor`, `clinic`, `dental`, `earwax`, `otoscope`, `surgic*`, `therap*`: **zero matches** in user-facing or listing text (the only hits were `body*` Compose typography tokens and `MaterialTheme` identifiers). "Endoscope" and "borescope" are used strictly as hardware-category names for a USB camera; the listing's framing is inspection ("If your camera does not work…", "the grainy sensors these cameras ship with"), the category is **Tools**, and the screenshots show a cardboard box and plastic sprues, not a body | PASS — **not a health app**, so no Health apps declaration, no medical-device declaration, no "does not diagnose, treat, cure or prevent" disclaimer required |
| Risk to maintain | Any future copy that says "ear", "nose", "wound", "check your ears", or that shows the probe used on a person, moves the app into the Health apps policy and triggers the declaration form and possibly a medical-device claim review | Keep the listing on inspection/industrial use only |

### 1.7 Families and target audience

| Requirement | What we have | Verdict | Action |
|---|---|---|---|
| Declare target age group(s) at app creation | Recommend **18 and over**, single group. Nothing about the app appeals to children: Tools category, requires a USB endoscope purchase, monochrome/technical UI | PASS | Select 18+ only; do not opt into Designed for Families |
| "Marketing elements cannot contradict the declared audience" | Icon, feature graphic and screenshots are technical; no cartoon characters, bright child-appeal styling or play patterns | PASS | — |
| Families Ads SDK requirement | No ads at all | NOT APPLICABLE | — |
| Restrict Minor Access | Optional and unnecessary — there is no adult content, only an absence of child appeal. Enabling it needlessly shrinks reach | NOT APPLICABLE | Leave off |
| Content rating (IARC) | `docs/play-listing.md` § Content rating: no violence, no user interaction, no location sharing, no in-app purchases → Everyone. Note that an IARC rating of **Everyone** and a target audience of **18+** coexist fine; they answer different questions | PASS | Answer "no" to "users can interact", "shares location", "digital purchases" — all true |

### 1.8 Spam, minimum functionality, deceptive behaviour, device and network abuse

| Requirement | What we have | Verdict |
|---|---|---|
| Minimum functionality: apps that "crash, do not have the basic degree of adequate utility as mobile apps, lack engaging content, or exhibit other behavior that is not consistent with a functional and engaging user experience are not allowed" | Real utility: live UVC/proprietary USB video, snapshot, MP4 recording, denoise, sharpen, per-device orientation memory, settings, About (`README.md` § Features). Not a webview wrapper, not a stub | PASS |
| **Reviewability risk** — without a physical endoscope the app shows only "Plug in your endoscope." (`strings.xml` `live_view_no_device`). A reviewer with no hardware sees one static screen and could read that as "lacks functionality" | `docs/play-listing.md` § App access already notes this for the reviewer | PASS with a caveat — put the note in the "App access" instructions field verbatim, and consider recording a short screen capture of a real session as the listing video so a reviewer sees the app working |
| Deceptive behaviour: no misrepresentation of function, no hidden functionality, no impersonation | Every listing claim maps to inspectable code (see 1.3). Debug paths are compiled out of release by `BuildConfig.DEBUG` (see 1.1) | PASS |
| Device and network abuse: no unauthorised network use, no interference with other apps/services, no self-update or dynamic code loading | No network stack at all; `isMinifyEnabled = false` (`android/build.gradle.kts:50`) but nothing is downloaded or loaded at runtime; no `DexClassLoader`, no reflection-based code loading | PASS |
| Malware / Mobile Unwanted Software: no data collection without disclosure, no silent behaviour | Nothing is collected. The one background-adjacent behaviour — the app opening itself on USB attach — is a documented Android intent filter the user confirms with the system's own dialog | PASS |
| Use of SDKs in Apps policy | No third-party SDKs; dependencies are AndroidX/Compose/Kotlin only (`gradle/libs.versions.toml`), all OSV-checked (`docs/dependencies.md`) | PASS |
| Restricted content (sexual, violence, hate, gambling, illegal, financial services, etc.) | None in the app or the listing | NOT APPLICABLE |

### 1.9 Target API level and developer verification

| Requirement | What we have | Verdict | Action |
|---|---|---|---|
| "Starting August 31, 2026, new apps and app updates must target Android 16 (API level 36) or higher" | `android/build.gradle.kts:16` — `targetSdk = 36`; confirmed in the release merged manifest `uses-sdk android:targetSdkVersion="36"` (line 7-9). Also `compileSdk = 37` (line 11), `minSdk = 29` (line 15) | PASS | — |
| Android developer verification — Play developers must register their apps in Play Console; the first enforced markets (Brazil, Indonesia, Singapore, Thailand) have a **30 September 2026** deadline, global rollout from 2027 | Not yet done — the app does not exist in the Console yet. Creating it registers the package name; identity verification is a separate Console flow | **ACTION NEEDED (process, not code)** | Complete identity verification in Play Console at account setup. It is 16 days away for the first markets, and a new app created after that date should be verified from the start |

---

## Declaration 2 — Play App Signing Terms of Service

> Accepted when the app is created / when an upload key is configured.

| What the developer agrees to | Our position | Verdict |
|---|---|---|
| Provide an app signing key to Google, or let Google generate one | `android/build.gradle.kts:36-60` loads signing config from `USB_SCOPE_KEYSTORE_PROPERTIES` or `C:/Projects/usb-endoscope-app-secrets/keystore.properties` — outside the repo, so no key or password is in the public source tree. That is the **upload** key; Play App Signing holds the app signing key | PASS |
| "It will not be possible to retrieve Your app signing key once it is provided to or generated by Google", and Google "may retain indefinitely a backup copy of the key(s) for disaster recovery purposes" | Accept Google-generated signing key. The upload key stays locally controlled and can be rotated if lost; the app signing key cannot | PASS — acknowledged |
| Publish using the Android App Bundle format | `docs/release.md` / `bundleRelease` path; `android/build/intermediates/bundle_manifest/release/…` shows a bundle is already being produced | PASS |
| Grant Google a non-exclusive licence to generate APKs from the bundle and to "modify Your app APKs to optimise performance, security and/or size", with the assurance that this "will not change the purpose of Your app" | No objection: the app has no signature-dependent logic and no integrity self-check that Google's re-signing would break. Note for the future — if the app ever verifies its own signature to back the "this is the build I published" claim, it must check the **app signing** certificate Play publishes, not the upload certificate | PASS |
| Sole remedy for disagreeing with future changes is to terminate use of the service | Acknowledged; nothing in the project depends on Play App Signing remaining unchanged | PASS |
| Open-source implications | MIT (`LICENSE`) places no restriction on who signs a binary; reproducible-build claims in the listing are about *source*, which anyone can build, not about byte-identical APKs. The listing does not claim byte-reproducibility, so Google's re-signing contradicts nothing we say | PASS |

**Verdict: can be accepted as written.** One housekeeping item: `docs/release.md` should state explicitly that the key at `C:/Projects/usb-endoscope-app-secrets/keystore.properties` is the **upload** key and must be backed up off-machine, since losing it means a Play-assisted upload-key reset rather than an unrecoverable app.

---

## Declaration 3 — US export laws

> "I acknowledge that my software application may be subject to United States export laws, regardless of my location or nationality. I agree that I have complied with all such laws, including any requirements for software with encryption functions. I hereby certify that my application is authorized for export from the United States under these laws." (Console wording; "Learn more" → https://support.google.com/googleplay/android-developer/answer/113770)

| Question | Answer (evidence) | Verdict |
|---|---|---|
| Why does this apply at all? | Google's page: Google is a US company and "the government considers it an export when someone outside the US downloads software from our servers" | Informational |
| Does the app implement, use or call encryption? | No. Grep of `android/src/main` and `core/src/main` finds no TLS, no `javax.crypto`, no `java.security` cipher use, no hashing for security purposes, no key material. There is no network stack to secure (`android/build.gradle.kts:106-127` proves the absence of INTERNET). The app reads USB bulk/control transfers and writes JPEG/MP4 via `MediaStore` (`media/MediaStoreSaver.kt`, `media/SurfaceRecorder.kt`). No Android platform crypto API is invoked by app code | Determinative |
| Does using an OS that contains crypto pull the app into Category 5 Part 2? | No. Controls attach to the item being exported. An application that neither contains nor calls cryptographic functionality is not an encryption item; the Android platform's own crypto is separately classified and is not "in" this app | — |
| Classification | **EAR99** — not listed on the Commerce Control List, and specifically not ECCN 5D992 (which covers mass-market *encryption* software) | PASS |
| CCATS / commodity classification request to BIS? | Not required. CCATS applies to encryption items seeking classification under License Exception ENC | NOT APPLICABLE |
| Annual self-classification report to BIS/NSA? | Not required. The obligation in 15 CFR 740.17(e)(3) attaches to items self-classified and exported under **License Exception ENC 740.17(b)(1)** — i.e. Category 5 Part 2 encryption items. An EAR99 item with no encryption functionality is outside that scope entirely (https://www.bis.gov/learn-support/encryption-controls/annual-self-classification, https://www.ecfr.gov/current/title-15/subtitle-B/chapter-VII/subchapter-C/part-740/section-740.17) | NOT APPLICABLE |
| Publicly-available encryption source-code email notification to BIS/NSA (15 CFR 742.15(b))? | Not required. That notification is for publicly available **encryption** source code. The repo at github.com/technicallyvu/usb-scope contains none | NOT APPLICABLE |
| Embargoed destinations | Google states it "blocks downloads to these countries" for Play-distributed apps. Nothing further is required of the developer, and the app has no distribution channel of its own | PASS (handled by Google) |
| Are we authorised to export? | Yes — EAR99 software, no licence required for general destinations, no listed party or end-use concern | PASS |

**Verdict: the export-compliance box can be ticked truthfully.** Re-examine only if the app ever gains network code, TLS, encrypted local storage, or a signature/integrity verification feature.

Caveat: this is an engineering analysis of a documented factual position, not legal advice. Anthony is the one certifying.

---

## Fixes, ordered by severity

1. ~~**Decide the Ko-fi link before the app is created.**~~ — **DONE 2026-09-14** (option 1, the safest). The "Support this project" block was deleted from `ui/TrustScreen.kt` and the `about_support_title`, `about_support_body` and `donate_url` strings from `android/src/main/res/values/strings.xml`; the About screen keeps only the source link, with a comment there recording the policy reason. `docs/play-listing.md` no longer mentions donations, coffee or Ko-fi, in the full description or in the Data safety bullet. The donation route lives on the GitHub repo (`.github/FUNDING.yml`) and technicallyvu.com, which Play explicitly allows. `TrustInfo.donateUrl` and the desktop About dialog are unchanged — the Windows build is not distributed through Play. `versionCode` was not bumped: nothing has been uploaded yet.
2. **Re-capture `art/screenshots/play/06-about.png`.** It shows `https://technicallyvu.com/usb-scope` as the source link; that URL 404s today and the shipping build now shows `https://github.com/technicallyvu/usb-scope` (`strings.xml:4`, commit `dd934f7`, 39 minutes after the screenshots were finalised). A screenshot that does not match the app is a metadata violation. Capture from a clean, tagged build so the version line does not read `-dirty`.
3. **Decide what lives at `https://technicallyvu.com/usb-scope`.** The privacy policy is at `/usb-scope/privacy` and renders; the parent 404s. Either publish a landing page or stop pointing anything at the parent.
4. **Complete Android developer verification in Play Console** at account setup — the first enforced markets hit 30 September 2026.
5. **Paste the App access note verbatim into the Console field**: a physical USB endoscope is required to see a live picture; without one the app shows "Plug in your endoscope." Consider a short screen-capture video so a hardware-less reviewer sees the app working — this is the most likely cause of a minimum-functionality rejection.
6. **Optional, low risk:** add a direct link to the privacy policy URL on the About screen; pad screenshots to ≈9:16 for Play's recommended format; soften "the ones whose bundled app is a large download with a long permission list"; re-shoot the live-view screenshots against a subject with no product packaging in frame; note in `docs/release.md` that the local keystore is the *upload* key and needs an off-machine backup.

## Overall verdict

**Yes, all three boxes can be ticked truthfully.** Fix 1 was applied on 2026-09-14.

- **Developer Program Policies:** the app is unusually clean against every section that applies (permissions, user data, IP, metadata, health, families, minimum functionality, device abuse, target API level). The single genuine violation risk was the external donation link under the Payments policy, removed from the Android app on 2026-09-14. Fixes 2–3 are metadata accuracy, which is also part of this declaration and must be done before the listing is submitted.
- **Play App Signing ToS:** nothing in the project conflicts with it; accept as written.
- **US export laws:** EAR99, no encryption functionality, no BIS filing of any kind. Certify without reservation.
