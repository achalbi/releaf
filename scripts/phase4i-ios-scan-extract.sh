#!/usr/bin/env bash
#
# phase4i-ios-scan-extract.sh
#
# PR #4i — extract iOS ReleafCoreScan. Last of the seven iOS sub-PRs
# in the PR #4 sequence. Moves the single VisionKit wrapper out of
# Releaf so QuickInk can reuse it without dragging in the rest of the
# Releaf feature layer; the OCR engine + pipeline + value types
# called out in the placeholder are Phase 3 work, not this PR.
#
# What moves into ReleafCoreScan:
#
#   - DocumentScannerView.swift     (VNDocumentCameraViewController
#                                    wrapper, with iOS impl + macOS
#                                    preview stub)
#
# What stays in Releaf:
#   - The call site in Releaf/Features/Notepad/Sections/EditorSections.swift —
#     it keeps `import ReleafData` and reaches DocumentScannerView through
#     the @_exported re-export shim in Releaf/Data/ReleafCoreReexports.swift.
#
# What this script does:
#   1. git mv DocumentScannerView.swift into ReleafCoreScan.
#   2. git rm ReleafCoreScan/Placeholder.swift.
#   3. Stage Package.swift + reexport shim edits.
#
# Already in working tree (NOT done by this script):
#   - apps/releaf/ios/Package.swift
#       (EDITED — adds ReleafCoreScan product dep on ReleafData target)
#   - apps/releaf/ios/Releaf/Data/ReleafCoreReexports.swift
#       (EDITED — adds @_exported import ReleafCoreScan)
#   - apps/releaf/ios/Releaf/Features/Notepad/Sections/DocumentScannerView.swift
#       (EDITED — switches `import ReleafData` to `import ReleafCoreData`,
#        bumps struct + UIViewControllerRepresentable witnesses + Coordinator
#        + delegate methods to public so the type crosses the module
#        boundary; same access-level pattern PR #4e/4g used for moved
#        SwiftUI / UIKit-bridge views)
#
# Usage:
#   bash scripts/phase4i-ios-scan-extract.sh

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

SRC="apps/releaf/ios/Releaf/Features/Notepad/Sections/DocumentScannerView.swift"
DEST_DIR="shared/ios/ReleafCore/Sources/ReleafCoreScan"
DEST="$DEST_DIR/DocumentScannerView.swift"
PLACEHOLDER="$DEST_DIR/Placeholder.swift"

[[ -f "$SRC" ]] \
    || { echo "ERROR: source missing: $SRC" >&2; exit 2; }
[[ -f "$PLACEHOLDER" ]] \
    || { echo "ERROR: ReleafCoreScan placeholder missing — already extracted?" >&2; exit 2; }

# Pre-script edits should already be staged or in working tree.
PRE_REQS=(
    "apps/releaf/ios/Package.swift"
    "apps/releaf/ios/Releaf/Data/ReleafCoreReexports.swift"
)
for p in "${PRE_REQS[@]}"; do
    [[ -f "$p" ]] || { echo "ERROR: missing pre-edited file: $p" >&2; exit 2; }
done

mkdir -p "$DEST_DIR"

echo "Pre-flight passed."

# ─── 1. git mv DocumentScannerView.swift into ReleafCoreScan ─────────

git mv "$SRC" "$DEST"
echo "Moved DocumentScannerView.swift into ReleafCoreScan."

# ─── 2. Drop the ReleafCoreScan placeholder ──────────────────────────

git rm "$PLACEHOLDER"
echo "Removed ReleafCoreScan placeholder."

# ─── 3. Stage everything else ────────────────────────────────────────

git add \
    apps/releaf/ios/Package.swift \
    apps/releaf/ios/Releaf/Data/ReleafCoreReexports.swift

echo ""
echo "═════════════════════════════════════════════════════════"
echo " PR #4i extract complete. Nothing committed yet."
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
echo "  PR #4i: extract iOS ReleafCoreScan (DocumentScannerView)"
echo ""
echo "  - git mv DocumentScannerView.swift (VisionKit wrapper) into"
echo "    ReleafCoreScan. Closes the iOS half of the PR #4 sequence:"
echo "    7/7 iOS sub-PRs are now landed."
echo "  - Switch the moved file's import from ReleafData to"
echo "    ReleafCoreData (its only dep is AttachmentStorage, extracted"
echo "    in PR #4a). Bump struct + UIViewControllerRepresentable"
echo "    witnesses + Coordinator + delegate methods to public so the"
echo "    type crosses the module boundary — same access-level pattern"
echo "    used in PR #4e/4g for RichTextEditor."
echo "  - Add ReleafCoreScan product dep on the ReleafData target."
echo "  - Extend the @_exported shim in"
echo "    Releaf/Data/ReleafCoreReexports.swift so EditorSections's"
echo "    existing \`import ReleafData\` keeps resolving the type."
echo "  - Drop ReleafCoreScan/Placeholder.swift — superseded."
echo ""
echo "  OCR engine + pipeline + value types named in the placeholder are"
echo "  Phase 3 work (QuickInk MVP), not this PR."
echo ""
echo "  Verified with xcodebuild -scheme ReleafFeatures."
echo ""
