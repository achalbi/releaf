# Capture-as-tab — implementation plan

Promote Quick Capture from a `ModalBottomSheet` to a top-level destination (the Leaf tab). This document is the contract: what changes, in what order, with what risk. Visual reference: [`design-system/quick-capture-prototype-v7.html`](../design-system/quick-capture-prototype-v7.html).

---

## TL;DR

The center "Leaf" slot in the BottomNav already exists (`BottomNavItem("leaf", …, BottomNavKind.Brand)`) — it just doesn't own a route. We give it one, point its tap target at `nav.navigate("capture")`, and delete the `showCapture` boolean state on both platforms. The BottomNav stays visible on the new tab; the FAB gets a coral ring to signal "you're on this tab."

Changes are **additive first** (Phases 1–3), then **swap** (Phase 4 builds the page content, replacing the modal at the FAB tap-site), then **clean up** (delete the dead `ModalBottomSheet` wrapper). Each phase compiles and runs on its own.

---

## Invariants — never break these

1. **The footer menu (BottomNav) stays visible on the Capture page.** This is the user's explicit requirement. `Routes.CAPTURE` must be in `Routes.topLevel`.
2. **The Leaf FAB long-press still records voice without a tab switch** (per `DAILY_CAPTURE_UX.md` §2.4). The gesture lives on the FAB itself, not on whatever screen the user is on.
3. **No SQLite migration.** Pre-tag categories ride the existing `tags` table (per v2 + v6 plans).
4. **No deep-link breakage.** Existing `releaf://capture?kind=…` URLs already route to the modal; they must keep working through the page version.
5. **Both platforms ship in the same PR.** Android and iOS stay structurally parallel per `PROMPT.md` constraint #1.

---

## Files affected

### Android

| File | Change |
| --- | --- |
| `android/app/src/main/java/app/releaf/mobile/MainActivity.kt` | Add `Routes.CAPTURE`, add to `topLevel`, add `routeForTab("leaf") → CAPTURE`, add NavHost composable, replace `onBrandTap` body, delete `showCapture` state + `if (showCapture) QuickCaptureSheet(…)` block. |
| `android/app/src/main/java/app/releaf/mobile/ui/components/BottomNav.kt` | Add `isSelected: Boolean` to `BrandTab`, render coral outline when true; plumb from `BottomNav` via `selectedId == "leaf"`. |
| `android/app/src/main/java/app/releaf/mobile/features/capture/CaptureScreen.kt` | **New file.** Top-level composable for the Capture page. v7 prototype body: page header + Day/Recents + Scan hero + pre-tag chip row + divider + 6-tile grid + footer. |
| `android/app/src/main/java/app/releaf/mobile/features/capture/CaptureViewModel.kt` | **New file.** State for Day/Recents toggle, recent captures stream for Recents tab, observe top-N tags for pre-tag chips. |
| `android/app/src/main/java/app/releaf/mobile/ui/components/CaptureMode.kt` | Add `Notes` (text) entry. Drop nothing — `Overview`/`Scans` keep working in the page-detail tab bar; the Capture page just doesn't surface them. |
| `android/app/src/main/java/app/releaf/mobile/ui/components/QuickCaptureSheet.kt` | After Phase 4: deprecation comment at the top. Eventually removable; keep for one release cycle in case any deep link still triggers it. |

### iOS

| File | Change |
| --- | --- |
| `ios/Releaf/Features/App/MainShell.swift` | Add Capture as a tab destination. Change `onBrandTap` from `showCapture = true` to selecting the Capture tab. Delete `@State private var showCapture` and the `.sheet(isPresented: $showCapture)` block. |
| `ios/Releaf/DesignSystem/Components/BottomNav.swift` (or wherever the iOS BottomNav lives) | Add active-state treatment to the brand button. |
| `ios/Releaf/Features/Capture/CaptureView.swift` | **New file.** SwiftUI mirror of the Android `CaptureScreen`. |
| `ios/Releaf/DesignSystem/Components/QuickCaptureSheet.swift` | Same deprecation note as Android. |

### Docs

