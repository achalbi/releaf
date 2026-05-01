#!/usr/bin/env bash
#
# phase4b-android-data-extract.sh
#
# PR #4b — Android equivalent of PR #4a. Extracts the four utility
# types (Uuidv7, IsoClock, FtsQuery, AttachmentStorage) out of
# `:apps:releaf` and into a new `:shared:data` Gradle library module.
#
# Strategy: same minimum-disruption shape as iOS, leveraging Kotlin's
# split-package-across-modules. Files keep their `package
# app.releaf.mobile.data.common` declaration; existing
# `import app.releaf.mobile.data.common.Uuidv7` etc. callers (~50
# files across Releaf) keep working unchanged because the Kotlin
# imports resolve by fully-qualified name regardless of which Gradle
# module the type lives in (as long as the consuming module declares
# a dep on `:shared:data`).
#
# What this script does:
#   1. git rm the 4 originals from apps/releaf/android/.../data/common/.
#      (Replacements already exist at shared/android/shared/data/...)
#   2. Stage all unstaged edits for this PR.
#
# Why git rm + new file rather than git mv:
#   - AttachmentStorage's content changed (added `appFolderName` static
#     for the QuickInk override; inlined the URI helpers to keep the
#     module dep-free). Other 3 files are content-identical but the
#     diff readability of "deleted from Releaf, added to :shared:data"
#     is clearer than a git mv with a comment-only edit.
#
# What this script does NOT do (already in working tree):
#   - shared/android/shared/data/build.gradle.kts                       (NEW)
#   - shared/android/shared/data/src/.../{Uuidv7,IsoClock,FtsQuery,AttachmentStorage}.kt (NEW)
#   - shared/android/settings.gradle.kts                                (EDITED — include :shared:data)
#   - shared/android/shared/sync/build.gradle.kts                       (EDITED — depend on :shared:data)
#   - shared/android/shared/sync/src/.../sync/SyncRepository.kt         (EDITED — drop nowIsoUtc, use IsoClock)
#   - apps/releaf/android/app/build.gradle.kts                          (EDITED — depend on :shared:data)
#
# Usage:
#   bash scripts/phase4b-android-data-extract.sh
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
    "shared/android/shared/data/build.gradle.kts"
    "shared/android/shared/data/src/main/kotlin/app/releaf/mobile/data/common/Uuidv7.kt"
    "shared/android/shared/data/src/main/kotlin/app/releaf/mobile/data/common/IsoClock.kt"
    "shared/android/shared/data/src/main/kotlin/app/releaf/mobile/data/common/FtsQuery.kt"
    "shared/android/shared/data/src/main/kotlin/app/releaf/mobile/data/common/AttachmentStorage.kt"
)
for p in "${PRE_REQS[@]}"; do
    if [[ ! -f "$p" ]]; then
        echo "ERROR: expected pre-PR file missing: $p" >&2
        exit 2
    fi
done

TO_RM=(
    "apps/releaf/android/app/src/main/java/app/releaf/mobile/data/common/Uuidv7.kt"
    "apps/releaf/android/app/src/main/java/app/releaf/mobile/data/common/IsoClock.kt"
    "apps/releaf/android/app/src/main/java/app/releaf/mobile/data/common/FtsQuery.kt"
    "apps/releaf/android/app/src/main/java/app/releaf/mobile/data/common/AttachmentStorage.kt"
)
for p in "${TO_RM[@]}"; do
    if [[ ! -f "$p" ]]; then
        echo "ERROR: expected source file missing: $p" >&2
        exit 2
    fi
done

echo "Pre-flight passed."

# ─── 1. Remove the 4 originals from :apps:releaf ──────────────────────

git rm apps/releaf/android/app/src/main/java/app/releaf/mobile/data/common/Uuidv7.kt
git rm apps/releaf/android/app/src/main/java/app/releaf/mobile/data/common/IsoClock.kt
git rm apps/releaf/android/app/src/main/java/app/releaf/mobile/data/common/FtsQuery.kt
git rm apps/releaf/android/app/src/main/java/app/releaf/mobile/data/common/AttachmentStorage.kt
echo "Removed 4 utility files from :apps:releaf."

# ─── 2. Stage everything else ─────────────────────────────────────────

git add \
  apps/releaf/android/app/build.gradle.kts \
  shared/android/settings.gradle.kts \
  shared/android/shared/data/ \
  shared/android/shared/sync/build.gradle.kts \
  shared/android/shared/sync/src/main/kotlin/app/releaf/mobile/data/sync/SyncRepository.kt

echo ""
echo "═════════════════════════════════════════════════════════"
echo " PR #4b extract complete. Nothing committed yet."
echo "═════════════════════════════════════════════════════════"
echo ""
echo "Review with:"
echo "  git status"
echo "  git diff --staged --stat"
echo ""
echo "Verify before committing:"
echo "  cd shared/android"
echo "  gradle :shared:data:assembleDebug"
echo "  gradle :shared:sync:assembleDebug"
echo "  gradle :apps:releaf:assembleDebug"
echo ""
echo "Suggested commit message:"
echo ""
echo "  PR #4b: extract Android :shared:data (utility types)"
echo ""
echo "  - Add :shared:data Gradle library module — pure utility types"
echo "    shared between Releaf and QuickInk. Zero external deps."
echo "  - Move Uuidv7, IsoClock, FtsQuery into :shared:data unchanged."
echo "  - Move AttachmentStorage in too, parameterized via new mutable"
echo "    static \`appFolderName\` (defaults to \"releaf\"; QuickInk"
echo "    overrides at app init). URI helpers (toFile/toUri) inlined"
echo "    to keep the module dep-free."
echo "  - Drop SyncRepository's temporary inline nowIsoUtc() helper"
echo "    (added in PR #3c); now uses IsoClock from :shared:data."
echo "  - Same Kotlin package preserved (\`app.releaf.mobile.data.common\`)"
echo "    so existing imports across Releaf (~50 files) keep working"
echo "    without per-file edits."
echo ""
echo "  Mirror of iOS PR #4a. Verified with"
echo "  'gradle :apps:releaf:assembleDebug' on the new build root."
echo ""
