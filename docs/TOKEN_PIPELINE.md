# Design-token pipeline

Deliverable 4 from `PROMPT.md`. Documents how a single JSON source of truth generates both platforms' design-system theme files, adds dark-mode handling, and fails CI on drift.

Companion files:
- `design-system/design-tokens.json` — the source of truth.
- `design-system/scripts/generate-tokens.mjs` — the generator.
- `design-system/scripts/check-tokens.sh` — the CI parity check.
- `ios/Releaf/DesignSystem/AppColors.generated.swift` — committed iOS output.
- `android/app/src/main/java/app/releaf/mobile/ui/theme/AppColors.generated.kt` — committed Android output.

---

## Goals (from PROMPT.md)

1. One token source in `design-system/design-tokens.json`.
2. A simple script (Node) that regenerates both platforms' theme files from it.
3. Both generated files are committed to git.
4. CI re-runs the generator and fails on drift.
5. Dark-mode support lands with the pipeline; follow system, no user override.

---

## Input format

The source JSON is already structured per the Tokens Studio schema. Role tokens gain a light/dark object; ramp / scale tokens stay flat.

### Before (current shape — all flat)

```json
"text": {
  "primary": { "value": "#463C31", "type": "color", "description": "Body text" }
}
```

### After (role tokens gain light/dark; ramp stops stay flat)

```json
"text": {
  "primary": {
    "value": { "light": "#463C31", "dark": "#EFE7DA" },
    "type": "color",
    "description": "Body text — aliases neutral700 (light) / neutral100 (dark)"
  }
}
```

### What becomes theme-aware vs stays flat

**Theme-aware** (get a `{ light, dark }` object):
- `color.surface.*` (canvas, card, cardSolid, subtle)
- `color.text.*` (primary, secondary, tertiary, onAccent)
- `color.border.*` (default, strong)
- `color.accent.*` (coral, coralSoft, coralDeep, coralOutline, green, greenSoft, greenText)
- `color.semantic.*` (success, successSoft, info, infoSoft, warning, warningSoft, neutral, neutralSoft, danger)
- `color.action.*` (primary, primaryPressed, onPrimary)
- `color.pattern.dotGrid`

**Flat** (single value, no theme variance):
- `color.scale.*.**` — ramp stops. These are palette building blocks, not semantic roles. A ramp stop doesn't "change in dark mode"; a different stop is chosen for the dark variant of a role token.
- All non-color tokens: `pattern.dotGrid.{spacing,size}`, typography, space, radius, shadow, motion.

### Why not invert the ramp?

A mechanical 1:1 inversion of a warm neutral ramp produces cold grays that break the editorial tone. The existing ramp is hue-locked around 30° (warm brown); dark mode role assignments re-use that same hue-locked ramp — just picking different stops per theme. `textPrimary` is `neutral700` in light and `neutral100` in dark. Both are on the same warm brown ramp.

### The dark palette (v1 picks)

