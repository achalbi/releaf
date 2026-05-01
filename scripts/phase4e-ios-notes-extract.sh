#!/usr/bin/env bash
#
# phase4e-ios-notes-extract.sh
#
# PR #4e — extract iOS ReleafCoreNotes. Moves 7 truly app-agnostic
# notes files out of Releaf and into the shared ReleafCoreNotes
# target.
#
# Trimmed scope (NOT all Notes files):
#
#   Moved:
#     Data layer:
#       - NotepadEntry.swift          (GRDB row + V2 payload mapping)
#       - NotepadCategory.swift       (category data)
#       - NotepadRepository.swift     (CRUD + FTS5 search)
#       - Attachments.swift           (typed JSON value types)
#     Feature layer:
#       - NotepadListViewModel.swift  (UI-agnostic VM)
#       - NotepadEditorViewModel.swift (UI-agnostic VM)
#       - RichTextEditor.swift        (UITextView wrapper, no DS deps)
#
#   STAYED in Releaf (require things ReleafCore doesn't yet have):
#     - RichTextFormatBar, EditorModeToggle, EntryDateRow,
#       NotesEditorSheet — all import ReleafDesignSystem (Releaf's,
#       not ReleafCore's). Move them in PR #4g (DesignSystem) which
#       sets up the shared design tokens first.
#     - NotepadEditorScreen, NotepadView, NotepadScreenViewModel,
#       NotepadCalendarBloom, NotepadGardenTiles, OverviewPane,
#       Recents/, Sections/ — Releaf-shaped UI on top of the VMs.
#       QuickInk writes its own thin equivalents.
#     - AyurvedicCatalog, DailyPlants, VoiceTranscriber — Releaf-
#       specific (plants, voice).
#
# Strategy: pure git mv. No content edits — files were already public,
# no Releaf-specific code paths. History stays clean.
#
# What this script does:
#   1. git mv 7 notes files into ReleafCoreNotes.
#   2. git rm ReleafCoreNotes/Placeholder.swift.
#   3. Stage Package.swift edits + shim addition.
#
# Already in working tree (NOT done by this script):
#   - shared/ios/ReleafCore/Package.swift     (EDITED — adds GRDB dep
#     on the package + on ReleafCoreNotes target)
#   - apps/releaf/ios/Package.swift           (EDITED — adds
#     ReleafCoreNotes product dep on ReleafData target)
#   - apps/releaf/ios/Releaf/Data/ReleafCoreReexports.swift (EDITED —
#     adds @_exported import ReleafCoreNotes)
#
# Usage:
#   bash scripts/phase4e-ios-notes-extract.sh

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
    "apps/releaf/ios/Releaf/Data/Notepad/NotepadEntry.swift"
    "apps/releaf/ios/Releaf/Data/Notepad/NotepadCategory.swift"
    "apps/releaf/ios/Releaf/Data/Notepad/NotepadRepository.swift"
    "apps/releaf/ios/Releaf/Data/Notepad/Attachments.swift"
    "apps/releaf/ios/Releaf/Features/Notepad/NotepadListViewModel.swift"
    "apps/releaf/ios/Releaf/Features/Notepad/NotepadEditorViewModel.swift"
    "apps/releaf/ios/Releaf/Features/Notepad/RichTextEditor.swift"
)
for p in "${TO_MOVE[@]}"; do
    if [[ ! -f "$p" ]]; then
        echo "ERROR: expected source file missing: $p" >&2
        exit 2
    fi
done

if [[ ! -f shared/ios/ReleafCore/Sources/ReleafCoreNotes/Placeholder.swift ]]; then
    echo "ERROR: ReleafCoreNotes/Placeholder.swift not present — already extracted?" >&2
    exit 2
fi

mkdir -p shared/ios/ReleafCore/Sources/ReleafCoreNotes

echo "Pre-flight passed."

# ─── 1. git mv 7 files into ReleafCoreNotes ────────────────────────────

DEST="shared/ios/ReleafCore/Sources/ReleafCoreNotes"
for f in "${TO_MOVE[@]}"; do
    base="$(basename "$f")"
    git mv "$f" "$DEST/$base"
done
echo "Moved 7 notes files into ReleafCoreNotes."

# ─── 2. Drop ReleafCoreNotes placeholder ───────────────────────────────

git rm "$DEST/Placeholder.swift"
echo "Removed ReleafCoreNotes placeholder."

# ─── 3. Stage everything else ──────────────────────────────────────────

git add \
  shared/ios/ReleafCore/Package.swift \
  apps/releaf/ios/Package.swift \
  apps/releaf/ios/Releaf/Data/ReleafCoreReexports.swift

echo ""
echo "═════════════════════════════════════════════════════════"
echo " PR #4e extract complete. Nothing committed yet."
echo "═════════════════════════════════════════════════════════"
echo ""
echo "Review with:"
echo "  git status"
echo "  git diff --staged --stat"
echo ""
echo "Verify on Mac:"
echo "  cd apps/releaf/ios"
echo "  xcodebuild -scheme ReleafFeatures \\"
echo "    -destination 'generic/platform=iOS Simulator' build 2>&1 \\"
echo "    | grep -E 'error:' | head -30"
echo ""
echo "Suggested commit:"
echo ""
echo "  PR #4e: extract iOS ReleafCoreNotes (notepad data + VMs +"
echo "  RichTextEditor)"
echo ""
echo "  - git mv NotepadEntry, NotepadCategory, NotepadRepository,"
echo "    Attachments (data layer) plus NotepadListViewModel,"
echo "    NotepadEditorViewModel, RichTextEditor (feature layer) into"
echo "    ReleafCoreNotes."
echo "  - Add GRDB dep on the ReleafCore package + on ReleafCoreNotes."
echo "  - Extend ReleafData's @_exported re-export shim to include"
echo "    ReleafCoreNotes. Existing Releaf callers keep their"
echo "    \`import ReleafData\` unchanged."
echo "  - Drop ReleafCoreNotes/Placeholder.swift — superseded."
echo ""
echo "  RichTextFormatBar, EditorModeToggle, EntryDateRow, NotesEditorSheet"
echo "  STAYED in Releaf — they import ReleafDesignSystem which is still"
echo "  empty. Move them in PR #4g once DesignSystem extracts."
echo ""
echo "  Verified with xcodebuild -scheme ReleafFeatures."
echo ""
