# Copyright (C) 2026 RideFlux project contributors.
# SPDX-License-Identifier: GPL-3.0-or-later
"""Render the RideFlux Play Store feature graphic (1024x500, opaque 24-bit PNG).

Usage (from the repository root, needs Pillow and the Windows fonts
Bahnschrift, Segoe UI and Microsoft JhengHei):
    python docs/play-store/tools/feature_graphic.py docs/play-store/feature-graphic-zh-TW.png zh-TW
    python docs/play-store/tools/feature_graphic.py docs/play-store/feature-graphic-en-US.png en-US

The wheel is drawn from the same 108x108 viewport geometry as
app/src/main/res/drawable/ic_launcher_foreground.xml. Copy lives in COPY below and
should stay in step with the store listing text.
"""
import math
import sys

from PIL import Image, ImageDraw, ImageFilter, ImageFont

OUT, LANG = sys.argv[1], sys.argv[2]
W, H = 1024, 500
SS = 2  # supersampling factor
CW, CH = W * SS, H * SS

CYAN = (0x00, 0xE5, 0xFF)
WHITE = (0xFF, 0xFF, 0xFF)
NAVY = (0x0F, 0x1A, 0x2E)

COPY = {
    "zh-TW": {
        "tagline": "電動獨輪車即時儀表板",
        "features": "速度・電量・溫度・PWM 警示・行程記錄・AR HUD",
        "cjk": True,
    },
    "en-US": {
        "tagline": "Live dashboard for electric unicycles",
        "features": "Speed · Battery · Temperature · PWM alerts · Trips · AR HUD",
        "cjk": False,
    },
}[LANG]

FONTS = "C:/Windows/Fonts/"


def font(name, size, variation=None):
    f = ImageFont.truetype(FONTS + name, size * SS)
    if variation:
        try:
            f.set_variation_by_name(variation)
        except (OSError, ValueError):
            pass
    return f


def layer():
    return Image.new("RGBA", (CW, CH), (0, 0, 0, 0))


def with_alpha(img, alpha):
    r, g, b, a = img.split()
    return Image.merge("RGBA", (r, g, b, a.point(lambda v: round(v * alpha))))


def background(cx, cy):
    # Radial wash from navy at the wheel to black, computed small then upscaled.
    sw, sh = W // 4, H // 4
    img = Image.new("RGB", (sw, sh))
    px = img.load()
    radius = 0.75 * W / 4
    for y in range(sh):
        for x in range(sw):
            t = min(1.0, math.hypot(x - cx / 4, y - cy / 4) / radius)
            t = t * t * (3 - 2 * t)  # smoothstep for a softer falloff
            px[x, y] = tuple(round(c * (1 - t)) for c in NAVY)
    return img.resize((CW, CH), Image.BICUBIC).convert("RGBA")


def draw_wheel(canvas, cx, cy, size):
    """Draw the launcher foreground (no background) centred at (cx, cy), `size` px = 108 units."""
    k = size / 108.0

    def p(x, y):
        return cx + (x - 54) * k, cy + (y - 54) * k

    def ring(d, r, width, color):
        ro, ri = (r + width / 2) * k, (r - width / 2) * k
        d.ellipse([cx - ro, cy - ro, cx + ro, cy + ro], fill=color + (255,))
        d.ellipse([cx - ri, cy - ri, cx + ri, cy + ri], fill=(0, 0, 0, 0))

    # Glow behind the tire.
    glow = layer()
    ring(ImageDraw.Draw(glow), 34, 10, CYAN)
    glow = glow.filter(ImageFilter.GaussianBlur(18 * SS))
    canvas.alpha_composite(with_alpha(glow, 0.55))

    lyr = layer()
    ring(ImageDraw.Draw(lyr), 34, 7, CYAN)
    canvas.alpha_composite(lyr)

    lyr = layer()
    ring(ImageDraw.Draw(lyr), 24, 2, CYAN)
    canvas.alpha_composite(with_alpha(lyr, 0.6))

    lyr = layer()
    d = ImageDraw.Draw(lyr)
    w = 1.6 * k
    for x1, y1, x2, y2 in [(54, 32, 54, 76), (32, 54, 76, 54), (38, 38, 70, 70), (70, 38, 38, 70)]:
        a, b = p(x1, y1), p(x2, y2)
        d.line([a, b], fill=CYAN + (255,), width=round(w))
        for x, y in (a, b):
            d.ellipse([x - w / 2, y - w / 2, x + w / 2, y + w / 2], fill=CYAN + (255,))
    canvas.alpha_composite(with_alpha(lyr, 0.35))

    pts = [p(x, y) for x, y in [(57, 34), (43, 58), (51, 58), (49, 74), (65, 50), (57, 50)]]
    lyr = layer()
    d = ImageDraw.Draw(lyr)
    d.polygon(pts, fill=WHITE + (255,))
    d.line(pts + [pts[0]], fill=WHITE + (255,), width=max(1, round(k)), joint="curve")
    canvas.alpha_composite(lyr)

    lyr = layer()
    r = 3 * k
    ImageDraw.Draw(lyr).ellipse([cx - r, cy - r, cx + r, cy + r], fill=CYAN + (255,))
    canvas.alpha_composite(lyr)


