#!/bin/bash
# Regenerate SharpScreen's app icons from design/icon/*.svg.
#
# Renders with Quick Look (qlmanage, WebKit's SVG engine, so gradients and
# filters match what was designed) and finishes with ImageMagick. Quick Look
# flattens onto white, so the macOS icon gets its transparent corners from a
# separately rendered tile mask; the Android adaptive foreground is full-bleed
# white over a white background and needs no transparency.
#
# Outputs:
#   MacHost/Resources/AppIcon.icns (+ AppIcon.iconset)
#   AndroidClient/app/src/main/res/mipmap-*/ic_launcher_foreground.png, ic_launcher.png
#   AndroidClient/app/src/debug/res/mipmap-*/ic_launcher_foreground.png  (DEV badge)
#   AndroidClient/app/src/main/res/drawable-nodpi/logo.png  (in-app logo)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/design/icon"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
command -v magick >/dev/null || { echo "ImageMagick (magick) is required" >&2; exit 1; }

render() { # svg, size -> $TMP/<name>.png
    local name; name="$(basename "$1" .svg)"
    qlmanage -t -s "$2" -o "$TMP" "$1" >/dev/null 2>&1
    mv "$TMP/$name.svg.png" "$TMP/$name.png"
}

# --- macOS ---------------------------------------------------------------
render "$SRC/sharpscreen.svg" 1024
render "$SRC/tile-mask.svg" 1024
magick "$TMP/sharpscreen.png" "$TMP/tile-mask.png" -alpha off -compose CopyOpacity -composite "$TMP/mac-1024.png"

ICONSET="$ROOT/MacHost/Resources/AppIcon.iconset"
rm -rf "$ICONSET"; mkdir -p "$ICONSET"
for spec in 16:icon_16x16 32:icon_16x16@2x 32:icon_32x32 64:icon_32x32@2x 128:icon_128x128 \
            256:icon_128x128@2x 256:icon_256x256 512:icon_256x256@2x 512:icon_512x512 1024:icon_512x512@2x; do
    magick "$TMP/mac-1024.png" -filter Lanczos -resize "${spec%%:*}x${spec%%:*}" "$ICONSET/${spec#*:}.png"
done
iconutil -c icns "$ICONSET" -o "$ROOT/MacHost/Resources/AppIcon.icns"
echo "macOS: MacHost/Resources/AppIcon.icns"

# --- Android -------------------------------------------------------------
RES="$ROOT/AndroidClient/app/src/main/res"
DEBUG_RES="$ROOT/AndroidClient/app/src/debug/res"
render "$SRC/android-foreground.svg" 1080
render "$SRC/android-foreground-dev.svg" 1080
# Legacy (pre-adaptive) icon: the macOS tile cropped to its edges.
magick "$TMP/mac-1024.png" -crop 824x824+100+100 +repage "$TMP/legacy.png"

for spec in mdpi:1 hdpi:1.5 xhdpi:2 xxhdpi:3 xxxhdpi:4; do
    density="${spec%%:*}"; factor="${spec#*:}"
    fg=$(python3 -c "print(round(108*$factor))"); legacy=$(python3 -c "print(round(48*$factor))")
    mkdir -p "$RES/mipmap-$density" "$DEBUG_RES/mipmap-$density"
    magick "$TMP/android-foreground.png" -filter Lanczos -resize "${fg}x${fg}" "$RES/mipmap-$density/ic_launcher_foreground.png"
    magick "$TMP/android-foreground-dev.png" -filter Lanczos -resize "${fg}x${fg}" "$DEBUG_RES/mipmap-$density/ic_launcher_foreground.png"
    magick "$TMP/legacy.png" -filter Lanczos -resize "${legacy}x${legacy}" "$RES/mipmap-$density/ic_launcher.png"
done
echo "Android: mipmap-*/ic_launcher_foreground.png (main + debug), ic_launcher.png"

# In-app logo above the app name: the macOS tile cropped to its edges, transparent corners.
magick "$TMP/mac-1024.png" -crop 824x824+100+100 +repage -filter Lanczos -resize 512x512 \
    "$RES/drawable-nodpi/logo.png"
echo "Android: drawable-nodpi/logo.png"
