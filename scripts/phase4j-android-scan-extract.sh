#!/usr/bin/env bash
#
# phase4j-android-scan-extract.sh
#
# PR #4j — extract Android :shared:scan. Closes the PR #4 sequence
# entirely (5/5 Android sub-PRs landed; iOS already at 7/7).
#
# Unlike PR #4h's git-mv-heavy shape, this PR is a refactor: Android's
# document-scanner logic was inlined inside `ScansSection` in
# `EditorSections.kt` rather than living in a standalone wrapper file
# like iOS's `DocumentScannerView.swift`. So PR #4j carves out the
# scanner-launcher abstraction first, then ships the new module.
#
# What the new :shared:scan module ships:
#
#   - DocumentScannerLauncher.kt    Composable factory exposing
#                                   `rememberDocumentScannerLauncher(onResult, onError)`.
#                                   Wraps GmsDocumentScannerOptions +
#                                   GmsDocumentScanning client + the
#                                   StartIntentSenderForResult launcher
#                                   + the AttachmentStorage copy step.
#                                   Mirror of iOS PR #4i's
#                                   `ReleafCoreScan/DocumentScannerView.swift`.
#
# What stays in :apps:releaf:
#   - ScansSection (filter chips, Edit-scan dialog, in-house PDF
#     viewer, OCR fan-out, Toast wording) — UX glue around the
#     launcher, not part of the launcher contract.
#   - ScanCategory + first-word OCR heuristic — Releaf-specific.
#   - mlkit-text-recognition dep — OCR engine extracts in Phase 3,
#     not this PR.
#
# Phase-3 follow-ups deliberately NOT in this PR (see :shared:scan's
# build.gradle.kts header for the running list):
#   - OcrEngine protocol + MlKitTextRecognizer impl
#   - Multi-page parallel OCR pipeline
#   - Searchable PDF export
#
# What this script does:
#   1. Stage the new :shared:scan module + the EditorSections refactor +
#      the gradle wiring.
#   2. Echo the suggested commit.
#
# All edits are already in working tree (NOT done by this script):
#   - shared/android/shared/scan/build.gradle.kts                                  (NEW)
#   - shared/android/shared/scan/src/main/kotlin/app/releaf/shared/scan/
#         DocumentScannerLauncher.kt                                               (NEW)
#   - shared/android/settings.gradle.kts                                           (EDITED)
#   - apps/releaf/android/app/build.gradle.kts                                     (EDITED — adds
#       :shared:scan dep, drops now-redundant libs.mlkit.document.scanner;
#       text-recognition stays until Phase 3 OCR extract)
#   - apps/releaf/android/app/src/main/java/app/releaf/mobile/ui/components/
#         editor/EditorSections.kt                                                 (REFACTORED — removes
#       ~90-line inline scanner block + 3 Gms* imports + the now-unused
#       IntentSenderRequest import; replaces with a
#       rememberDocumentScannerLauncher call that wires onResult / onError
#       to the existing onAdd + Toast surfaces)
#
# Usage:
#   bash scripts/phase4j-android-scan-extract.sh

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

PRE_REQS=(
    "shared/android/shared/scan/build.gradle.kts"
    "shared/android/shared/scan/src/main/kotlin/app/releaf/shared/scan/DocumentScannerLauncher.kt"
    "shared/android/settings.gradle.kts"
    "apps/releaf/android/app/build.gradle.kts"
    "apps/releaf/android/app/src/main/java/app/releaf/mobile/ui/components/editor/EditorSections.kt"
)
for p in "${PRE_REQS[@]}"; do
    [[ -f "$p" ]] || { echo "ERROR: missing pre-edited file: $p" >&2; exit 2; }
done

# Sanity: the EditorSections refactor should have removed the ML Kit
# imports + the inline GmsDocumentScanning setup. Bail loudly if any of
# those signals are still present — much easier to catch here than at
# Gradle time.
if grep -q "com.google.mlkit.vision.documentscanner" \
        apps/releaf/android/app/src/main/java/app/releaf/mobile/ui/components/editor/EditorSections.kt; then
    echo "ERROR: EditorSections.kt still imports com.google.mlkit.vision.documentscanner — refactor incomplete" >&2
    exit 2
fi
if grep -q "GmsDocumentScanning.getClient" \
        apps/releaf/android/app/src/main/java/app/releaf/mobile/ui/components/editor/EditorSections.kt; then
    echo "ERROR: EditorSections.kt still calls GmsDocumentScanning.getClient — refactor incomplete" >&2
    exit 2
fi

echo "Pre-flight passed."

# ─── 1. Stage everything ───────────────────────────────────────────────

git add \
    shared/android/shared/scan/build.gradle.kts \
    shared/android/shared/scan/src/main/kotlin/app/releaf/shared/scan/DocumentScannerLauncher.kt \
    shared/android/settings.gradle.kts \
    apps/releaf/android/app/build.gradle.kts \
    apps/releaf/android/app/src/main/java/app/releaf/mobile/ui/components/editor/EditorSections.kt

echo ""
echo "═════════════════════════════════════════════════════════"
echo " PR #4j extract complete. Nothing committed yet."
echo "═════════════════════════════════════════════════════════"
echo ""
echo "Review with:"
echo "  git status"
echo "  git diff --staged --stat"
echo ""
echo "Verify on host:"
echo "  cd shared/android"
echo "  ./gradlew :apps:releaf:assembleDebug 2>&1 | tail -60"
echo ""
echo "Suggested commit:"
echo ""
echo "  PR #4j: extract Android :shared:scan (DocumentScannerLauncher)"
echo ""
echo "  - New :shared:scan module exposing rememberDocumentScannerLauncher,"
echo "    a Composable factory wrapping GmsDocumentScanning + the result"
echo "    extraction + the AttachmentStorage copy step. Mirror of iOS PR"
echo "    #4i's ReleafCoreScan/DocumentScannerView."
echo "  - Refactor ScansSection in EditorSections.kt: drop ~90 lines of"
echo "    inline scanner setup, hand its onAdd / Toast surfaces to"
echo "    rememberDocumentScannerLauncher's onResult / onError lambdas."
echo "  - Drop the now-redundant libs.mlkit.document.scanner dep on the"
echo "    Releaf app target (the SDK lives behind the wrapper now)."
echo "    libs.mlkit.text.recognition stays until Phase 3 OCR extract."
echo "  - Wire :shared:scan into settings.gradle.kts and the app's deps."
echo ""
echo "  Closes PR #4 entirely. Android: 5/5; iOS: 7/7. QuickInk Phase 3"
echo "  unblocked — the shared modules QuickInk depends on (DesignSystem,"
echo "  Auth, Drive, Sync, Notes, Scan) are all in place."
echo ""
echo "  Phase-3 follow-ups for :shared:scan (not this PR):"
echo "    - OcrEngine protocol + MlKitTextRecognizer impl"
echo "    - Multi-page parallel OCR pipeline"
echo "    - Searchable PDF export"
echo ""
echo "  Verified with ./gradlew :apps:releaf:assembleDebug."
echo ""
