# Stories v3 — Claude Code handoff

**For:** Claude Code (or any engineer) picking up the Stories v3 build
**Status:** Ready to start
**Owner:** TBD
**Last updated:** May 2026

This document is the bridge between the v3 design and a shipped feature. Read
top to bottom once, then come back to §4 (phase plan) and work one task at a
time. The visual source of truth is `design/stories-mockup-v3.html` — open
it in a browser before touching any screen.

---

## 0. How to use this with Claude Code

1. Point Claude Code at the repo root: `/Users/achalindiresh/workspace/releaf/apps/quickink`.
2. Tell it to read §1 (pre-flight) first. The four files there are required context.
3. Have it confirm which phase it's about to start by quoting the task name from §4.
4. Work one *task* at a time. Each task has its own files and acceptance criteria; commit between tasks so review is granular.
5. When a phase is done, run the §7 verification commands and tick the phase box at the top of §4.

If Claude Code is missing context for a decision, it should *stop and ask* rather than guess. Bias toward asking — Stories touches the storage schema, the nav bar, and the share infrastructure.

---

## 1. Pre-flight reading (required, in this order)

1. `design/BRAND.md` — color tokens, type, voice, icon set. Don't introduce new colors.
2. `design/WORKSPACE_SPEC.md` — the IA Stories slots into. Folders, tags, entities, smart collections — Stories is a peer to these.
3. `design/STORIES_DESIGN.md` — the feature spec. Especially:
   - §3d *v3 — the shipping direction*
   - §6 *Data model*
   - §8 *Auto-grouping modes*
   - §10 *Phased rollout*
4. `design/stories-mockup-v3.html` — the visual spec. Seven surfaces in one
   file. Open in a browser; use a phone-width emulation (320 px) when
   eyeballing density.

If anything below conflicts with one of the four above, the above wins.

---

## 2. The feature in 30 seconds

Stories lets a user assemble a chosen subset of their scans, photos, notes,
and voice clips into a curated narrative ordered by time, with their own
notes between items, and share it as a PDF / image / native share / public
web link.

The story object is a *list of items the user picked*, ordered by position.
Auto-grouping suggests one cluster at a time (e.g. "Tokyo, May 4–7") as a
calm card on the Stories tab; users either accept and edit, or dismiss.

v3 is the polish-on-v1 direction. Same data model and screen count as v1
with six refinements baked in (calm suggestion, smart cover, sample-page
preview, cleaner editor slots, sticky day markers + end-card in the reader,
share sheet with preview thumbnail).

---

## 3. Codebase context

### iOS — `ios/QuickInk/`

```
ios/QuickInk/
├── App/                          (SwiftUI app entry, splash)
├── DesignSystem/
│   ├── QuickInkTheme.swift       ← use these tokens. Don't add new ones.
│   └── Fonts/                    ← Cormorant Garamond + Caveat .ttf
├── Home/                         (existing feature — mirror this pattern)
│   ├── HomeScreen.swift
│   ├── WorkspaceHomeViewModel.swift
│   ├── FolderPickerSheet.swift   ← good reference for bottom sheets
│   ├── SmartCollectionScreen.swift
│   └── …
├── Notes/                        (existing feature)
│   ├── NotesListScreen.swift
│   ├── NoteEditorScreen.swift    ← good reference for editor patterns
│   └── ExportSheet.swift
├── Scan/                         (existing feature, camera)
├── Search/
├── Nav/
│   └── QuickInkBottomNavBar.swift ← add Stories tab here
└── Resources/Assets.xcassets/    (six existing line icons; add `IconStory`)
```

Storage: **GRDB** (see `Home/WorkspaceHomeViewModel.swift` and
`Home/SmartCollectionScreen.swift` for the read-query pattern; see existing
migrations for the write pattern).

### Android — `android/app/src/main/java/app/quickink/mobile/`