| File | Change |
| --- | --- |
| `docs/NAV_GRAPH.md` | `quick_capture` Modal → `capture` Top-level. Update the deep-link table. Update the per-flow diagrams. |
| `docs/DAILY_CAPTURE_UX.md` | §2 entry points: replace "tap FAB → modal sheet rises" with "tap FAB → navigate to Capture tab." |

---

## Phased rollout

Each phase compiles and runs on its own. Stop after any phase if something looks wrong; the modal still works through Phase 3.

### Phase 1 — Android `CaptureScreen.kt` scaffold (additive)

**Risk: zero.** New file, not wired to anything.

1. Create `features/capture/CaptureScreen.kt` with a `@Composable fun CaptureScreen(…)` that renders the v7 body.
2. Add a `@Preview` against the cream canvas so the design is verifiable in Android Studio without launching the app.
3. Build → confirm green.

### Phase 2 — Wire `CAPTURE` route + reroute FAB

**Risk: medium.** The FAB tap target changes; modal goes away in this phase.

In `MainActivity.kt`:

```kotlin
private object Routes {
    // … existing routes …
    const val CAPTURE = "capture"

    // … existing helpers …

    val topLevel = setOf(HOME, NOTEBOOKS, NOTEPAD, SETTINGS, CAPTURE)  // ← add CAPTURE

    fun routeForTab(tabId: String): String? = when (tabId) {
        "home"     -> HOME
        "notebook" -> NOTEBOOKS
        "leaf"     -> CAPTURE      // ← new mapping
        "notepad"  -> NOTEPAD
        "settings" -> SETTINGS
        else       -> null
    }

    fun selectedTabForRoute(route: String?): String = when (route) {
        HOME      -> "home"
        NOTEBOOKS -> "notebook"
        CAPTURE   -> "leaf"        // ← new mapping
        NOTEPAD   -> "notepad"
        SETTINGS  -> "settings"
        else      -> "home"
    }
}
```

In `SignedInShell`:

```kotlin
// DELETE: var showCapture by remember { mutableStateOf(false) }

// REPLACE in BottomNav callback:
onBrandTap = {
    nav.navigate(Routes.CAPTURE) {
        popUpTo(Routes.HOME) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

// DELETE the entire `if (showCapture) QuickCaptureSheet(…)` block.
```

In `SignedInNavHost`, add:

```kotlin
composable(Routes.CAPTURE) {
    CaptureScreen(
        session = session,
        onSelectMode = { mode ->
            // Same beginQuickCapture flow that lived in the modal:
            scope.launch {
                val chapterId = releafApp.notebookRepository.resolveQuickCaptureChapter()
                val page = releafApp.pageRepository.createPage(chapterId, null, "")
                nav.navigate(Routes.pageLocal(page.id, mode))
            }
        },
        onOpenSearch   = { nav.navigate(Routes.SEARCH) },         // see "Open questions" below
        onOpenCalendar = { nav.navigate(Routes.CALENDAR) },
    )
}
```

**Verification:** tap FAB → lands on Capture tab; BottomNav visible; tapping any other tab pops Capture; deep-link `releaf://capture` lands on the tab.

### Phase 3 — Active-tab ring on `BrandTab`

**Risk: low.** Visual-only.

In `BottomNav.kt`:

```kotlin
@Composable
private fun BrandTab(
    item: BottomNavItem,
    isSelected: Boolean,                    // ← new
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    // … existing setup …

    Box(
        modifier = Modifier
            .offset(y = -lift)
            .size(outerDiameter)
            .drawBehind {
                // … existing ambient + contact shadows …
            }
            .background(AppColors.Canvas, CircleShape)
            .let {
                if (isSelected) {
                    // Coral outline outside the cream ring.
                    it.border(1.5.dp, AppAccent.primary, CircleShape)
                } else it
            },
    ) { … }
}
```

Plumb from caller:

```kotlin
BottomNavKind.Brand -> BrandTab(
    item = item,
    isSelected = selectedId == item.id,    // ← new
    modifier = Modifier.weight(1f),
    onClick = { onBrandTap?.invoke() ?: onSelect(item.id) },
)
```

