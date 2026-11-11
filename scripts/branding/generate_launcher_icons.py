#!/usr/bin/env python3
"""Generate the HMX Insta launcher icon resources from the official logo.

Input:  "hmx insta.png" (the official HMX INSTA logo, never modified)
Output: the adaptive-icon layers consumed by the branding patch, plus the
        legacy square icons kept for reference.

The logo is a rounded-square gradient badge on a white field. Android adaptive
icons are masked to a circle inscribed in the middle 72/108 of the layer, so the
badge is scaled to 62% of the canvas and centred: that keeps the whole wordmark
inside the mask while leaving the badge's own rounded corners recognisable.
The white field outside the badge becomes transparent, the background layer is a
flat deep dark, and the original file is left untouched.

Everything here is deterministic: the same logo always produces byte-identical
assets, which is what scripts/ci/validate_branding.sh checks.
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import pngtool

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
# The official logo, as placed in the repository. The brief names it
# "hmx insta.png"; the file in the repository is "hm insta.png". Both are
# accepted, the first existing one wins, and the file is never modified.
LOGO_NAMES = ("hm insta.png", "hmx insta.png")
LOGO = next((os.path.join(REPO_ROOT, name) for name in LOGO_NAMES
             if os.path.isfile(os.path.join(REPO_ROOT, name))), None)
PATCH_RESOURCES = os.path.join(REPO_ROOT, "patches", "src", "main", "resources", "branding")
LEGACY_DIR = os.path.join(REPO_ROOT, "branding", "legacy")

# Adaptive icon layers are 108dp; the badge keeps the artwork recognisable.
CANVAS = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}
LEGACY = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}

BADGE_SCALE = 0.62
BACKGROUND = (0x0B, 0x0E, 0x1A)  # deep dark
# A pixel counts as the white field around the badge when it is bright and
# unsaturated. The anti-aliased corner of the logo sits at (241,245,246), so
# the threshold has to be below that.
WHITE_FIELD_MIN = 235
WHITE_FIELD_SPREAD = 16

MONOCHROME_VECTOR = """<?xml version="1.0" encoding="utf-8"?>
<!-- Themed-icon silhouette for the HMX Insta launcher icon. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:fillColor="#FFFFFFFF"
        android:pathData="M38,30h9v20h14v-20h9v48h-9v-20h-14v20h-9z" />
</vector>
"""


def badge_alpha(width, height, pixels):
    """Alpha mask that keeps the badge and drops the white field around it."""
    alpha_rows = []
    for y in range(height):
        row = pixels[y]
        left = None
        right = None
        for x in range(width):
            i = x * 4
            red, green, blue = row[i], row[i + 1], row[i + 2]
            if (red >= WHITE_FIELD_MIN and green >= WHITE_FIELD_MIN and blue >= WHITE_FIELD_MIN
                    and max(red, green, blue) - min(red, green, blue) <= WHITE_FIELD_SPREAD):
                continue
            if left is None:
                left = x
            right = x
        if left is None:
            alpha_rows.append(None)
            continue
        # One pixel of dilation keeps the anti-aliased fringe of the badge.
        left = max(0, left - 1)
        right = min(width - 1, right + 1)
        alpha_rows.append((left, right))

    masked = []
    for y in range(height):
        row = bytearray(pixels[y])
        span = alpha_rows[y]
        if span is None:
            for x in range(width):
                row[x * 4 + 3] = 0
            masked.append(row)
            continue
        left, right = span
        for x in range(width):
            if x < left or x > right:
                row[x * 4 + 3] = 0
        masked.append(row)
    return masked


def build_foreground(source, source_w, source_h, canvas):
    badge = int(round(canvas * BADGE_SCALE))
    scaled = pngtool.resize(source, source_w, source_h, badge, badge)
    transparent = [bytearray(canvas * 4) for _ in range(canvas)]
    offset = (canvas - badge) // 2
    return pngtool.paste(transparent, canvas, canvas, scaled, badge, badge, offset, offset)


def main():
    if not os.path.isfile(LOGO):
        print(f"ERROR: the official logo is missing: {LOGO}", file=sys.stderr)
        return 1

    width, height, pixels = pngtool.read_png(LOGO)
    print(f"logo: {os.path.relpath(LOGO, REPO_ROOT)} ({width}x{height})")
    masked = badge_alpha(width, height, pixels)

    os.makedirs(PATCH_RESOURCES, exist_ok=True)
    os.makedirs(LEGACY_DIR, exist_ok=True)

    for density, canvas in CANVAS.items():
        foreground = build_foreground(masked, width, height, canvas)
        foreground_path = os.path.join(PATCH_RESOURCES, f"foreground-{density}.png")
        pngtool.write_png(foreground_path, canvas, canvas, foreground)

        background = pngtool.solid(canvas, canvas, BACKGROUND)
        background_path = os.path.join(PATCH_RESOURCES, f"background-{density}.png")
        pngtool.write_png(background_path, canvas, canvas, background, rgb=True)

        print(f"  {density}: foreground {canvas}x{canvas}, background {canvas}x{canvas}")

    for density, size in LEGACY.items():
        badge = pngtool.resize(masked, width, height, size, size)
        legacy_path = os.path.join(LEGACY_DIR, f"ic_launcher-{density}.png")
        pngtool.write_png(legacy_path, size, size, badge)
        print(f"  {density}: legacy {size}x{size}")

    with open(os.path.join(PATCH_RESOURCES, "ig_launcher_monochrome.xml"), "w", encoding="utf-8") as handle:
        handle.write(MONOCHROME_VECTOR)
    print("  monochrome vector written")
    return 0


if __name__ == "__main__":
    sys.exit(main())
