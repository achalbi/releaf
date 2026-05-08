#!/bin/bash
# strip-spm-shadow-files.sh
#
# Wipes macOS AppleDouble (`._*`) shadow files from SwiftPM checkouts
# before the Swift compiler can choke on them.
#
# Why this exists:
#   When DerivedData lives on a non-APFS filesystem (ExFAT external
#   drives, network shares, USB sticks, etc.) macOS can't store
#   extended attributes natively, so it shadows every file that has
#   xattrs with a sidecar named `._<filename>`. SwiftPM ships its
#   package archives with xattrs intact, so every "Resolve Package
#   Dependencies" pass leaves dozens of `._OIDAuthState.h`-style
#   files next to the real headers in `<DerivedData>/<project>/
#   SourcePackages/checkouts/<package>/Sources/...`.
#
#   The Swift driver then tries to parse those sidecars as headers
#   while building modulemap-based modules and the build dies with
#   `error: source file is not valid UTF-8`. The fix is to strip them
#   before each build. Cheap (~50ms over a fully-resolved checkout)
#   and idempotent.
#
#   Symptom we hit during the iOS fullscreen viewer ship: AppAuth-iOS
#   refused to compile its precompiled module map because every
#   header had a `._*.h` shadow next to it. The recurrence is
#   automatic — even after a manual `find -delete`, the very next
#   build re-extracts them. Wiring this into the Xcode build phase
#   keeps the failure from surfacing again.
#
# Two ways to invoke:
#   1. Run Script build phase (preferred — automatic on every build).
#      The phase passes BUILD_DIR through, which we use to locate
#      DerivedData without hard-coding the user's home dir.
#   2. CLI before `xcodebuild`:
#        ./apps/quickink/scripts/strip-spm-shadow-files.sh
#      Falls back to `~/Library/Developer/Xcode/DerivedData` so the
#      most common solo-dev setup just works.

set -euo pipefail

# Resolve the DerivedData root. Priority order:
#   1. Caller-supplied positional arg (handy for testing).
#   2. Xcode-supplied $BUILD_DIR (Run Script build phase env). Strip
#      everything from `/Build/Products` onward to land back at
#      `<DerivedData>/<ProjectName>-<hash>/`.
#   3. The default symlink at ~/Library/Developer/Xcode/DerivedData.
if [[ -n "${1:-}" ]]; then
    derived_root="$1"
elif [[ -n "${BUILD_DIR:-}" ]]; then
    derived_root="${BUILD_DIR%/Build/Products*}"
else
    derived_root="$HOME/Library/Developer/Xcode/DerivedData"
fi

if [[ ! -d "$derived_root" ]]; then
    # Nothing to clean. Don't fail the build for this — a fresh
    # checkout (no DerivedData yet) is a perfectly valid state.
    exit 0
fi

# Restrict the find scope to SourcePackages so we don't accidentally
# touch user code. AppleDouble shadow files in user source would be a
# separate problem this script isn't responsible for.
find "$derived_root" \
    -path "*/SourcePackages/*" \
    -name "._*" \
    -delete 2>/dev/null || true

# Also clean the global SwiftPM cache — it's not the path that fails
# today (the failure is in the per-project SourcePackages dir), but
# stripping it too is cheap and keeps a future cache-warmed build from
# hitting the same failure.
swiftpm_cache="$HOME/Library/Caches/org.swift.swiftpm"
if [[ -d "$swiftpm_cache" ]]; then
    find "$swiftpm_cache" -name "._*" -delete 2>/dev/null || true
fi
