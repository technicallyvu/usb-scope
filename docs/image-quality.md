# Image quality: what the camera really delivers, and what we do about it

Written 2026-09-13, when sharpening was added. This is the place to point anyone who asks "the box said
1920x1080, why are my photos small?"

## What the camera really delivers

The `i4season-yuv` probe (an i4season "su4p-002", firmware 5.0.13, USB 2CE3:3828) sends **320x240 YUYV at
about 11 frames a second**, and nothing else. Three separate pieces of evidence say so:

1. **The camera's own info block advertises a single size.** Every session starts by asking the device to
   describe itself; the 480-byte reply carries exactly one width and one height (320 and 240 on this unit,
   at offsets 46 and 48 — see `I4seasonInfoBlock`). There is no list of supported modes, because there is
   no choice to make.
2. **There is no command to ask for a different size.** The vendor library exposes a set-preview-size path
   only for its libuvc-backed camera type (`ctype == 2`): `SetPreviewSize` returns `-1` without sending
   anything for every other type, and its MFi camera class stubs `resolutionSet` empty. For the YUV type
   (`ctype == 1`) — which is what this probe is classified as — the size is read from the device and never
   set. Nothing in the protocol can request 640x480 or anything else, so nothing in our driver pretends to.
3. **The vendor app upscales on save.** Its photos come out at 1920x1440 for a 4:3 picture and 1920x1080
   for a 16:9 one — the same two numbers whatever the scene, which is the signature of a fixed resize, not
   of a sensor. The "1920x1080" in the printed manual is the product family's marketing figure, not a
   measurement of this sensor.

So: the manual's number is real in the sense that *some* product in that family may reach it. This one does
not, and no software — ours or the vendor's — can make 320x240 into more than 320x240.

## Our stance

**We never upscale.** Photos and clips are written at the camera's native 320x240 (rotated or mirrored if
you asked for that, which can make it 240x320). A file that says 1920x1440 while carrying 320x240 worth of
information is a file that lies about itself, and it is four times the size for nothing.

An **honest, clearly-labelled** upscale on save is still on the wishlist (`next-steps.md`, features §1) —
opt-in, and named as an upscale. That is a different thing from doing it silently by default.

## Temporal denoise

On by default in both apps. Adaptive frame blending: still parts of the picture are averaged across frames,
which removes sensor grain, while anything that moves passes straight through unblended, so detail is not
smeared. Strength is adjustable (Settings → Image on Android). The motion metric and its calibration are
written up in [phase3a-notes.md](phase3a-notes.md).

## Sharpening

Off by default; Settings → Image → **Sharpen picture**, with a strength slider (default 50 %).

- **What it is.** A luma-only unsharp mask: each pixel's brightness is compared to the average of its 3x3
  neighbourhood, and the difference is added back — `y' = clamp(y + amount * (y - blur(y)))`, with
  `amount = strength`, so full strength adds one whole difference and no more. Deliberately mild.
- **After the denoiser, never before.** An unsharp mask amplifies exactly the fine, high-frequency content
  that sensor grain is made of. Sharpening first would sharpen the noise and hand the denoiser a harder
  picture; both shells run denoise → sharpen.
- **Luma only.** On YUYV frames only the Y samples are rewritten and the U/V bytes are untouched; on decoded
  JPEG frames one brightness delta is computed per pixel and added to red, green and blue alike. Either way
  the sharpener cannot shift a hue or leave a coloured halo along an edge — the usual giveaway of a
  sharpener let loose on the colour channels.
- **It adds no detail.** None. It makes edges *read* as crisper on a screen by exaggerating the contrast
  that is already there. Nothing that the sensor did not sample comes back, and at high strength you will
  see light and dark fringes along edges, which is the exaggeration becoming visible.
- **Saved files match the screen.** With sharpening (or denoise) on, a snapshot is the picture you are
  looking at, re-encoded — not the camera's original bytes.

## How to verify

1. Plug the probe in and point it at something with fine texture and a hard edge — printed text works well.
2. Settings → Image → turn **Sharpen picture** on, come back to the live view, and toggle it a few times.
   Edges should tighten; the picture should not change size, colour or framing.
3. Push **Sharpening strength** to 100 % and look at a high-contrast edge: the light/dark fringe that
   appears is the mask overshooting, and is the reason the default is 50 %.
4. Take a snapshot with it on and open the file: same 320x240 (or 240x320 rotated), sharpened the same way
   the screen was.