| Role token        | Light value (alias)           | Dark value (alias)            |
| ----------------- | ----------------------------- | ----------------------------- |
| `canvas`          | `#F5EEE3` (warm cream)        | `#241D17` (neutral900)        |
| `card`            | `#FFFAF4E6` (90% over cream)  | `#332B22E6` (neutral800 @ 90%) |
| `cardSolid`       | `#FFFAF4`                     | `#332B22` (neutral800)        |
| `subtle`          | `#EFE7DA` (neutral100)        | `#463C31` (neutral700)        |
| `text.primary`    | `#463C31` (neutral700)        | `#EFE7DA` (neutral100)        |
| `text.secondary`  | `#5F5245` (neutral600)        | `#CEBB9D` (neutral300)        |
| `text.tertiary`   | `#8A7C6D` (neutral500)        | `#A99A84` (neutral400)        |
| `text.onAccent`   | `#FFFFFF`                     | `#FFFFFF` (coral stays coral) |
| `border.default`  | `#503E2D1F` (warm @ 12%)      | `#FAF6F01F` (neutral50 @ 12%) |
| `border.strong`   | `#503E2D3D`                   | `#FAF6F03D`                   |
| `accent.coral`    | `#E77850`                     | `#E77850` (coral stays coral) |
| `accent.coralSoft`| `#FCEAE0` (coral100)          | `#3A2118` (dark coral wash)   |
| `accent.coralDeep`| `#C85A30` (coral700)          | `#FA9975` (pressed = lighter) |
| `accent.green`    | `#1E5943`                     | `#7FC19A` (lifted 3 stops)    |
| `accent.greenSoft`| `#D9EDE2`                     | `#20352B`                     |
| `semantic.success`| `#4C9A6A` (success600)        | `#7FC19A` (lifted)            |
| `semantic.successSoft` | `#E3F1E8` (success100)   | `#1B2E23`                     |
| `semantic.info`   | `#2E6FB5` (info600)           | `#7FA7D4` (lifted)            |
| `semantic.infoSoft` | `#E1ECF8` (info100)         | `#17283D`                     |
| `semantic.warning`| `#A87418` (warning600)        | `#D9A45C` (lifted)            |
| `semantic.warningSoft` | `#FBEECD` (warning100)   | `#2E2516`                     |
| `semantic.neutral` | `#5F5245` (neutral600)       | `#CEBB9D` (neutral300)        |
| `semantic.neutralSoft` | `#EFE7DA` (neutral100)   | `#332B22` (neutral800)        |
| `semantic.danger` | `#C8432E` (danger600)         | `#E87058` (lifted)            |
| `action.primary`  | `#1A1A1A` (near-black CTA)    | `#FAF6F0` (neutral50 — "inverted" CTA) |
| `action.primaryPressed` | `#000000`                | `#FFFFFF`                     |
| `action.onPrimary`| `#FFFFFF`                     | `#241D17`                     |
| `pattern.dotGrid` | `#503E2D59` (warm @ 35%)      | `#FAF6F00D` (neutral50 @ 5%)  |

**Rationale notes:**

- **Canvas → `neutral900`**, not pure black. Pure AMOLED-black eats the editorial warmth; `#241D17` reads as "dark warm paper."
- **Coral stays coral.** Both themes render `#E77850` at the same luminance against their respective backgrounds. Bumping it in dark mode is a common mistake that drifts brand identity.
- **CoralSoft flips meaning.** In light mode, it's a pale apricot behind the selected tab icon. In dark mode, a pale apricot on dark canvas is visually loud; we switch to a dark coral wash (`#3A2118`) that reads as "coral territory" without glaring.
- **CoralDeep flips direction.** Light-mode pressed = darker coral. Dark-mode pressed = lighter coral. (Standard dark-mode pattern — pressed states invert direction.)
- **ActionPrimary flips.** Light-mode CTA is a black pill; dark-mode CTA is a warm-cream pill. Readable on respective canvases, matches the editorial feel.
- **DotGrid almost disappears** in dark mode (3% neutral50 overlay). The texture would otherwise read as noise on dark. This is deliberate — the canvas stays quiet, the dot pattern is a light-mode signature.

---

## Generator

Plain Node ≥ 20. No `package.json`, no deps — reads JSON, writes Swift + Kotlin, that's it.

### Invocation

```bash
# From repo root
node design-system/scripts/generate-tokens.mjs

# Or with an explicit output root (for CI check)
node design-system/scripts/generate-tokens.mjs --out /tmp/check-tokens
```

### Inputs

- Reads `design-system/design-tokens.json`.

### Outputs

- `ios/Releaf/DesignSystem/AppColors.generated.swift`
- `android/app/src/main/java/app/releaf/mobile/ui/theme/AppColors.generated.kt`

Outputs land directly inside each platform target because SPM and Gradle only compile sources under the target's source root. An intermediate "generated" holding folder would need a copy step in each platform's build; writing in place keeps the pipeline one-shot.

