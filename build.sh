#!/usr/bin/env bash
#
# Feurstagram build pipeline.
#
#   ./build.sh <instagram.apk|.apkm|.xapk|.apks> [--clone] [--install] [--debug]
#                [--allow-signature-bypass] [--downloader] [--incontext]
#
# Builds the patch bundle (.mpp) from the Gradle project, then applies it to the
# given Instagram APK with the local Morphe CLI, producing ./feurstagram.apk.
#   --clone     install side-by-side as a separate package (com.instagram.android.feurstagram)
#   --install   install the result on the connected ADB device
#   --debug     enable the Debug bridge patch: the settings become drivable over
#               ADB broadcasts (see extensions/.../DebugBridge.java). Never ship it.
#   --allow-signature-bypass
#               keep the "Signature check bypass" patch. The patch is DISABLED
#               by default; enable it only for throwaway-signed test builds.
#               It is refused when signing with the release keystore.
#   --downloader
#               apply the downloader's own patches: the "Downloader share
#               target" patch (DISABLED by default because it declares an
#               exported activity) and the "Downloader media observer" patch
#               (DISABLED by default because it reads the media the user
#               browses). Enable them only for a reviewed build that must
#               carry the downloader (the device test). The caller is
#               expected to assert both patches in the patch report.
#   --ghost
#               apply the "Ghost Mode settings" patch (DISABLED by default,
#               because it changes what the app shows). Enable it only for a
#               reviewed build that must carry the privacy screen.
#   --incontext
#               apply the "In-context download actions" patch (DISABLED by
#               default): the HMX download chip over feed, reels and story
#               media. It implies --downloader, because the chip queues through
#               the downloader's existing engine and service.
#
# Split bundles: an APKMirror .apkm (or .xapk/.apks) is a zip of base.apk plus
# split APKs. The Morphe CLI patches one APK, so those are merged into a single
# universal APK with APKEditor (tools/APKEditor-*.jar) before patching. The merge
# is cached under build/merged/ and reused while it is newer than the bundle.
#
# A single .apk input is only accepted when it is a complete standalone APK. A
# bundle-derived base APK (one that declares requiredSplitTypes) is rejected:
# patching it produces INSTALL_FAILED_MISSING_SPLIT because the split payload is
# not present. Supply the complete original .apkm/.apks bundle instead.
#
# Validation is performed by scripts/ci/validate_{input_apk,bundle,standalone_apk}.sh
# and is controlled by the same environment used in CI:
#   EXPECTED_INSTAGRAM_PACKAGE, EXPECTED_INSTAGRAM_VERSION,
#   EXPECTED_INSTAGRAM_VERSION_CODE, EXPECTED_META_CERT_SHA256,
#   EXPECTED_APK_SIGNER_SHA256, MIN_RES_ENTRIES, MIN_APPLIED_PATCHES,
#   EXPECTED_PATCHES (comma separated), EXPECTED_BUNDLE_SHA256
#
# Signing: set FEURSTAGRAM_KEYSTORE_PASS (and optionally FEURSTAGRAM_KEY_PASS)
# to sign with feurstagram.keystore. That keystore is PKCS12, which the Morphe
# CLI cannot read (it expects BKS), so the APK is built unsigned and signed with
# the Android SDK's apksigner — this reproduces the existing release signature,
# so users update in place without uninstalling. Override the keystore/alias
# with FEURSTAGRAM_KEYSTORE / FEURSTAGRAM_KEY_ALIAS. Without a keystore password
# the CLI signs with a throwaway key (fine for testing, not for release).
set -euo pipefail

# Note the "|| true" on the ls|head lookups below: under `pipefail` a glob that
# matches nothing makes the whole substitution fail, and `set -e` would abort the
# script before the "not found" message it is meant to feed could ever print.
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# The CLI jar was renamed morphe-cli-* -> morphe-desktop-* upstream at 1.11; accept both.
CLI="$(ls -t "$DIR"/tools/morphe-cli-*.jar "$DIR"/tools/morphe-desktop-*.jar 2>/dev/null | head -1 || true)"
OUT="$DIR/feurstagram.apk"

# Morphe's Android Gradle plugin targets JDK 17-21; pin to 21 so the build does
# not pick up a newer system JDK.
if [ -d "/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home" ]; then
    export JAVA_HOME="/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home"
fi

# The patcher dependency lives on GitHub Packages. Credentials come from
# ~/.gradle/gradle.properties (gpr.user/gpr.key); otherwise fall back to the
# GitHub CLI token if it is available (needs the read:packages scope).
if [ -z "${GITHUB_TOKEN:-}" ] && command -v gh >/dev/null 2>&1; then
    export GITHUB_TOKEN="$(gh auth token 2>/dev/null || true)"
    export GITHUB_ACTOR="${GITHUB_ACTOR:-$(gh api user --jq .login 2>/dev/null || true)}"