```
mobile/
├── ui/
│   ├── theme/
│   │   ├── QuickInkColors.kt     ← use these tokens. Don't add new ones.
│   │   └── QuickInkTheme.kt
│   └── components/
├── features/                     (one package per feature)
│   ├── home/
│   ├── notes/
│   ├── scan/
│   ├── search/
│   ├── nav/                      ← add Stories tab here
│   ├── workspace/
│   └── …
├── data/                         (Room, one package per entity)
│   ├── captureperson/
│   │   ├── CapturePersonEntity.kt
│   │   └── CapturePersonDao.kt
│   ├── capturetag/
│   └── capture/
└── QuickInkApp.kt                (Room database is registered here)
```

Storage: **Room**. Existing entities live one-per-folder under `data/`; add
new entities the same way (e.g. `data/story/StoryEntity.kt` +
`StoryDao.kt`).

---

## 4. Phase plan

Six phases, each estimated. Tick the box when the phase passes verification.

- [ ] Phase 0 — Scaffolding (1 day)
- [ ] Phase 1 — Data model + Stories tab (4 days)
- [ ] Phase 2 — Editor + sheets (5 days)
- [ ] Phase 3 — Reader + day markers (3 days)
- [ ] Phase 4 — Share sheet + PDF/image export (3 days)
- [ ] Phase 5 — Auto-suggestion engine (3 days)
- [ ] Phase 6 — Public link backend (3 days, can ship behind flag)

### Phase 0 — Scaffolding

**Goal:** the app builds with a new (empty) Stories tab in the bottom nav.

| Task | Files | Done when |
|---|---|---|
| 0.1 Create iOS feature folder | `ios/QuickInk/Stories/StoriesShelfScreen.swift` (placeholder body `Text("Stories")`) | Builds, app runs |
| 0.2 Wire iOS bottom nav | `ios/QuickInk/Nav/QuickInkBottomNavBar.swift` | Stories appears as third tab; tapping shows placeholder |
| 0.3 Create Android feature package | `mobile/features/stories/StoriesShelfScreen.kt` (Composable returning `Text("Stories")`) | Builds, app runs |
| 0.4 Wire Android bottom nav | `mobile/features/nav/*.kt` | Stories appears as third tab |
| 0.5 Add IconStory asset, both platforms | `ios/.../IconStory.imageset/`, `android/.../drawable/ic_story.xml` | Icon shows in nav |

**Verification:** Build both apps. Open. See Stories tab in the bottom nav with a placeholder body.

### Phase 1 — Data model + Stories tab

**Goal:** Story rows persist, the shelf renders existing stories (no creation flow yet).

| Task | Files | Done when |
|---|---|---|
| 1.1 GRDB schema (iOS) | new migration file under iOS migrations dir; mirror existing migration style | `story` and `story_item` tables created; migration runs idempotently |
| 1.2 Swift models | `ios/QuickInk/Stories/StoryModels.swift` | `Story` and `StoryItem` structs conform to `Codable`, `FetchableRecord`, `PersistableRecord` |
| 1.3 Room schema (Android) | `mobile/data/story/StoryEntity.kt`, `StoryDao.kt`, `mobile/data/storyitem/…` + register in `QuickInkApp.kt` | Same tables, same shape |
| 1.4 Repository / VM (iOS) | `ios/QuickInk/Stories/StoriesShelfViewModel.swift` | Async fetch returns `[Story]` from GRDB |
| 1.5 Repository / VM (Android) | `mobile/features/stories/StoriesShelfViewModel.kt` | Same |
| 1.6 Shelf UI (iOS) | `ios/QuickInk/Stories/StoriesShelfScreen.swift` | Renders the §7.1 layout from mockup: search bar, "Suggested · today" hero card *(empty state for now)*, "Your stories" list, FAB |
| 1.7 Shelf UI (Android) | `mobile/features/stories/StoriesShelfScreen.kt` | Same |
| 1.8 Seed data for dev | a debug-only seeder that inserts 2-3 fake stories | Cards appear when launching debug build |

