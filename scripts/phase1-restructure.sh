#!/usr/bin/env bash
#
# phase1-restructure.sh
#
# Phase 1 of the QuickInk spinoff: mechanical move of the existing
# Releaf top-level dirs into the monorepo layout described in
# docs/QUICKINK_PROPOSAL.md §3 and docs/QUICKINK_DESIGN.md §4.
#
# What this script does — atomic, history-preserving, NO content edits
# beyond the path-fixups listed below:
#
#   1. git mv ios            → apps/releaf/ios
#   2. git mv android        → apps/releaf/android
#   3. git mv design-system  → shared/design-system
#   4. Repoint symlinks broken by the moves (plants.json ×2,
#      canonical-json-fixture.json ×1).
#   5. Update path constants in:
#        - shared/design-system/scripts/generate-tokens.mjs
#        - shared/design-system/scripts/check-tokens.sh
#   6. Update top-level README.md layout diagram.
#   7. Update docstring references to old paths in .swift / .kt / .md
#      files (so copy-pasted commands actually work after the move).
#   8. Stage everything with `git add -u`.
#
# What this script DOES NOT do:
#   - Commit. The user reviews `git diff --staged` and commits manually.
#   - Touch any code logic. This is a mechanical move only.
#   - Extract the shared ReleafCore package — that's Phase 2 (separate PR).
#   - Scaffold QuickInk — that's Phase 3.
#
# Pre-flight requirements:
#   - Working tree clean (no uncommitted modifications)
#   - On the branch you want this PR to land against (typically
#     `quickink-phase-1` branched from `main`)
#   - .git/index.lock not present
#
# Usage:
#   bash scripts/phase1-restructure.sh
#
# Recovery if anything goes wrong before commit:
#   git restore --staged .
#   git restore .
#   git clean -fd apps/ shared/    # nukes the new dirs

set -euo pipefail

# Run from repo root regardless of cwd.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT"

# ─── Pre-flight ────────────────────────────────────────────────────────

if [[ ! -d .git ]]; then
    echo "ERROR: not in a git repo (no .git at $REPO_ROOT)" >&2
    exit 2
fi

if [[ -f .git/index.lock ]]; then
    echo "ERROR: stale .git/index.lock present. Remove it manually first:" >&2
    echo "  rm $REPO_ROOT/.git/index.lock" >&2
    exit 2
fi

if ! git diff --quiet || ! git diff --cached --quiet; then
    echo "ERROR: working tree has uncommitted changes. Commit or stash first." >&2
    git status --short >&2
    exit 2
fi

# Verify nothing's already moved (this script is not idempotent — it's
# a one-shot. Re-running on a half-moved tree would compound the mess).
if [[ -d apps/releaf || -d shared/design-system ]]; then
    echo "ERROR: apps/releaf or shared/design-system already exists." >&2
    echo "If a previous run failed mid-flight, restore with:" >&2
    echo "  git restore --staged ." >&2
    echo "  git restore ." >&2
    echo "  git clean -fd apps/ shared/" >&2
    exit 2
fi

if [[ ! -d ios || ! -d android || ! -d design-system ]]; then
    echo "ERROR: expected top-level dirs (ios, android, design-system) not found." >&2
    exit 2
fi

CURRENT_BRANCH="$(git rev-parse --abbrev-ref HEAD)"
echo "Current branch: $CURRENT_BRANCH"
if [[ "$CURRENT_BRANCH" == "main" ]]; then
    echo "WARNING: you are on main. Recommended: branch first with" >&2
    echo "  git checkout -b quickink-phase-1" >&2
    echo "Press Ctrl-C to abort, or hit Return to continue on main." >&2
    read -r
fi

echo "Pre-flight passed. Starting moves."

# ─── 1–3. The three big moves ──────────────────────────────────────────

mkdir -p apps/releaf shared

git mv ios            apps/releaf/ios
git mv android        apps/releaf/android
git mv design-system  shared/design-system

echo "Moved ios → apps/releaf/ios"
echo "Moved android → apps/releaf/android"
echo "Moved design-system → shared/design-system"

# ─── 4. Repoint broken symlinks ────────────────────────────────────────
#
# git mv preserves the symlink TARGET text verbatim — it doesn't rewrite
# relative paths to compensate for the new source location. We have to
# do it. `ln -sfn TARGET LINK` overwrites the existing symlink in place.
#
# Symlinks in the repo (excluding build artefacts under .build/):
#   apps/releaf/ios/Releaf/Data/Notepad/Resources/plants.json
#       was: ../../../../../design-system/plants.json
#       now: ../../../../../../../shared/design-system/plants.json
#
#   apps/releaf/ios/Tests/ReleafDataTests/Resources/canonical-json-fixture.json
#       was: ../../../../design-system/fixtures/canonical-json-fixture.json
#       now: ../../../../../../shared/design-system/fixtures/canonical-json-fixture.json
#
#   apps/releaf/android/app/src/main/assets/plants.json
#       was: ../../../../../design-system/plants.json
#       now: ../../../../../../../shared/design-system/plants.json
#
# Verify each new target resolves to a real file before continuing.

