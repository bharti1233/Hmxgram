#!/usr/bin/env bash
#
# Offline tests for the universal-APK packaging gates (scenarios A-H).
#
# The validators are exercised with real ZIP files and stubbed Android tools
# (aapt/aapt2/apksigner/zipalign/adb), so the suite runs on a plain runner with
# no Android SDK. Optionally, real artifacts can be checked:
#
#   REAL_FIXTURES=1 \
#   WORKING_APK=/path/to/official-merged.apk \
#   FAILED_APK=/path/to/failed-split-metadata.apk \
#   STOCK_APK=/path/to/stock-base.apk \
#   bash scripts/ci/test_universal_packaging.sh
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/../.."

fail=0
note() { printf '%s\n' "$*"; }
bad() { printf 'ERROR: %s\n' "$*" >&2; fail=1; }

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp/bin" "$tmp/fake"

# ---------------------------------------------------------------------------
# Stub Android tools
# ---------------------------------------------------------------------------
cat > "$tmp/bin/aapt" <<'EOS'
#!/usr/bin/env bash
case "$*" in
  *"dump badging"*) cat "${FAKE_BADGING:?}" ;;
  *"dump xmltree"*) cat "${FAKE_MANIFEST:?}" ;;
  *) echo "aapt stub: unsupported: $*" >&2; exit 2 ;;
esac
EOS

cat > "$tmp/bin/aapt2" <<'EOS'
#!/usr/bin/env bash
case "$*" in
  *"dump xmltree"*) cat "${FAKE_MANIFEST:?}" ;;
  *"dump configurations"*) echo "configurations" ;;
  *"dump resources"*) echo "resources" ;;
  *) echo "aapt2 stub: unsupported: $*" >&2; exit 2 ;;
esac
EOS

cat > "$tmp/bin/apksigner" <<'EOS'
#!/usr/bin/env bash
if [ "${FAKE_SIGNER_FAIL:-0}" = "1" ]; then
  echo "DOES NOT VERIFY"
  exit 1
fi
cat "${FAKE_CERTS:?}"
EOS

cat > "$tmp/bin/zipalign" <<'EOS'
#!/usr/bin/env bash
exit "${FAKE_ZIPALIGN_FAIL:-0}"
EOS

cat > "$tmp/bin/adb" <<'EOS'
#!/usr/bin/env bash
set -euo pipefail
case "$1" in
  wait-for-device) exit 0 ;;
  devices) printf 'List of devices attached\nfake-serial\tdevice\n'; exit 0 ;;
  install)
    [ "${FAKE_ADB_INSTALL_FAIL:-0}" = "1" ] && { echo "Failure [INSTALL_FAILED_MISSING_SPLIT]" >&2; exit 1; }
    exit 0 ;;
  shell)
    case "$*" in
      *"getprop ro.product.cpu.abi"*) echo arm64-v8a ;;
      *"getprop ro.build.version.sdk"*) echo 35 ;;
      *"getprop ro.build.version.release"*) echo 15 ;;
      *"getprop ro.product.model"*) echo "Fake OPPO A38" ;;
      *"pm path"*) echo "package:/data/app/com.instagram.android/base.apk" ;;
      *"monkey"*) exit 0 ;;
      *) exit 0 ;;
    esac ;;
  *) exit 0 ;;
esac
EOS

