#!/usr/bin/env bash
#
# check-tokens.sh
#
# CI parity check: regenerate AppColors.generated.{swift,kt} into a scratch
# mirror tree, then diff against the in-tree committed files. Non-zero exit
# if drift is detected.
#
# This catches two failure modes:
#   1. Someone edited design-tokens.json but forgot to run the generator.
#   2. Someone edited the generator and the committed output is stale.
#
# Usage:
#   design-system/scripts/check-tokens.sh
#
# Exit codes:
#   0 — committed output matches a fresh regeneration.
#   1 — drift detected. Diff is printed to stderr; run the generator and commit.
#   2 — tool failure (Node missing, generator crashed, etc.).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
GENERATOR="$REPO_ROOT/shared/design-system/scripts/generate-tokens.mjs"

# Platform target paths — must match the generator. When these diverge, both
# files live in one place (the generator), so the fix is localized.
IOS_REL="shared/ios/ReleafCore/Sources/ReleafCoreDesignSystem/AppColors.generated.swift"
ANDROID_REL="apps/releaf/android/app/src/main/java/app/releaf/mobile/ui/theme/AppColors.generated.kt"

# --- preflight ---

if ! command -v node >/dev/null 2>&1; then
    echo "check-tokens.sh: node not found on PATH" >&2
    exit 2
fi

if [[ ! -f "$GENERATOR" ]]; then
    echo "check-tokens.sh: missing generator at $GENERATOR" >&2
    exit 2
fi

# --- regenerate into a throwaway mirror ---

SCRATCH="$(mktemp -d -t releaf-tokens.XXXXXX)"
trap 'rm -rf "$SCRATCH"' EXIT

if ! node "$GENERATOR" --out "$SCRATCH" >/dev/null; then
    echo "check-tokens.sh: generator failed" >&2
    exit 2
fi

# --- diff ---

drift=0
for rel in "$IOS_REL" "$ANDROID_REL"; do
    committed="$REPO_ROOT/$rel"
    fresh="$SCRATCH/$rel"
    if [[ ! -f "$committed" ]]; then
        echo "check-tokens.sh: missing committed file $committed" >&2
        drift=1
        continue
    fi
    if [[ ! -f "$fresh" ]]; then
        echo "check-tokens.sh: generator did not produce $fresh" >&2
        drift=1
        continue
    fi
    if ! diff -u "$committed" "$fresh" >&2; then
        drift=1
    fi
done

if [[ "$drift" -ne 0 ]]; then
    echo "" >&2
    echo "check-tokens.sh: generated token output is out of date." >&2
    echo "Run: node shared/design-system/scripts/generate-tokens.mjs" >&2
    echo "Then commit the regenerated AppColors.generated.* files." >&2
    exit 1
fi

echo "check-tokens.sh: token outputs in sync."
