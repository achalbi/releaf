#!/usr/bin/env bash
#
# phase3c-android-sync-extract.sh
#
# PR #3c of the QuickInk spinoff. Restructures Android's Gradle root
# to shared/android/ as a true multi-module monorepo build, extracts
# app-agnostic sync + drive code into :shared:sync and :shared:drive
# library modules, and refactors SyncRepository to take a
# SyncDataSource interface (mirror of iOS PR #3b).
#
# What this script does — atomic, history-preserving:
#
#   1. git mv apps/releaf/android/gradle/   → shared/android/gradle/
#   2. git rm apps/releaf/android/{settings.gradle.kts, build.gradle.kts}
#      (replaced by shared/android/{settings.gradle.kts, build.gradle.kts}
#       written in this PR)
#   3. git mv apps/releaf/android/gradle.properties → shared/android/gradle.properties
#   4. git mv 6 app-agnostic Sync source files into
#      shared/android/shared/sync/src/main/kotlin/app/releaf/mobile/data/sync/
#   5. git mv 3 app-agnostic Drive source files into
#      shared/android/shared/drive/src/main/kotlin/app/releaf/mobile/data/drive/
#   6. git rm the OLD apps/releaf/android/.../data/sync/SyncRepository.kt
#      (the new orchestrator already exists at
#       shared/android/shared/sync/.../SyncRepository.kt — substantial
#       rewrite, treated as delete+add for an honest reviewable diff).
#   7. Stage all unstaged edits.
#   8. Print final git status + suggested commit message + verification
#      commands.
#
# What this script does NOT do (already in working tree from PR #3c
# file-creation work):
#
#   - shared/android/{settings.gradle.kts,build.gradle.kts}     (NEW)
#   - shared/android/shared/sync/build.gradle.kts               (NEW)
#   - shared/android/shared/drive/build.gradle.kts              (NEW)
#   - shared/android/shared/sync/src/.../SyncDataSource.kt      (NEW)
#   - shared/android/shared/sync/src/.../SyncRepository.kt      (NEW)
#   - shared/android/shared/sync/src/.../SyncJson.kt            (NEW)
#   - apps/releaf/android/.../sync/ReleafSyncDataSource.kt      (NEW)
#   - apps/releaf/android/app/build.gradle.kts                  (EDITED)
#   - apps/releaf/android/.../ReleafApp.kt                      (EDITED)
#   - apps/releaf/android/.../sync/SyncWorker.kt                (EDITED)
#   - apps/releaf/android/.../sync/SyncPayloads.kt              (EDITED)
#   - apps/releaf/android/gradle/libs.versions.toml             (EDITED — added android-library plugin alias)
#
# Pre-flight requirements:
#   - On a branch (recommended: continue on quickink-phase-1 or branch off)
#   - Working tree should already contain the NEW files listed above.
#   - .git/index.lock not present.
#
# Usage:
#   bash scripts/phase3c-android-sync-extract.sh
#
# Recovery if the script fails mid-way:
#   git restore --staged .
#   git restore .
#   git clean -fd shared/android/gradle/ shared/android/gradle.properties

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

# Files this PR's earlier work created/edited and that the script depends on.
PRE_REQS=(
    "shared/android/settings.gradle.kts"
    "shared/android/build.gradle.kts"
    "shared/android/shared/sync/build.gradle.kts"
    "shared/android/shared/drive/build.gradle.kts"
    "shared/android/shared/sync/src/main/kotlin/app/releaf/mobile/data/sync/SyncDataSource.kt"
    "shared/android/shared/sync/src/main/kotlin/app/releaf/mobile/data/sync/SyncRepository.kt"
    "shared/android/shared/sync/src/main/kotlin/app/releaf/mobile/data/sync/SyncJson.kt"
    "apps/releaf/android/app/src/main/java/app/releaf/mobile/data/sync/ReleafSyncDataSource.kt"
)
for p in "${PRE_REQS[@]}"; do
    if [[ ! -f "$p" ]]; then
        echo "ERROR: expected pre-PR file missing: $p" >&2
        exit 2
    fi
