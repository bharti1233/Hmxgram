#!/usr/bin/env bash
#
# Offline unit tests for the downloader's resolution and bookkeeping rules.
#
# The downloader core is deliberately free of Android imports, so it can be
# compiled and exercised with a plain JDK: no device, no emulator, no
# network. The Android-only parts (MediaStore, notifications, the UI) are
# covered by the static validation in scripts/ci/validate_downloader.sh.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/../.."

EXT="extensions/extension/src/main/java/com/feurstagram/extension/downloader"
TESTS="scripts/ci/tests"

if ! command -v javac >/dev/null 2>&1; then
    echo "ERROR: javac not found; a JDK is required for the offline downloader tests" >&2
    exit 1
fi

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

echo "== compiling the downloader core and its tests =="
javac -d "$tmp" -nowarn -encoding UTF-8 \
    "$EXT/MediaKind.java" \
    "$EXT/MediaNaming.java" \
    "$EXT/MediaItem.java" \
    "$EXT/MediaResolver.java" \
    "$EXT/MediaSource.java" \
    "$EXT/FeedMediaAdapter.java" \
    "$EXT/StoryMediaAdapter.java" \
    "$EXT/ReelMediaAdapter.java" \
    "$EXT/DownloadError.java" \
    "$EXT/DownloadTask.java" \
    "$EXT/DuplicateDetector.java" \
    "$TESTS/DownloaderLogicTest.java"

echo "== running the downloader logic tests =="
java -cp "$tmp" DownloaderLogicTest
