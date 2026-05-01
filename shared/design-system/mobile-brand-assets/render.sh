#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
SRC="$ROOT/source"
OUT="$ROOT/generated"
RSVG="${RSVG_CONVERT:-rsvg-convert}"

mkdir -p "$OUT"

"$RSVG" -b "#0B4328" "$SRC/releaf-app-icon.svg" -w 1024 -h 1024 -o "$OUT/releaf-app-icon-1024.png"
"$RSVG" -b "#0B4328" "$SRC/releaf-app-icon.svg" -w 512 -h 512 -o "$OUT/releaf-app-icon-512.png"
"$RSVG" -b "#F5EEDF" "$SRC/releaf-logo-lockup.svg" -w 960 -h 320 -o "$OUT/releaf-logo-lockup.png"
"$RSVG" "$SRC/releaf-splash-screen.svg" -w 1290 -h 2796 -o "$OUT/releaf-splash-screen-1290x2796.png"
"$RSVG" "$SRC/releaf-landing-page.svg" -w 1290 -h 2796 -o "$OUT/releaf-landing-page-1290x2796.png"
"$RSVG" "$SRC/releaf-mobile-icons.svg" -w 1200 -h 640 -o "$OUT/releaf-mobile-icons.png"