fi

# The extension is an Android library, so the Android SDK must be locatable.
if [ -z "${ANDROID_HOME:-}" ]; then
    for sdk in "$HOME/Library/Android/sdk" "$HOME/Android/Sdk" \
        "/opt/homebrew/share/android-commandlinetools" "/usr/local/share/android-commandlinetools" \
        "${ANDROID_SDK_ROOT:-}"; do
        if [ -n "$sdk" ] && { [ -d "$sdk/platform-tools" ] || [ -d "$sdk/build-tools" ]; }; then
            export ANDROID_HOME="$sdk"
            break
        fi
    done
fi

# Run every JVM tool with the pinned JDK rather than whatever `java` is on PATH
# (Homebrew often puts a JDK 17 there, which the patcher rejects).
JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/java}"
JAVA_BIN="${JAVA_BIN:-java}"

# Signing material. apksigner ships with the Android SDK build-tools.
KEYSTORE="${FEURSTAGRAM_KEYSTORE:-$DIR/feurstagram.keystore}"
KEY_ALIAS="${FEURSTAGRAM_KEY_ALIAS:-feurstagram}"
APKSIGNER=""
if [ -n "${ANDROID_HOME:-}" ]; then
    APKSIGNER="$(ls -t "$ANDROID_HOME"/build-tools/*/apksigner 2>/dev/null | head -1 || true)"
fi

APK=""
CLONE=0
INSTALL=0
DEBUG=0
ALLOW_SIGNATURE_BYPASS=0
ENABLE_DOWNLOADER=0
ENABLE_GHOST=0
ENABLE_INCONTEXT=0
for arg in "$@"; do
    case "$arg" in
        --clone) CLONE=1 ;;
        --install) INSTALL=1 ;;
        --debug) DEBUG=1 ;;
        --allow-signature-bypass) ALLOW_SIGNATURE_BYPASS=1 ;;
        --downloader) ENABLE_DOWNLOADER=1 ;;
        --ghost) ENABLE_GHOST=1 ;;
        --incontext) ENABLE_INCONTEXT=1 ;;
        *) APK="$arg" ;;
    esac
done

if [ -z "$APK" ] || [ ! -f "$APK" ]; then
    echo "usage: ./build.sh <instagram.apk|.apkm|.xapk|.apks> [--clone] [--install] [--debug] [--allow-signature-bypass] [--downloader] [--ghost] [--incontext]" >&2
    exit 1
fi
if [ -z "$CLI" ]; then
    echo "Error: Morphe CLI not found under tools/ (morphe-cli-*.jar / morphe-desktop-*.jar)." >&2
    exit 1
fi

VALIDATE_INPUT="$DIR/scripts/ci/validate_input_apk.sh"
VALIDATE_BUNDLE="$DIR/scripts/ci/validate_bundle.sh"
VALIDATE_STANDALONE="$DIR/scripts/ci/validate_standalone_apk.sh"
VERIFY_REPORT="$DIR/scripts/ci/verify_patch_report.py"
for validator in "$VALIDATE_INPUT" "$VALIDATE_BUNDLE" "$VALIDATE_STANDALONE" "$VERIFY_REPORT"; do
    if [ ! -f "$validator" ]; then
        echo "Error: required validator missing: $validator" >&2
        exit 1
    fi
done

# A split bundle has to become one universal APK before the patcher can touch it.
case "$APK" in
    *.apkm | *.xapk | *.apks)
        echo "==> [0/4] Validating bundle $(basename "$APK")"
        bash "$VALIDATE_BUNDLE" "$APK" || {
            echo "Error: bundle validation failed. The complete original bundle is required." >&2
            exit 1
        }
        EDITOR="$(ls -t "$DIR"/tools/APKEditor-*.jar 2>/dev/null | head -1 || true)"
        if [ -z "$EDITOR" ]; then
            echo "Error: $(basename "$APK") is a split bundle and needs APKEditor to merge." >&2
            echo "       Fetch it into tools/, then re-run:" >&2
            echo "       gh release download V1.4.9 -R REAndroid/APKEditor -p 'APKEditor-1.4.9.jar' -D tools/" >&2
            exit 1
        fi
        MERGED="$DIR/build/merged/$(basename "${APK%.*}").apk"
        if [ -f "$MERGED" ] && [ "$MERGED" -nt "$APK" ]; then
            echo "==> [0/4] Reusing merged bundle: $(basename "$MERGED")"
        else
            echo "==> [0/4] Merging splits from $(basename "$APK")"
            mkdir -p "$(dirname "$MERGED")"
            # The bundle holds base.apk plus feature/density splits; -f overwrites
            # a stale merge. Instagram's is ~400 MB, so give the JVM room. The
            # per-file merge log is noisy, so keep it on disk rather than inline.
            #
            # -clean-meta is mandatory: Merger only calls clearMeta() when that
            # flag is set, and clearMeta() is what removes the original
            # META-INF/*.MF|SF|RSA signatures and the Play source stamp file
            # "stamp-cert-sha256". Without it the merged APK keeps Meta's
            # signatures and a stale Google source stamp, which Android 11+
            # rejects after we re-sign (INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES)
            # and which the standalone validator refuses to publish.
            if ! "$JAVA_BIN" -Xmx4g -jar "$EDITOR" m -f -clean-meta -i "$APK" -o "$MERGED" > "$MERGED.log" 2>&1; then
                echo "Error: APKEditor failed to merge $(basename "$APK")." >&2
                echo "       Log: $MERGED.log" >&2
                exit 1
            fi
            echo "    merged: $MERGED"
        fi
        APK="$MERGED"
        ;;
    *)
        echo "==> [0/4] Validating single APK input $(basename "$APK")"
        bash "$VALIDATE_INPUT" "$APK" || {
            echo "Error: input APK rejected. Use the complete original .apkm/.apks bundle." >&2
            exit 1
        }
        ;;