done

# Files this script will move / remove.
TO_MOVE_SYNC=(
    "apps/releaf/android/app/src/main/java/app/releaf/mobile/data/sync/CanonicalJson.kt"
    "apps/releaf/android/app/src/main/java/app/releaf/mobile/data/sync/DrivePath.kt"
    "apps/releaf/android/app/src/main/java/app/releaf/mobile/data/sync/Manifest.kt"
    "apps/releaf/android/app/src/main/java/app/releaf/mobile/data/sync/DeviceIdentity.kt"
    "apps/releaf/android/app/src/main/java/app/releaf/mobile/data/sync/SyncStateEntity.kt"
    "apps/releaf/android/app/src/main/java/app/releaf/mobile/data/sync/SyncStateDao.kt"
)
TO_MOVE_DRIVE=(
    "apps/releaf/android/app/src/main/java/app/releaf/mobile/data/drive/DriveClient.kt"
    "apps/releaf/android/app/src/main/java/app/releaf/mobile/data/drive/DriveClientPath.kt"
    "apps/releaf/android/app/src/main/java/app/releaf/mobile/data/drive/OkHttpDriveClient.kt"
)
TO_RM=(
    "apps/releaf/android/app/src/main/java/app/releaf/mobile/data/sync/SyncRepository.kt"
    "apps/releaf/android/settings.gradle.kts"
    "apps/releaf/android/build.gradle.kts"
)
TO_GIT_MV_GRADLE_ROOT=(
    "apps/releaf/android/gradle.properties"
)
for p in "${TO_MOVE_SYNC[@]}" "${TO_MOVE_DRIVE[@]}" "${TO_RM[@]}" "${TO_GIT_MV_GRADLE_ROOT[@]}"; do
    if [[ ! -f "$p" ]]; then
        echo "ERROR: expected source file missing: $p" >&2
        exit 2
    fi
done

if [[ ! -d apps/releaf/android/gradle ]]; then
    echo "ERROR: expected source directory missing: apps/releaf/android/gradle" >&2
    exit 2
fi

# Make sure the destination dirs exist (parent must exist for git mv).
mkdir -p shared/android/shared/sync/src/main/kotlin/app/releaf/mobile/data/sync
mkdir -p shared/android/shared/drive/src/main/kotlin/app/releaf/mobile/data/drive

echo "Pre-flight passed. Starting moves."

# ─── 1. Move app-agnostic Sync sources into :shared:sync ───────────────

SYNC_DEST="shared/android/shared/sync/src/main/kotlin/app/releaf/mobile/data/sync"
for f in "${TO_MOVE_SYNC[@]}"; do
    base="$(basename "$f")"
    git mv "$f" "$SYNC_DEST/$base"
done
echo "Moved 6 Sync sources into :shared:sync."

# ─── 2. Move app-agnostic Drive sources into :shared:drive ─────────────

DRIVE_DEST="shared/android/shared/drive/src/main/kotlin/app/releaf/mobile/data/drive"
for f in "${TO_MOVE_DRIVE[@]}"; do
    base="$(basename "$f")"
    git mv "$f" "$DRIVE_DEST/$base"
done
echo "Moved 3 Drive sources into :shared:drive."

# ─── 3. Move Gradle wrapper / version catalog into the new root ────────

git mv apps/releaf/android/gradle shared/android/gradle
echo "Moved apps/releaf/android/gradle/ → shared/android/gradle/"

git mv apps/releaf/android/gradle.properties shared/android/gradle.properties
echo "Moved apps/releaf/android/gradle.properties → shared/android/gradle.properties"

# ─── 4. Remove the obsolete top-level Gradle config + old SyncRepository ──

# These are replaced by shared/android/{settings.gradle.kts,build.gradle.kts}
# (already on disk from the PR #3c file-creation work).
git rm apps/releaf/android/settings.gradle.kts
git rm apps/releaf/android/build.gradle.kts
echo "Removed obsolete apps/releaf/android/{settings.gradle.kts, build.gradle.kts}"

