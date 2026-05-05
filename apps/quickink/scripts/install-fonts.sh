#!/usr/bin/env bash
#
# install-fonts.sh
#
# Installs Fraunces and Inter (OFL) into both iOS and Android
# resource directories with the filenames the QuickInk theme
# expects. Two modes:
#
#   1. ZIP MODE — pass a path to a Google Fonts download zip:
#
#        ./scripts/install-fonts.sh ~/Downloads/Google_Fonts.zip
#
#      Get the zip from https://fonts.google.com/selection after
#      adding Fraunces + Inter via their specimen pages. The script
#      extracts the six TTFs we need and copies them into place.
#      `find` handles both flat zips and ones with `static/`
#      subfolders.
#
#   2. CDN MODE — no args. Tries to download from jsdelivr's mirror
#      of `github.com/google/fonts`. Faster when it works, but
#      occasionally Inter gets restructured in the upstream repo
#      and the URL 404s. Use ZIP MODE when that happens.
#
# After running you should see (regardless of mode):
#
#   ios/QuickInk/DesignSystem/Fonts/Fraunces-Regular.ttf
#   ios/QuickInk/DesignSystem/Fonts/Fraunces-Medium.ttf
#   ios/QuickInk/DesignSystem/Fonts/Fraunces-Italic.ttf
#   ios/QuickInk/DesignSystem/Fonts/Fraunces-MediumItalic.ttf
#   ios/QuickInk/DesignSystem/Fonts/Inter-Regular.ttf
#   ios/QuickInk/DesignSystem/Fonts/Inter-Medium.ttf
#
#   android/app/src/main/res/font/fraunces_regular.ttf
#   android/app/src/main/res/font/fraunces_medium.ttf
#   android/app/src/main/res/font/fraunces_italic.ttf
#   android/app/src/main/res/font/fraunces_medium_italic.ttf
#   android/app/src/main/res/font/inter_regular.ttf
#   android/app/src/main/res/font/inter_medium.ttf

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

IOS_FONTS="${APP_DIR}/ios/QuickInk/DesignSystem/Fonts"
ANDROID_FONTS="${APP_DIR}/android/app/src/main/res/font"

mkdir -p "${IOS_FONTS}" "${ANDROID_FONTS}"

# (zip basename | iOS filename | Android filename)
#
# Google Fonts' newer multi-axis build embeds the optical size in
# the filename and PostScript name. We pin to:
#   - Fraunces 72pt — the display optical. Picked for the boutique
#     magazine feel (high contrast, airy hairlines, dramatic
#     italics). At our smallest rendering size (cardTitle 14pt) the
#     strokes will read thin — fine on retina, watch in QA on
#     lower-density Android. To swap to a body-optimised optical
#     instead, change "_72pt-" → "_14pt-" below and "SemiBold" →
#     "Medium" in QuickInkFont.frauncesPostScriptName, then re-run.
#   - Inter 18pt — Inter's smallest optical, perfect for our
#     11–16pt UI text.
#
# Note: Fraunces 72pt's static set goes Light → Regular → SemiBold →
# Bold (no Medium). The spec calls for SemiBold (CSS weight 600) at
# H2/page-title sizes, so the "heading weight" maps to SemiBold
# here. The Android resource still gets named `fraunces_medium.ttf`
# — the resource ID is what Compose binds to FontWeight.Medium, the
# file's internal weight class doesn't matter. Renaming the
# resource would force every R.font.* call site to change.
#
# iOS keeps the original names so the bundled file → PostScript
# name mapping stays obvious (PS name embedded in the file is
# `Fraunces72pt-Regular`/`Fraunces72pt-SemiBold`, matching what
# QuickInkFont.appSerif() requests).
declare -a TARGETS=(
  "Fraunces_72pt-Regular.ttf|Fraunces_72pt-Regular.ttf|fraunces_regular.ttf"
  "Fraunces_72pt-SemiBold.ttf|Fraunces_72pt-SemiBold.ttf|fraunces_medium.ttf"
  "Fraunces_72pt-Italic.ttf|Fraunces_72pt-Italic.ttf|fraunces_italic.ttf"
  "Fraunces_72pt-SemiBoldItalic.ttf|Fraunces_72pt-SemiBoldItalic.ttf|fraunces_medium_italic.ttf"
  "Inter_18pt-Regular.ttf|Inter_18pt-Regular.ttf|inter_regular.ttf"
  "Inter_18pt-Medium.ttf|Inter_18pt-Medium.ttf|inter_medium.ttf"
)

print_targets() {
  echo "Installing fonts into:"
  echo "  iOS:     ${IOS_FONTS}"
  echo "  Android: ${ANDROID_FONTS}"
  echo
}

# ---------- ZIP MODE ----------