**Verification:** the FAB shows a thin coral ring when on Capture, none on other tabs.

### Phase 4 — Build the Capture page body

**Risk: medium.** Lots of new UI; bugs here are visual, not behavioral.

Inside `CaptureScreen.kt`, render — top to bottom:

1. **Page header** — `RELEAF` eyebrow + serif "Capture" title (left); search + calendar icon buttons (right).
2. **Day/Recents segmented control** — `forest` background for the active segment.
3. **Day-meta label** — "Thursday · April 30" right-aligned beside the toggle.
4. **Scan hero composable** — full v7 dimensions. Gradient mustard, eyebrow, title, blurb, paper detail, corner brackets, Scan now / Import buttons.
5. **"Pre-tag & scan" label row** + horizontal `LazyRow` of chips (active state for the most-recently-used tag).
6. **"or capture differently" divider** — three-element row with hairlines.
7. **`LazyVerticalGrid(GridCells.Fixed(3))`** of the six capture tiles.
8. **Footer hint** — "Hold the green Leaf button…"

The `CaptureViewModel` exposes:

```kotlin
data class CaptureUiState(
    val scope: Scope = Scope.Day,                 // Day | Recents
    val pretagChips: List<TagChip> = emptyList(), // Top-5 tags by recency-weighted frequency
    val recents: List<RecentCapture> = emptyList()  // For Recents scope
)
```

The pre-tag query: `SELECT t.id, t.name, COUNT(*) AS c FROM tags t JOIN capture_tags ct ON ct.tag_id = t.id JOIN captures c ON c.id = ct.capture_id WHERE c.kind = 'scan' AND c.deleted_at IS NULL GROUP BY t.id ORDER BY c DESC LIMIT 5`.

### Phase 5 — iOS parallel

Mirror Phases 1–4 on iOS:

- New `CaptureView.swift` with the same body.
- `MainShell.swift` adds Capture as a `TabView` destination (or whatever pattern the existing iOS app uses; check `MainShell.swift` lines 57–98 for the actual structure).
- `onBrandTap` switches tabs instead of setting `showCapture`.
- Delete `@State private var showCapture` and `.sheet(isPresented: $showCapture)`.

### Phase 6 — Docs

- `docs/NAV_GRAPH.md`: `quick_capture` row in the "New-in-v2 destinations" table → `capture` (Top-level, hosted by itself, "BottomNav visible"). Update the deep-link table. Update the §"Capture flows (modal tree)" diagram — capture-flows now branch from the Capture tab, not from a modal node.
- `docs/DAILY_CAPTURE_UX.md` §2.1 (Entry points): "Floating Leaf button. Tap → **Capture tab** (not QuickCaptureSheet modal)."

### Phase 7 — Verify

Build:
- Android: `./gradlew :app:assembleDebug`
- iOS: open `ios/Package.swift` in Xcode; previews must render

Manual checklist:
- [ ] Tap leaf FAB on Home → lands on Capture tab.
- [ ] BottomNav visible on Capture; FAB shows coral active-ring.
- [ ] Tap Home / Library / Notepad / Settings from Capture → navigates correctly; ring on FAB disappears.
- [ ] Tap Capture from another tab → returns; Day/Recents toggle position is preserved.
- [ ] Long-press FAB on any tab → records voice without switching tabs.
- [ ] Tap any capture tile → existing `beginQuickCapture` flow runs and lands on the new page editor.
- [ ] Deep link `releaf://capture` → Capture tab. `releaf://capture?kind=photo` → Capture tab + opens Photo flow.
- [ ] Search and calendar header icons route to existing `SEARCH` and `CALENDAR` destinations.

---

## Open questions

### 1 · Where does the search header icon route?

The current app has no `Routes.SEARCH` route — search is a feature in `DAILY_CAPTURE_UX.md §4.2` but not yet built. The Capture page header includes a search icon. Three options:

- **(a)** Stub it for now: tap shows a "coming soon" toast.
- **(b)** Skip the search icon in v7 — wait until search is implemented.
- **(c)** Build a minimal `SearchScreen` as part of this PR.

