#!/usr/bin/env python3
"""Fail a build when a protected Instagram surface changes.

The baseline is a frozen, signed APK. The candidate is the APK produced by the
current build. Protected surfaces are resolved in both APKs using stable
anchors from ``scripts/ci/protected_methods.json`` (descriptors, string sets or
regexes), never hardcoded obfuscated method names. Each resolved class is
canonicalised with ``dexdump -d`` and compared.

Canonicalisation keeps:

  * class descriptor, access flags, superclass and interfaces;
  * every field (name, type, access, static value);
  * every method (name, type, access, registers/ins/outs);
  * every instruction mnemonic, register, literal and resolved reference;
  * catch ranges mapped to method-relative instruction ordinals and catch types.

It drops only:

  * dex indices in ``// method@...``-style comments;
  * instruction byte offsets and code-unit addresses;
  * ``positions`` (line numbers) and ``locals`` (debug info);
  * class/field/method table indices.

Usage:
  verify_protected_methods.py --manifest M --baseline A.apk --candidate B.apk
  verify_protected_methods.py --self-test
"""
import argparse
import difflib
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile

CLASS_START_RE = re.compile(r"^\s*Class #\d+")
CLASS_DESC_RE = re.compile(r"^  Class descriptor  : '(.*)'\s*$")
INSTR_RE = re.compile(r"^([0-9a-f]+): [0-9a-f ]+\|([0-9a-f]{4}): (.*)$")
CATCH_RANGE_RE = re.compile(r"^\s*(0x[0-9a-f]+) - (0x[0-9a-f]+)\s*$")
HANDLER_RE = re.compile(r"^(\s*)handlers\s+: (0x[0-9a-f]+)\s*$")
# A method or field header carries the byte address it lives at. The address is
# layout: rebuilding a dex file moves every method in it, even the ones nobody
# touched. The name and the descriptor are behaviour and stay compared.
ADDRESSED_HEADER_RE = re.compile(r"^[0-9a-f]+:\s+\|\[[0-9a-f]+\]\s+(.*)$")
# The source file index is the position of the original file in the dex string
# table. It moves when the table is rebuilt; the file name itself is stable.
SOURCE_FILE_RE = re.compile(r"^(\s*)source_file_idx\s+:\s+\d+\s+\((.*)\)\s*$")
# A data block (a switch payload, for example) is printed with the address it
# lives at, followed by its bytes and its description. The bytes and the
# description are behaviour; the address is layout.
DATA_BLOCK_RE = re.compile(
    r"^[0-9a-f]+:\s+([0-9a-f ]+?)\s*\.\.\.\s+\|([0-9a-f]{4}):\s+(.*)$"
)
DEBUG_HEADER_RE = re.compile(r"^\s*(positions|locals)\s*:")
NEXT_AFTER_DEBUG_RE = re.compile(
    r"^\s{6}(catches|name|type|access|code|registers|ins|outs|insns size)\s*:"
    r"|^\s{4}#\d+\s+:"
    r"|^\s{2}(Class descriptor|Access flags|Superclass|Interfaces|Static fields|"
    r"Instance fields|Direct methods|Virtual methods)\b"
)