chmod +x "$tmp/bin"/*

# ---------------------------------------------------------------------------
# Fixture helpers
# ---------------------------------------------------------------------------
META_CERT="3a10c50c18ba937506c2875e9047be74f8fd8b86fae2ff9feaa50a81fcb4c014"
RELEASE_CERT="cfddc301047c7b08b7151cf15d91a585f1d9518b6cdefadfe40f0996d11b3918"

make_apk() { # path res_count
  python3 - "$1" "$2" <<'PY'
import sys, zipfile
path, count = sys.argv[1], int(sys.argv[2])
with zipfile.ZipFile(path, "w") as z:
    z.writestr("AndroidManifest.xml", b"manifest")
    z.writestr("resources.arsc", b"arsc")
    z.writestr("classes.dex", b"dex")
    for i in range(count):
        z.writestr(f"res/r{i}.png", b"img")
PY
}

make_bundle() { # path base_apk density_split(0/1)
  python3 - "$1" "$2" "$3" <<'PY'
import sys, zipfile
path, base, density = sys.argv[1], sys.argv[2], sys.argv[3] == "1"
with zipfile.ZipFile(path, "w") as z:
    z.write(base, "base.apk")
    z.writestr("info.json", b"{}")
    if density:
        z.writestr("split_config.arm64_v8a.apk", b"abi-split")
        z.writestr("split_config.xxhdpi.apk", b"density-split")
        z.writestr("split_config.en.apk", b"lang-split")
PY
}

write_manifest() { # path kind(clean|split)
  if [ "$2" = "split" ]; then
    cat > "$1" <<'EOF'
E: manifest
  A: http://schemas.android.com/apk/res/android:requiredSplitTypes(0x0101064e)="base__density"
  A: http://schemas.android.com/apk/res/android:splitTypes(0x0101064f)=""
  E: application
    E: meta-data
      A: name="com.android.vending.splits.required"
      A: value=true
      A: name="com.android.vending.splits"
    E: meta-data
      A: name="com.android.stamp.type"
EOF
  else
    cat > "$1" <<'EOF'
E: manifest
  A: versionCode=386014712
  A: versionName="454.0.0.54.77"
  E: application
    E: activity
      A: name="com.instagram.android.MainActivity"
EOF
  fi
}

write_badging() { # path version code
  cat > "$1" <<EOF
package: name='com.instagram.android' versionCode='$3' versionName='$2'
sdkVersion:'28'
targetSdkVersion:'37'
densities: '160' '240' '320' '480' '640' '65534' '65535'
native-code: 'arm64-v8a'
EOF
}

write_certs() { # path signer
  cat > "$1" <<EOF
Verifies
Verified using v2 scheme (APK Signature Scheme v2): true
Verified using v3 scheme (APK Signature Scheme v3): true
Signer #1 certificate DN: CN=Test
Signer #1 certificate SHA-256 digest: $2
EOF
}

write_meta_certs() { # path
  cat > "$1" <<EOF
Verifies
Verified using v2 scheme (APK Signature Scheme v2): true
Verified using v3 scheme (APK Signature Scheme v3): true
Signer #1 certificate DN: O=Meta
Signer #1 certificate SHA-256 digest: $META_CERT
EOF
}

check() { # name expected_rc command...
  local name="$1" want="$2"; shift 2
  local got
  set +e
  "$@" > "$tmp/$name.log" 2>&1
  got=$?
  set -e
  [ "$got" = "$want" ] || bad "$name: expected exit $want, got $got"
  [ "$got" = "$want" ] || sed 's/^/    /' "$tmp/$name.log" >&2
}
expect_log() { grep -qF "$2" "$tmp/$1.log" || { bad "$1: log missing: $2"; sed 's/^/    /' "$tmp/$1.log" >&2; }; }

# Common fixtures
good_apk="$tmp/good.apk"; make_apk "$good_apk" 5
base_apk="$tmp/base.apk"; make_apk "$base_apk" 3
good_bundle="$tmp/good.apkm"; make_bundle "$good_bundle" "$base_apk" 1
flat_bundle="$tmp/flat.apkm"; make_bundle "$flat_bundle" "$base_apk" 0
good_sha="$(sha256sum "$good_bundle" | awk '{print $1}')"

write_manifest "$tmp/manifest-clean.txt" clean
write_manifest "$tmp/manifest-split.txt" split
write_badging "$tmp/badging-good.txt" 454.0.0.54.77 386014712
write_badging "$tmp/badging-wrong.txt" 1.2.3.4 386014712
write_certs "$tmp/certs-release.txt" "$RELEASE_CERT"
write_certs "$tmp/certs-wrong.txt" "1111111111111111111111111111111111111111111111111111111111111111"
write_meta_certs "$tmp/certs-meta.txt"

BASE_ENV=(env PATH="$tmp/bin:$PATH" FAKE_MANIFEST="$tmp/manifest-clean.txt" FAKE_BADGING="$tmp/badging-good.txt" FAKE_CERTS="$tmp/certs-release.txt")

note "== A: complete valid bundle and standalone APK pass =="
check A_bundle 0 "${BASE_ENV[@]}" FAKE_MANIFEST="$tmp/manifest-split.txt" FAKE_CERTS="$tmp/certs-meta.txt" \
  bash scripts/ci/validate_bundle.sh \
    --expected-sha256 "$good_sha" --expected-version 454.0.0.54.77 \
    --expected-version-code 386014712 --expected-meta-cert "$META_CERT" \
    --extract-base "$tmp/extracted-base.apk" "$good_bundle"
[ -s "$tmp/extracted-base.apk" ] || bad "A_bundle: base.apk was not extracted"

check A_merge 0 "${BASE_ENV[@]}" MIN_RES_ENTRIES=5 \
  bash scripts/ci/validate_standalone_apk.sh --stage merge "$good_apk"

check A_final 0 "${BASE_ENV[@]}" MIN_RES_ENTRIES=5 \
  bash scripts/ci/validate_standalone_apk.sh --stage final \
    --expected-version 454.0.0.54.77 --expected-version-code 386014712 \
    --expected-signer "$RELEASE_CERT" "$good_apk"

note "== B: plain base APK with split metadata is rejected =="
check B_plain_base 1 "${BASE_ENV[@]}" FAKE_MANIFEST="$tmp/manifest-split.txt" \
  bash scripts/ci/validate_input_apk.sh --expected-version 454.0.0.54.77 "$good_apk"
expect_log B_plain_base "bundle-derived base APK"

check B_flat_bundle 1 "${BASE_ENV[@]}" FAKE_MANIFEST="$tmp/manifest-split.txt" FAKE_CERTS="$tmp/certs-meta.txt" \
  bash scripts/ci/validate_bundle.sh "$flat_bundle"
expect_log B_flat_bundle "not a complete bundle"

note "== C: wrong Instagram version is rejected =="
check C_version 1 "${BASE_ENV[@]}" FAKE_MANIFEST="$tmp/manifest-split.txt" FAKE_BADGING="$tmp/badging-wrong.txt" FAKE_CERTS="$tmp/certs-meta.txt" \
  bash scripts/ci/validate_bundle.sh --expected-version 454.0.0.54.77 "$good_bundle"
expect_log C_version "expected '454.0.0.54.77'"

check C_input_version 1 "${BASE_ENV[@]}" FAKE_BADGING="$tmp/badging-wrong.txt" \
  bash scripts/ci/validate_input_apk.sh --expected-version 454.0.0.54.77 "$good_apk"
expect_log C_input_version "expected '454.0.0.54.77'"

note "== D: wrong bundle SHA-256 is rejected =="
check D_sha 1 "${BASE_ENV[@]}" FAKE_MANIFEST="$tmp/manifest-split.txt" FAKE_CERTS="$tmp/certs-meta.txt" \
  bash scripts/ci/validate_bundle.sh \
    --expected-sha256 "0000000000000000000000000000000000000000000000000000000000000000" "$good_bundle"
expect_log D_sha "expected 0000000000000000000000000000000000000000000000000000000000000000"

note "== E: missing density resources are rejected =="
check E_resources 1 "${BASE_ENV[@]}" MIN_RES_ENTRIES=12000 \
  bash scripts/ci/validate_standalone_apk.sh --stage merge "$good_apk"
expect_log E_resources "density/config splits were not merged"

note "== F: split metadata remaining after merge is rejected =="
check F_split_metadata 1 "${BASE_ENV[@]}" FAKE_MANIFEST="$tmp/manifest-split.txt" MIN_RES_ENTRIES=5 \
  bash scripts/ci/validate_standalone_apk.sh --stage merge "$good_apk"
expect_log F_split_metadata "split metadata remains"

cat > "$tmp/manifest-vending.txt" <<'EOF'
E: manifest
  E: application
    E: meta-data
      A: name="com.android.vending.splits.required"
      A: value=true
    E: meta-data
      A: name="com.android.stamp.source"
      A: value="https://play.google.com/store"
EOF
check F_vending_metadata 1 "${BASE_ENV[@]}" FAKE_MANIFEST="$tmp/manifest-vending.txt" MIN_RES_ENTRIES=5 \
  bash scripts/ci/validate_standalone_apk.sh --stage merge "$good_apk"
expect_log F_vending_metadata "Play split/stamp meta-data remains"

# The Play split table and source stamp files must be gone too.
python3 - "$good_apk" "$tmp/apk-with-split-table.apk" <<'PY'
import sys, shutil, zipfile
shutil.copyfile(sys.argv[1], sys.argv[2])
with zipfile.ZipFile(sys.argv[2], "a") as z:
    z.writestr("res/xml/splits0.xml", b"<splits/>")
PY
check F_split_table 1 "${BASE_ENV[@]}" MIN_RES_ENTRIES=5 \
  bash scripts/ci/validate_standalone_apk.sh --stage merge "$tmp/apk-with-split-table.apk"
expect_log F_split_table "res/xml/splits0.xml is present"

note "== G: invalid signature and wrong signer are rejected =="
check G_bad_signature 1 "${BASE_ENV[@]}" FAKE_SIGNER_FAIL=1 MIN_RES_ENTRIES=5 \
  bash scripts/ci/validate_standalone_apk.sh --stage final "$good_apk"
expect_log G_bad_signature "signature is invalid"

check G_wrong_signer 1 "${BASE_ENV[@]}" FAKE_CERTS="$tmp/certs-wrong.txt" MIN_RES_ENTRIES=5 \
  bash scripts/ci/validate_standalone_apk.sh --stage final --expected-signer "$RELEASE_CERT" "$good_apk"
expect_log G_wrong_signer "expected $RELEASE_CERT"

note "== H: failed device install is fatal (publish is step-gated) =="
check H_install_fail 1 "${BASE_ENV[@]}" PATH="$tmp/bin:$PATH" FAKE_ADB_INSTALL_FAIL=1 \
  bash scripts/ci/install_smoke_test.sh "$good_apk"
expect_log H_install_fail "adb install failed"

check H_install_ok 0 "${BASE_ENV[@]}" PATH="$tmp/bin:$PATH" \
  bash scripts/ci/install_smoke_test.sh "$good_apk"

# The publish step must come after the install test, and must re-validate.
install_line="$(grep -n 'name: Install smoke test on arm64 Android 15' .github/workflows/apk.yml | cut -d: -f1)"
publish_line="$(grep -n 'name: Publish throwaway test APK to private input repo' .github/workflows/apk.yml | cut -d: -f1)"
[ -n "$install_line" ] && [ -n "$publish_line" ] || bad "H_order: could not locate install/publish steps"
[ "${install_line:-0}" -lt "${publish_line:-0}" ] || bad "H_order: publish step must run after the install smoke test"
grep -q 'validate_standalone_apk.sh' .github/workflows/apk.yml || bad "H_order: publish path must re-validate standalone packaging"

# ---------------------------------------------------------------------------
# Optional real-artifact integration checks
# ---------------------------------------------------------------------------
if [ "${REAL_FIXTURES:-0}" = "1" ]; then
  note "== Real artifact checks =="
  [ -f "${WORKING_APK:?}" ] || bad "WORKING_APK is missing"
  [ -f "${FAILED_APK:?}" ] || bad "FAILED_APK is missing"
  [ -f "${STOCK_APK:?}" ] || bad "STOCK_APK is missing"

  check R_working_pass 0 env \
    EXPECTED_INSTAGRAM_VERSION=454.0.0.54.77 EXPECTED_INSTAGRAM_VERSION_CODE=386014712 \
    bash scripts/ci/validate_standalone_apk.sh --stage final \
      --expected-signer "$RELEASE_CERT" "$WORKING_APK"
  expect_log R_working_pass "Standalone APK validation passed (final stage)"

  check R_failed_rejected 1 env \
    EXPECTED_INSTAGRAM_VERSION=454.0.0.54.77 EXPECTED_INSTAGRAM_VERSION_CODE=386014712 \
    bash scripts/ci/validate_standalone_apk.sh --stage final "$FAILED_APK"
  expect_log R_failed_rejected "split metadata remains"

  check R_stock_input_rejected 1 env \
    EXPECTED_INSTAGRAM_VERSION=454.0.0.54.77 \
    bash scripts/ci/validate_input_apk.sh "$STOCK_APK"
  expect_log R_stock_input_rejected "bundle-derived base APK"

  check R_stock_bundle_rejected 1 env \
    EXPECTED_INSTAGRAM_VERSION=454.0.0.54.77 \
    bash scripts/ci/validate_bundle.sh "$STOCK_APK"
  expect_log R_stock_bundle_rejected "no base.apk"

  # A modified signed APK must fail signature verification.
  tampered="$tmp/tampered.apk"
  cp "$WORKING_APK" "$tampered"
  python3 - "$tampered" <<'PY'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1], "a") as z:
    z.writestr("classes.dex.injected", b"tamper")
PY
  check R_tampered_rejected 1 env \
    EXPECTED_INSTAGRAM_VERSION=454.0.0.54.77 EXPECTED_INSTAGRAM_VERSION_CODE=386014712 \
    bash scripts/ci/validate_standalone_apk.sh --stage final "$tampered"
  expect_log R_tampered_rejected "signature is invalid"
fi

if [ "$fail" -ne 0 ]; then
  printf '\nUniversal packaging gate tests failed.\n' >&2
  exit 1
fi
printf '\nUniversal packaging gate tests passed.\n'
