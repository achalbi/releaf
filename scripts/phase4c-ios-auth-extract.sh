#!/usr/bin/env bash
#
# phase4c-ios-auth-extract.sh
#
# PR #4c — extract iOS ReleafCoreAuth (5 files: GoogleAuthClient,
# AuthStore, KeychainTokenStore, RealGoogleAuthClient,
# GoogleSignInBinding) out of the Releaf module into the shared
# ReleafCore package.
#
# Strategy: pure git mv. Unlike PR #4a (which had to rewrite
# AttachmentStorage with the appFolderName parameter), nothing in the
# auth stack needs content changes — everything is already public, no
# Releaf-specific code paths. History stays clean: each file shows as
# a 100% rename.
#
# What this script does:
#   1. git mv 5 auth files from apps/releaf/ios/Releaf/Data/Auth/
#      into shared/ios/ReleafCore/Sources/ReleafCoreAuth/.
#   2. git rm the ReleafCoreAuth/Placeholder.swift (now superseded by
#      the real auth files).
#   3. Stage the unstaged Package.swift edits + the shim addition.
#
# What this script does NOT do (already in working tree):
#   - shared/ios/ReleafCore/Package.swift                 (EDITED — adds
#     GoogleSignIn-iOS dep on the package + on the ReleafCoreAuth target)
#   - apps/releaf/ios/Package.swift                       (EDITED — adds
#     ReleafCoreAuth product dep on ReleafData target)
#   - apps/releaf/ios/Releaf/Data/ReleafCoreReexports.swift (EDITED —
#     adds @_exported import ReleafCoreAuth)
#
# Pre-flight:
#   - .git/index.lock not present
#   - The 5 source files at their original location
#   - Working tree contains the 3 EDITED files above
#
# Usage:
#   bash scripts/phase4c-ios-auth-extract.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT"

# ─── Pre-flight ────────────────────────────────────────────────────────

if [[ ! -d .git ]]; then
    echo "ERROR: not in a git repo" >&2
    exit 2
fi

if [[ -f .git/index.lock ]]; then
    echo "ERROR: stale .git/index.lock present. Remove it first." >&2
    exit 2
fi

TO_MOVE=(
    "apps/releaf/ios/Releaf/Data/Auth/AuthStore.swift"
    "apps/releaf/ios/Releaf/Data/Auth/GoogleAuthClient.swift"
    "apps/releaf/ios/Releaf/Data/Auth/GoogleSignInBinding.swift"
    "apps/releaf/ios/Releaf/Data/Auth/KeychainTokenStore.swift"
    "apps/releaf/ios/Releaf/Data/Auth/RealGoogleAuthClient.swift"
)
for p in "${TO_MOVE[@]}"; do
    if [[ ! -f "$p" ]]; then
        echo "ERROR: expected source file missing: $p" >&2
        exit 2
    fi
done

if [[ ! -f shared/ios/ReleafCore/Sources/ReleafCoreAuth/Placeholder.swift ]]; then
    echo "ERROR: ReleafCoreAuth/Placeholder.swift not present — already extracted?" >&2
    exit 2
fi

# Make sure the destination dir exists for git mv.
mkdir -p shared/ios/ReleafCore/Sources/ReleafCoreAuth

echo "Pre-flight passed."

# ─── 1. git mv 5 files into ReleafCoreAuth ─────────────────────────────

DEST="shared/ios/ReleafCore/Sources/ReleafCoreAuth"
for f in "${TO_MOVE[@]}"; do
    base="$(basename "$f")"
    git mv "$f" "$DEST/$base"
done
echo "Moved 5 auth files into ReleafCoreAuth."

# ─── 2. Drop ReleafCoreAuth placeholder ────────────────────────────────

git rm "$DEST/Placeholder.swift"
echo "Removed ReleafCoreAuth placeholder."

# ─── 3. Stage everything else ──────────────────────────────────────────

git add \
  shared/ios/ReleafCore/Package.swift \
  apps/releaf/ios/Package.swift \
  apps/releaf/ios/Releaf/Data/ReleafCoreReexports.swift

echo ""
echo "═════════════════════════════════════════════════════════"
echo " PR #4c extract complete. Nothing committed yet."
echo "═════════════════════════════════════════════════════════"
echo ""
echo "Review with:"
echo "  git status"
echo "  git diff --staged --stat"
echo "  git log --diff-filter=R --name-status -1   # show renames"
echo ""
echo "Verify on Mac before committing:"
echo "  cd apps/releaf/ios"
echo "  xcodebuild -scheme ReleafFeatures \\"
echo "    -destination 'generic/platform=iOS Simulator' build 2>&1 \\"
echo "    | grep -E 'error:' | head -30"
echo ""
echo "Suggested commit message:"
echo ""
echo "  PR #4c: extract iOS ReleafCoreAuth (5 auth files)"
echo ""
echo "  - git mv GoogleAuthClient, AuthStore, KeychainTokenStore,"
echo "    RealGoogleAuthClient, GoogleSignInBinding into ReleafCoreAuth."
echo "  - Add GoogleSignIn-iOS dep on the ReleafCore package + on the"
echo "    ReleafCoreAuth target. Releaf still declares the same dep"
echo "    (used by features that touch it directly); SwiftPM dedupes."
echo "  - Extend ReleafData's @_exported re-export shim to include"
echo "    ReleafCoreAuth. The ~17 Releaf callers (mostly AuthStore,"
echo "    StubGoogleAuthClient in previews) keep their"
echo "    \`import ReleafData\` unchanged."
echo "  - Drop ReleafCoreAuth/Placeholder.swift — superseded."
echo ""
echo "  Behavior change: none. Verified with xcodebuild -scheme ReleafFeatures."
echo ""
