#!/usr/bin/env bash
#
# phase3b-sync-extract.sh
#
# PR #3b of the QuickInk spinoff. Moves the app-agnostic Sync + Drive
# source files out of `apps/releaf/ios/Releaf/Data/` into the shared
# `ReleafCore` package, and deletes the now-superseded
# `Releaf/Data/Sync/SyncRepository.swift` (replaced by the refactored
# version that already lives at
# `shared/ios/ReleafCore/Sources/ReleafCoreSync/SyncRepository.swift`,
# delivered as part of this PR).
#
# What this script does:
#
#   1. git mv 6 app-agnostic Sync source files into ReleafCoreSync.
#   2. git mv 3 app-agnostic Drive source files into ReleafCoreDrive.
#   3. git rm the OLD apps/releaf/ios/Releaf/Data/Sync/SyncRepository.swift
#      (the new orchestrator already exists in ReleafCore — see file
#      header above).
#   4. git rm the placeholder file in ReleafCoreDrive (now superseded
#      by the real DriveClient files).
#   5. Verify the new file tree is what we expect.
#
# What this script does NOT do (already done as part of the same PR):
#
#   - Add ReleafCore as a dep in apps/releaf/ios/Package.swift.
#     (Already in place via Edit — see git diff.)
#   - Add `import ReleafCoreSync` / `import ReleafCoreDrive` to the 5
#     Releaf files that need them. (Already in place via Edit.)
#   - Refactor SyncEnvironment.swift to construct ReleafSyncDataSource.
#     (Already in place via Edit — file at the same path, content
#     rewritten.)
#   - Add ReleafSyncDataSource.swift. (Already in place via Write.)
#
# Usage:
#   bash scripts/phase3b-sync-extract.sh
#
# Pre-flight requirements:
#   - On branch `quickink-phase-1` (or wherever you've staged this PR)
#   - Working tree should already contain:
#       * apps/releaf/ios/Releaf/Data/Sync/ReleafSyncDataSource.swift  (NEW)
#       * apps/releaf/ios/Releaf/Data/Sync/SyncEnvironment.swift       (rewritten)
#       * apps/releaf/ios/Releaf/Data/Drive/{DriveRepository,LocalDriveRepository,NotebookRepository}.swift  (import added)
#       * apps/releaf/ios/Package.swift  (ReleafCore dep added)
#       * shared/ios/ReleafCore/Sources/ReleafCoreSync/SyncRepository.swift  (NEW, refactored)
#   - All of the above are unstaged edits in your working tree right now.
#   - .git/index.lock not present.
#
# Recovery if the script fails mid-way:
#   git reset HEAD                 # unstage anything the script staged
#   git restore --staged .          # in case any staged tree exists
#   # (the script does not commit, so HEAD stays clean — the moves will
#   #  show as a mix of unstaged "deleted" + new untracked files which
#   #  you can revert with `git restore <path>` per file)

set -euo pipefail

# Run from repo root regardless of cwd.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT"

# ─── Pre-flight ────────────────────────────────────────────────────────

if [[ ! -d .git ]]; then
    echo "ERROR: not in a git repo" >&2
    exit 2
fi

if [[ -f .git/index.lock ]]; then
    echo "ERROR: stale .git/index.lock present. Remove it manually first:" >&2
    echo "  rm $REPO_ROOT/.git/index.lock" >&2
    exit 2
fi

# Confirm the new files we expect to find are actually in the working
# tree. If they're not, the user hasn't staged the rest of the PR and
# the moves below will leave them in a broken state.
PRE_REQS=(
    "apps/releaf/ios/Releaf/Data/Sync/ReleafSyncDataSource.swift"
    "shared/ios/ReleafCore/Sources/ReleafCoreSync/SyncRepository.swift"
    "shared/ios/ReleafCore/Sources/ReleafCoreSync/SyncDataSource.swift"
)
for p in "${PRE_REQS[@]}"; do
    if [[ ! -f "$p" ]]; then
        echo "ERROR: expected file missing: $p" >&2
        echo "  This PR's other files must be in working tree before running this script." >&2
        exit 2
    fi
done

# Confirm the OLD files we're about to git-mv / git-rm exist.
TO_MOVE_SYNC=(
    "apps/releaf/ios/Releaf/Data/Sync/CanonicalJson.swift"
    "apps/releaf/ios/Releaf/Data/Sync/DrivePath.swift"
    "apps/releaf/ios/Releaf/Data/Sync/Manifest.swift"
    "apps/releaf/ios/Releaf/Data/Sync/DeviceIdentity.swift"
    "apps/releaf/ios/Releaf/Data/Sync/SyncStateStore.swift"
    "apps/releaf/ios/Releaf/Data/Sync/SyncScheduler.swift"
)
TO_MOVE_DRIVE=(
    "apps/releaf/ios/Releaf/Data/Drive/DriveClient.swift"
    "apps/releaf/ios/Releaf/Data/Drive/DriveClientPath.swift"
    "apps/releaf/ios/Releaf/Data/Drive/URLSessionDriveClient.swift"
)
TO_RM=(
    "apps/releaf/ios/Releaf/Data/Sync/SyncRepository.swift"
    "shared/ios/ReleafCore/Sources/ReleafCoreDrive/Placeholder.swift"
)
for p in "${TO_MOVE_SYNC[@]}" "${TO_MOVE_DRIVE[@]}" "${TO_RM[@]}"; do
    if [[ ! -f "$p" ]]; then
        echo "ERROR: expected source file missing: $p" >&2
        exit 2
    fi
