#!/usr/bin/env python3
"""Adversarial checks for the narrowly allowed native feed hook."""
import importlib.util
from pathlib import Path

spec = importlib.util.spec_from_file_location(
    "verifier", Path(__file__).with_name("verify_protected_methods.py"))
verifier = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verifier)

base = [
    "      name          : 'bind'",
    "      registers     : 26",
    "      ins           : 5",
    "      outs          : 6",
    "      insns size    : 5 16-bit code units",
    "header: X.A.bind:(LX/A;LX/B;LX/C;LX/D;)V",
    "instr 0000: if-eqz v0, 0004",
    "instr 0002: move-object v1, v2",
    "instr 0003: nop",
    "instr 0004: return-void",
    "      catches       : (none)",
]
hook = ("instr 0000: invoke-static/range {v24, v25}, "
        "Lcom/feurstagram/extension/downloader/DownloaderBinder;."
        "bindFeed:(Ljava/lang/Object;Ljava/lang/Object;)V")
candidate = base[:4] + ["      insns size    : 8 16-bit code units", base[5], hook,
                       "instr 0003: if-eqz v0, 0007", "instr 0005: move-object v1, v2",
                       "instr 0006: nop", "instr 0007: return-void", base[-1]]
assert verifier.strip_feed_hook(candidate) == base
assert verifier.strip_feed_hook(base) == base
for replacement in [hook.replace("v24", "v23"), hook.replace("0000", "0001")]:
    changed = candidate.copy()
    changed[6] = replacement
    try:
        verifier.strip_feed_hook(changed)
    except ValueError:
        pass
    else:
        raise AssertionError("invalid hook accepted")
changed = candidate.copy()
changed[8] = "instr 0005: move-object v1, v3"
assert verifier.strip_feed_hook(changed) != base
print("Native feed exact-diff tests: PASS")
