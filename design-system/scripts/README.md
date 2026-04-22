# design-system/scripts

Token generator + CI parity check. Node (v20+), zero dependencies.

## Files

| File | Purpose |
| --- | --- |
| `generate-tokens.mjs` | Reads `design-tokens.json`, emits `AppColors.generated.swift` + `AppColors.generated.kt` directly into the iOS and Android source trees (see "Output paths" below). |
| `check-tokens.sh` | CI parity check: regenerates to a temp dir and diffs against the committed files. Fails if drift is detected. |

Full design rationale lives in `docs/TOKEN_PIPELINE.md`. This README is for the ergonomics.

## Regenerate after a token edit

```sh
node design-system/scripts/generate-tokens.mjs
```

### Output paths

| Platform | Path |
| --- | --- |
| iOS | `ios/Releaf/DesignSystem/AppColors.generated.swift` |
| Android | `android/app/src/main/java/app/releaf/mobile/ui/theme/AppColors.generated.kt` |

Each file lives directly inside the platform target — Swift Package Manager
and Gradle only compile what's under the source root, so shipping an
intermediate "generated" holding folder would require a separate copy step.
The generator writes in place instead.

The header of each file says `GENERATED — DO NOT EDIT`. If you hand-edit, the CI check will fail.

Commit both files alongside the `design-tokens.json` change in the same commit — reviewers should see the palette delta and the generated diff together.

## What the generator does

1. Parses `design-tokens.json`.
2. Emits **ramps** (`color.scale.{neutral,coral,success,info,warning,danger}.<stop>`) as flat, appearance-agnostic members on both platforms:
   - Swift: `public static let neutral50 = Color(hex: 0xFAF6F0)`
   - Kotlin: `val Neutral50 = Color(0xFFFAF6F0)`
3. Emits **role tokens** (`color.{surface,text,border,accent,semantic,action,pattern}.*`) as theme-aware members that resolve at runtime:
   - Swift: `dynamicColor(light:, dark:)` helper wraps `Color(UIColor { trait in ... })`.
   - Kotlin: `@Composable @ReadOnlyComposable get() = if (isSystemInDarkTheme()) ...`.
4. Collapses role-token path aliases (e.g. `onAccent` ↔ `textOnAccent`) so each JSON path produces exactly one primary declaration plus one `= primary` alias — never two primaries.

The set of emitted role tokens is the `ROLE_TOKENS` manifest at the top of `generate-tokens.mjs`. To add or rename a role, edit that manifest and run the generator.

## CI parity check

```sh
design-system/scripts/check-tokens.sh
```

Wire this into CI for both mobile targets (or once in a shared `lint` job — the check is platform-agnostic).

Exit codes:

| Code | Meaning |
| --- | --- |
| `0` | Committed output matches a fresh regeneration. |
| `1` | Drift detected. Full `diff -u` is printed to stderr; run the generator and commit. |
| `2` | Tool failure (Node missing, generator crashed, committed file absent, etc.). |

The check catches two failure modes:
- Someone edited `design-tokens.json` but forgot to run the generator.
- Someone edited the generator and the committed output is stale.

## Testing the generator locally

```sh
# Write to a scratch directory without clobbering the committed files.
node design-system/scripts/generate-tokens.mjs --out /tmp/releaf-scratch
ls /tmp/releaf-scratch/generated/
```

## Adding a new role token

1. Add the new key under the appropriate group in `design-tokens.json` with `{ light: "#RRGGBB", dark: "#RRGGBB", description: "..." }`. Use 8-digit hex (`#RRGGBBAA`) when you need a non-opaque stop — the generator extracts alpha automatically.
2. Add an entry to `ROLE_TOKENS` in `generate-tokens.mjs`: `{ path: ['group', 'name'], swift: 'swiftName', kotlin: 'KotlinName' }`.
3. Run `node design-system/scripts/generate-tokens.mjs`.
4. Commit the three files together.

## Adding a ramp stop

1. Add the stop under `color.scale.<ramp>.<stop>` in `design-tokens.json`.
2. Add the stop string to the ramp's `stops` array in `RAMPS` in `generate-tokens.mjs`.
3. Run the generator and commit.

Ramps are intentionally flat (no light/dark split) — they exist so a product surface can pick a specific hue when the role system isn't expressive enough. If you find yourself theme-switching a ramp stop, promote it to a role token instead.