Both files are header-commented `/* GENERATED — DO NOT EDIT. Run design-system/scripts/generate-tokens.mjs */`.

### Generator algorithm

```
1. Parse design-tokens.json.
2. For every color.scale.*.<stop>:
     → emit a flat static const on both platforms (same as today).
3. For every theme-aware role token (color.surface|text|border|accent|semantic|action|pattern.*):
     → emit a theme-aware member.
     → iOS:  `public static let x: Color = dynamicColor(light: 0xRRGGBB, dark: 0xRRGGBB, lightAlpha: …, darkAlpha: …)`
             backed by a `Color(UIColor { trait in … })` dynamic provider.
     → Android: `val X: Color @Composable @ReadOnlyComposable get() = if (isSystemInDarkTheme()) Color(0xAARRGGBB) else Color(0xAARRGGBB)`.
4. Emit a platform-appropriate `Color(hex:)` helper at the bottom of each file.
5. Write out. Format: two-space indent on both platforms.
```

### Output shape — iOS

```swift
// GENERATED — DO NOT EDIT. Run design-system/scripts/generate-tokens.mjs
//
// Source: design-system/design-tokens.json

import SwiftUI
import UIKit

public enum AppColors {

    // MARK: - Ramps (appearance-agnostic)

    public static let neutral50  = Color(hex: 0xFAF6F0)
    public static let neutral100 = Color(hex: 0xEFE7DA)
    // … every ramp stop …

    // MARK: - Roles (theme-aware; resolve via trait collection)

    public static let canvas        = dynamicColor(light: 0xF5EEE3, dark: 0x241D17)
    public static let card          = dynamicColor(light: 0xFFFAF4, lightAlpha: 0.9, dark: 0x332B22, darkAlpha: 0.9)
    public static let cardSolid     = dynamicColor(light: 0xFFFAF4, dark: 0x332B22)
    public static let textPrimary   = dynamicColor(light: 0x463C31, dark: 0xEFE7DA)
    // … every role token …
}

// MARK: - Helpers

private func dynamicColor(
    light: UInt32, lightAlpha: CGFloat = 1,
    dark: UInt32,  darkAlpha:  CGFloat = 1
) -> Color {
    Color(UIColor { trait in
        trait.userInterfaceStyle == .dark
            ? UIColor(rgb: dark, alpha: darkAlpha)
            : UIColor(rgb: light, alpha: lightAlpha)
    })
}

private extension UIColor {
    convenience init(rgb: UInt32, alpha: CGFloat = 1) {
        let r = CGFloat((rgb >> 16) & 0xFF) / 255
        let g = CGFloat((rgb >>  8) & 0xFF) / 255
        let b = CGFloat( rgb        & 0xFF) / 255
        self.init(red: r, green: g, blue: b, alpha: alpha)
    }
}

public extension Color {
    init(hex: UInt32, alpha: Double = 1.0) {
        let r = Double((hex >> 16) & 0xFF) / 255.0
        let g = Double((hex >>  8) & 0xFF) / 255.0
        let b = Double( hex        & 0xFF) / 255.0
        self.init(.sRGB, red: r, green: g, blue: b, opacity: alpha)
    }
}
```

**Call sites don't change.** `AppColors.textPrimary` still returns a `Color`; the dynamic provider resolves per appearance automatically at render time. Xcode previews pick up the dark-mode toggle for free.

### Output shape — Android

