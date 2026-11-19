#!/bin/bash
# Write dist/<version>/android-update.json for the Android app's self-update: version,
# download URL, size and SHA-256 of this release's APK. Attach the file to the GitHub
# Release; the app reads
# https://github.com/CodeCompose7/SharpScreen/releases/latest/download/android-update.json
# and installs the APK only when its size and SHA-256 match.
#
#   scripts/make_android_update.sh 0.3.2
#
# For a test feed: SHARPSCREEN_DIST_DIR (folder holding the APK, default dist/<version>)
# and SHARPSCREEN_APK_URL / SHARPSCREEN_NOTES_URL (default: the GitHub Release).
set -euo pipefail
VERSION="${1:?usage: make_android_update.sh <version>}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${SHARPSCREEN_DIST_DIR:-$ROOT/dist/$VERSION}"
APK="$OUT/SharpScreen-$VERSION.apk"
[ -f "$APK" ] || { echo "No APK at $APK" >&2; exit 1; }

# Version and package from the APK itself (aapt2, else apkanalyzer); fall back to the
# version string, computed the way app/build.gradle.kts does (major*10000+minor*100+patch).
ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
AAPT2="$(ls -d "$ANDROID_HOME"/build-tools/*/aapt2 2>/dev/null | sort -V | tail -1 || true)"
APKANALYZER="$(ls -d "$ANDROID_HOME"/cmdline-tools/*/bin/apkanalyzer 2>/dev/null | sort -V | tail -1 || true)"
PACKAGE="" CODE="" NAME="" MIN_SDK=""
if [ -n "$AAPT2" ] && BADGING="$("$AAPT2" dump badging "$APK" 2>/dev/null)"; then
    PACKAGE="$(echo "$BADGING" | sed -n "s/^package: name='\([^']*\)'.*/\1/p")"
    CODE="$(echo "$BADGING" | sed -n "s/^package: .* versionCode='\([0-9]*\)'.*/\1/p")"
    NAME="$(echo "$BADGING" | sed -n "s/^package: .* versionName='\([^']*\)'.*/\1/p")"
    MIN_SDK="$(echo "$BADGING" | sed -n "s/^sdkVersion:'\([0-9]*\)'.*/\1/p")"
elif [ -n "$APKANALYZER" ]; then
    PACKAGE="$("$APKANALYZER" manifest application-id "$APK" 2>/dev/null || true)"
    CODE="$("$APKANALYZER" manifest version-code "$APK" 2>/dev/null || true)"
    NAME="$("$APKANALYZER" manifest version-name "$APK" 2>/dev/null || true)"
    MIN_SDK="$("$APKANALYZER" manifest min-sdk "$APK" 2>/dev/null || true)"
fi
if [ -z "$CODE" ] || [ -z "$NAME" ]; then
    echo "aapt2/apkanalyzer unavailable; taking the version from '$VERSION'" >&2
    IFS=. read -r MAJOR MINOR PATCH <<< "$VERSION"
    PATCH="${PATCH%%[!0-9]*}"
    CODE=$((10#$MAJOR * 10000 + 10#$MINOR * 100 + 10#${PATCH:-0}))
    NAME="$VERSION"
fi
MIN_SDK="${MIN_SDK:-$(sed -n 's/.*minSdk = \([0-9]*\).*/\1/p' "$ROOT/AndroidClient/app/build.gradle.kts" | head -1)}"
# A debug build has another package (io.sharpscreen.app.debug) and cannot update the app.
if [ -n "$PACKAGE" ] && [ "$PACKAGE" != "io.sharpscreen.app" ] && [ -z "${SHARPSCREEN_APK_URL:-}" ]; then
    echo "$APK is $PACKAGE, not io.sharpscreen.app" >&2; exit 1
fi
[ "$NAME" = "$VERSION" ] || [ -n "${SHARPSCREEN_APK_URL:-}" ] || { echo "APK versionName $NAME != $VERSION" >&2; exit 1; }

SHA256="$(shasum -a 256 "$APK" | cut -d' ' -f1)"
SIZE="$(stat -f %z "$APK" 2>/dev/null || stat -c %s "$APK")"
URL="${SHARPSCREEN_APK_URL:-https://github.com/CodeCompose7/SharpScreen/releases/download/v$VERSION/SharpScreen-$VERSION.apk}"
NOTES="${SHARPSCREEN_NOTES_URL:-https://github.com/CodeCompose7/SharpScreen/releases/tag/v$VERSION}"

cat > "$OUT/android-update.json" <<JSON
{
  "versionCode": $CODE,
  "versionName": "$NAME",
  "apkUrl": "$URL",
  "sha256": "$SHA256",
  "size": $SIZE,
  "notesUrl": "$NOTES",
  "minSdk": $MIN_SDK
}
JSON
echo "android-update.json: $OUT/android-update.json ($NAME, versionCode $CODE, $SIZE bytes)"