esac

# The launcher artwork is ours, not Instagram's: the adaptive icon layers are
# replaced with the official HMX Insta logo before the patcher sees the APK.
# The app name itself is set by the "HMX Insta app name" patch.
BRANDING_ASSETS="$DIR/patches/src/main/resources/branding"
if [ ! -d "$BRANDING_ASSETS" ] || [ ! -f "$BRANDING_ASSETS/foreground-xxxhdpi.png" ]; then
    echo "Error: the launcher branding assets are missing: $BRANDING_ASSETS" >&2
    echo "       Regenerate them with: python3 scripts/branding/generate_launcher_icons.py" >&2
    exit 1
fi
echo "==> [0/4] Applying HMX Insta launcher branding"
python3 "$DIR/scripts/branding/apply_launcher_branding.py" "$APK" "$BRANDING_ASSETS" || {
    echo "Error: could not apply the launcher branding; refusing to patch an Instagram-branded APK." >&2
    exit 1
}

# The merged/patch input must be a genuinely standalone APK: no split metadata,
# merged density payload, readable resource table, expected identity.
echo "==> [0/4] Validating standalone packaging of $(basename "$APK")"
bash "$VALIDATE_STANDALONE" --stage merge "$APK" || {
    echo "Error: the APK is not a valid standalone universal APK. Refusing to patch it." >&2
    exit 1
}

echo "==> [1/4] Building patch bundle (.mpp)"
"$DIR/gradlew" -p "$DIR" :patches:build
MPP="$(ls -t "$DIR"/patches/build/libs/patches-*[0-9].mpp 2>/dev/null | grep -v -- '-sources\|-javadoc' | head -1 || true)"
if [ -z "$MPP" ]; then
    echo "Error: no .mpp produced under patches/build/libs/" >&2
    exit 1
fi
echo "    bundle: $MPP"

echo "==> [2/4] Applying to $(basename "$APK")"
if [ "$ALLOW_SIGNATURE_BYPASS" -eq 1 ]; then
    echo "    !! signature-check bypass ENABLED (test builds only)"
else
    echo "    signature-check bypass disabled"
fi
# Scratch files are purged by default in the CLI; -r writes a JSON report of each
# patch step so a fingerprint that stopped matching is visible instead of silent.
REPORT="$DIR/build/patch-report.json"
mkdir -p "$DIR/build"
ARGS=(-jar "$CLI" patch -p "$MPP" -f -r "$REPORT" -o "$OUT")
[ "$CLONE" -eq 1 ] && ARGS+=(-e "Clone")
[ "$DEBUG" -eq 1 ] && ARGS+=(-e "Debug bridge")
# The downloader share target is default-off in the patch source: it declares
# an exported activity, so it is only applied when the caller explicitly asks
# for it. The patch report then has to list it, which verify_patch_report.py
# enforces through EXPECTED_PATCHES.
#
# The in-context action sits on top of that engine: it queues through the same
# service, so enabling it implies the downloader patches.
[ "$ENABLE_INCONTEXT" -eq 1 ] && ENABLE_DOWNLOADER=1
[ "$ENABLE_DOWNLOADER" -eq 1 ] && ARGS+=(-e "Downloader share target" -e "Downloader media observer")
[ "$ENABLE_INCONTEXT" -eq 1 ] && ARGS+=(-e "In-context download actions")
[ "$ENABLE_GHOST" -eq 1 ] && ARGS+=(-e "Ghost Mode settings")

