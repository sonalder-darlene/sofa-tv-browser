#!/usr/bin/env bash
# Renders the app's banner and the store graphics from the SVGs in art/.
# Needs rsvg-convert, oxipng and the Inter font. Usage: tools/render-art.sh   (run from the repository root)
set -euo pipefail
img=fastlane/metadata/android/en-US/images
render() { rsvg-convert -w "$2" -h "$3" "art/$1" -o "$4" && oxipng -q -o 4 --strip all "$4" && echo "$4"; }
render banner.svg 640 360 app/src/main/res/drawable-xhdpi/banner.png
render banner.svg 1280 720 "$img/tvBanner.png"
render icon.svg 512 512 "$img/icon.png"
render feature-graphic.svg 1024 500 "$img/featureGraphic.png"
