#!/usr/bin/env bash
#
# Offline unit tests for the ghost-mode rules.
#
# The policy is deliberately free of Android imports, so it can be compiled and
# exercised with a plain JDK: no device, no emulator, no network.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/../.."

EXT="extensions/extension/src/main/java/com/feurstagram/extension/ghost"
TESTS="scripts/ci/tests"

if ! command -v javac >/dev/null 2>&1; then
    echo "ERROR: javac not found; a JDK is required for the offline ghost-mode tests" >&2
    exit 1
fi

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

echo "== compiling the ghost-mode policy and its tests =="
javac -d "$tmp" -nowarn -encoding UTF-8 \
    "$EXT/GhostFeature.java" \
    "$EXT/GhostPolicy.java" \
    "$TESTS/GhostModeTest.java"

echo "== running the ghost-mode tests =="
java -cp "$tmp" GhostModeTest
