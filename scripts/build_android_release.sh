#!/bin/bash
# Build the SharpScreen Android release APK signed with the release key.
#
# The keystore lives outside the repo (default ~/.android/sharpscreen-release.jks)
# and its password in the macOS keychain item "sharpscreen-android-release".
# Any of these can be overridden with the env vars build.gradle.kts reads:
# SHARPSCREEN_KEYSTORE_PATH, SHARPSCREEN_KEYSTORE_PASSWORD, SHARPSCREEN_KEY_ALIAS,
# SHARPSCREEN_KEY_PASSWORD. Refuses to finish if the APK is not signed with it.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
VERSION="$(tr -d '[:space:]' < "$ROOT_DIR/VERSION")"

KS="${SHARPSCREEN_KEYSTORE_PATH:-$HOME/.android/sharpscreen-release.jks}"
[ -f "$KS" ] || { echo "Release keystore not found: $KS" >&2; exit 1; }
PW="${SHARPSCREEN_KEYSTORE_PASSWORD:-$(security find-generic-password -s sharpscreen-android-release -w 2>/dev/null || true)}"
[ -n "$PW" ] || { echo "No keystore password: set SHARPSCREEN_KEYSTORE_PASSWORD or add the keychain item sharpscreen-android-release" >&2; exit 1; }

export SHARPSCREEN_KEYSTORE_PATH="$KS"
export SHARPSCREEN_KEYSTORE_PASSWORD="$PW"
export SHARPSCREEN_KEY_ALIAS="${SHARPSCREEN_KEY_ALIAS:-sharpscreen}"
export SHARPSCREEN_KEY_PASSWORD="${SHARPSCREEN_KEY_PASSWORD:-$PW}"
export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"

echo "Building SharpScreen Android $VERSION (release)..."
(cd "$ROOT_DIR/AndroidClient" && ./gradlew -q assembleRelease)

APK="$ROOT_DIR/AndroidClient/app/build/outputs/apk/release/app-release.apk"
APKSIGNER="$(ls -d "$ANDROID_HOME"/build-tools/*/apksigner | sort -V | tail -1)"
CERTS="$("$APKSIGNER" verify --print-certs "$APK")"
if echo "$CERTS" | grep -q "CN=Android Debug"; then
    echo "APK is signed with the DEBUG key; the release key was not used." >&2
    exit 1
fi
echo "$CERTS" | grep -E "certificate DN|certificate SHA-256"

OUT="${SHARPSCREEN_DIST_DIR:-$ROOT_DIR/dist/$VERSION}"
mkdir -p "$OUT"
# Never replace a published APK: a test build once overwrote dist/0.2.0.
if [ -e "$OUT/SharpScreen-$VERSION.apk" ]; then
    echo "$OUT/SharpScreen-$VERSION.apk exists; not overwriting. APK left at $APK (set SHARPSCREEN_DIST_DIR for another folder)." >&2
    exit 1
fi
cp "$APK" "$OUT/SharpScreen-$VERSION.apk"
echo "APK: $OUT/SharpScreen-$VERSION.apk"
