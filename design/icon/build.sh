#!/bin/zsh
# Regenerates every Shroud app icon from the SVG masters in this folder.
# Needs Google Chrome (headless rasterizer) and sips; both ship on a dev Mac.
set -euo pipefail
cd "${0:A:h}"
root=../..
chrome="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
tmp=$(mktemp -d)
trap 'rm -rf $tmp' EXIT

# render <svg> <out.png> [transparent]: 1024 px. Headless Chrome clamps small
# windows, so smaller sizes are downscaled from this with sips.
render() {
  local bg=000000ff
  [[ ${3:-} == transparent ]] && bg=00000000
  "$chrome" --headless=new --disable-gpu --hide-scrollbars --force-device-scale-factor=1 \
    --default-background-color=$bg --window-size=1024,1024 \
    --screenshot="$tmp/shot.png" "file://${1:A}" >/dev/null 2>&1
  mv "$tmp/shot.png" "$2"
}

# App Store icons must not carry an alpha channel.
opaque() {
  if sips -g hasAlpha "$1" | grep -q 'hasAlpha: yes'; then
    echo "error: $1 has an alpha channel" >&2
    exit 1
  fi
}

ios=$root/ios/shroud/Assets.xcassets/AppIcon.appiconset
render shroud-icon.svg        $ios/AppIcon.png
render shroud-icon-dark.svg   $ios/AppIcon-Dark.png
render shroud-icon-tinted.svg $ios/AppIcon-Tinted.png
for f in $ios/AppIcon*.png; do opaque $f; done
cp $ios/AppIcon.png preview.png
cp shroud-mark.svg $root/ios/shroud/Assets.xcassets/BrandMark.imageset/

# Web: the full-bleed square for iOS home screens; a smaller glyph for the
# maskable PWA slots (Android masks down to a 40 % radius circle); the icon
# with rounded, transparent corners for the "any" PWA slots.
web=$root/web/public
cp shroud-favicon.svg $web/favicon.svg
sips -Z 180 $ios/AppIcon.png --out $web/apple-touch-icon.png >/dev/null
sed 's/scale(0.9)/scale(0.78)/' shroud-icon.svg > $tmp/maskable.svg
render $tmp/maskable.svg $tmp/maskable.png
sips -Z 192 $tmp/maskable.png --out $web/icon-maskable-192.png >/dev/null
sips -Z 512 $tmp/maskable.png --out $web/icon-maskable-512.png >/dev/null
sed 's/<rect width="1024" height="1024"/& rx="230"/' shroud-icon.svg > $tmp/rounded.svg
render $tmp/rounded.svg $tmp/rounded.png transparent
sips -Z 192 $tmp/rounded.png --out $web/icon-192.png >/dev/null
sips -Z 512 $tmp/rounded.png --out $web/icon-512.png >/dev/null
# web/src/components/BrandMark.tsx inlines the glyph path by hand: keep it in sync.
echo "icons written"