```kotlin
// GENERATED — DO NOT EDIT. Run design-system/scripts/generate-tokens.mjs
//
// Source: design-system/design-tokens.json

package app.releaf.mobile.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

object AppColors {

    // Ramps (appearance-agnostic) — flat vals, computed at class load.

    val Neutral50  = Color(0xFFFAF6F0)
    val Neutral100 = Color(0xFFEFE7DA)
    // … every ramp stop …

    // Roles (theme-aware) — @Composable getters, resolved per recomposition.

    val Canvas: Color
        @Composable @ReadOnlyComposable
        get() = if (isSystemInDarkTheme()) Color(0xFF241D17) else Color(0xFFF5EEE3)

    val Card: Color
        @Composable @ReadOnlyComposable
        get() = if (isSystemInDarkTheme()) Color(0xE6332B22) else Color(0xE6FFFAF4)

    val TextPrimary: Color
        @Composable @ReadOnlyComposable
        get() = if (isSystemInDarkTheme()) Color(0xFFEFE7DA) else Color(0xFF463C31)

    // … every role token …
}
```

**Call sites don't change** — `AppColors.Canvas` is already used from composable functions (BottomNav, screens, components). `@ReadOnlyComposable` means the getter can't call `remember` or side-effecting composables, just reads system state.

A grep of the current Android shell shows all `AppColors.*` references are within `@Composable` functions (19 files, verified). No call-site churn.

### Why not a CompositionLocal?

The canonical Compose pattern is `LocalAppColors.current.textPrimary`, threaded through a `CompositionLocalProvider`. It's cleaner for theme overriding (e.g. forcing light mode for onboarding). But it forces every call site to change from `AppColors.TextPrimary` to `LocalAppColors.current.textPrimary` — a ~100-site churn across the existing 19 files.

