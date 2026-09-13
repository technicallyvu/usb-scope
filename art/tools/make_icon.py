"""Builds the Android launcher icon layers and the Play Store icon from art/icon-source.png.

Run from the repo root:  python art/tools/make_icon.py
Needs Pillow + numpy. The source is the 2048 px squircle render Anthony chose (2026-09-13).
The white line art is lifted off the gradient into a transparent foreground (adaptive icon
safe zone, 62 of 108 dp), a black copy becomes the monochrome (themed icon) layer, and the
Play icon is the same art over a full-bleed gradient sampled from the source.
"""
import numpy as np
from PIL import Image, ImageDraw

SRC = 'art/icon-source.png'
SQUIRCLE = (517, 518, 1531, 1531)      # bounds of the squircle inside the source render
CORNER_RADIUS, INSET = 170, 60         # inset skips the squircle's own rim highlight
GRADIENT_TL, GRADIENT_BR = (131, 217, 214), (103, 173, 224)   # sampled from the source
LINE = (0xE9, 0xF9, 0xF6)
DENSITIES = {'mdpi': 108, 'hdpi': 162, 'xhdpi': 216, 'xxhdpi': 324, 'xxxhdpi': 432}

def extract_art():
    a = np.asarray(Image.open(SRC).convert('RGB')).astype(float)
    x0, y0, x1, y1 = SQUIRCLE
    sq = a[y0:y1 + 1, x0:x1 + 1]; h, w = sq.shape[:2]
    m = Image.new('L', (w, h), 0)
    ImageDraw.Draw(m).rounded_rectangle((INSET, INSET, w - 1 - INSET, h - 1 - INSET),
                                        radius=max(20, CORNER_RADIUS - INSET), fill=255)
    alpha = np.clip((sq.min(axis=2) - 130) / (215 - 130), 0, 1) * (np.asarray(m) > 0)
    ys, xs = np.where(alpha > 0.5)
    pad = int(0.035 * w)
    box = (max(0, xs.min() - pad), max(0, ys.min() - pad), min(w, xs.max() + pad + 1), min(h, ys.max() + pad + 1))
    rgba = np.dstack([np.full((h, w), c) for c in LINE] + [(alpha * 255)]).astype(np.uint8)
    return Image.fromarray(rgba, 'RGBA').crop(box)

def foreground(art, px, mono=False, safe_dp=62):
    canvas = Image.new('RGBA', (px, px), (0, 0, 0, 0))
    aw, ah = art.size; s = min(px * safe_dp / 108 / aw, px * safe_dp / 108 / ah)
    layer = art.resize((int(aw * s), int(ah * s)), Image.LANCZOS)
    if mono:
        z = Image.new('L', layer.size, 0); layer = Image.merge('RGBA', (z, z, z, layer.split()[3]))
    canvas.alpha_composite(layer, ((px - layer.width) // 2, (px - layer.height) // 2))
    return canvas

def gradient(px):
    tl, br = np.array(GRADIENT_TL), np.array(GRADIENT_BR)
    t = (np.add.outer(np.arange(px), np.arange(px)) / (2 * (px - 1)))[..., None]
    return Image.fromarray((tl * (1 - t) + br * t).astype(np.uint8), 'RGB')

if __name__ == '__main__':
    art = extract_art()
    for d, px in DENSITIES.items():
        foreground(art, px).save(f'android/src/main/res/mipmap-{d}/ic_launcher_foreground.png')
        foreground(art, px, mono=True).save(f'android/src/main/res/mipmap-{d}/ic_launcher_monochrome.png')
    play = gradient(512).convert('RGBA')
    fg = foreground(art, int(512 * 108 / 72)); off = (fg.width - 512) // 2
    play.alpha_composite(fg.crop((off, off, off + 512, off + 512)))
    play.convert('RGB').save('art/icon-play-512.png')
    print('icon layers written')
