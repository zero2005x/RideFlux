# Copyright (C) 2026 RideFlux project contributors.
# SPDX-License-Identifier: GPL-3.0-or-later
"""Render the RideFlux adaptive launcher icon (108x108 viewport) to a 512x512 Play Store PNG.

Usage (from the repository root, needs Pillow):
    python docs/play-store/tools/render_icon.py app/src/main/ic_launcher-playstore.png

Mirrors app/src/main/res/drawable/ic_launcher_background.xml and ic_launcher_foreground.xml;
update this script if those vectors change. Draws at 8x supersampling and downsamples
with LANCZOS for anti-aliasing. The output is a full-bleed square: Play applies its own mask.
"""
import math
import sys

from PIL import Image, ImageDraw

OUT = sys.argv[1]
FINAL = 512
SS = 8
N = FINAL * SS
K = N / 108.0  # viewport units -> pixels

CYAN = (0x00, 0xE5, 0xFF)
WHITE = (0xFF, 0xFF, 0xFF)


def p(v):
    return v * K


def background():
    # Radial gradient centred at (54,54), radius 77: #0F1A2E -> #000000.
    small = 512
    img = Image.new("RGB", (small, small))
    px = img.load()
    start = (0x0F, 0x1A, 0x2E)
    r_px = 77 / 108 * small
    c = small / 2
    for y in range(small):
        for x in range(small):
            t = min(1.0, math.hypot(x + 0.5 - c, y + 0.5 - c) / r_px)
            px[x, y] = tuple(round(s * (1 - t)) for s in start)
    return img.resize((N, N), Image.BICUBIC).convert("RGBA")


def layer():
    return Image.new("RGBA", (N, N), (0, 0, 0, 0))


def with_alpha(img, alpha):
    r, g, b, a = img.split()
    a = a.point(lambda v: round(v * alpha))
    return Image.merge("RGBA", (r, g, b, a))


def ring(draw, r, width, color):
    c = p(54)
    ro, ri = p(r + width / 2), p(r - width / 2)
    draw.ellipse([c - ro, c - ro, c + ro, c + ro], fill=color + (255,))
    draw.ellipse([c - ri, c - ri, c + ri, c + ri], fill=(0, 0, 0, 0))


def main():
    img = background()

    # Outer tire ring: r=34, stroke 7.
    lyr = layer()
    ring(ImageDraw.Draw(lyr), 34, 7, CYAN)
    img.alpha_composite(lyr)

    # Inner rim: r=24, stroke 2, alpha 0.6.
    lyr = layer()
    ring(ImageDraw.Draw(lyr), 24, 2, CYAN)
    img.alpha_composite(with_alpha(lyr, 0.6))

    # Spokes: one path, stroke 1.6, alpha 0.35, round caps.
    lyr = layer()
    d = ImageDraw.Draw(lyr)
    w = 1.6
    for x1, y1, x2, y2 in [(54, 32, 54, 76), (32, 54, 76, 54), (38, 38, 70, 70), (70, 38, 38, 70)]:
        d.line([p(x1), p(y1), p(x2), p(y2)], fill=CYAN + (255,), width=round(p(w)))
        for x, y in ((x1, y1), (x2, y2)):
            rr = p(w / 2)
            d.ellipse([p(x) - rr, p(y) - rr, p(x) + rr, p(y) + rr], fill=CYAN + (255,))
    img.alpha_composite(with_alpha(lyr, 0.35))

    # Lightning bolt: white fill + 1-unit white stroke with round joins.
    bolt = [(57, 34), (43, 58), (51, 58), (49, 74), (65, 50), (57, 50)]
    pts = [(p(x), p(y)) for x, y in bolt]
    lyr = layer()
    d = ImageDraw.Draw(lyr)
    d.polygon(pts, fill=WHITE + (255,))
    d.line(pts + [pts[0]], fill=WHITE + (255,), width=round(p(1)), joint="curve")
    for x, y in pts:
        rr = p(0.5)
        d.ellipse([x - rr, y - rr, x + rr, y + rr], fill=WHITE + (255,))
    img.alpha_composite(lyr)

    # Axle dot: r=3.
    lyr = layer()
    c, rr = p(54), p(3)
    ImageDraw.Draw(lyr).ellipse([c - rr, c - rr, c + rr, c + rr], fill=CYAN + (255,))
    img.alpha_composite(lyr)

    # Play requires a 32-bit PNG; keep it opaque (full-bleed square).
    img.resize((FINAL, FINAL), Image.LANCZOS).save(OUT, "PNG")


if __name__ == "__main__":
    main()