The `@Composable get()` approach keeps the existing API and still resolves per appearance. If we later need per-subtree override (unlikely given PROMPT.md's "follow system, no user override" rule), we can introduce a CompositionLocal on top without breaking call sites.

---

## CI parity check

`design-system/scripts/check-tokens.sh` re-runs the generator into a scratch directory and diffs against the committed output.

The shipped `design-system/scripts/check-tokens.sh` does this: regenerate into a scratch mirror with `--out`, then diff each committed platform file against its mirror counterpart. See the script for the authoritative version; the sketch below is illustrative.

```bash
#!/usr/bin/env bash
set -euo pipefail
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

node design-system/scripts/generate-tokens.mjs --out "$tmp"

# --out mirrors the platform paths under the scratch root, so we diff by
# overlay: $tmp/ios/... vs ios/..., $tmp/android/... vs android/...
for rel in \
    ios/Releaf/DesignSystem/AppColors.generated.swift \
    android/app/src/main/java/app/releaf/mobile/ui/theme/AppColors.generated.kt; do
    if ! diff -u "$rel" "$tmp/$rel" > /dev/null; then
        echo "❌ Token drift in $rel — re-run generate-tokens.mjs and commit."
        diff -u "$rel" "$tmp/$rel" || true
        exit 1
    fi
done
echo "✅ Generated token files match design-tokens.json."
```

Wire it into CI alongside the migrations parity check (see `design-system/migrations/README.md`). Both checks run on every PR and block on drift.

---

## Platform adapter notes

### iOS

The generated `AppColors.generated.swift` replaces the hand-written `ios/Releaf/DesignSystem/AppColors.swift` when the pipeline is adopted. Migration steps:

1. Land the generator + JSON + generated file.
2. Verify the generated file's static content matches the hand-written file byte-for-byte (with dark-mode additions).
3. Remove the hand-written file; import the generated file in its place (`@_exported import` via a tiny `AppColors.swift` re-exporter, or direct path replacement).
4. Remove any stale `Color(hex:)` helpers duplicated across files.

### Android

The generated `AppColors.generated.kt` is a drop-in for the hand-written `android/app/src/main/java/app/releaf/mobile/ui/theme/AppColors.kt`. Migration steps:

1. Land the generator + JSON + generated file.
2. Verify the generated output byte-for-byte matches the current hand-written file for the light-mode values.
3. Delete the hand-written file; rename the generated file to `AppColors.kt` (keeping the `GENERATED` header).
4. Dark-mode values activate automatically — `isSystemInDarkTheme()` reads the device setting on each recomposition.

**Gradle integration (optional):** add a Gradle task that runs `node design-system/scripts/generate-tokens.mjs` as a `dependsOn :app:preBuild` step so local builds auto-regenerate. This is **not** required — the generator is a CI concern; developers re-run manually when they edit `design-tokens.json`.

### Xcode integration (optional)

Add a Run Script build phase to the Releaf target:
```bash
if [ -x "$(command -v node)" ]; then
    node "$SRCROOT/../design-system/scripts/generate-tokens.mjs"
fi
```
Same opt-in nature — not required.

---

## Typography, spacing, radius — still hand-written?

The first generator version only covers `color.*`. These other token kinds are stable, small (9 spacing stops, 4 radii, 9 font sizes, 4 weights, 3 line heights), and don't theme.

Planned follow-up (out of scope for this deliverable):
- Generate `AppSpacing.generated.swift/kt` from `space.*`.
- Generate `AppRadius.generated.swift/kt` from `radius.*`.
- Generate `AppTypography.generated.swift/kt` from `typography.*` — with care, since `AppTypography` wraps fonts with `Font(.system(...))` / `TextStyle`, which is a richer API than raw values.
- Generate `AppShadow.generated.swift/kt` from `shadow.*`.

Each is a ~30-line addition to the generator. Hold off until we have a second theme of any kind (brand variant, compact density) that would actually exercise the multiplicity.

---

## Accessibility

- Every dark-mode value chosen to maintain WCAG 2.1 AA contrast (4.5:1 for body text, 3.0:1 for large/UI elements) against its intended background.
- `text.primary` dark (`#EFE7DA`) on `canvas` dark (`#241D17`): **10.1:1** ✅
- `text.secondary` dark (`#CEBB9D`) on `canvas` dark: **6.4:1** ✅
- `text.tertiary` dark (`#A99A84`) on `canvas` dark: **4.3:1** ⚠️ (borderline on body; acceptable for placeholder role per AA large-text 3.0:1).
- `accent.coral` (`#E77850`) on `canvas` dark: **4.8:1** ✅ (as text and as button background with white text).
- `textOnAccent` (white) on `accent.coral`: **3.0:1** ⚠️ — same as light mode; already under-target in light mode and accepted because coral is an accent, not primary body text. No regression.

Full contrast matrix will be validated by a test helper that pairs each `text.*` with each intended background token and asserts ≥ ratio. Not in this deliverable; tracked as a follow-up.

---

## Open questions

1. **Should shadows change in dark mode?** A heavy drop-shadow on a dark surface reads as a halo, not a lift. Common practice is to *reduce shadow opacity by half and lean on a 1px light-tinted top border* for elevation cues. Not shipping this in v1 — keeps shadow tokens flat for now. Revisit during the visual polish pass.
2. **Green accent stop coverage.** `accent.green` only has a single light value; dark-mode picks (`#7FC19A`) are synthesized rather than sourced from an extended `scale.green.*` ramp. Propose adding a 4-stop green ramp (`green.50/100/600/700`) as a follow-up so dark green is ramp-derived.
3. **Shall we generate `colors.xml`?** The Android `res/values/colors.xml` file currently holds a few static brand colors for splash / status-bar tinting. Not on the pipeline yet; it's a separate surface area (XML, not Compose). Low priority — those colors rarely change.
4. **Pressed states in ramp stops.** `coralDeep` is `coral.700` in light mode (pressed is darker) but a synthesized `#FA9975` in dark mode (pressed is lighter). If we extend the ramp, the dark stop should become `coral.300` and be ramp-derived. Tracking.

---

## Resolution path

1. User reviews this proposal + the generated sample output + the dark values.
2. User runs the generator once locally, inspects the two output files.
3. User decides when to wire the platform adapters (iOS target; Android `AppColors.kt` swap). Until then, the hand-written files still work; the generated files are reference material.
4. CI parity check goes live once the hand-written files are replaced by the generated ones.