retarget_symlink() {
    local symlink_path="$1"
    local new_target="$2"

    if [[ ! -L "$symlink_path" ]]; then
        echo "ERROR: expected symlink at $symlink_path" >&2
        exit 3
    fi

    ln -sfn "$new_target" "$symlink_path"

    # Resolve through the symlink to make sure it lands on a real file.
    if [[ ! -e "$symlink_path" ]]; then
        echo "ERROR: retargeted symlink $symlink_path → $new_target does not resolve" >&2
        exit 3
    fi
}

retarget_symlink \
    apps/releaf/ios/Releaf/Data/Notepad/Resources/plants.json \
    ../../../../../../../shared/design-system/plants.json

retarget_symlink \
    apps/releaf/ios/Tests/ReleafDataTests/Resources/canonical-json-fixture.json \
    ../../../../../../shared/design-system/fixtures/canonical-json-fixture.json

retarget_symlink \
    apps/releaf/android/app/src/main/assets/plants.json \
    ../../../../../../../shared/design-system/plants.json

echo "Retargeted 3 symlinks."

# ─── 5. Update token-pipeline path constants ───────────────────────────
#
# generate-tokens.mjs and check-tokens.sh both compute REPO_ROOT
# relative to themselves and then concatenate hard-coded
# 'design-system/...', 'ios/...', 'android/...' segments. After the
# move all three need adjusting.

GENERATOR=shared/design-system/scripts/generate-tokens.mjs
CHECK_TOKENS=shared/design-system/scripts/check-tokens.sh

# generate-tokens.mjs:
#   REPO_ROOT was script_dir/../../  (script lived at design-system/scripts/)
#                                     → repo root at design-system/../../  = root
#   REPO_ROOT now must be script_dir/../../../  (script at shared/design-system/scripts/)
sed -i.bak \
    -e "s|resolve(__dirname, '..', '..')|resolve(__dirname, '..', '..', '..')|" \
    -e "s|join(REPO_ROOT, 'design-system'|join(REPO_ROOT, 'shared', 'design-system'|" \
    -e "s|\['ios', 'Releaf'|['apps', 'releaf', 'ios', 'Releaf'|g" \
    -e "s|\['android', 'app'|['apps', 'releaf', 'android', 'app'|g" \
    "$GENERATOR"
rm "$GENERATOR.bak"

# check-tokens.sh:
sed -i.bak \
    -e 's|cd "$SCRIPT_DIR/../.."|cd "$SCRIPT_DIR/../../.."|' \
    -e 's|/design-system/scripts/generate-tokens.mjs|/shared/design-system/scripts/generate-tokens.mjs|' \
    -e 's|^IOS_REL="ios/Releaf|IOS_REL="apps/releaf/ios/Releaf|' \
    -e 's|^ANDROID_REL="android/app|ANDROID_REL="apps/releaf/android/app|' \
    -e 's|design-system/scripts/generate-tokens.mjs|shared/design-system/scripts/generate-tokens.mjs|g' \
    "$CHECK_TOKENS"
rm "$CHECK_TOKENS.bak"

echo "Updated generate-tokens.mjs + check-tokens.sh path constants."

# ─── 6. Update top-level README.md layout diagram ──────────────────────
#
# README has an ASCII tree showing the old top-level layout. Replace
# the diagram block.

python3 - <<'PY'
import re, pathlib
p = pathlib.Path('README.md')
src = p.read_text()
old = """\
```
releaf/
├── docs/
│   ├── ARCHITECTURE.md      System design, MVVM, Drive-backed storage
│   └── DRIVE_SCHEMA.md      Exact folder + JSON layout in Google Drive
├── design-system/
│   ├── design-tokens.json   Tokens Studio schema — colors / type / spacing
│   └── DESIGN_SYSTEM.md     Human-readable design system doc
├── ios/
│   ├── Package.swift        SwiftPM manifest — previews render from here
│   └── Releaf/              Swift sources (DesignSystem, Data, Features)
└── android/
    ├── settings.gradle.kts  Root + app project
    ├── build.gradle.kts
    ├── gradle/              Gradle wrapper + version catalog
    └── app/                 Android app module
```"""
new = """\
```
releaf/
├── docs/
│   ├── ARCHITECTURE.md      System design, MVVM, Drive-backed storage
│   ├── DRIVE_SCHEMA.md      Exact folder + JSON layout in Google Drive
│   ├── QUICKINK_PROPOSAL.md QuickInk spinoff — repo restructure plan
│   ├── QUICKINK_DESIGN.md   QuickInk engineering design (sync, OCR, CI)
│   └── QUICKINK_BRAND_BRIEF.md   Brand brief for the QuickInk sibling app
├── apps/
│   └── releaf/
│       ├── ios/
│       │   ├── Package.swift  SwiftPM manifest — previews render from here
│       │   └── Releaf/        Swift sources (DesignSystem, Data, Features)
│       └── android/
│           ├── settings.gradle.kts  Root + app project
│           ├── build.gradle.kts
│           ├── gradle/        Gradle wrapper + version catalog
│           └── app/           Android app module
└── shared/
    └── design-system/
        ├── design-tokens.json   Tokens Studio schema — colors / type / spacing
        └── DESIGN_SYSTEM.md     Human-readable design system doc
```"""
if old not in src:
    raise SystemExit("ERROR: README.md layout block not found verbatim — abort.")
