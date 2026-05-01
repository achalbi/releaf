#!/usr/bin/env bash
#
# phase4d-android-auth-extract.sh
#
# PR #4d — Android equivalent of PR #4c. Extracts the 3 app-agnostic
# auth files (AuthStore, GoogleAuthClient, RealGoogleAuthClient) out
# of `:apps:releaf` and into a new `:shared:auth` Gradle library
# module.
#
# `GoogleSignInBinding.kt` STAYS in :apps:releaf — it pulls
# `R.string.google_web_client_id` from Releaf's resources, which is
# app-specific (each app has its own OAuth Web Client ID). QuickInk
# writes its own QuickInkSignInBinding.kt later that does the same
# against its own R string.
#
# Strategy: pure git mv. Same Kotlin package preserved
# (`app.releaf.mobile.auth`) so existing imports across Releaf
# (~25 callers) keep working without per-file edits.
#
# What this script does:
#   1. git mv 3 auth files into :shared:auth.
#   2. Stage the unstaged Gradle config edits.
#
# What this script does NOT do (already in working tree):
#   - shared/android/shared/auth/build.gradle.kts        (NEW)
#   - shared/android/settings.gradle.kts                 (EDITED — include :shared:auth)
#   - apps/releaf/android/app/build.gradle.kts           (EDITED — depend on :shared:auth)
#
# Usage:
#   bash scripts/phase4d-android-auth-extract.sh

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

if [[ ! -f shared/android/shared/auth/build.gradle.kts ]]; then
    echo "ERROR: shared/android/shared/auth/build.gradle.kts missing — earlier PR #4d work not in tree." >&2
    exit 2
fi

TO_MOVE=(
    "apps/releaf/android/app/src/main/java/app/releaf/mobile/auth/AuthStore.kt"
    "apps/releaf/android/app/src/main/java/app/releaf/mobile/auth/GoogleAuthClient.kt"
    "apps/releaf/android/app/src/main/java/app/releaf/mobile/auth/RealGoogleAuthClient.kt"
)
for p in "${TO_MOVE[@]}"; do
    if [[ ! -f "$p" ]]; then
        echo "ERROR: expected source file missing: $p" >&2
        exit 2
    fi
done

mkdir -p shared/android/shared/auth/src/main/kotlin/app/releaf/mobile/auth

echo "Pre-flight passed."

# ─── 1. git mv 3 auth files into :shared:auth ──────────────────────────

DEST="shared/android/shared/auth/src/main/kotlin/app/releaf/mobile/auth"
for f in "${TO_MOVE[@]}"; do
    base="$(basename "$f")"
    git mv "$f" "$DEST/$base"
done
echo "Moved 3 auth files into :shared:auth."

# ─── 2. Stage everything else ──────────────────────────────────────────

git add \
  shared/android/settings.gradle.kts \
  shared/android/shared/auth/ \
  apps/releaf/android/app/build.gradle.kts

echo ""
echo "═════════════════════════════════════════════════════════"
echo " PR #4d extract complete. Nothing committed yet."
echo "═════════════════════════════════════════════════════════"
echo ""
echo "Review with:"
echo "  git status"
echo "  git diff --staged --stat"
echo "  git log --diff-filter=R --name-status -1   # show renames"
echo ""
echo "Verify:"
echo "  cd shared/android"
echo "  gradle :shared:auth:assembleDebug"
echo "  gradle :apps:releaf:assembleDebug"
echo ""
echo "Suggested commit message:"
echo ""
echo "  PR #4d: extract Android :shared:auth"
echo ""
echo "  - Add :shared:auth Gradle library module."
echo "  - git mv AuthStore, GoogleAuthClient, RealGoogleAuthClient into"
echo "    :shared:auth — pure renames (no content changes)."
echo "  - GoogleSignInBinding.kt STAYS in :apps:releaf because it pulls"
echo "    R.string.google_web_client_id from Releaf's resources. QuickInk"
echo "    will write its own SignInBinding against its own R string."
echo "  - Same Kotlin package preserved (\`app.releaf.mobile.auth\`) so"
echo "    ~25 existing Releaf imports keep working unchanged."
echo ""
echo "  Mirror of iOS PR #4c. Verified with"
echo "  'gradle :apps:releaf:assembleDebug' on the new build root."
echo ""
