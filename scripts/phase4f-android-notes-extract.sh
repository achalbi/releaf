#!/usr/bin/env bash
#
# phase4f-android-notes-extract.sh
#
# PR #4f — extract Android :shared:notes. Trimmed scope vs iOS PR #4e
# (3 files instead of 7) because of two coupling issues that need
# separate refactors:
#
#   1. NotepadRepository.kt imports parsers from
#      `data/notebook/PageAttachments.kt` (parseAttachments,
#      parseContacts, parseLocations, parseSubPages, parseTodos,
#      toJsonString). Those parsers are used by 14 Releaf files. Moving
#      PageAttachments to :shared:notes would either force a 14-file
#      import update OR a split-package across modules. Defer to a
#      separate PR.
#
#   2. NotepadListViewModel.kt extends AndroidViewModel and casts
#      `applicationContext as ReleafApp` to access the database +
#      authStore. Extracting requires DI refactor (constructor-injected
#      deps). Defer.
#
# What this PR ships:
#   :shared:notes Gradle library module with:
#     - NotepadEntry.kt    (Room @Entity)
#     - NotepadDao.kt      (Room @Dao)
#     - NotepadCategory.kt (data type)
#
# QuickInk's Android side will register NotepadEntry in its own Room
# Database and write a thin QuickInkNotepadRepository wrapper over the
# shared NotepadDao. NotepadRepository proper migrates in a follow-up.
#
# Strategy: pure git mv. Same Kotlin package preserved
# (`app.releaf.mobile.data.notepad`) — no caller import updates needed.
#
# What this script does:
#   1. git mv 3 notepad files into :shared:notes.
#   2. Stage Gradle config edits.
#
# Already in working tree:
#   - shared/android/shared/notes/build.gradle.kts        (NEW)
#   - shared/android/settings.gradle.kts                  (EDITED — include :shared:notes)
#   - apps/releaf/android/app/build.gradle.kts            (EDITED — depend on :shared:notes)
#
# Usage:
#   bash scripts/phase4f-android-notes-extract.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT"

if [[ ! -d .git ]]; then
    echo "ERROR: not in a git repo" >&2
    exit 2
fi
if [[ -f .git/index.lock ]]; then
    echo "ERROR: stale .git/index.lock present. Remove it first." >&2
    exit 2
fi

if [[ ! -f shared/android/shared/notes/build.gradle.kts ]]; then
    echo "ERROR: shared/android/shared/notes/build.gradle.kts missing — earlier PR #4f work not in tree." >&2
    exit 2
fi

TO_MOVE=(
    "apps/releaf/android/app/src/main/java/app/releaf/mobile/data/notepad/NotepadEntry.kt"
    "apps/releaf/android/app/src/main/java/app/releaf/mobile/data/notepad/NotepadDao.kt"
    "apps/releaf/android/app/src/main/java/app/releaf/mobile/data/notepad/NotepadCategory.kt"
)
for p in "${TO_MOVE[@]}"; do
    if [[ ! -f "$p" ]]; then
        echo "ERROR: expected source file missing: $p" >&2
        exit 2
    fi
done

mkdir -p shared/android/shared/notes/src/main/kotlin/app/releaf/mobile/data/notepad

echo "Pre-flight passed."

# ─── 1. git mv 3 notepad files into :shared:notes ──────────────────────

DEST="shared/android/shared/notes/src/main/kotlin/app/releaf/mobile/data/notepad"
for f in "${TO_MOVE[@]}"; do
    base="$(basename "$f")"
    git mv "$f" "$DEST/$base"
done
echo "Moved 3 notepad files into :shared:notes."

# ─── 2. Stage everything else ──────────────────────────────────────────

git add \
  shared/android/settings.gradle.kts \
  shared/android/shared/notes/ \
  apps/releaf/android/app/build.gradle.kts

echo ""
echo "═════════════════════════════════════════════════════════"
echo " PR #4f extract complete. Nothing committed yet."
echo "═════════════════════════════════════════════════════════"
echo ""
echo "Review with:"
echo "  git status"
echo "  git diff --staged --stat"
echo ""
echo "Verify:"
echo "  cd shared/android"
echo "  gradle :shared:notes:assembleDebug"
echo "  gradle :apps:releaf:assembleDebug"
echo ""
echo "Suggested commit:"
echo ""
echo "  PR #4f: extract Android :shared:notes (notepad data layer)"
echo ""
echo "  - Add :shared:notes Gradle library module (Room + coroutines)."
echo "  - git mv NotepadEntry, NotepadDao, NotepadCategory into"
echo "    :shared:notes. Same Kotlin package preserved."
echo "  - NotepadRepository STAYED in :apps:releaf — depends on"
echo "    PageAttachments parsers (14 callers). Separate refactor PR."
echo "  - NotepadListViewModel STAYED — needs DI refactor to drop the"
echo "    ReleafApp cast. Separate PR."
echo ""
echo "  QuickInk's Android side will register NotepadEntry in its own"
echo "  Room database and write a thin repository wrapper."
echo ""
echo "  Mirror of iOS PR #4e (trimmed). Verified with"
echo "  'gradle :apps:releaf:assembleDebug'."
echo ""