**Verification:** Open app on both platforms; see seeded stories rendering with correct typography, the cream/coral palette, and the cover colour pulling from each story's `cover_image_id`.

### Phase 2 — Editor + sheets

**Goal:** Create, edit, reorder, and persist a story end to end. No share, no auto-suggest yet.

| Task | Files | Done when |
|---|---|---|
| 2.1 Editor UI shell (iOS) | `ios/QuickInk/Stories/StoryEditorScreen.swift` | Mockup §7.3 layout — top cover strip, scrollable item list, `+ Add` buttons, bottom action bar |
| 2.2 Editor UI shell (Android) | `mobile/features/stories/StoryEditorScreen.kt` | Same |
| 2.3 + Add bottom sheet (iOS) | `ios/QuickInk/Stories/StoryAddSheet.swift` | Three sections (capture / library / layout); insertion subtitle reads `"after — \"\(precedingCaption)\""`; mirror `FolderPickerSheet.swift` pattern |
| 2.4 + Add bottom sheet (Android) | `mobile/features/stories/StoryAddSheet.kt` | Same; use `ModalBottomSheet` |
| 2.5 ⋯ Item menu sheet (iOS) | `ios/QuickInk/Stories/StoryItemMenuSheet.swift` | Item preview at top, Edit caption / Set as cover, inline Layout pills, Replace + reorder, destructive Remove below divider |
| 2.6 ⋯ Item menu sheet (Android) | `mobile/features/stories/StoryItemMenuSheet.kt` | Same |
| 2.7 Reorder (iOS) | inline | Long-press on a card enters drag mode; release commits new positions to GRDB |
| 2.8 Reorder (Android) | use `reorderable` lib (already approved per design doc §12) | Same |
| 2.9 Auto-save | repository write-debounced to 500 ms after last change | Status toast shows "Saved just now" then fades after 3 s |
| 2.10 Voice clip recording | `ios/QuickInk/Stories/VoiceClipRecorder.swift` (AVAudioRecorder); Android uses `MediaRecorder` | Tap-and-hold records, AAC-LC 64 kbps, max 10 s, waveform renders on the page |

**Verification:** Create a new story from the FAB. Add 5 items (a photo, a scan, a typed paragraph, a handwritten note, a voice clip). Reorder. Open ⋯ on one item and change its layout to Half. Close and reopen the app — state survives.

### Phase 3 — Reader + day markers

**Goal:** Tap a story → see the v3 reader layout. Works for both the author (preview) and recipients of a future public link.

| Task | Files | Done when |
|---|---|---|
| 3.1 Reader UI (iOS) | `ios/QuickInk/Stories/StoryReaderScreen.swift` | Mockup §7.4 — cover (color from hero), attribution, sticky day markers, items, pull quotes, end card |
| 3.2 Reader UI (Android) | `mobile/features/stories/StoryReaderScreen.kt` | Same |
| 3.3 Day-marker derivation | new helper `StoryDayMarkers.swift` / `.kt` | Walks items in order; emits a marker when the date or time-of-day bucket (morning/noon/evening/night) changes |
| 3.4 Dominant-colour cover | iOS uses `UIImage.dominantColor` (Vision); Android uses Palette API | Cover gradient uses a real color from the chosen hero photo, falls back to coral when no photo exists |
| 3.5 Hook Preview button | from editor → reader | Tap "Preview" in editor opens the reader at page 1 |
| 3.6 End-card actions | inline | "Reply with a note" and "Make your own" buttons hooked up (both can stub a no-op + toast in Phase 3; real behaviour ships in Phases 4 and 6) |

**Verification:** Open one of the seeded stories. Reader cover pulls a believable color. Scrolling shows sticky day markers ("— MAY 4 · EVENING —") that change as you cross day boundaries. End-card appears after the last item.

### Phase 4 — Share sheet + PDF/image export

