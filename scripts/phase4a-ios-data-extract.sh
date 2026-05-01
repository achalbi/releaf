#!/usr/bin/env bash
#
# phase4a-ios-data-extract.sh
#
# PR #4a of the QuickInk spinoff. Extracts the four utility types
# (Uuidv7, IsoClock, FtsQuery, AttachmentStorage) out of Releaf and
# into ReleafCoreData (the shared SwiftPM target).
#
# Strategy: minimum-disruption extract via `@_exported import`. The
# moved files are copied (in this PR's earlier work) into
# ReleafCoreData with their public surface unchanged. ReleafData ships
# a thin shim that re-exports ReleafCoreData so existing Releaf source
# files (~30 callers across notebook repos, sync code, page screens,
# etc.) keep their `import ReleafData` and don't need any updates.
#
# What this script does:
#   1. git rm the 4 originals from apps/releaf/ios/Releaf/.
#      (Their replacements already exist in ReleafCoreData/.)
#   2. git rm the ReleafCoreData/Placeholder.swift (no longer needed).
#   3. Stage all unstaged edits for this PR.
#
# Why git rm + new file rather than git mv:
#   - The new files have a public-modifier change (AttachmentStorage
#     went from internal enum to public enum + public methods + a new
#     public mutable static `appFolderName` for the QuickInk override).
#     Similarity drops below git's rename-detection threshold; a clean
#     delete+add gives reviewers an honest diff.
#   - Uuidv7 / IsoClock / FtsQuery contents are identical; reviewers
#     reading those will see "deleted from Releaf, added to ReleafCore"
#     and that's clearer than a misleading "rename" with no content delta.
#
# What this script does NOT do (already in working tree):
#   - shared/ios/ReleafCore/Sources/ReleafCoreData/Uuidv7.swift          (NEW)
#   - shared/ios/ReleafCore/Sources/ReleafCoreData/IsoClock.swift        (NEW)
#   - shared/ios/ReleafCore/Sources/ReleafCoreData/FtsQuery.swift        (NEW)
#   - shared/ios/ReleafCore/Sources/ReleafCoreData/AttachmentStorage.swift (NEW)
#   - apps/releaf/ios/Releaf/Data/ReleafCoreReexports.swift              (NEW — shim)
#   - apps/releaf/ios/Package.swift                                       (EDITED — ReleafCoreData dep)
#   - shared/ios/ReleafCore/Sources/ReleafCoreSync/SyncRepository.swift   (EDITED — drop nowIsoUtc, use IsoClock)
#
# Usage:
#   bash scripts/phase4a-ios-data-extract.sh
#
# Pre-flight:
#   - On a branch (continue on quickink-phase-1 or branch off)
#   - Working tree contains the NEW files above
#   - .git/index.lock not present
#
# Recovery:
#   git restore --staged .
#   git restore .

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
    echo "ERROR: stale .git/index.lock present. Remove it first:" >&2
    echo "  rm $REPO_ROOT/.git/index.lock" >&2
    exit 2
fi

PRE_REQS=(
    "shared/ios/ReleafCore/Sources/ReleafCoreData/Uuidv7.swift"
    "shared/ios/ReleafCore/Sources/ReleafCoreData/IsoClock.swift"
    "shared/ios/ReleafCore/Sources/ReleafCoreData/FtsQuery.swift"
    "shared/ios/ReleafCore/Sources/ReleafCoreData/AttachmentStorage.swift"
    "apps/releaf/ios/Releaf/Data/ReleafCoreReexports.swift"
)
for p in "${PRE_REQS[@]}"; do
    if [[ ! -f "$p" ]]; then
        echo "ERROR: expected pre-PR file missing: $p" >&2
        exit 2
    fi
done

TO_RM=(
    "apps/releaf/ios/Releaf/Data/Notepad/Uuidv7.swift"
    "apps/releaf/ios/Releaf/Data/Notepad/IsoClock.swift"
    "apps/releaf/ios/Releaf/Data/Notepad/FtsQuery.swift"
    "apps/releaf/ios/Releaf/Features/Notepad/Sections/AttachmentStorage.swift"
    "shared/ios/ReleafCore/Sources/ReleafCoreData/Placeholder.swift"
)
for p in "${TO_RM[@]}"; do
    if [[ ! -f "$p" ]]; then
        echo "ERROR: expected source file missing: $p" >&2
        exit 2
    fi
done

echo "Pre-flight passed."

# ─── 1. Remove the 4 originals from Releaf ────────────────────────────

git rm apps/releaf/ios/Releaf/Data/Notepad/Uuidv7.swift
git rm apps/releaf/ios/Releaf/Data/Notepad/IsoClock.swift
git rm apps/releaf/ios/Releaf/Data/Notepad/FtsQuery.swift
git rm apps/releaf/ios/Releaf/Features/Notepad/Sections/AttachmentStorage.swift
echo "Removed 4 utility files from Releaf."

# ─── 2. Drop ReleafCoreData placeholder ───────────────────────────────

git rm shared/ios/ReleafCore/Sources/ReleafCoreData/Placeholder.swift
echo "Removed ReleafCoreData placeholder."

# ─── 3. Stage everything else ─────────────────────────────────────────

git add \
  apps/releaf/ios/Package.swift \
  apps/releaf/ios/Releaf/Data/ReleafCoreReexports.swift \
  shared/ios/ReleafCore/Sources/ReleafCoreData/ \
  shared/ios/ReleafCore/Sources/ReleafCoreSync/SyncRepository.swift

echo ""
echo "═════════════════════════════════════════════════════════"
echo " PR #4a extract complete. Nothing committed yet."
echo "═════════════════════════════════════════════════════════"
echo ""
echo "Review with:"
echo "  git status"
echo "  git diff --staged --stat"
echo ""
echo "Verify on Mac before committing:"
echo "  cd apps/releaf/ios"
echo "  xcodebuild -scheme ReleafFeatures \\"
echo "    -destination 'generic/platform=iOS Simulator' build 2>&1 \\"
echo "    | grep -E 'error:' | head -30"
echo ""
echo "Suggested commit message:"
echo ""
echo "  PR #4a: extract iOS ReleafCoreData (utility types)"
echo ""
echo "  - Move Uuidv7, IsoClock, FtsQuery into ReleafCoreData target."
echo "  - Move AttachmentStorage in too, parameterized via new"
echo "    public-mutable-static \`appFolderName\` (defaults to \"Releaf\";"
echo "    QuickInk overrides at app init)."
echo "  - Add @_exported import shim in ReleafData so existing Releaf"
echo "    callers (~30 files) keep their \`import ReleafData\` unchanged."
echo "  - Drop SyncRepository's temporary inline nowIsoUtc() helper"
echo "    (added in PR #3b); it now uses IsoClock from ReleafCoreData."
echo "  - Drop ReleafCoreData/Placeholder.swift — superseded by the"
echo "    real content."
echo ""
echo "  Verified with xcodebuild -scheme ReleafFeatures."
echo ""
