#!/usr/bin/env python3
"""Replace Instagram's launcher icon with the official HMX Insta artwork.

Instagram ships one adaptive icon: `mipmap/ig_launcher` points at an
`<adaptive-icon>` XML that references `mipmap/ig_launcher_foreground` and
`mipmap/ig_launcher_background` (PNG per density) plus
`drawable/ig_launcher_monochrome` (a vector). The APK's file names are
obfuscated, so the paths are resolved from the resource table with aapt2
instead of being guessed.

The artwork is generated from the official logo by
scripts/branding/generate_launcher_icons.py; this script only places it:

  * the foreground layer gets the logo, scaled to the layer's own pixel size so
    the geometry Instagram shipped is preserved exactly;
  * the background layer gets a flat deep dark.

The monochrome (themed icon) layer is a vector, and a vector cannot be dropped
into an APK without the resource compiler. The branding patch replaces it
instead, where the XML is written as text and compiled by the patcher.

Usage:
  apply_launcher_branding.py <apk> <branding-assets-dir> [--dry-run]
"""
import os
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import pngtool

FOREGROUND_RESOURCE = "mipmap/ig_launcher_foreground"
BACKGROUND_RESOURCE = "mipmap/ig_launcher_background"

DENSITY_ORDER = ("xxxhdpi", "xxhdpi", "xhdpi", "hdpi", "mdpi")


def find_aapt2():
    if shutil.which("aapt2"):
        return shutil.which("aapt2")
    android_home = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if android_home:
        tools = os.path.join(android_home, "build-tools")
        if os.path.isdir(tools):
            for entry in sorted(os.listdir(tools), reverse=True):
                candidate = os.path.join(tools, entry, "aapt2")
                if os.path.isfile(candidate):
                    return candidate
    raise SystemExit("ERROR: aapt2 not found (install the Android build-tools or set ANDROID_HOME)")


def dump_resources(aapt2, apk):
    """Return {resource name: {density: (path, type)}} from aapt2's dump."""
    result = subprocess.run([aapt2, "dump", "resources", apk],
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False)
    if result.returncode != 0:
        raise SystemExit(f"ERROR: aapt2 dump resources failed: {result.stderr.decode(errors='replace')[:400]}")
    text = result.stdout.decode("utf-8", "replace")

    resources = {}
    current = None
    for line in text.splitlines():
        header = re.match(r"\s*resource 0x[0-9a-f]+ (\S+)$", line)
        if header:
            current = header.group(1)
            resources.setdefault(current, {})
            continue
        entry = re.match(r"\s*\(([^)]+)\) \(file\) (\S+) type=(\S+)$", line)
        if entry and current:
            density, path, kind = entry.group(1), entry.group(2), entry.group(3)
            resources[current][density] = (path, kind)
    return resources


def png_size(data):
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        return None
    return struct.unpack(">II", data[16:24])


def pick_asset(assets_dir, prefix, density, target_size):
    """Load the generated asset for a density, scaled to the target size."""
    path = os.path.join(assets_dir, f"{prefix}-{density}.png")
    if not os.path.isfile(path):
        return None
    width, height, pixels = pngtool.read_png(path)
    if target_size and (width, height) != target_size:
        pixels = pngtool.resize(pixels, width, height, target_size[0], target_size[1])
    return pixels, width, height


def main():
    if len(sys.argv) < 3:
        print(__doc__, file=sys.stderr)
        return 2
    apk = sys.argv[1]
    assets_dir = sys.argv[2]
    dry_run = "--dry-run" in sys.argv[3:]

    aapt2 = find_aapt2()
    resources = dump_resources(aapt2, apk)

    plan = []
    for resource_name, prefix in ((FOREGROUND_RESOURCE, "foreground"),
                                   (BACKGROUND_RESOURCE, "background")):
        entries = resources.get(resource_name)
        if not entries:
            raise SystemExit(f"ERROR: {resource_name} is not in the resource table; the launcher icon cannot be rebranded")
        for density, (path, kind) in sorted(entries.items()):
            if kind != "PNG":
                raise SystemExit(f"ERROR: {resource_name} ({density}) is {kind}, expected PNG")
            plan.append((density, path, prefix))

    print(f"launcher icon plan for {os.path.basename(apk)}:")
    for density, path, prefix in plan:
        print(f"  {prefix:11} {density:8} -> {path}")
    if dry_run:
        return 0

    with zipfile.ZipFile(apk) as archive:
        entries = archive.infolist()
        contents = {info.filename: archive.read(info.filename) for info in entries}

    replacements = {}
    with tempfile.TemporaryDirectory() as workdir:
        for density, path, prefix in plan:
            original = contents.get(path)
            if original is None:
                raise SystemExit(f"ERROR: {path} is not in the APK")
            size = png_size(original)
            asset = pick_asset(assets_dir, prefix, density, size)
            if asset is None:
                fallback = next((d for d in DENSITY_ORDER if os.path.isfile(
                    os.path.join(assets_dir, f"{prefix}-{d}.png"))), None)
                if fallback is None:
                    raise SystemExit(f"ERROR: no generated {prefix} asset for {density}")
                asset = pick_asset(assets_dir, prefix, fallback, size)
            pixels, width, height = asset
            out = os.path.join(workdir, os.path.basename(path))
            pngtool.write_png(out, width, height, pixels, rgb=(prefix == "background"))
            with open(out, "rb") as handle:
                replacements[path] = handle.read()

    temp_apk = apk + ".branding.tmp"
    with zipfile.ZipFile(apk) as source, zipfile.ZipFile(temp_apk, "w", zipfile.ZIP_DEFLATED) as target:
        for info in source.infolist():
            data = replacements.get(info.filename, source.read(info.filename))
            target.writestr(info, data)
    os.replace(temp_apk, apk)

    print(f"replaced {len(replacements)} launcher icon file(s)")
    for path in sorted(replacements):
        print(f"  {path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