**Goal:** Author can export and share. No backend required.

| Task | Files | Done when |
|---|---|---|
| 4.1 Share sheet (iOS) | `ios/QuickInk/Stories/StoryShareSheet.swift` | Mockup §7.5 layout: preview thumbnail at top, 2×2 share-option grid, link box at bottom |
| 4.2 Share sheet (Android) | `mobile/features/stories/StoryShareSheet.kt` | Same |
| 4.3 PDF export (iOS) | use `UIGraphicsPDFRenderer`; one item per page when item layout is `full`, two per page when `half`, four when `grid` | Saved PDF opens in Files, looks like the reader |
| 4.4 PDF export (Android) | `PrintedPdfDocument` | Same |
| 4.5 Long image export | render the full reader to an off-screen view and snapshot it; cap at 12 items per image | PNG saved to camera roll |
| 4.6 Native share | `UIActivityViewController` (iOS); `ACTION_SEND` (Android) | Choosing WhatsApp/Messages/Mail attaches the PDF or image |

**Verification:** Open a story → Share → Save as PDF. Open the PDF. It should look like the reader, with the cover on the first page and pages of items after.

### Phase 5 — Auto-suggestion engine

**Goal:** The "Suggested · today" hero card on the Stories tab is populated by a real algorithm.

| Task | Files | Done when |
|---|---|---|
| 5.1 Shared algorithm spec | `shared/algorithms/story-suggestions.md` (create new) | Documents the date-clustering rule from `STORIES_DESIGN.md` §8.1 |
| 5.2 Suggestion engine (iOS) | `ios/QuickInk/Stories/StorySuggestionEngine.swift` | Runs on `Library` refresh; emits at most one `StorySuggestion` to a small in-memory cache |
| 5.3 Suggestion engine (Android) | `mobile/features/stories/StorySuggestionEngine.kt` | Same |
| 5.4 Hero card hookup | shelf VM observes suggestion cache | Hero card appears only when a high-confidence cluster exists; "Not interested" dismisses it for the session |
| 5.5 Suggestion preview screen | `StorySuggestionPreviewScreen.swift` / `.kt` | Mockup §7.2 — sample cover + first page + item strip, "Edit first" / "Make story" CTAs |

**Verification:** Add ~15 dated dummy items to the library, spaced over three days within an 18-hour gap, then a 3-day silence, then another cluster. The first cluster should appear as a suggestion. Dismiss; verify it stays dismissed for the session.

### Phase 6 — Public link backend (behind flag)

**Goal:** Author can publish a story as a public web page.

This phase has backend work. Keep it behind a feature flag (default off in
TestFlight, on for internal). Reference `STORIES_DESIGN.md` §9 for the
endpoint and slug rules.

---

## 5. Data model — code-ready

**iOS — GRDB**

```swift
// ios/QuickInk/Stories/StoryModels.swift
import GRDB
import Foundation

struct Story: Codable, FetchableRecord, PersistableRecord {
    var id: String                       // ULID
    var title: String
    var subtitle: String?
    var coverItemId: String?
    var coverStyle: CoverStyle = .photo  // .photo | .typographic | .gradient
    var themeStyle: ThemeStyle = .editorial
    var groupingMode: GroupingMode = .timeline
    var timeRangeStart: Date?
    var timeRangeEnd: Date?
    var status: Status = .draft          // .draft | .published
    var shareMode: ShareMode = .private  // .private | .publicLink | .inApp | .exported
    var shareSlug: String?
    var createdAt: Date
    var updatedAt: Date

    enum CoverStyle: String, Codable { case photo, typographic, gradient }
    enum ThemeStyle: String, Codable { case editorial, scrapbook, minimal }
    enum GroupingMode: String, Codable { case timeline, activity, custom }
    enum Status: String, Codable { case draft, published }
    enum ShareMode: String, Codable { case `private`, publicLink, inApp, exported }
}

struct StoryItem: Codable, FetchableRecord, PersistableRecord {
    var id: String                       // ULID
    var storyId: String
    var position: Int                    // ordinal; 1024-spaced for cheap reorder
    var kind: Kind                       // see below
    var refId: String?                   // documentId / photoId / noteId / voiceClipId
    var text: String?                    // for textBlock / handwrittenNote / caption
    var caption: String?
    var occurredAt: Date?                // override; defaults to source item's date
    var layout: Layout = .full
    var createdAt: Date

    enum Kind: String, Codable {
        case document, photo, note, voiceClip
        case textBlock, handwrittenNote
        case dateDivider, placePin
    }
    enum Layout: String, Codable { case full, half, grid }
}

struct StorySuggestion {                 // ephemeral, not persisted in v3
    let id: String
    let reason: String                   // "12 photos and 3 receipts, May 4–7, near Tokyo."
    let candidateRefs: [String]
    let score: Double
}
```