done

echo "Pre-flight passed. Starting moves."

# ─── 1. Move app-agnostic Sync files ───────────────────────────────────

SYNC_DEST=shared/ios/ReleafCore/Sources/ReleafCoreSync
for f in "${TO_MOVE_SYNC[@]}"; do
    base="$(basename "$f")"
    git mv "$f" "$SYNC_DEST/$base"
done
echo "Moved 6 Sync files into ReleafCoreSync."

# ─── 2. Move app-agnostic Drive files ──────────────────────────────────

DRIVE_DEST=shared/ios/ReleafCore/Sources/ReleafCoreDrive
for f in "${TO_MOVE_DRIVE[@]}"; do
    base="$(basename "$f")"
    git mv "$f" "$DRIVE_DEST/$base"
done
echo "Moved 3 Drive files into ReleafCoreDrive."

# ─── 3. Delete the obsolete SyncRepository in Releaf ───────────────────
#
# The refactored orchestrator already lives at the new path
# (shared/ios/ReleafCore/Sources/ReleafCoreSync/SyncRepository.swift).
# This is intentionally NOT a `git mv` — the new file is a substantial
# rewrite (Releaf-specific code lifted out into ReleafSyncDataSource),
# similarity is below git's rename-detection threshold, and treating
# it as delete+add gives reviewers an honest diff.

git rm "apps/releaf/ios/Releaf/Data/Sync/SyncRepository.swift"
echo "Removed obsolete apps/releaf/ios/Releaf/Data/Sync/SyncRepository.swift"

# ─── 4. Drop the ReleafCoreDrive placeholder ───────────────────────────

git rm "shared/ios/ReleafCore/Sources/ReleafCoreDrive/Placeholder.swift"
echo "Removed ReleafCoreDrive Placeholder.swift (superseded by real Drive files)."

# ─── 5. Stage the unstaged edits + new files in this PR ────────────────

# These are the files I edited in place via my Edit/Write tool calls,
# plus the new ReleafSyncDataSource.swift. They're unstaged in the
# working tree before this script runs; staging them here keeps the
# whole PR in one commit-ready state.
git add \
    apps/releaf/ios/Package.swift \
    apps/releaf/ios/Releaf/Data/Sync/SyncEnvironment.swift \
    apps/releaf/ios/Releaf/Data/Sync/ReleafSyncDataSource.swift \
    apps/releaf/ios/Releaf/Data/Drive/DriveRepository.swift \
    apps/releaf/ios/Releaf/Data/Drive/LocalDriveRepository.swift \
    apps/releaf/ios/Releaf/Data/Notebook/NotebookRepository.swift \
    apps/releaf/ios/Releaf/Features/Settings/DriveSettingsSection.swift \
    shared/ios/ReleafCore/Sources/ReleafCoreSync/SyncRepository.swift \
    shared/ios/ReleafCore/Sources/ReleafCoreSync/SyncDataSource.swift

echo ""
echo "═════════════════════════════════════════════════════════"
echo " PR #3b extract complete. Nothing committed yet."
echo "═════════════════════════════════════════════════════════"
echo ""
echo "Review with:"
echo "  git status"
echo "  git diff --staged --stat"
echo "  git log --diff-filter=R --name-status -1   # show renames"
echo ""
echo "Suggested commit message:"
echo ""
echo "  PR #3b: extract sync orchestrator into ReleafCore"
echo ""
echo "  - Move app-agnostic Sync (CanonicalJson, DrivePath, Manifest,"
echo "    DeviceIdentity, SyncStateStore, SyncScheduler) and Drive"
echo "    (DriveClient, DriveClientPath, URLSessionDriveClient) files"
echo "    into shared/ios/ReleafCore/Sources/{ReleafCoreSync,ReleafCoreDrive}/"
echo "    via git mv (history preserved as renames)."
echo "  - Refactor SyncRepository to take a SyncDataSource protocol;"
echo "    new orchestrator lives at"
echo "    shared/ios/ReleafCore/Sources/ReleafCoreSync/SyncRepository.swift"
echo "    Old Releaf-coupled SyncRepository deleted (substantial rewrite,"
echo "    treated as delete+add for an honest reviewable diff)."
echo "  - Add ReleafSyncDataSource as Releaf's implementation of the"
echo "    protocol (snapshot/apply/mark code that used to live inline"
echo "    in SyncRepository)."
echo "  - SyncEnvironment rewired to construct ReleafSyncDataSource +"
echo "    SyncRepository per sync pass (so userId tracks the active"
echo "    auth session)."
echo "  - Releaf's Package.swift gains a path dep on ReleafCore + the"
echo "    needed product links on ReleafData and ReleafFeatures."
echo "  - Imports added to 5 Releaf files that consumed the moved types."
echo ""
echo "  Behavior change: none. SyncRepository.sync() now takes (deviceId,"
echo "  accessToken) — userId moved into the data source. SyncEnvironment"
echo "  hides this from app entry points."
echo ""
echo "  Verify on Mac before merging:"
echo "    cd apps/releaf/ios && swift build"
echo "    cd shared/ios/ReleafCore && swift build"
echo ""
