#!/bin/bash
# Write dist/<version>/appcast.xml for Sparkle: one item for the notarized DMG
# of this release, signed with the EdDSA key in this Mac's login keychain
# (or SPARKLE_KEY_FILE). Attach the file to the GitHub Release; the app reads
# https://github.com/CodeCompose7/SharpScreen/releases/latest/download/appcast.xml
#
#   scripts/make_appcast.sh 0.3.2
set -euo pipefail
VERSION="${1:?usage: make_appcast.sh <version>}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DMG="$ROOT/dist/$VERSION/SharpScreen-$VERSION.dmg"
BIN="$ROOT/MacHost/.build/artifacts/sparkle/Sparkle/bin"
[ -f "$DMG" ] || { echo "No DMG at $DMG" >&2; exit 1; }
[ -x "$BIN/sign_update" ] || { echo "Sparkle tools missing; run 'swift build' in MacHost first" >&2; exit 1; }
# Sign the file users will download, so it must already be notarized and stapled.
xcrun stapler validate "$DMG" >/dev/null || { echo "$DMG is not notarized/stapled yet" >&2; exit 1; }

KEY_ARGS=()
[ -n "${SPARKLE_KEY_FILE:-}" ] && KEY_ARGS=(--ed-key-file "$SPARKLE_KEY_FILE")
SIGNATURE="$("$BIN/sign_update" "${KEY_ARGS[@]+"${KEY_ARGS[@]}"}" "$DMG")"   # sparkle:edSignature="…" length="…"
NOTES="$(python3 "$ROOT/scripts/changelog_html.py" "$VERSION" < "$ROOT/CHANGELOG.md")"
MIN_OS="$(grep -A1 '<key>LSMinimumSystemVersion</key>' "$ROOT/scripts/build_mac.sh" | grep -o '[0-9][0-9.]*' | head -1)"
URL="https://github.com/CodeCompose7/SharpScreen/releases/download/v$VERSION/SharpScreen-$VERSION.dmg"
DATE="$(LC_ALL=C date -u '+%a, %d %b %Y %H:%M:%S +0000')"

cat > "$ROOT/dist/$VERSION/appcast.xml" <<XML
<?xml version="1.0" encoding="utf-8"?>
<rss version="2.0" xmlns:sparkle="http://www.andymatuschak.org/xml-namespaces/sparkle">
  <channel>
    <title>SharpScreen</title>
    <link>https://github.com/CodeCompose7/SharpScreen</link>
    <item>
      <title>SharpScreen $VERSION</title>
      <pubDate>$DATE</pubDate>
      <sparkle:version>$VERSION</sparkle:version>
      <sparkle:shortVersionString>$VERSION</sparkle:shortVersionString>
      <sparkle:minimumSystemVersion>$MIN_OS</sparkle:minimumSystemVersion>
      <sparkle:fullReleaseNotesLink>https://github.com/CodeCompose7/SharpScreen/releases/tag/v$VERSION</sparkle:fullReleaseNotesLink>
      <description><![CDATA[$NOTES]]></description>
      <enclosure url="$URL" $SIGNATURE type="application/octet-stream"/>
    </item>
  </channel>
</rss>
XML
echo "appcast: dist/$VERSION/appcast.xml"
