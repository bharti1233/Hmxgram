#!/usr/bin/env python3
"""Check the downloader's capture hook in a patched APK at the dex level.

The hook is inserted where Instagram's parser has just put the parsed media
into a register. The call takes that register and returns the media unchanged,
and a `move-result-object` puts it back.

Two things are wrong when the hook is misplaced, and both break the app on
Android 11+:

  * the hook lands *before* Instagram's own `move-result-object`, so that
    move-result consumes the capture call's result instead of the parser's —
    a move-result on a void call is invalid dex, the class fails verification
    and every media parse throws (the app then sits on a spinner whenever the
    network is on);
  * the capture call is not followed by the restore, so the register that held
    the media is lost.

The check reads the disassembly of every dex that references the registry and
asserts that each capture call returns an object and is followed by the
restore into the same register.

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

REGISTRY = b"Lcom/feurstagram/extension/downloader/MediaRegistry;"
# dexdump prints methods in smali form: "Lcom/…/MediaRegistry;.capture:(Ljava/lang/Object;)Ljava/lang/Object;"
CAPTURE_CALL = re.compile(r"MediaRegistry[.;](?:->|\.)capture:\(Ljava/lang/Object;\)(Ljava/lang/Object;|V)")
OBJECT_RETURN = "(Ljava/lang/Object;)Ljava/lang/Object;"
INVOKE = re.compile(r"invoke-static(?:/range)?\s*\{[^}]*?v(\d+)")
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


def is_instruction(line):
    return "|" in line and ":" in line.split("|")[0]


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
            candidates = []
            for name in names:
                data = archive.read(name)
                if REGISTRY in data:
                    path = os.path.join(workdir, os.path.basename(name))
                    with open(path, "wb") as handle:
                        handle.write(data)
                    candidates.append(path)
            if not candidates:
                print("ERROR: the downloader capture hook is not in the APK at all", file=sys.stderr)
                return 1

            print(f"checking {len(candidates)} dex file(s) that reference the capture hook")
            checked = 0
            broken = []
            for path in candidates:
                process = subprocess.Popen([dexdump, "-d", path], stdout=subprocess.PIPE,
                                           stderr=subprocess.DEVNULL, text=True,
                                           errors="replace")
                pending = None
                previous = None
                while True:
                    line = process.stdout.readline()
                    if not line:
                        break
                    if not is_instruction(line):
                        # A method boundary: the restore never came.
                        if pending is not None:
                            broken.append((pending, "no restore instruction follows the capture call"))
                            pending = None
                        continue
                    if pending is not None:
                        move = MOVE.search(line)
                        if move and pending[1] is not None and move.group(1) == pending[1]:
                            pending = None
                        else:
                            broken.append((pending, "the next instruction is: " + line.strip()))
                            pending = None
                    call = CAPTURE_CALL.search(line)
                    if call:
                        checked += 1
                        invoke = INVOKE.search(line)
                        register = invoke.group(1) if invoke else None
                        returns_object = call.group(1) == OBJECT_RETURN
                        after_move = bool(previous and MOVE.search(previous))
                        reasons = []
                        if not returns_object:
                            reasons.append("the capture call must return the media, not void")
                        if not after_move:
                            reasons.append(
                                "the capture call must come after Instagram's move-result-object, "
                                "otherwise that move-result consumes the capture's result")
                        if reasons:
                            broken.append(((line, register, returns_object), "; ".join(reasons)))
                        else:
                            pending = (line, register, returns_object)
                    previous = line
                process.wait()

    if checked == 0:
        print("ERROR: no capture call found in the dex files", file=sys.stderr)
        return 1
    for (call_line, register, returns_object), reason in broken:
        print("ERROR: the capture hook is broken", file=sys.stderr)
        print("  call:   " + call_line.strip(), file=sys.stderr)
        print("  reason: " + reason, file=sys.stderr)
        if not returns_object:
            print("  the capture call must return the media, not void", file=sys.stderr)
    if broken:
        print(f"ERROR: {len(broken)} of {checked} capture call(s) are broken", file=sys.stderr)
        return 1
    print(f"ok: all {checked} capture call(s) return the media and put it back")
    return 0


if __name__ == "__main__":
    sys.exit(main())
