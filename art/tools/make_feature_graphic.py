"""Renders the Google Play feature graphic (1024 x 500) from the icon artwork.

Run from the repo root:  python art/tools/make_feature_graphic.py
Uses the line art lifted from art/icon-source.png by make_icon.py, the same gradient, and the
Segoe UI fonts that ship with Windows (falls back to Pillow's default font elsewhere).
"""
import os
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFont

sys.path.insert(0, os.path.dirname(__file__))
from make_icon import extract_art, GRADIENT_TL, GRADIENT_BR, LINE  # noqa: E402

W, H = 1024, 500
OUT = 'art/feature-graphic-1024x500.png'
TITLE = 'USB Scope'
LINES = ['No permissions.', 'No internet.', 'Open source.']
SUB = 'Endoscope viewer for Android'


def font(size, bold=False):
    for path in ([r'C:\Windows\Fonts\segoeuib.ttf'] if bold else [r'C:\Windows\Fonts\segoeui.ttf']):
        if os.path.exists(path):
            return ImageFont.truetype(path, size)
    return ImageFont.load_default()


def gradient(w, h):
    tl, br = np.array(GRADIENT_TL, float), np.array(GRADIENT_BR, float)
    t = ((np.arange(h)[:, None] / (h - 1)) * 0.35 + (np.arange(w)[None, :] / (w - 1)) * 0.65)[..., None]
    return Image.fromarray((tl * (1 - t) + br * t).astype(np.uint8), 'RGB')


if __name__ == '__main__':
    canvas = gradient(W, H).convert('RGBA')

    # Line art on the left, ~76 % of the height, vertically centred.
    art = extract_art()
    target_h = int(H * 0.76)
    s = target_h / art.height
    art = art.resize((int(art.width * s), target_h), Image.LANCZOS)
    art_x = 96
    canvas.alpha_composite(art, (art_x, (H - target_h) // 2))

    # Text block on the right.
    d = ImageDraw.Draw(canvas)
    x = art_x + art.width + 88
    white = (*LINE, 255)
    y = 88
    d.text((x, y), TITLE, font=font(84, bold=True), fill=white)
    y += 108
    f = font(44)
    for line in LINES:
        d.text((x, y), line, font=f, fill=white)
        y += 56
    d.text((x, y + 14), SUB, font=font(26), fill=(*LINE, 210))

    canvas.convert('RGB').save(OUT)
    print('wrote', OUT)