# With a keystore password, defer signing to apksigner (the CLI can't read the
# PKCS12 keystore); otherwise let the CLI sign with a throwaway key for testing.
SIGN_WITH_APKSIGNER=0
if [ -n "${FEURSTAGRAM_KEYSTORE_PASS:-}" ]; then
    if [ ! -f "$KEYSTORE" ]; then
        echo "Error: keystore not found: $KEYSTORE" >&2
        exit 1
    fi
    if [ -z "$APKSIGNER" ]; then
        echo "Error: apksigner not found under \$ANDROID_HOME/build-tools." >&2
        echo "       Install the Android SDK build-tools, or unset FEURSTAGRAM_KEYSTORE_PASS" >&2
        echo "       to sign with a throwaway key (testing only)." >&2
        exit 1
    fi
    SIGN_WITH_APKSIGNER=1
    ARGS+=(--unsigned)
fi

# The signature-check bypass rewrites Instagram's integrity allowlist. It is a
# test-only affordance: refuse to combine it with release keystore signing.
if [ "$ALLOW_SIGNATURE_BYPASS" -eq 1 ] && [ "$SIGN_WITH_APKSIGNER" -eq 1 ]; then
    echo "Error: --allow-signature-bypass is test-only and cannot be used with the release keystore." >&2
    exit 1
fi

# Morphe applies every default-enabled patch unless it is explicitly disabled.
# The bypass is default-enabled, so opt out here unless the caller asked for it.
if [ "$ALLOW_SIGNATURE_BYPASS" -eq 0 ]; then
    ARGS+=(-d "Signature check bypass")
fi

# Operator-selected exclusions, comma separated (DISABLE_PATCHES). Used to skip
# a patch whose fingerprint no longer matches the pinned base. A typo is safe:
# Morphe rejects unknown names instead of ignoring them.
if [ -n "${DISABLE_PATCHES:-}" ]; then
    IFS=',' read -r -a DISABLED <<< "$DISABLE_PATCHES"
    for name in "${DISABLED[@]}"; do
        name="${name#"${name%%[![:space:]]*}"}"
        name="${name%"${name##*[![:space:]]}"}"
        [ -n "$name" ] || continue
        echo "    disabling patch: $name"
        ARGS+=(-d "$name")
    done
fi
ARGS+=("$APK")
"$JAVA_BIN" "${ARGS[@]}"

if [ "$SIGN_WITH_APKSIGNER" -eq 1 ]; then
    echo "    signing with apksigner ($(basename "$KEYSTORE"), alias $KEY_ALIAS)"
    # Force v1+v2+v3 so the signature is accepted across the whole user base's
    # devices and matches the schemes prior releases shipped; v4 (the .idsig
    # sidecar) is only useful for adb incremental install, so leave it off.
    "$APKSIGNER" sign \
        --ks "$KEYSTORE" \
        --ks-key-alias "$KEY_ALIAS" \
        --ks-pass "pass:$FEURSTAGRAM_KEYSTORE_PASS" \
        --key-pass "pass:${FEURSTAGRAM_KEY_PASS:-$FEURSTAGRAM_KEYSTORE_PASS}" \
        --v1-signing-enabled true \
        --v2-signing-enabled true \
        --v3-signing-enabled true \
        --v4-signing-enabled false \
        "$OUT"
    # apksigner only writes a v4 .idsig when v4 is enabled; clean up just in case.
    rm -f "$OUT.idsig"
    "$APKSIGNER" verify --print-certs "$OUT" 2>/dev/null \
        | grep -i "SHA-256" | head -1 | sed 's/^/    cert /' || true
fi

# Surface which patches actually applied. The CLI aborts on the first failure, so
# this is mostly a receipt — but a fingerprint that quietly stopped matching on a
# new Instagram build is exactly the regression that shipped as issue #117.
# A failed, missing, or incomplete report is a hard build failure: publishing must
# never proceed with silently skipped patches.
if [ ! -f "$REPORT" ]; then
    echo "Error: Morphe did not write the patch report: $REPORT" >&2
    exit 1
fi
if ! command -v python3 >/dev/null 2>&1; then
    echo "Error: python3 is required to verify the patch report." >&2
    exit 1
fi
python3 "$VERIFY_REPORT" "$REPORT" || {
    echo "Error: the patch report did not pass verification; refusing to continue." >&2
    exit 1
}

echo "==> [3/4] Validating final standalone APK"
if [ "$CLONE" -eq 1 ]; then
    export EXPECTED_INSTAGRAM_PACKAGE="com.instagram.android.feurstagram"
fi
bash "$VALIDATE_STANDALONE" --stage final "$OUT" || {
    echo "Error: final APK failed standalone/signature validation. Refusing to publish it." >&2
    exit 1
}

echo "==> [4/4] Output: $OUT"
[ "$DEBUG" -eq 1 ] && echo "    !! debug build: settings are drivable over ADB broadcasts — do not release"
if [ "$INSTALL" -eq 1 ]; then
    echo "    installing on device..."
    adb install -r "$OUT"
fi