p.write_text(src.replace(old, new))
PY

# Other README path mentions (Getting started block).
sed -i.bak \
    -e 's|cd ios|cd apps/releaf/ios|g' \
    -e 's|cd android|cd apps/releaf/android|g' \
    -e 's|Open `android/`|Open `apps/releaf/android/`|g' \
    README.md
rm README.md.bak

echo "Updated README.md layout + getting-started commands."

# ─── 7. Update docstring path references ───────────────────────────────
#
# Many .swift / .kt / .md files contain comments pointing at
# `design-system/...` paths. After the move those copy-pasted commands
# / file references go to nowhere. Fix in bulk.
#
# Skipped:
#   - Anything under .git/, .build/, .gradle/, build/, .kotlin/
#   - .resolved files (auto-generated, will regenerate on next build)
#
# Substitutions (each applied repo-wide):
#   "design-system/"                  → "shared/design-system/"
#   "../../design-system/"            → relative paths preserved as-is
#                                       (these live INSIDE design-system or
#                                       are already correct in moved files)

# Use a portable find that excludes build / VCS dirs.
mapfile -t TARGETS < <(find . \
    -type f \
    \( -name '*.swift' -o -name '*.kt' -o -name '*.kts' -o -name '*.md' -o -name '*.toml' \) \
    ! -path './.git/*' \
    ! -path '*/.build/*' \
    ! -path '*/.gradle/*' \
    ! -path '*/.kotlin/*' \
    ! -path '*/build/*' \
    ! -path '*/node_modules/*' \
    ! -path '*/DerivedData/*' \
    ! -path '*/Package.resolved*')

for f in "${TARGETS[@]}"; do
    # Only touch files that contain a bare "design-system/" reference
    # NOT preceded by "shared/" (already-fixed) or "/" (absolute paths).
    if grep -qE '(^|[^/a-zA-Z])design-system/' "$f" 2>/dev/null; then
        # Match: design-system/ when preceded by start-of-line, whitespace,
        # backtick, opening paren, slash-not-shared, or quote.
        # Replace with shared/design-system/.
        # Use perl for reliable lookbehind.
        perl -i -pe 's{(?<![/\w-])design-system/}{shared/design-system/}g' "$f"
    fi
done

echo "Updated docstring path references in .swift / .kt / .kts / .md / .toml files."

# ─── 8. Stage everything ───────────────────────────────────────────────

git add -u
git add apps/ shared/

echo ""
echo "═════════════════════════════════════════════════════════"
echo " Phase 1 restructure complete. Nothing committed yet."
echo "═════════════════════════════════════════════════════════"
echo ""
echo "Review with:"
echo "  git status"
echo "  git diff --staged"
echo "  git diff --staged --stat"
echo ""
echo "Suggested commit message:"
echo ""
echo "  Phase 1: monorepo restructure for QuickInk spinoff"
echo ""
echo "  - Move ios/         → apps/releaf/ios/"
echo "  - Move android/     → apps/releaf/android/"
echo "  - Move design-system/ → shared/design-system/"
echo "  - Repoint plants.json + canonical-json-fixture.json symlinks"
echo "  - Update generate-tokens.mjs + check-tokens.sh path constants"
echo "  - Update top-level README layout + getting-started commands"
echo "  - Update docstring path references in source comments"
echo ""
echo "  Mechanical move only. No code logic changed. Releaf still"
echo "  builds + previews. ReleafCore extraction is Phase 2 (next PR)."
echo "  See docs/QUICKINK_PROPOSAL.md and docs/QUICKINK_DESIGN.md."
echo ""
echo "After commit, regenerate token outputs to verify the script paths:"
echo "  node shared/design-system/scripts/generate-tokens.mjs"
echo "  bash shared/design-system/scripts/check-tokens.sh"
echo ""
