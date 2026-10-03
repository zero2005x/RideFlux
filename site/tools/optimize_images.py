#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Regenerate the website's raster assets from the Play Store artwork.

Run from the repository root (needs Pillow):

    python site/tools/optimize_images.py

Reads  docs/play-store/phone/<locale>/*.png, docs/play-store/feature-graphic-<locale>.png
       and app/src/main/ic_launcher-playstore.png
Writes site/assets/{shots,og,icons}/ - the outputs are committed, so the site build
itself (site/build.py) never needs Pillow.
"""
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
STORE = ROOT / "docs" / "play-store"
OUT = ROOT / "site" / "assets"

# Play Store locale -> site asset folder. Every other site language shows the English set.
LOCALES = {"en-US": "en", "zh-TW": "zh-TW"}
SHOT_SIZE = (540, 960)      # half of the 1080x1920 originals: sharp at 2x on a 270 px column
OG_SIZE = (1200, 630)       # Open Graph / Twitter card


def save_webp(img: Image.Image, dest: Path, quality: int = 82) -> None:
    dest.parent.mkdir(parents=True, exist_ok=True)
    img.save(dest, "WEBP", quality=quality, method=6)
    print(f"{dest.relative_to(ROOT)}  {dest.stat().st_size / 1024:.0f} KB")


def screenshots() -> None:
    for store_locale, folder in LOCALES.items():
        for src in sorted((STORE / "phone" / store_locale).glob("*.png")):
            with Image.open(src) as im:
                im = im.convert("RGB").resize(SHOT_SIZE, Image.LANCZOS)
                save_webp(im, OUT / "shots" / folder / (src.stem + ".webp"))


def og_cards() -> None:
    for store_locale, folder in LOCALES.items():
        src = STORE / f"feature-graphic-{store_locale}.png"
        with Image.open(src) as im:
            im = im.convert("RGB")
            # Cover-fit: scale to the target height, then centre-crop the width.
            scale = OG_SIZE[1] / im.height
            im = im.resize((round(im.width * scale), OG_SIZE[1]), Image.LANCZOS)
            left = (im.width - OG_SIZE[0]) // 2
            im = im.crop((left, 0, left + OG_SIZE[0], OG_SIZE[1]))
        dest = OUT / "og" / f"{folder}.jpg"
        dest.parent.mkdir(parents=True, exist_ok=True)
        im.save(dest, "JPEG", quality=86, optimize=True, progressive=True)
        print(f"{dest.relative_to(ROOT)}  {dest.stat().st_size / 1024:.0f} KB")


def icons() -> None:
    src = ROOT / "app" / "src" / "main" / "ic_launcher-playstore.png"
    with Image.open(src) as im:
        im = im.convert("RGBA")
        for name, size in (("favicon-32.png", 32), ("icon-192.png", 192),
                           ("apple-touch-icon.png", 180), ("icon-512.png", 512)):
            dest = OUT / "icons" / name
            dest.parent.mkdir(parents=True, exist_ok=True)
            im.resize((size, size), Image.LANCZOS).save(dest, "PNG", optimize=True)
            print(f"{dest.relative_to(ROOT)}  {dest.stat().st_size / 1024:.0f} KB")


if __name__ == "__main__":
    screenshots()
    og_cards()
    icons()
