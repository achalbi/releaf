#!/usr/bin/env bash
#
# QuickInk release helper.
#
#   ./release.sh
#
# Bumps versionCode, rebuilds the release AAB, verifies it was signed
# with the upload key (not debug), then commits the version.properties
# bump. Stops at the first failure.
#
# Run from anywhere — paths are anchored relative to this script.

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ANDROID_DIR="$SCRIPT_DIR"
GRADLE_DIR="$ANDROID_DIR/../../../shared/android"
VERSION_PROPS="$ANDROID_DIR/version.properties"
KEYSTORE_PROPS="$ANDROID_DIR/keystore.properties"
KEYSTORE_FILE="$ANDROID_DIR/upload-keystore.jks"
AAB="$ANDROID_DIR/app/build/outputs/bundle/release/quickink-release.aab"

step() { printf '\n\033[1;36m▸ %s\033[0m\n' "$1"; }
ok()   { printf '\033[1;32m✓ %s\033[0m\n' "$1"; }
fail() { printf '\033[1;31m✗ %s\033[0m\n' "$1" >&2; exit 1; }

step "Bump versionCode and build release AAB"
( cd "$GRADLE_DIR" && ./gradlew :apps:quickink:bumpVersionCode :apps:quickink:bundleRelease )

NEW_CODE=$(awk -F= '/^versionCode=/ { print $2 }' "$VERSION_PROPS")
[[ -n "$NEW_CODE" ]] || fail "Could not read versionCode from $VERSION_PROPS"
ok "Built AAB at versionCode $NEW_CODE"

step "Verify upload-key signature on AAB"
[[ -f "$AAB" ]] || fail "AAB not found at $AAB"
[[ -f "$KEYSTORE_PROPS" ]] || fail "Missing $KEYSTORE_PROPS — cannot verify cert"
[[ -f "$KEYSTORE_FILE" ]]  || fail "Missing $KEYSTORE_FILE"

STORE_PASS=$(awk -F= '/^storePassword=/ { sub(/^[^=]+=/,""); print }' "$KEYSTORE_PROPS")
EXPECTED_SHA=$(keytool -list -v -keystore "$KEYSTORE_FILE" -storepass "$STORE_PASS" 2>/dev/null \
    | awk '/SHA256:/ { print $2; exit }')
ACTUAL_SHA=$(keytool -printcert -jarfile "$AAB" 2>/dev/null \
    | awk '/SHA256:/ { print $2; exit }')

if [[ -z "$EXPECTED_SHA" || -z "$ACTUAL_SHA" ]]; then
    fail "Could not read certificate fingerprints"
fi
if [[ "$EXPECTED_SHA" != "$ACTUAL_SHA" ]]; then
    printf '  expected: %s\n  actual:   %s\n' "$EXPECTED_SHA" "$ACTUAL_SHA" >&2
    fail "AAB cert does not match upload keystore — would reject from Play"
fi
ok "Signed with upload key (SHA-256 matches)"

step "Commit version.properties bump"
if git -C "$ANDROID_DIR" diff --quiet -- version.properties 2>/dev/null \
   && git -C "$ANDROID_DIR" diff --cached --quiet -- version.properties 2>/dev/null; then
    ok "version.properties unchanged — skipping commit"
else
    git -C "$ANDROID_DIR" add version.properties
    git -C "$ANDROID_DIR" commit -m "QuickInk: bump versionCode to $NEW_CODE" --quiet
    ok "Committed bump"
fi

printf '\n\033[1;32mReady to upload:\033[0m %s\n' "$AAB"
printf '   versionCode: %s\n' "$NEW_CODE"
printf '   size:        %s\n' "$(ls -lh "$AAB" | awk '{print $5}')"
printf '\nNext: upload to Play Console → Release → Production → Create new release.\n'
