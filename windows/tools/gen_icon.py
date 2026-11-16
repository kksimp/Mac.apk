#!/usr/bin/env python3
"""gen_icon.py -- draws the Win.apk icon (launcher/winapk.ico and winapk.png).

A blue-to-teal rounded tile holding a white window, and in the window a 2x2
grid of coloured app tiles: Android apps, in a Windows window. Drawn at
1024 px and scaled down; the sizes up to 24 px drop the title bar and the
tile's margin so the grid stays readable.

    windows/tools/gen_icon.py   (needs Pillow; rewrites windows/launcher/winapk.ico and winapk.png)
"""

import os

from PIL import Image, ImageDraw, ImageFilter

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "launcher")
S = 1024


def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(len(a)))


def draw(size, small=False):
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    # The tile: a diagonal gradient, with a soft light over its top half.
    grad = Image.new("RGBA", (S, S))
    blue, teal = (64, 98, 255, 255), (14, 186, 160, 255)
    px = grad.load()
    for y in range(S):
        for x in range(S):
            t = (x * 0.45 + (S - y) * 0.55) / S
            light = int(34 * (1 - y / (S / 2)) ** 2) if y < S // 2 else 0
            c = lerp(teal, blue, min(1.0, max(0.0, t)))
            px[x, y] = lerp(c, (255, 255, 255, 255), light / 255)[:3] + (255,)
    m = 0 if small else 40
    mask = Image.new("L", (S, S), 0)
    ImageDraw.Draw(mask).rounded_rectangle((m, m, S - m, S - m), radius=int((S - 2 * m) * 0.23), fill=255)
    img.paste(grad, (0, 0), mask)

    # The window, on a shadow.
    wx0, wy0, wx1, wy1 = (150, 190, 874, 830) if small else (190, 230, 834, 800)
    r = 90 if small else 70
    shadow = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    ImageDraw.Draw(shadow).rounded_rectangle((wx0, wy0 + 24, wx1, wy1 + 24), radius=r, fill=(10, 30, 80, 90))
    img.alpha_composite(shadow.filter(ImageFilter.GaussianBlur(28)))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle((wx0, wy0, wx1, wy1), radius=r, fill=(255, 255, 255, 255))
    bar = 0 if small else 120
    if bar:  # the title bar: its three controls at the end, a line under it
        for i in range(3):
            cx = wx1 - 70 - i * 62
            d.ellipse((cx - 20, wy0 + 40, cx + 20, wy0 + 80), fill=(200, 208, 230))
        d.line((wx0, wy0 + bar, wx1, wy0 + bar), fill=(226, 231, 243), width=6)

    # The apps.
    pad = 90 if small else 64
    gx0, gy0, gx1, gy1 = wx0 + pad, wy0 + bar + pad - (8 if bar else 0), wx1 - pad, wy1 - pad
    gap = 60 if small else 44
    tw, th = (gx1 - gx0 - gap) / 2, (gy1 - gy0 - gap) / 2
    colours = [(61, 220, 132), (255, 176, 32), (77, 120, 255), (255, 90, 120)]
    for i, c in enumerate(colours):
        x0, y0 = gx0 + (i % 2) * (tw + gap), gy0 + (i // 2) * (th + gap)
        d.rounded_rectangle((x0, y0, x0 + tw, y0 + th), radius=int(min(tw, th) * 0.28), fill=c + (255,))
    return img.resize((size, size), Image.LANCZOS)


def main():
    big = draw(256)
    big.save(os.path.join(OUT, "winapk.png"))
    sizes = [16, 20, 24, 32, 40, 48, 64, 96, 128, 256]
    images = [draw(s, small=s <= 24) for s in sizes]
    images[-1].save(os.path.join(OUT, "winapk.ico"), sizes=[(s, s) for s in sizes], append_images=images[:-1])


if __name__ == "__main__":
    main()