def sha256_file(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _ordinal_lookup(address_to_ordinal):
    addresses = sorted(address_to_ordinal)

    def ordinal_for(address):
        if address in address_to_ordinal:
            return address_to_ordinal[address]
        later = [candidate for candidate in addresses if candidate >= address]
        return address_to_ordinal[later[0]] if later else "?"

    return ordinal_for


def normalize_class(lines):
    """Return the canonical instruction/field/method lines for one class."""
    address_to_ordinal = {}
    for line in lines:
        match = INSTR_RE.match(line)
        if match:
            address_to_ordinal[int(match.group(1), 16)] = match.group(2)
    ordinal_for = _ordinal_lookup(address_to_ordinal)

    out = []
    skipping_debug = False
    for raw in lines:
        line = raw.rstrip("\n")
        if CLASS_START_RE.match(line):
            continue
        if DEBUG_HEADER_RE.match(line):
            skipping_debug = True
            continue
        if skipping_debug:
            if NEXT_AFTER_DEBUG_RE.match(line) or CLASS_START_RE.match(line):
                skipping_debug = False
            else:
                continue

        match = INSTR_RE.match(line)
        if match:
            instruction = match.group(3).split(" // ", 1)[0].rstrip()
            out.append("instr %s: %s" % (match.group(2), instruction))
            continue

        match = CATCH_RANGE_RE.match(line)
        if match:
            start = ordinal_for(int(match.group(1), 16))
            end = ordinal_for(int(match.group(2), 16))
            out.append("catch-range %s-%s" % (start, end))
            continue

        match = HANDLER_RE.match(line)
        if match:
            out.append("handlers %s" % ordinal_for(int(match.group(2), 16)))
            continue

        match = ADDRESSED_HEADER_RE.match(line)
        if match:
            out.append("header: %s" % match.group(1))
            continue

        match = SOURCE_FILE_RE.match(line)
        if match:
            out.append("%ssource_file: %s" % (match.group(1), match.group(2)))
            continue

        match = DATA_BLOCK_RE.match(line)
        if match:
            # The payload bytes, the code unit it belongs to and the
            # description are all compared; only the address is dropped.
            out.append("data %s: %s %s" % (
                match.group(1).strip(), match.group(2), match.group(3)))
            continue

        # Table indices are layout, not behaviour.
        line = re.sub(r"^(\s*)#\d+(\s+):", r"\1#\2:", line)
        out.append(line)

    return out


def scan_dex_multi(dexdump, dex_path, specs):
    """Stream one dex once and return {surface_id: {descriptor: lines}}."""
    process = subprocess.Popen(
        [dexdump, "-d", dex_path],
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
        text=True,
        errors="replace",
        bufsize=1024 * 1024,
    )
    found = {}
    block = []
    descriptor = None

    def finish():
        if not block or descriptor is None:
            return
        text = None
        normalized = None
        for spec in specs:
            matched = descriptor in spec["descriptors"]
            if not matched and spec["string_sets"]:
                if text is None:
                    text = "\n".join(block)
                matched = any(
                    all(needle in text for needle in item)
                    for item in spec["string_sets"]
                )
            if not matched and spec["regex_sets"]:
                if text is None:
                    text = "\n".join(block)
                matched = any(
                    all(re.search(pattern, text) for pattern in item)
                    for item in spec["regex_sets"]
                )
            if matched:
                if normalized is None:
                    normalized = normalize_class(block)
                found.setdefault(spec["id"], {})[descriptor] = normalized

    for line in process.stdout:
        if CLASS_START_RE.match(line):
            finish()
            block = []
            descriptor = None
            continue
        match = CLASS_DESC_RE.match(line.rstrip("\n"))
        if match:
            descriptor = match.group(1)
        block.append(line)
    finish()

    returncode = process.wait()
    if returncode != 0:
        raise RuntimeError("dexdump failed for %s (exit %d)" % (dex_path, returncode))
    return found


def manifest_application_descriptor(aapt, apk):
    result = subprocess.run(
        [aapt, "dump", "xmltree", apk, "AndroidManifest.xml"],
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
        text=True,
        errors="replace",
        check=False,
    )
    if result.returncode != 0:
        raise RuntimeError("aapt could not dump AndroidManifest.xml")
    in_application = False
    for line in result.stdout.splitlines():
        if re.match(r"^\s*E: application\b", line):
            in_application = True
            continue
        if in_application:
            match = re.search(r'android:name\(0x01010003\)="([^"]+)"', line)
            if match:
                return "L%s;" % match.group(1).replace(".", "/")
            if re.match(r"^\s*E: ", line):
                break
    raise RuntimeError("no android:name on the <application> element")


class ApkIndex:
    def __init__(self, apk, dexdump, workdir):
        self.apk = apk
        self.dexdump = dexdump
        self.dir = os.path.join(workdir, os.path.basename(apk) + ".dex")
        os.makedirs(self.dir, exist_ok=True)
        with zipfile.ZipFile(apk) as archive:
            names = sorted(
                name for name in archive.namelist()
                if re.match(r"^classes\d*\.dex$", name)
            )
            if not names:
                raise RuntimeError("%s has no classes*.dex entries" % apk)
            for name in names:
                archive.extract(name, self.dir)
        self.dexes = {
            name: os.path.join(self.dir, name) for name in names
        }
        self.blobs = {
            name: open(path, "rb").read() for name, path in self.dexes.items()
        }

    def candidates(self, needles):
        wanted = [needle.encode("utf-8") for needle in needles if needle]
        if not wanted:
            return list(self.dexes)
        return [
            name for name, blob in self.blobs.items()
            if any(needle in blob for needle in wanted)
        ]

    def resolve(self, surfaces, aapt):
        app_descriptor = None
        if any(surface.get("manifest_application") for surface in surfaces):
            app_descriptor = manifest_application_descriptor(aapt, self.apk)

        specs_by_dex = {}
        results = {surface["id"]: {} for surface in surfaces}
        for surface in surfaces:
            descriptors = set(surface.get("descriptors") or ())
            if surface.get("manifest_application") and app_descriptor:
                descriptors.add(app_descriptor)
            string_sets = [tuple(item) for item in (surface.get("string_sets") or ())]
            regex_sets = [tuple(item) for item in (surface.get("regex_sets") or ())]
            candidate_strings = list(surface.get("candidate_strings") or ())
            if not (descriptors or string_sets or regex_sets):
                continue
            needles = list(descriptors) + candidate_strings
            for item in string_sets:
                needles.extend(item)
            spec = {
                "id": surface["id"],
                "descriptors": descriptors,
                "string_sets": string_sets,
                "regex_sets": regex_sets,
            }
            for dex_name in self.candidates(needles):
                specs_by_dex.setdefault(dex_name, []).append(spec)

        for dex_name in sorted(specs_by_dex):
            found = scan_dex_multi(self.dexdump, self.dexes[dex_name], specs_by_dex[dex_name])
            for surface_id, classes in found.items():
                results[surface_id].update(classes)
        return results


def compare_surface(surface_id, baseline, candidate):
    problems = []
    baseline_keys = set(baseline)
    candidate_keys = set(candidate)
    if baseline_keys != candidate_keys:
        missing = sorted(baseline_keys - candidate_keys)
        extra = sorted(candidate_keys - baseline_keys)
        if missing:
            problems.append(
                "%s: candidate is missing protected class(es): %s"
                % (surface_id, ", ".join(missing))
            )
        if extra:
            problems.append(
                "%s: candidate resolved unexpected class(es): %s"
                % (surface_id, ", ".join(extra))
            )
    for descriptor in sorted(baseline_keys & candidate_keys):
        if baseline[descriptor] != candidate[descriptor]:
            diff = list(
                difflib.unified_diff(
                    baseline[descriptor],
                    candidate[descriptor],
                    fromfile="baseline:" + descriptor,
                    tofile="candidate:" + descriptor,
                    lineterm="",
                    n=2,
                )
            )
            problems.append(
                "%s: %s changed\n%s"
                % (surface_id, descriptor, "\n".join(diff[:120]))
            )
    return problems


def self_test():
    """Exercise the canonicaliser without an APK or dexdump."""
    base = [
        "  Class #0            -\n",
        "  Class descriptor  : 'Lexample/Guard;'\n",
        "  Access flags      : 0x0001 (PUBLIC)\n",
        "  Superclass        : 'Ljava/lang/Object;'\n",
        "  Direct methods    -\n",
        "    #0              : (in Lexample/Guard;)\n",
        "      name          : 'check'\n",
        "      type          : '(I)Z'\n",
        "      access        : 0x0009 (PUBLIC STATIC)\n",
        "      code          -\n",
        "      registers     : 2\n",
        "      ins           : 1\n",
        "      outs          : 0\n",
        "      insns size    : 4 16-bit code units\n",
        "000100: 12a0                                   |0000: const/4 v0, #int 1 // #1\n",
        "000102: 2170                                   |0001: monitor-enter v0\n",
        "000104: 0e00                                   |0002: return v0\n",
        "000106: 0000                                   |0003: nop\n",
        "      catches       : 0\n",
        "      positions     :\n",
        "        0x0000 line=1\n",
        "      locals        :\n",
        "        0x0000 - 0x0004 reg=0 this Lexample/Guard;\n",
    ]
    same = list(base)
    same[0] = "  Class #9            -\n"
    same[14] = "000900: 12a0                                   |0000: const/4 v0, #int 1 // #99\n"
    same[20] = "        0x0000 line=99\n"
    changed_opcode = list(base)
    changed_opcode[14] = "000100: 12a0                                   |0000: const/4 v0, #int 2 // #2\n"
    changed_register = list(base)
    changed_register[15] = "000102: 2170                                   |0001: monitor-enter v1\n"
    changed_access = list(base)
    changed_access[8] = "      access        : 0x0001 (PUBLIC)\n"
    # A method header carries the byte address it lives at. Rebuilding a dex
    # file moves every header in it, including the ones nobody touched.
    base.append("0000a0:                                        |[0000a0] Lexample/Guard;.check:(I)Z\n")
    base.append("    source_file_idx   : 1520 (Guard.java)\n")
    moved_addresses = list(base)
    moved_addresses[23] = (
        "000900:                                        |[000900] Lexample/Guard;.check:(I)Z\n"
    )
    # The source file index is the position of the file in the dex string table.
    moved_source_index = list(base)
    moved_source_index[24] = "    source_file_idx   : 1615 (Guard.java)\n"
    # A data block (a switch payload) is printed with the address it lives
    # at, its bytes and its description.
    base.append(
        "0000b0: 0001 1000 0000 0000 900d 0000 040d ... |0010: packed-switch-data (4 units)\n"
    )
    moved_data_block = list(base)
    moved_data_block[25] = (
        "000900: 0001 1000 0000 0000 900d 0000 040d ... |0010: packed-switch-data (4 units)\n"
    )

    checks = [
        ("identical after layout/debug noise", normalize_class(base) == normalize_class(same)),
        (
            "identical after the method addresses move",
            normalize_class(base) == normalize_class(moved_addresses),
        ),
        (
            "identical after the source file index moves",
            normalize_class(base) == normalize_class(moved_source_index),
        ),
        (
            "identical after the data block address moves",
            normalize_class(base) == normalize_class(moved_data_block),
        ),
        ("literal change detected", normalize_class(base) != normalize_class(changed_opcode)),
        ("register change detected", normalize_class(base) != normalize_class(changed_register)),
        ("access change detected", normalize_class(base) != normalize_class(changed_access)),
    ]

    # The comparison layer must also fail closed on a changed or missing class,
    # independently of dexdump.
    try:
        import importlib.util

        spec = importlib.util.spec_from_file_location(
            "verify_protected_methods", __file__
        )
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        changed = module.compare_surface(
            "selftest", {"Lx/A;": ["instr 1: nop"]}, {"Lx/A;": ["instr 1: return"]}
        )
        missing = module.compare_surface("selftest", {"Lx/A;": ["nop"]}, {})
        checks.append(("changed class fails", bool(changed)))
        checks.append(("missing class fails", bool(missing)))
    except Exception as error:  # noqa: BLE001 - a self-test failure, not a pass
        print("self-test FAIL: comparison layer: %s" % error, file=sys.stderr)
        return 1
    failures = [name for name, ok in checks if not ok]
    if failures:
        for name in failures:
            print("self-test FAIL: %s" % name, file=sys.stderr)
        return 1
    print("protected-method self-test: PASS (%d checks)" % len(checks))
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest")
    parser.add_argument("--baseline")
    parser.add_argument("--candidate")
    parser.add_argument("--report")
    parser.add_argument("--dexdump", default=shutil.which("dexdump") or "dexdump")
    parser.add_argument("--aapt", default=shutil.which("aapt") or "aapt")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()

    if args.self_test:
        return self_test()
    if not (args.manifest and args.baseline and args.candidate):
        parser.error("--manifest, --baseline and --candidate are required")

    with open(args.manifest, encoding="utf-8") as handle:
        manifest = json.load(handle)

    gate = manifest.get("gate") or {}
    pinned = (manifest.get("baseline") or {}).get("apk_sha256")
    baseline_sha = sha256_file(args.baseline)
    candidate_sha = sha256_file(args.candidate)
    if pinned and baseline_sha != pinned:
        print(
            "ERROR: baseline APK SHA-256 is %s, manifest pins %s"
            % (baseline_sha, pinned),
            file=sys.stderr,
        )
        return 1

    surfaces = gate.get("surfaces") or []
    if not surfaces:
        print("ERROR: manifest has no gate.surfaces", file=sys.stderr)
        return 1

    workdir = tempfile.mkdtemp(prefix="hmx-protected-")
    problems = []
    report_surfaces = []
    try:
        baseline_index = ApkIndex(args.baseline, args.dexdump, workdir)
        candidate_index = ApkIndex(args.candidate, args.dexdump, workdir)
        try:
            baseline_all = baseline_index.resolve(surfaces, args.aapt)
            if candidate_sha == baseline_sha:
                # Same bytes: the candidate is the baseline (used by the M0
                # self-check). Reuse the result instead of scanning twice.
                candidate_all = baseline_all
            else:
                candidate_all = candidate_index.resolve(surfaces, args.aapt)
        except Exception as error:  # noqa: BLE001 - fail closed with context
            print("ERROR: protected-surface resolution failed: %s" % error, file=sys.stderr)
            return 1

        for surface in surfaces:
            surface_id = surface["id"]
            baseline_classes = baseline_all.get(surface_id, {})
            if surface.get("enforce", True):
                candidate_classes = candidate_all.get(surface_id, {})
            else:
                candidate_classes = baseline_classes

            if not baseline_classes:
                message = "%s: no baseline class matched the declared anchors" % surface_id
                if surface.get("enforce", True):
                    problems.append(message)
                    report_surfaces.append(
                        {"id": surface_id, "status": "fail", "reason": message}
                    )
                else:
                    report_surfaces.append(
                        {"id": surface_id, "status": "recorded", "reason": "no match"}
                    )
                continue

            if not surface.get("enforce", True):
                report_surfaces.append(
                    {
                        "id": surface_id,
                        "status": "recorded",
                        "reason": surface.get("note", "not enforced at this milestone"),
                        "classes": sorted(baseline_classes),
                    }
                )
                continue

            surface_problems = compare_surface(
                surface_id, baseline_classes, candidate_classes
            )
            if surface_problems:
                problems.extend(surface_problems)
                report_surfaces.append(
                    {"id": surface_id, "status": "fail", "classes": sorted(baseline_classes)}
                )
            else:
                report_surfaces.append(
                    {"id": surface_id, "status": "pass", "classes": sorted(baseline_classes)}
                )
    finally:
        shutil.rmtree(workdir, ignore_errors=True)

    report = {
        "kind": "hmx-insta-protected-methods",
        "result": "fail" if problems else "pass",
        "baseline": {"apk": args.baseline, "sha256": baseline_sha},
        "candidate": {"apk": args.candidate, "sha256": candidate_sha},
        "surfaces": report_surfaces,
        "problems": problems,
    }
    if args.report:
        with open(args.report, "w", encoding="utf-8") as handle:
            json.dump(report, handle, indent=2)
            handle.write("\n")

    for surface in report_surfaces:
        print("%-6s %s" % (surface["status"].upper(), surface["id"]))
    if problems:
        print("\nPROTECTED-METHOD GATE FAILED", file=sys.stderr)
        for problem in problems:
            print("\n" + problem, file=sys.stderr)
        return 1
    print("\nProtected-method gate passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