**Recommend (a)** — the icon's affordance is right; the screen behind it can land in a follow-up.

### 2 · Cold-launch behavior

Today the app cold-launches into Home. Should it land on Capture for capture-first users? `DAILY_CAPTURE_UX.md §6.3` says "Today is the primary launcher shortcut" — implying Home/Today is the cold-launch surface, not Capture. **Recommend:** keep cold-launch on Home; Capture is one tap away via the FAB. Revisit if usage data says otherwise.

### 3 · The pre-tag chip row when the user has zero scan history

Brand-new install → no captures → no tag aggregates. The pre-tag row would be empty except for "+ New tag." That's fine as an empty state but worth being explicit. **Recommend:** when the aggregate query returns < 3 rows, hide the pre-tag section entirely (label + chips). Re-show once the user has any scan-tagged capture.

### 4 · Should the modal `QuickCaptureSheet` be deleted in this PR or one cycle later?

After Phase 4 the modal is dead code — nothing renders it. But existing Drive/restore data may reference it via deep links from earlier app versions. **Recommend:** delete the rendering site (the `if (showCapture) {…}` block in `MainActivity.kt`), but keep the `QuickCaptureSheet.kt` file with a deprecation header for one release. Remove fully in the next PR after we've verified no deep-link path lands there.

### 5 · iOS BottomNav active-state implementation

I haven't seen the iOS `BottomNav.swift` yet. Confirm the iOS pattern matches Android's (separate `BrandTab` with the lifted coral disc) before Phase 5 — if iOS uses a different abstraction, Phase 5 needs minor rework but the goal is the same.

---

## Risk + rollback

**Per-phase rollback** (each phase is a single commit):

- Phase 1: revert the new file. Zero blast radius.
- Phase 2: revert `MainActivity.kt`. The modal returns; Capture tab disappears.
- Phase 3: revert `BottomNav.kt`. The FAB stops indicating active state but everything else works.
- Phase 4: revert `CaptureScreen.kt`. Capture tab still routes but renders the scaffold (or nothing).

**Cross-cutting risks:**

- **Compose-foundation 1.7.x sheet-on-sheet bug** is what motivated this change. After Phase 2 the bug surface goes away on Android.
- **Deep links written before this PR** that triggered the modal will now land on the Capture tab — different surface, same intent. Verify by exercising every `releaf://capture*` URL in the manual checklist.
- **iOS `.sheet(isPresented:)` interaction with the new tab-based flow** — iOS's `TabView` may have its own state quirks when removing the sheet wrapper. Test thoroughly during Phase 5.

---

## What this doesn't do

- **No new capture features.** Pre-tag categories are visualized; the actual "scanner opens with tag pre-set" wiring is its own follow-up. v7 ships the page surface; the pre-tag interaction is wired through to existing `tags` rows but the scanner-to-tag handoff requires the scan flow to accept a `tagId` parameter, which it doesn't yet.
- **No Notes implementation.** The Notes tile lands on the same `pageLocal(page.id, mode)` route as the others, with `mode = CaptureMode.Notes`. The actual "keyboard up, first newline commits" experience from `DAILY_CAPTURE_UX.md §2.3` is a separate follow-up.
- **No Recents content.** The Recents toggle works (segmented control updates state), but the Recents view itself is empty/placeholder until built. Day stays the default.
- **No iPhone SE fallback.** The v7 prototype noted that 375 × 667 doesn't fit. Whether to fall back to a tab swap (Scan | Capture) on small screens is a follow-up.

---

## Sequencing

The phases are designed so each one compiles and runs. Suggested order, one phase per commit:

1. Phase 1 (Android scaffold)
2. Phase 3 (active-ring) — can be done before Phase 2 since it doesn't depend on routing
3. Phase 2 (route + reroute FAB)
4. Phase 4 (full page body)
5. Phase 5 (iOS parallel — internally also phases 1–4)
6. Phase 6 (docs)
7. Phase 7 (verify)

This sequence ships a working modal → working tab → working tab with full content, instead of forcing a "broken in the middle" state.
