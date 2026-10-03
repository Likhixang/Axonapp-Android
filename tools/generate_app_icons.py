#!/usr/bin/env python3
"""Generate Android launcher resources from the exact Axonapp-iOS light icon.

Requires Pillow. Run from any directory; no font, redraw, or network download.
The adaptive foreground fits the Android 66dp safe zone; the background is
plain white, matching iOS and never the application's configurable accent.
"""
from pathlib import Path
import argparse
import hashlib
import json
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "tools/assets/axonapp-ios-icon.png"
RES = ROOT / "app/src/main/res"


def images():
    source = Image.open(SOURCE).convert("RGB")
    # Extract the existing artwork without retaining the source's square white
    # margins. A white foreground canvas prevents transparency/JPEG edge noise.
    from typing import Any, cast
    pixels = cast(Any, source.load())
    points = [(x, y) for y in range(source.height) for x in range(source.width)
              if min(pixels[x, y]) < 200]
    if not points:
        raise ValueError("Source icon has no artwork")
    box = (min(x for x, _ in points), min(y for _, y in points),
           max(x for x, _ in points) + 1, max(y for _, y in points) + 1)
    art = source.crop(box)
    foreground = Image.new("RGB", (432, 432), "white")
    art.thumbnail((256, 256), Image.Resampling.LANCZOS)
    foreground.paste(art, ((432 - art.width) // 2, (432 - art.height) // 2))
    legacy = source.resize((192, 192), Image.Resampling.LANCZOS)
    return source, foreground, legacy


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    source, foreground, legacy = images()
    targets = {
        RES / "drawable-nodpi/axonhub_logo.png": source,
        RES / "drawable-nodpi/axonapp_launcher_foreground.png": foreground,
        RES / "mipmap-xxxhdpi/ic_launcher.png": legacy,
        RES / "mipmap-xxxhdpi/ic_launcher_round.png": legacy,
    }
    for path, image in targets.items():
        if args.check:
            current = Image.open(path).convert("RGB")
            if current.size != image.size or current.tobytes() != image.tobytes():
                raise SystemExit(f"Icon resource out of sync: {path}")
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            image.save(path)
    print(json.dumps({"sourceSha256": hashlib.sha256(SOURCE.read_bytes()).hexdigest(),
                      "background": "#FFFFFF", "resources": len(targets),
                      "safeZoneDp": 64, "verified": args.check}))


if __name__ == "__main__":
    main()
