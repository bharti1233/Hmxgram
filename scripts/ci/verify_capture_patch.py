#!/usr/bin/env python3
"""Check the downloader's capture hook in a patched APK at the dex level.

The hook is inserted right after Instagram's media parser produced the model.
The call itself would destroy the register that holds the model, so the patch
has to put the model straight back with a `move-result-object` into the same
register. When it does not, Instagram continues with a broken model: the feed
never loads and the app sits on a spinner (that is the regression this check
exists for).

The check looks at the disassembly of every dex that references the registry
and asserts that each capture call is followed by the restore.

Usage:
  verify_capture_patch.py <apk>
"""
import os
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile

REGISTRY = "Lcom/feurstagram/extension/downloader/MediaRegistry;"
CAPTURE = REGISTRY + "->capture(Ljava/lang/Object;)Ljava/lang/Object;"
INVOKE = re.compile(r"invoke-static\s*\{v(\d+)\}")
MOVE = re.compile(r"move-result-object\s+v(\d+)")


def find_dexdump():
    if shutil.which("dexdump"):
        return shutil.which("dexdump")
    android_home = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if android_home:
        tools = os.path.join(android_home, "build-tools")
        if os.path.isdir(tools):
            for entry in sorted(os.listdir(tools), reverse=True):
                candidate = os.path.join(tools, entry, "dexdump")
                if os.path.isfile(candidate):
                    return candidate
    return None


def main():
    if len(sys.argv) != 2:
        print(__doc__, file=sys.stderr)
        return 2
    apk = sys.argv[1]
    if not os.path.isfile(apk):
        print(f"ERROR: no such APK: {apk}", file=sys.stderr)
        return 1

    dexdump = find_dexdump()
    if dexdump is None:
        print("NOTICE: dexdump is not available; the capture hook was not checked")
        return 0

    with tempfile.TemporaryDirectory() as workdir:
        with zipfile.ZipFile(apk) as archive:
            names = [n for n in archive.namelist() if n.endswith(".dex")]
            if not names:
                print("ERROR: the APK has no dex files", file=sys.stderr)
                return 1
            # Only the dex files that reference the registry are worth dumping.
            candidates = []
            for name in names:
                data = archive.read(name)
                if REGISTRY.encode() in data:
                    path = os.path.join(workdir, os.path.basename(name))
                    with open(path, "wb") as handle:
                        handle.write(data)
                    candidates.append(path)
            if not candidates:
                print("ERROR: the downloader capture hook is not in the APK at all", file=sys.stderr)
                return 1

            print(f"checking {len(candidates)} dex file(s) that reference the capture hook")
            checked = 0
            broken = 0
            for path in candidates:
                process = subprocess.Popen([dexdump, "-d", path], stdout=subprocess.PIPE,
                                           stderr=subprocess.DEVNULL, text=True,
                                           errors="replace")
                while True:
                    line = process.stdout.readline()
                    if not line:
                        break
                    if CAPTURE not in line:
                        continue
                    checked += 1
                    match = INVOKE.search(line)
                    register = match.group(1) if match else None
                    # The next disassembly line is the instruction after the call.
                    following = process.stdout.readline()
                    restored = False
                    if following:
                        move = MOVE.search(following)
                        restored = bool(move and register and move.group(1) == register)
                    if not restored:
                        broken += 1
                        print("ERROR: the capture call is not followed by the restore")
                        print("  call:     " + line.strip())
                        if following:
                            print("  next:     " + following.strip())
                process.wait()

    if checked == 0:
        print("ERROR: no capture call found in the dex files", file=sys.stderr)
        return 1
    if broken:
        print(f"ERROR: {broken} of {checked} capture call(s) destroy the parsed media", file=sys.stderr)
        return 1
    print(f"ok: all {checked} capture call(s) put the parsed media back")
    return 0


if __name__ == "__main__":
    sys.exit(main())
