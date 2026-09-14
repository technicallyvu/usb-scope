# Google Play listing — USB Scope

Draft of 13 September 2026. Character limits are Play's; counts are shown so edits stay inside them.

## App name (30 characters max)

```
USB Scope: Endoscope Camera
```
(27 characters)

## Short description (80 characters max)

```
Endoscope & borescope viewer. No permissions, no internet, no tracking. Open source.
```
(80 characters)

Alternative if Play's reviewer objects to the ampersand or the length:

```
Endoscope viewer with zero permissions and no internet access. Open source.
```
(74 characters)

## Full description (4000 characters max)

```
USB Scope shows the live picture from a USB endoscope or borescope on your phone, takes photos and
records clips. That is all it does, and it does it without asking for anything.

NO PERMISSIONS
The app requests no permissions at all. Not camera, not microphone, not location, not storage, not
notifications. Check for yourself: Settings → Apps → USB Scope → Permissions says "No permissions
requested".

NO INTERNET
USB Scope does not have the INTERNET permission, so Android will not let it open a network connection.
There is no account, no analytics, no crash reporting, no ads and no third-party code. The build fails on
purpose if the internet permission ever appears.

YOUR PHOTOS STAY YOURS
Snapshots and clips go straight into your Gallery, in an album called USB Scope. They contain the picture,
the time and an orientation tag. No location, no identifiers. The app never reads them back.

WHAT IT DOES
• Live view the moment you plug the camera in. Tap Allow once and the app opens itself.
• Snapshot and record from the screen or from the button on the cable: one press for a photo, two presses
  to start or stop a clip.
• Rotate and mirror the picture; the app remembers the setting for each camera you use.
• Noise reduction for the grainy sensors these cameras ship with, and optional sharpening.
• A clean full-screen view. The controls fade out while you work and come back with a tap.
• Dark mode and your phone's colour theme.
• An About screen that shows the permission count (zero), the licence and where the source code lives.

SUPPORTED CAMERAS
USB Scope is built for the common USB-C endoscopes sold under many names that identify themselves as
USB 2CE3:3828 or 0329:2022 (the ones whose bundled app is a large download with a long permission list).
The single-interface YUV type is verified on real hardware. The two-interface MJPEG type is implemented
from public documentation. Standard UVC cameras are supported experimentally over bulk transfer. If your
camera does not work, the About screen tells you how to report it.

OPEN SOURCE
The whole app is MIT licensed. Anyone can read the code, build it, and check every claim on this page.
The source link is on the About screen.

NO SUBSCRIPTION, NO UPSELL
USB Scope is free. If it saved you from installing an app you did not trust, the About screen has a link
to buy the developer a coffee. It opens in your browser; the app itself stays offline.

FAIR WARNING ABOUT RESOLUTION
These cameras deliver 320×240 no matter what the box says. Some bundled apps upscale the picture before
saving so the files look like HD. USB Scope saves exactly what the camera sends.
```
(about 2,400 characters)

## Category and tags

- Category: **Tools**
- Tags: endoscope, borescope, inspection camera, USB camera, OTG

## Content rating

Fill the IARC questionnaire truthfully: no violence, no user interaction, no sharing of location, no
purchases inside the app. The result is **Everyone**.

## Data safety form

Answer **"No, this app does not collect or share user data"** for every category. Supporting facts for the
reviewer if asked:

- No INTERNET permission in the manifest (they can inspect the APK).
- No SDKs of any kind (no analytics, ads, crash reporting, payments).
- Photos and clips are written through the system MediaStore into the user's own Gallery and are never read
  or uploaded by the app.
- Settings are stored in the app's private SharedPreferences and deleted on uninstall.
- The two outbound links (source code, donation page) open the system browser; nothing is sent from the app.

Security practices section: data is not encrypted in transit (there is no transit); users can delete data
(delete the files from the Gallery; uninstall removes settings).

## Privacy policy URL

```
https://technicallyvu.com/usb-scope/privacy
```
Must be live before submission. Text is in `docs/privacy-policy.md`.

## Ads declaration

"No, my app does not contain ads."

## App access

"All functionality is available without special access." (Note for the reviewer: a physical USB endoscope
is required to see a live picture; the app shows a "Plug in your endoscope" screen otherwise.)

## Store assets

- App icon: `art/icon-play-512.png` (512 × 512, from Anthony's design).
- Feature graphic (1024 × 500): the icon's gradient with the line art on the left and three words on the
  right — "No permissions. No internet. Open source." — still to be made.
- Phone screenshots (at least 2, ideally 6, 16:9 or 9:16). Capture on the Fold 7 with the real camera:
  1. Live view with the controls showing, pointed at something recognisable (a coin, a label).
  2. Live view with the controls hidden (the full-screen picture).
  3. Recording in progress with the REC timer and stats overlay.
  4. The snapshot toast right after a cable-button press.
  5. Settings screen.
  6. About screen showing "Permissions requested: 0".
  Take them in dark mode for a consistent set; the emulator frames in `.superpowers/re/frames/final/` are
  fine for layout reference but use real scope pictures for the listing.

## Release notes for 0.3.1 (500 characters max)

```
First public release. Live view, snapshots and clips from USB endoscopes with zero permissions and no
internet access. Cable-button capture, noise reduction, optional sharpening, per-camera rotation memory,
dark mode, and an About screen that shows exactly what the app can and cannot do.
```
(about 300 characters)
