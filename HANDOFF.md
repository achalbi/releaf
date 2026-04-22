# Handoff — BottomNav polish + token shifts (2026-04-21)

Session context for Claude Code picking this up. Builds as far as the Android Gradle compile; iOS verified by static token review (no Xcode on the machine). v2 `PROMPT.md` has been delivered and is the direction for the next session — see "Next phase" below.

## What was happening

Continuation of the BottomNav polish described in the previous handoff (2026-04-20). That session landed the floating editorial card with a labeled five-tab layout. This session:

1. **Removed the tab labels.** The five tabs are now icon-only. Titles survive for VoiceOver / TalkBack only (`accessibilityLabel` / `contentDescription`).
2. **Bumped icon size** 20 → 24 pt/dp to compensate for the missing label.
3. **Swapped the Notepad icon** from a pencil to a ruled-page glyph: SF Symbol `note.text` on iOS, `Icons.AutoMirrored.Filled.EventNote` on Android. Android avoids the `StickyNote2` deprecation by using the AutoMirrored variant.
4. **Pulled `textPrimary` off pure near-black.** Was `neutral900` (#241D17). Iterated to 800 → 700 → 500 → 600 and settled on **`neutral700` (#463C31)**. `textSecondary` shifted one stop to match (now `neutral600`, #5F5245). `textTertiary` unchanged at `neutral500` (#8A7C6D). Three-tier hierarchy preserved.
5. **Raised the dot-grid alpha** 14 % → 25 % → **35 %** (`0x59` on Android, `0.35` on iOS). Canvas texture is now clearly visible.

All three sources stayed in lockstep: iOS `AppColors.swift`, Android `AppColors.kt`, and the `design-system/design-tokens.json` source of truth.

## Files touched this session

- `ios/Releaf/DesignSystem/Components/BottomNav.swift` — label removal, icon 24 pt, `note.text`
- `android/app/src/main/java/app/releaf/mobile/ui/components/BottomNav.kt` — label removal, icon 24 dp, `AutoMirrored.EventNote`, pruned `Text` / `AppTypography` imports
- `ios/Releaf/DesignSystem/AppColors.swift` — `textPrimary` → `neutral700`, `textSecondary` → `neutral600`, `dotGrid` alpha 0.35
- `android/app/src/main/java/app/releaf/mobile/ui/theme/AppColors.kt` — same shifts mirrored
- `design-system/design-tokens.json` — `text.primary`, `text.secondary`, `pattern.dotGrid` values and descriptions

## Current BottomNav values (both platforms in sync)

| Token             | Value                                   |
| ----------------- | --------------------------------------- |
| Outer h-padding   | `AppSpacing.s6` (24) left + right       |
| Outer bottom      | `AppSpacing.s6` (24)                    |
| Row h-padding     | `AppSpacing.s1` (4)                     |
| Row v-padding     | `AppSpacing.s1` (4)                     |
| Chip h-padding    | `AppSpacing.s2` (8)                     |
| Chip v-padding    | `AppSpacing.s2` (8)                     |
| Chip radius       | `AppRadius.md` (12)                     |
| Card radius       | `AppRadius.nav` (16)                    |
| Card fill         | `AppColors.cardSolid` / `CardSolid`     |
| Card border       | 1dp `borderDefault` / `BorderDefault`   |
| Card shadow (iOS) | `.appShadow(.md)`                       |
| Card shadow (And) | 8dp `.shadow()`                         |
| **Regular icon**  | **24** (bumped from 20)                 |
| **Label**         | **removed** (VoiceOver/TalkBack only)   |
| Leaf diameter     | 56                                      |
| Leaf lift         | 16                                      |
| Leaf gradient     | `coral → coralDeep` top-to-bottom       |
| **Notepad icon**  | **`note.text` / `AutoMirrored.EventNote`** |

## Current color tokens (changed this session)

| Token           | Resolves to                    | Hex       |
| --------------- | ------------------------------ | --------- |
| `textPrimary`   | `neutral700`                   | `#463C31` |
| `textSecondary` | `neutral600`                   | `#5F5245` |
| `textTertiary`  | `neutral500` (unchanged)       | `#8A7C6D` |
| `dotGrid`       | warm brown @ 35 % alpha        | `#503E2D59` / `rgba(80,62,45,0.35)` |

**Dark-mode reminder:** `textPrimary` at `#463C31` has no dark partner yet. When the token pipeline ships (v2 step 2), the dark counterpart should be something in the 50–100 range — do **not** invert the ramp 1:1.

## Android card-layering trick (unchanged, don't break)

Android `BottomNav.kt` uses a **sibling Box with `matchParentSize()`** for the card surface (shadow + background + border), rendered *behind* the Row. The Row itself is unclipped. This is deliberate: the lifted leaf button uses `.offset(y = -16.dp)` and must overflow past the card's top edge — if `.shadow()` were attached directly to the Row (which `clip=true` by default), the leaf would clip. See roughly lines 115–125 and 215–235 of the current file.

## iOS equivalent

iOS uses `.background(cardBackground).overlay(hairlineBorder)` on the `HStack` and relies on SwiftUI not clipping drawn content by default — the leaf's `.offset(y: -16)` just renders outside the background's bounds, which is what we want. No `.clipShape()` on the HStack.

## Verification status

- **Android**: `gradle :app:compileDebugKotlin` ran clean this session (three times through the icon / label iterations — final run: `BUILD SUCCESSFUL in 9s`, exit 0, no warnings on BottomNav.kt). Gradle 9.4.1 + openjdk 25 via Homebrew, despite the project pinning AGP 8.13.2 / JDK 17 — it works. No `gradlew` wrapper script exists in the repo; next agent should either install `gradle` too or add a wrapper.
- **iOS**: Not compiled. No Xcode installed on the machine (only Command Line Tools). Verified statically: every referenced token (`AppColors.cardSolid`, `borderDefault`, `coral`, `coralSoft`, `coralDeep`, `textPrimary`, `textOnAccent`; `AppSpacing.s1/s2/s6`; `AppRadius.nav/md`; `AppText.tag`; `.appShadow(.md/.fab)`) resolves against its definition. Run `BottomNav_Previews` in Xcode to confirm visually.

## Known tensions (unchanged from previous handoff)

- Selected chip has no haptic/ripple on either platform (`indication = null` on Android, `.buttonStyle(.plain)` on iOS). Earlier user preference was "no ripple / after-effect" — keep that unless asked.
- iOS doesn't have the "sibling card" layering Android needs; confirm on a physical device that the leaf button's gradient + shadow render correctly over the (now 35 %-alpha) dot grid.
- `AppText.tag` / `AppTypography.Tag` are still used elsewhere in the codebase — the Android import got pruned here because BottomNav no longer uses it, but the tokens remain in `AppTypography.kt` / `AppTypography.swift` for other callers.

## Tasks

Previous "Liquid Glass" tasks (#27 / #28) are stale. This polish pass is effectively done. The next task is the v2 build kickoff described in `PROMPT.md` — see below.

## Next phase (v2)

`PROMPT.md` at repo root is the v2 build brief. Before any feature code, six deliverables are required (paraphrased):

1. Full SQLite DDL + FTS5 virtual tables + triggers, with **shared migration numbering** across iOS (GRDB) and Android (Room + `RoomDatabase.Callback`).
2. Navigation graph additions (mermaid or similar) — Daily Log, Notepad editor, Capture detail, Task detail, Search, conflict resolver, URL-scheme dispatch for `releaf://`.
3. Drive schema rewrite in `docs/DRIVE_SCHEMA.md` reflecting the new manifest-based layout (manifest.json, `notebooks/`, `notepad_entries/yyyy/mm/`, `captures/`, tombstones).
4. Design-token pipeline proposal + dark-mode token addition.
5. Markdown editor spike (1 h prototype per platform) proving lossless CommonMark round-trip.
6. Open-questions list — **see `docs/OPEN_QUESTIONS.md`** (created this session). Has 12 items with proposed answers; awaits user review before DDL work starts.

**Hard constraint from `PROMPT.md`:** do not scaffold new tables or screens until DDL + nav graph are reviewed. The existing UI shell (Home, Notebook list / detail, Page detail, Notepad, Settings, Login with fixture data) must stay buildable and previewable on both platforms throughout the migration.

## Quick-reference snippets

### Android — BottomNav root structure (unchanged)

```kotlin
Box(
    modifier = Modifier
        .fillMaxWidth()
        .padding(start = s6, end = s6, bottom = s6),
) {
    Box(  // card surface, drawn behind the Row
        modifier = Modifier
            .matchParentSize()
            .shadow(8.dp, navShape)
            .background(CardSolid, navShape)
            .border(1.dp, BorderDefault, navShape),
    )
    Row(  // tab cells, unclipped
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = s1, vertical = s1),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) { /* ... */ }
}
```

### Android — RegularTab is now icon-only

```kotlin
Box(modifier = modifier.clickable(..., indication = null), contentAlignment = Alignment.Center) {
    Icon(
        imageVector = item.icon,
        contentDescription = item.title, // TalkBack only
        tint = tint,
        modifier = Modifier
            .clip(RoundedCornerShape(AppRadius.md))
            .background(bg)
            .padding(horizontal = AppSpacing.s2, vertical = AppSpacing.s2)
            .size(24.dp),
    )
}
```

### iOS — RegularTab is now icon-only

```swift
Button(action: onTap) {
    Image(systemName: item.systemIcon)
        .font(.system(size: 24, weight: .regular))
        .foregroundColor(isSelected ? AppColors.coral : AppColors.textPrimary)
        .padding(.horizontal, AppSpacing.s2)
        .padding(.vertical, AppSpacing.s2)
        .background(
            RoundedRectangle(cornerRadius: AppRadius.md, style: .continuous)
                .fill(isSelected ? AppColors.coralSoft : Color.clear)
        )
        .frame(maxWidth: .infinity)
        .contentShape(Rectangle())
}
.buttonStyle(.plain)
.accessibilityLabel(Text(item.title))   // VoiceOver only
```
