#!/usr/bin/env bash
#
# Downloader-core unit tests. Plain JDK only: no Android SDK, no Gradle, no
# device, no network. The core must compile with --release 11 and pass every
# assertion in DownloaderCoreTest and DownloaderUiTest.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/../.."

MAIN_DIR="extensions/extension/src/main/java/com/feurstagram/extension/downloader/core"
GHOST_DIR="extensions/extension/src/main/java/com/feurstagram/extension/ghost"
TEST_DIR="extensions/extension/src/test/java/com/feurstagram/extension/downloader/core"
GHOST_TEST_DIR="extensions/extension/src/test/java/com/feurstagram/extension/ghost"

mapfile -t MAIN_SOURCES < <(find "$MAIN_DIR" -maxdepth 1 -type f -name '*.java' | LC_ALL=C sort)
mapfile -t GHOST_SOURCES < <(find "$GHOST_DIR" -maxdepth 1 -type f -name '*.java' | LC_ALL=C sort)
mapfile -t TEST_SOURCES < <(find "$TEST_DIR" -maxdepth 1 -type f -name '*.java' | LC_ALL=C sort)
mapfile -t GHOST_TEST_SOURCES < <(find "$GHOST_TEST_DIR" -maxdepth 1 -type f -name '*.java' | LC_ALL=C sort)

if [ "${#MAIN_SOURCES[@]}" -eq 0 ]; then
  echo "ERROR: no downloader-core sources under $MAIN_DIR" >&2
  exit 1
fi
if [ "${#TEST_SOURCES[@]}" -eq 0 ]; then
  echo "ERROR: no downloader-core tests under $TEST_DIR" >&2
  exit 1
fi
if [ "${#GHOST_SOURCES[@]}" -eq 0 ]; then
  echo "ERROR: no ghost-mode sources under $GHOST_DIR" >&2
  exit 1
fi
if [ "${#GHOST_TEST_SOURCES[@]}" -eq 0 ]; then
  echo "ERROR: no ghost-mode tests under $GHOST_TEST_DIR" >&2
  exit 1
fi

WORK="$(mktemp -d "${TMPDIR:-/tmp}/hmx-downloader-core.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/classes"

echo "== Compiling downloader core (--release 11, -Werror) =="
echo "   core sources: ${#MAIN_SOURCES[@]}"
echo "   ghost sources: ${#GHOST_SOURCES[@]}"
echo "   test sources: ${#TEST_SOURCES[@]} + ${#GHOST_TEST_SOURCES[@]} ghost"
javac --release 11 -encoding UTF-8 -Xlint:all -Werror \
  -d "$WORK/classes" "${MAIN_SOURCES[@]}" "${GHOST_SOURCES[@]}" \
  "${TEST_SOURCES[@]}" "${GHOST_TEST_SOURCES[@]}"

echo
echo "== Running downloader core tests =="
java -cp "$WORK/classes" com.feurstagram.extension.downloader.core.DownloaderCoreTest
java -cp "$WORK/classes" com.feurstagram.extension.downloader.core.DownloaderUiTest
java -cp "$WORK/classes" com.feurstagram.extension.downloader.core.MediaObservationTest
java -cp "$WORK/classes" com.feurstagram.extension.downloader.core.ExplicitDownloadTest
java -cp "$WORK/classes" com.feurstagram.extension.downloader.core.InContextActionTest
java -cp "$WORK/classes" com.feurstagram.extension.downloader.core.OverlayLifecyclePolicyTest

echo
echo "== Running the ghost-mode policy tests =="
java -cp "$WORK/classes" com.feurstagram.extension.ghost.GhostPolicyTest

echo
echo "Downloader core logic tests passed."