def motion_streaks(canvas, wheel_left, cy):
    # Faint horizontal streaks trailing the wheel to suggest speed.
    lyr = layer()
    d = ImageDraw.Draw(lyr)
    streaks = [(-70, 150, 0.30), (-38, 230, 0.45), (-6, 110, 0.25), (26, 200, 0.40), (58, 140, 0.28)]
    for dy, length, alpha in streaks:
        y = cy + dy * SS
        x_end = wheel_left - 18 * SS
        x_start = x_end - length * SS
        for i in range(24):  # fade in from the left
            seg0 = x_start + (x_end - x_start) * i / 24
            seg1 = x_start + (x_end - x_start) * (i + 1) / 24
            a = round(255 * alpha * (i + 1) / 24)
            d.line([(seg0, y), (seg1, y)], fill=CYAN + (a,), width=3 * SS)
    canvas.alpha_composite(lyr)


def fit(text, name, size, max_w, variation=None):
    """Largest font at or below `size` whose rendered `text` fits within `max_w` canvas px."""
    probe = ImageDraw.Draw(Image.new("RGBA", (1, 1)))
    while True:
        f = font(name, size, variation)
        box = probe.textbbox((0, 0), text, font=f)
        if box[2] - box[0] <= max_w or size <= 12:
            return f, box
        size -= 1


def main():
    margin = 56 * SS
    wheel_size = 330 * SS
    wheel_vis = wheel_size * 2 * 37.5 / 108  # outer edge of the tire stroke
    gap = 64 * SS
    max_text_w = CW - 2 * margin - wheel_vis - gap

    title, title_box = fit("RideFlux", "bahnschrift.ttf", 104, max_text_w, "Bold")
    tag, tag_box = fit(COPY["tagline"], "msjhbd.ttc" if COPY["cjk"] else "seguisb.ttf",
                       38 if COPY["cjk"] else 34, max_text_w)
    feat, feat_box = fit(COPY["features"], "msjh.ttc" if COPY["cjk"] else "segoeui.ttf",
                         22 if COPY["cjk"] else 21, max_text_w)
    text_w = max(b[2] - b[0] for b in (title_box, tag_box, feat_box))

    # Centre the wheel + text composition horizontally.
    left = (CW - (wheel_vis + gap + text_w)) / 2
    wheel_cx, wheel_cy = left + wheel_vis / 2, CH // 2
    text_x = left + wheel_vis + gap

    canvas = background(wheel_cx / SS, wheel_cy / SS)  # args in final px
    motion_streaks(canvas, wheel_cx - wheel_size * 34 / 108, wheel_cy)
    draw_wheel(canvas, wheel_cx, wheel_cy, wheel_size)
    d = ImageDraw.Draw(canvas)
    gap1, gap2 = 22 * SS, 28 * SS
    rule_h = 4 * SS
    block_h = (title_box[3] - title_box[1]) + gap1 + (tag_box[3] - tag_box[1]) + gap2 + rule_h + gap2 + (feat_box[3] - feat_box[1])
    y = (CH - block_h) // 2

    d.text((text_x, y - title_box[1]), "RideFlux", font=title, fill=WHITE)
    y += title_box[3] - title_box[1] + gap1
    d.text((text_x, y - tag_box[1]), COPY["tagline"], font=tag, fill=(0x9E, 0xF3, 0xFF))
    y += tag_box[3] - tag_box[1] + gap2
    d.rounded_rectangle([text_x, y, text_x + 72 * SS, y + rule_h], radius=rule_h // 2, fill=CYAN)
    y += rule_h + gap2
    d.text((text_x, y - feat_box[1]), COPY["features"], font=feat, fill=(0xB8, 0xC4, 0xD6))

    print(f"{LANG}: wheel_cx={wheel_cx / SS:.0f} text_x={text_x / SS:.0f} "
          f"right={(text_x + text_w) / SS:.0f} tag={tag.size // SS}px feat={feat.size // SS}px")

    canvas.convert("RGB").resize((W, H), Image.LANCZOS).save(OUT, "PNG")


if __name__ == "__main__":
    main()