# Old SyncRepository.kt — replaced by shared/android/.../SyncRepository.kt.
# Treated as delete+add (substantial rewrite, similarity below git's
# rename-detection threshold) so the diff stays honest.
git rm apps/releaf/android/app/src/main/java/app/releaf/mobile/data/sync/SyncRepository.kt
echo "Removed obsolete apps/releaf/android/.../data/sync/SyncRepository.kt"

# ─── 5. Stage every unstaged edit + new file from this PR ──────────────

git add \
  apps/releaf/android/app/build.gradle.kts \
  apps/releaf/android/app/src/main/java/app/releaf/mobile/ReleafApp.kt \
  apps/releaf/android/app/src/main/java/app/releaf/mobile/data/sync/ReleafSyncDataSource.kt \
  apps/releaf/android/app/src/main/java/app/releaf/mobile/data/sync/SyncWorker.kt \
  apps/releaf/android/app/src/main/java/app/releaf/mobile/data/sync/SyncPayloads.kt \
  shared/android/

# ─── 6. Final report ───────────────────────────────────────────────────

echo ""
echo "═════════════════════════════════════════════════════════"
echo " PR #3c restructure complete. Nothing committed yet."
echo "═════════════════════════════════════════════════════════"
echo ""
echo "Review with:"
echo "  git status"
echo "  git diff --staged --stat"
echo "  git log --diff-filter=R --name-status -1   # show renames"
echo ""
echo "AFTER COMMITTING — verification commands (run from shared/android/):"
echo ""
echo "  cd shared/android"
echo "  # Local SDK pointer needed for gradle. Copy from the old Releaf"
echo "  # location (where it was untracked):"
echo "  cp ../../apps/releaf/android/local.properties ./local.properties"
echo ""
echo "  # Use system gradle (or Android Studio bundled gradle). The"
echo "  # current Releaf repo has no checked-in gradlew, so 'gradle'"
echo "  # from PATH or '~/Library/Android/sdk/...' bundled wrapper:"
echo "  gradle :shared:sync:assembleDebug"
echo "  gradle :shared:drive:assembleDebug"
echo "  gradle :apps:releaf:assembleDebug"
echo ""
echo "  # Or open shared/android/ in Android Studio (File → Open),"
echo "  # let it sync the Gradle project, then Build → Make Project."
echo ""
echo "Suggested commit message:"
echo ""
echo "  PR #3c: Android sync extract + Gradle multi-module restructure"
echo ""
echo "  - Promote shared/android/ to the new Gradle root. settings.gradle.kts"
echo "    + build.gradle.kts + gradle/ wrapper + gradle.properties live"
echo "    there now; apps/releaf/android/app/ becomes a leaf :apps:releaf"
echo "    module reached via projectDir override."
echo "  - Add :shared:sync (Room + serialization + coroutines) and"
echo "    :shared:drive (OkHttp + coroutines) library modules."
echo "  - Move app-agnostic Sync (CanonicalJson, DrivePath, Manifest,"
echo "    DeviceIdentity, SyncStateEntity, SyncStateDao) and Drive"
echo "    (DriveClient, DriveClientPath, OkHttpDriveClient) into the"
echo "    new modules via git mv."
echo "  - Refactor SyncRepository to take a SyncDataSource interface;"
echo "    new orchestrator at shared/android/.../sync/SyncRepository.kt."
echo "    Old Releaf-coupled SyncRepository deleted."
echo "  - Add ReleafSyncDataSource as Releaf's implementation."
echo "  - SyncWorker constructs fresh ReleafSyncDataSource + SyncRepository"
echo "    per work pass with the active session's userId; ReleafApp's"
echo "    syncRepository field removed."
echo "  - Promote SyncJson out of SyncPayloads into its own file in"
echo "    :shared:sync so both sides consume the same Json instance."
echo "  - Add android-library plugin alias to libs.versions.toml."
echo ""
echo "  Mirror of iOS PR #3b. Behavior change: none. Verified with"
echo "  'gradle :apps:releaf:assembleDebug' on the new build root."
echo ""
