#!/usr/bin/env bash
#
# install-fonts.sh
#
# Installs Inter (OFL) into both iOS and Android resource directories
# with the filenames the QuickInk theme expects. Two modes:
#
#   1. ZIP MODE — pass a path to a Google Fonts download zip:
#
#        ./scripts/install-fonts.sh ~/Downloads/Google_Fonts.zip
#
#      Get the zip from https://fonts.google.com/selection after
#      adding Inter via its specimen page. The script extracts the
#      two TTFs we need and copies them into place. `find` handles
#      both flat zips and ones with `static/` subfolders.
#
#   2. CDN MODE — no args. Tries to download from jsdelivr's mirror
#      of `github.com/google/fonts`. Faster when it works, but
#      occasionally Inter gets restructured in the upstream repo
#      and the URL 404s. Use ZIP MODE when that happens.
#
# Cormorant Garamond and Caveat are committed directly to the repo
# (Cormorant ships ten files, Caveat one — small enough that pulling
# them every time isn't worth the script complexity).
#
# After running you should see (regardless of mode):
#
#   ios/QuickInk/DesignSystem/Fonts/Inter_18pt-Regular.ttf
#   ios/QuickInk/DesignSystem/Fonts/Inter_18pt-Medium.ttf
#
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
# Inter 18pt is Inter's smallest optical, perfect for our 11–16pt UI
# text. iOS keeps the original PascalCase filename so the bundled
# file → PostScript name mapping stays obvious (PS name embedded in
# the file is `Inter18pt-Regular`/`Inter18pt-Medium`, matching what
# QuickInkFont.ui() requests).
declare -a TARGETS=(
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
    echo "${failed} file(s) missing from zip. Check that Inter is in"
    echo "your Google Fonts selection (needs Regular and Medium)."
    echo "Re-download from https://fonts.google.com/selection and retry."
    exit 1
  fi

  print_done
}

# ---------- CDN MODE ----------

install_from_cdn() {
  local gfonts="https://cdn.jsdelivr.net/gh/google/fonts@main/ofl"

  declare -a URLS=(
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
    echo "likely the upstream repo path changed. Use ZIP MODE instead:"
    echo
    echo "  1. https://fonts.google.com/specimen/Inter — Get font"
    echo "  2. https://fonts.google.com/selection — Download all"
    echo "  3. ./scripts/install-fonts.sh ~/Downloads/Google_Fonts.zip"
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
Inter via its specimen page, then "Download all".
EOF
  exit 0
else
  install_from_cdn
fi