**Android — Room**

Mirror the Swift shapes 1:1. `@Entity(tableName = "story")` and `@Entity(tableName = "story_item")` with the same columns; use a `Converters` class for the enums (store as `TEXT`). DAOs match the read patterns in `WorkspaceHomeViewModel.swift`.

**Position spacing.** Use 1024-spaced integers for `position` so a reorder is a single update (`new = (prev + next) / 2`) instead of a full rewrite. Renormalize on collision.

---

## 6. Visual implementation guide

### Tokens — use the existing ones only

| What | iOS | Android |
|---|---|---|
| Canvas | `QuickInkColors.canvas` | `QuickInkColors.Canvas` |
| Surface | `QuickInkColors.surface` | `QuickInkColors.Surface` |
| Coral | `QuickInkColors.accent` | `QuickInkColors.Coral` |
| Ink | `QuickInkColors.ink` | `QuickInkColors.Ink` |
| Ink soft | `QuickInkColors.inkSoft` | `QuickInkColors.InkSoft` |
| Paper warm 1/2/3 | `paperWarm1` / `2` / `3` | `PaperWarm1` / `2` / `3` |
| Border | `QuickInkColors.border` | `QuickInkColors.Border` |

If any token name doesn't match, fall back to the hex in `BRAND.md` table and ask whether to extend the design system.

### Type

| Use | Font | Weight | Size |
|---|---|---|---|
| Screen title | Cormorant Garamond | 500 | 28 |
| Section heading | Cormorant Garamond | 500 | 16 |
| Body | System sans | 400 | 14 |
| Caption / italic | Cormorant Garamond italic | 400 | 12 |
| Handwritten subtitle | Caveat | 500 | 18 |
| Section label all-caps | System sans | 500 | 11, letter-spacing 1.5 |

Two weights only across all UI surfaces — 400 and 500. Never 600+.

### New icons

Two icons need adding by the designer (same family as the existing six in `design/exports/icons/`):

- **story** — stacked-pages glyph; goes in the bottom nav
- **share-link** — chain link; used in the share sheet "Public link" tile

For now Claude Code can use a temporary system icon (`book.closed` on iOS, `Icons.AutoMirrored.Outlined.MenuBook` on Android) and add a TODO comment with `// TODO(design): replace with line icon when shipped from design/exports/icons/story.svg`.

### Common patterns

- **Bottom sheets:** mirror `ios/QuickInk/Home/FolderPickerSheet.swift` for iOS structure (state-driven, 38×4 handle, sentence-case header, 0.5 px section dividers). Android: use Material 3 `ModalBottomSheet` with `WindowInsets.navigationBars` handling.
- **Lists with elevation-on-active:** the `.active` item in the editor uses a 1.5 px coral border + soft shadow. Look at `NoteEditorScreen.swift` for the same pattern.
- **Auto-save toast:** look at any existing screen that does background save (Notes editor likely has it); reuse that timing.

---

## 7. Verification commands