install_from_zip() {
  local zip="$1"
  if [ ! -f "${zip}" ]; then
    echo "Error: ${zip} does not exist or is not a file"
    exit 1
  fi
  if ! command -v unzip >/dev/null 2>&1; then
    echo "Error: 'unzip' not found on PATH"
    exit 1
  fi

  print_targets
  echo "Reading ${zip}..."

  local tmp
  tmp="$(mktemp -d -t qi-fonts.XXXXXX)"
  trap 'rm -rf "${tmp}"' EXIT

  unzip -q "${zip}" -d "${tmp}"

  local failed=0
  for entry in "${TARGETS[@]}"; do
    IFS='|' read -r basename ios_name android_name <<< "${entry}"
    printf "  → %-32s " "${basename}"

    # Find the file anywhere inside the extracted tree. Google's
    # zips put statics under `<Family>/static/`; pulling by name
    # is resilient to that and to any layout tweaks they make.
    local src
    src="$(find "${tmp}" -type f -name "${basename}" 2>/dev/null | head -n 1)"

    if [ -z "${src}" ]; then
      echo "NOT FOUND in zip"
      failed=$((failed + 1))
      continue
    fi

    cp "${src}" "${IOS_FONTS}/${ios_name}"
    cp "${src}" "${ANDROID_FONTS}/${android_name}"
    echo "OK"
  done

  if [ "${failed}" -gt 0 ]; then
    echo
    echo "${failed} file(s) missing from zip. Check that BOTH families"
    echo "are in your Google Fonts selection:"
    echo "  Fraunces — needs Regular, Medium, Italic, MediumItalic"
    echo "  Inter — needs Regular, Medium"
    echo "Re-download from https://fonts.google.com/selection and retry."
    exit 1
  fi

  print_done
}

# ---------- CDN MODE ----------

install_from_cdn() {
  local gfonts="https://cdn.jsdelivr.net/gh/google/fonts@main/ofl"

  declare -a URLS=(
    "${gfonts}/fraunces/static/Fraunces_72pt-Regular.ttf"
    "${gfonts}/fraunces/static/Fraunces_72pt-SemiBold.ttf"
    "${gfonts}/fraunces/static/Fraunces_72pt-Italic.ttf"
    "${gfonts}/fraunces/static/Fraunces_72pt-SemiBoldItalic.ttf"
    "${gfonts}/inter/static/Inter_18pt-Regular.ttf"
    "${gfonts}/inter/static/Inter_18pt-Medium.ttf"
  )

  print_targets

  local failed=0
  local i=0
  for entry in "${TARGETS[@]}"; do
    IFS='|' read -r _ ios_name android_name <<< "${entry}"
    local url="${URLS[${i}]}"
    i=$((i + 1))

    printf "  → %-32s " "${ios_name}"

    local tmp
    tmp="$(mktemp -t qi-font.XXXXXX)"
    if curl -sSL --fail --output "${tmp}" "${url}"; then
      cp "${tmp}" "${IOS_FONTS}/${ios_name}"
      cp "${tmp}" "${ANDROID_FONTS}/${android_name}"
      rm -f "${tmp}"
      echo "OK"
    else
      rm -f "${tmp}"
      echo "FAIL  (${url})"
      failed=$((failed + 1))
    fi
  done

  if [ "${failed}" -gt 0 ]; then
    echo
    echo "${failed} file(s) failed to download from the CDN. Most"
    echo "likely the upstream repo path changed (Inter is the usual"
    echo "culprit). Use ZIP MODE instead:"
    echo
    echo "  1. https://fonts.google.com/specimen/Fraunces — Get font"
    echo "  2. https://fonts.google.com/specimen/Inter — Get font"
    echo "  3. https://fonts.google.com/selection — Download all"
    echo "  4. ./scripts/install-fonts.sh ~/Downloads/Google_Fonts.zip"
    exit 1
  fi

  print_done
}

print_done() {
  echo
  echo "Done. Next steps:"
  echo "  iOS — Package.swift already declares .process(\"DesignSystem/Fonts\");"
  echo "        QuickInkFont.registerAll() in AppMain.swift picks them up at"
  echo "        launch. In Xcode: Product → Clean Build Folder → Run."
  echo "  Android — Compose auto-resources res/font/. ./gradlew :app:assembleDebug"
  echo "        or Build → Rebuild Project in Android Studio."
}

# ---------- Dispatch ----------

if [ "$#" -ge 1 ] && [ "$1" != "--help" ] && [ "$1" != "-h" ]; then
  install_from_zip "$1"
elif [ "$#" -ge 1 ]; then
  cat <<EOF
Usage:
  install-fonts.sh                          # CDN mode (jsdelivr + google/fonts)
  install-fonts.sh <path-to-google-fonts-zip>   # ZIP mode

Get the zip from https://fonts.google.com/selection after adding
Fraunces and Inter via their specimen pages, then "Download all".
EOF
  exit 0
else
  install_from_cdn
fi
