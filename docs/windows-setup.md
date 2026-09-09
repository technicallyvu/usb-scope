# Windows one-time setup: bind WinUSB to the endoscope

The endoscope is a vendor-class USB device (not a webcam), so Windows installs no driver for it.
libusb needs the generic **WinUSB** driver bound to it once. This is per device model and survives
unplugging. It does not affect any other device.

1. Download Zadig from https://zadig.akeo.ie (Zadig 2.9, part of the libwdi 1.5.1 release). It is a
   single portable .exe by the author of Rufus. No install.
2. Plug in the endoscope.
3. Run Zadig. If Windows asks, allow it to run as administrator.
4. Menu **Options → List All Devices**.
5. In the dropdown pick **supercamera** (USB ID 2CE3 3828). Make sure it is the composite parent
   entry, not an "(Interface 0)" / "(Interface 1)" child.
6. The right-hand target driver box should read **WinUSB (v6.1.7600.16385)**. Use the arrows if not.
7. Click **Install Driver** (or **Replace Driver**). Wait for "The driver was installed successfully."
8. Close Zadig. Unplug and re-plug the endoscope.

Verify (PowerShell):

    Get-PnpDevice -InstanceId 'USB\VID_2CE3&PID_3828*' | Select Status, Class, FriendlyName

Expected: `Status OK`, `Class USBDevice`. Then:

    .\gradlew.bat :desktop:probe --args="--list"

should list `2CE3:3828 ... <- useeplus`.

Note: `probe --list` shows the endoscope even before the driver is bound (libusb can read its
descriptors through the hub), but opening it fails with `LIBUSB_ERROR_NOT_SUPPORTED` until WinUSB
is installed. The probe prints this guide's path when that happens.

## Undo
Device Manager → Universal Serial Bus devices → supercamera → Uninstall device → tick
"Delete the driver software for this device".