Run these after each phase. They're the gates.

```bash
# iOS — build + unit tests
cd ios && xcodebuild -scheme QuickInk -destination 'platform=iOS Simulator,name=iPhone 15' build test

# Android — build + unit tests
cd android && ./gradlew :app:test :app:assembleDebug

# Both — lint
cd ios && swiftlint
cd android && ./gradlew :app:lint
```

For each new screen, take a screenshot at iPhone 15 width (390 pt) and put it next to the matching frame in `design/stories-mockup-v3.html` — they should match within a hair on spacing, type rhythm, and color.

---

## 8. Don't-do list

These are things to *not* do, no matter how reasonable they seem mid-build.

- **Don't introduce new color tokens.** If you need a color, it's already in `BRAND.md`. If genuinely missing, stop and raise it.
- **Don't introduce new fonts or weights.** Two weights only — 400, 500.
- **Don't use ALL CAPS for body or UI labels.** Sentence case throughout. The only exception is the small italic section labels in the section dividers ("CAPTURE NEW", "FROM YOUR LIBRARY") which are deliberately small (11 px) and letter-spaced.
- **Don't add an emoji anywhere.** Brand voice is editorial.
- **Don't build the v2 chapbook surfaces.** That's a possible v2.0 visual overhaul, not v3.
- **Don't build the in-app feed share target.** It depends on a friend graph that doesn't exist; ships in v1.1.
- **Don't auto-publish a story.** All publishing is explicit. The "Public link" tile requires a tap *and* confirmation before the slug is generated.
- **Don't drop the `cover_item_id` foreign key when the referenced item is removed from the story.** Null it instead. The story should survive removal of its cover item.
- **Don't ship the v3 sample-page suggestion preview as a static screenshot** — it must render the actual first page using the same components the reader uses. Half the point is that the user sees the real output.

---

## 9. Open questions — answer before starting

1. **Voice clip storage location.** Local-only in v3, or sync to user's Drive backup? Drive backup adds complexity but matches the existing capture pattern. *Recommendation: local-only in v3; Drive sync as a follow-up.*
2. **Item count cap.** `STORIES_DESIGN.md` §13 says cap at 50 in v1. Confirm 50 for v3 too. *Recommendation: yes.*
3. **Smart cover — what happens for non-photo stories?** A renovation log made entirely of receipts has no hero photo. *Recommendation: fall back to a paper-warm gradient + typographic cover.*
4. **Suggestion dismissal persistence.** Session-only or persisted? *Recommendation: session-only in v3; persisted dismissals are a v1.1 polish.*
5. **End-card "Reply with a note" target.** Where does the reply land — the author's notes inbox? An email? In-app DM (doesn't exist)? *Recommendation: stub to a no-op + toast in v3, decide in v1.1.*

---

## 10. Definition of done

Stories v3 ships when:

1. All six phases pass their verification gates.
2. A user can create, edit, share-as-PDF, and share-as-link a 10-item story without touching settings.
3. The Stories tab is reachable from the bottom nav in both apps and renders without crashes on empty state, one-story state, and 20+ stories state.
4. The auto-suggestion hero card appears at least once during a 5-minute "capture a bunch of stuff" session in the QA build.
5. The share-as-PDF output, opened in Preview / Adobe / Files, looks like the reader screen — same cover, same day markers, same captions, same end card.
6. Screenshots of each screen, taken on iOS and Android at default font size, match `design/stories-mockup-v3.html` within a hair.
7. There are no new color tokens, no new font weights, no emojis, and no `// TODO` comments left without a Linear ticket linked.

---

## Companion files

- `design/STORIES_DESIGN.md` — the why and what
- `design/stories-mockup-v3.html` — the visual spec (open in browser)
- `design/stories-mockup.html` — landing page comparing v1, v2, v3
- `design/BRAND.md` — tokens, type, voice
- `design/WORKSPACE_SPEC.md` — the IA Stories slots into
