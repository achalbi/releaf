# Workspace tab — folders & tags refresh (Claude Code handoff)

**For:** Claude Code (or any engineer) picking up this work
**Status:** Ready to start
**Owner:** TBD
**Last updated:** May 2026

> **Scope.** This is *not* a redesign of the Workspace tab. We are replacing
> two regions inside the existing tab — the **folders list** and the **tag
> vocabulary section** — with the refined taxonomy in §4. Everything else on
> the tab (header, search pill, sync status, Recent rail, FAB, bottom nav)
> stays exactly as it is.

The visual reference for the two changed regions is
`design/workspace-tab-A-v2.html`. Open it in a browser at 390 px width
before touching any code. Treat that file as the source of truth for the
folders section and the tag vocabulary section *only* — its header, search
pill and chrome are illustrative and may differ from production.

---

## 0. How to use this with Claude Code

1. Point Claude Code at the repo root: `/Users/achalindiresh/workspace/releaf/apps/quickink`.
2. Read §1 (pre-flight) first. The four files there are required context.
3. Confirm which phase you are about to start by quoting the task name from §5.
4. Work one task at a time, commit between tasks.
5. iOS and Android ship together, file-for-file.
6. Run the §8 verification at the end of every phase and tick the phase box in §5.

If context is missing for a decision, **stop and ask** rather than guess —
the surrounding tab is shipping code and should not be touched.

---

## 1. Pre-flight reading (required, in this order)

1. `design/BRAND.md` — color tokens, type, voice. Don't introduce new colors except the seven bucket hues in §4.3.
2. `design/WORKSPACE_SPEC.md` — the IA. Read §5 (layers), §6 (folder types), §7 (tag types), §9 (naming rules), §16 (MVP scope).
3. The QuickInk Reference Card PDF (in `design/`) — folder taxonomy + the three-question capture flow. §4 of this doc supersedes the card's tag list.
4. `design/workspace-tab-A-v2.html` — visual spec for the two changed regions.

If anything below conflicts with one of the four above, the above wins —
*except* the tag vocabulary, where §4 of this doc supersedes the reference
card.

---

## 2. What's changing, what isn't

The Workspace tab today is the screen rendered by `HomeScreen.swift` (iOS) /
`HomeScreen.kt` (Android). The vertical layout, top to bottom, is:

```
1. Greeting header + avatar                  ← KEEP
2. Search pill                                ← KEEP
3. Sync status pill                           ← KEEP
4. Daylight / sustainability hero card        ← KEEP
5. Recent scans rail                          ← KEEP
6. ─────────────────────────────────────────
   Category grid (2-column legacy)           ← REPLACE with §3.1
   Tag library entry point                   ← REPLACE with §3.2
   ─────────────────────────────────────────
7. Zap FAB                                    ← KEEP
8. Bottom nav                                 ← KEEP
```

Region 6 is the only thing this handoff touches. Anything outside region 6 is
out of scope and must render byte-for-byte identical before and after the
change (verified by the snapshot tests in §8).

---

## 3. The two new regions

Both regions are inserted in place of the existing 2-column category grid, in
the order below. Both render inside the existing `HomeScreen` scroll
container — they are sections, not new screens.

### 3.1 Folders section

A tiered list. Three tier blocks (Workflow · Life Domains · Creative &
Output), each block is a `TierHeader` + a stack of `FolderRow` components.
Twelve rows total. Tap a row → `FolderDetailScreen` (already shipped).

Component spec is in §6. Visual reference is the upper half of
`workspace-tab-A-v2.html`.

### 3.2 Tag vocabulary section

Seven `TagBucketBlock` components rendered in this order: Status · People ·
Org & Place · Energy · Time-sensitivity · Kind · Source. Each block is the
bucket bar + name + question + count + a pill row. Tap a pill → existing
tag-filtered list (`TagLibraryScreen` → `TagFilteredEntriesScreen`).

Component spec is in §6. Visual reference is the lower half of
`workspace-tab-A-v2.html`.

---

## 4. Data — folder seed & tag vocabulary

These two seeds are the source of truth. Bake them into a single
`workspace_seed.json` shipped with the app and applied during the
categories → folders migration. Both platforms read the same file.

### 4.1 Folder seed

Twelve folders, three tiers. `tier` is presentation only; the underlying
`folders` table is flat.

```json
{
  "folders": [
    { "id": "inbox",     "name": "Inbox",     "tier": 1, "type": "Inbox",     "desc": "Capture zone — everything lands here first" },
    { "id": "archive",   "name": "Archive",   "tier": 1, "type": "Archive",   "desc": "Completed or dormant, kept for reference" },

    { "id": "finance",   "name": "Finance",   "tier": 2, "type": "Reference", "desc": "bills, taxes, subscriptions" },
    { "id": "medical",   "name": "Medical",   "tier": 2, "type": "Reference", "desc": "appointments, scripts, results" },
    { "id": "family",    "name": "Family",    "tier": 2, "type": "Reference", "desc": "people, relationships, home" },
    { "id": "travel",    "name": "Travel",    "tier": 2, "type": "Project",   "desc": "trips, bookings, places" },
    { "id": "events",    "name": "Events",    "tier": 2, "type": "Project",   "desc": "weddings, parties, conferences" },
    { "id": "legal",     "name": "Legal",     "tier": 2, "type": "Reference", "desc": "contracts, IDs, records" },
    { "id": "lifestyle", "name": "Lifestyle", "tier": 2, "type": "Reference", "desc": "fitness, food, hobbies, style" },

    { "id": "learning",  "name": "Learning",  "tier": 3, "type": "Reference", "desc": "notes from books, podcasts" },
    { "id": "projects",  "name": "Projects",  "tier": 3, "type": "Project",   "desc": "committed efforts w/ an end" },
    { "id": "ideas",     "name": "Ideas",     "tier": 3, "type": "Reference", "desc": "sparks, someday/maybe" }
  ]
}
```

`type` maps to the four behavioral folder types in `WORKSPACE_SPEC.md` §6.
Inbox is system-managed (cannot be renamed or deleted).

### 4.2 Tag vocabulary seed

Seven buckets, 32 tags. **This supersedes the reference card.** The bucket is
a presentation grouping; in the schema each tag has a `bucket` column.

```json
{
  "buckets": [
    {
      "id": "status",
      "name": "Status",
      "question": "what state is it in?",
      "controlled": true,
      "hue": "#4F46E5",
      "tags": ["active", "todo", "later", "done"]
    },
    {
      "id": "people",
      "name": "People",
      "question": "who is this about?",
      "prefix": "p/",
      "controlled": false,
      "hue": "#0F9F6E",
      "tags": ["p/mom", "p/manager", "p/sarah"]
    },
    {
      "id": "orgplace",
      "name": "Org & Place",
      "question": "what organization or location?",
      "prefix": ["org/", "place/"],
      "controlled": false,
      "hue": "#0891B2",
      "tags": ["org/aws", "org/clinic", "place/lisbon", "place/home"]
    },
    {
      "id": "energy",
      "name": "Energy",
      "question": "what state of mind does it need?",
      "controlled": true,
      "hue": "#C2570A",
      "tags": ["focus", "shallow", "errand", "call"]
    },
    {
      "id": "time",
      "name": "Time-sensitivity",
      "question": "which horizon?",
      "controlled": true,
      "exclusive": true,
      "hue": "#D33B4D",
      "tags": ["today", "thisweek", "thismonth"]
    },
    {
      "id": "kind",
      "name": "Kind",
      "question": "what kind of content?",
      "controlled": false,
      "hue": "#7C3AED",
      "tags": ["idea", "quote", "recipe", "checklist", "template"]
    },
    {
      "id": "source",
      "name": "Source",
      "question": "where did it come from?",
      "controlled": true,
      "auto_applied": true,
      "hue": "#0E7FB8",
      "tags": ["scan", "voice", "handwritten", "web", "email", "book", "podcast", "article", "conversation"]
    }
  ]
}
```

Rules enforced in the schema:

- `controlled: true` → user cannot create new tags in this bucket.
- `exclusive: true` → a note can carry at most one tag from this bucket. Enforce at the write boundary.
- `auto_applied: true` → set by the capture pipeline based on the source channel; user does not type these.
- `prefix` → user-extensible; the prefix is autocompleted.

Cross-bucket: a note has at most six total tags (warning at five, hard cap at
six). Status, Time and Energy are mutually exclusive within their bucket.

### 4.3 Color tokens — additive only

Add these to the theme. Do not touch existing tokens.

iOS (`QuickInkTheme.swift`):

```swift
extension QuickInkColors {
  static let bucketStatus   = Color(hex: 0x4F46E5)
  static let bucketPeople   = Color(hex: 0x0F9F6E)
  static let bucketOrgPlace = Color(hex: 0x0891B2)
  static let bucketEnergy   = Color(hex: 0xC2570A)
  static let bucketTime     = Color(hex: 0xD33B4D)
  static let bucketKind     = Color(hex: 0x7C3AED)
  static let bucketSource   = Color(hex: 0x0E7FB8)

  // Tier stripes
  static let tier1 = Color(hex: 0x6366F1)
  static let tier2 = QuickInkColors.ink
  static let tier3 = Color(hex: 0x10B981)
}
```

Android (`ui/theme/QuickInkColors.kt`):

```kotlin
val BucketStatus   = Color(0xFF4F46E5)
val BucketPeople   = Color(0xFF0F9F6E)
val BucketOrgPlace = Color(0xFF0891B2)
val BucketEnergy   = Color(0xFFC2570A)
val BucketTime     = Color(0xFFD33B4D)
val BucketKind     = Color(0xFF7C3AED)
val BucketSource   = Color(0xFF0E7FB8)

val Tier1 = Color(0xFF6366F1)
val Tier2 = QuickInkInk
val Tier3 = Color(0xFF10B981)
```

Pill background = bucket hue at 12 % opacity over canvas. Pill border = hue at
100 %. Pill text = hue at 100 %.

---

## 5. Phase plan

Five phases. Tick boxes here as you complete them.

- [ ] **Phase 1 · Tokens & primitives.** Bucket hues + tier stripe tokens in theme; `TagPill`, `FolderRow`, `TierHeader`, `TagBucketBlock` shared components. No screen wired up yet.
- [ ] **Phase 2 · Data layer.** Ship `workspace_seed.json`; migrate the existing `categories` table to the new `folders` model (mapping in §7); add the `bucket` column on `tags`; enforce `controlled`, `exclusive`, `auto_applied` at the write boundary.
- [ ] **Phase 3 · Folders section.** Replace the legacy category grid in `HomeScreen` (iOS + Android) with the three tier blocks and the twelve folder rows. Counts pulled live from the folders/documents tables.
- [ ] **Phase 4 · Tag vocabulary section.** Render the seven `TagBucketBlock` components beneath the folders section. Tap a pill → existing tag-filtered list.
- [ ] **Phase 5 · Polish & acceptance.** Empty / loading states for the two new regions, accessibility labels, snapshot test for the unchanged regions (§8), snapshot test for the two new regions.

Explicitly out of scope:

- The Workspace tab's other regions (header, search, sync pill, Daylight card, Recent rail, FAB, nav).
- Smart Collections, Entity surface, Ask Workspace (deferred per `WORKSPACE_SPEC.md` §16.2).
- The capture review screen's tag picker (uses the same vocabulary; tracked separately).

---

## 6. Component spec

All measurements at 390 dp logical width. Sections sit inside the existing
`HomeScreen` scroll, share its 22 px horizontal padding.

### `TierHeader`

| Element | Spec |
|---------|------|
| Tier numeral | 14 pt Cormorant Garamond, coral `#D97757`, letter-spacing 0.06 em |
| Tier label | 11 pt Inter Medium, ink, letter-spacing 0.13 em, uppercase |
| Tier sub (optional) | 11 pt Inter Regular Italic, muted |
| Divider | 1 px border below the header row, 8 px top margin |

### `FolderRow`

| Element | Spec |
|---------|------|
| Tier stripe | 3 × 26 px rounded, color per tier |
| Name | 15 pt Inter Medium, ink |
| Description | 12 pt Inter Regular, muted, single line, ellipsised |
| Count pill | 12 pt, ink-soft on `--border-softer`, 999 px radius, padding 3 × 8 px |
| Chevron | 18 pt, muted |
| Row padding | 13 px vertical, 14 px gap, bottom border 1 px (last row has no border) |
| Tap target | Whole row |

### `TagBucketBlock`

| Element | Spec |
|---------|------|
| Bar | 3 × 26 px rounded, bucket hue |
| Bucket name | 12.5 pt Inter Semibold, uppercase, bucket hue, letter-spacing 0.04 em |
| Bucket prefix glyph (e.g. `(#p/)`) | Inline after name, muted, regular weight |
| Question | 11 pt Inter Regular Italic, muted |
| Count pill | 11 pt, same style as folder count pill |
| Pill row | Wrap, 6 px gap, 15 px left indent under the bar |
| Block padding | 12 px top, bottom border 1 px (last block has no border) |

### `TagPill`

| Element | Spec |
|---------|------|
| Height | 26 px |
| Padding | 0 × 11 px |
| Radius | 999 px |
| Font | 12 pt Inter Medium |
| Border | 1 px, bucket hue |
| Background | bucket hue @ 12 % over canvas |
| Text | bucket hue |
| `+ add` variant | Dashed border, muted text, canvas background |

---

## 7. Migration — categories → folders

The legacy `categories` table is being replaced by `folders`. The mapping is
shipped with `workspace_seed.json`. The migration runs once on first launch
after the update.

| Legacy category | New folder | Notes |
|-----------------|-----------|-------|
| `inbox`, `default`, untagged | `inbox` | system-managed, cannot be deleted |
| `archive`, `archived` | `archive` | |
| `finance`, `bills`, `taxes` | `finance` | merge |
| `medical`, `health` | `medical` | merge |
| `family`, `home`, `personal` | `family` | merge |
| `travel` | `travel` | |
| `events`, `weddings` | `events` | |
| `legal`, `contracts` | `legal` | |
| `lifestyle`, `fitness`, `food` | `lifestyle` | merge |
| `learning`, `notes`, `study` | `learning` | merge |
| `projects`, `work` | `projects` | |
| `ideas`, `someday` | `ideas` | |
| anything not in the above | `inbox` | with a `#needs-triage` system tag |

Migration is reversible for 30 days (per `WORKSPACE_SPEC.md` §15.4) — back up
the legacy `categories` rows in a `_legacy_categories` table before the
migration runs.

---

## 8. Acceptance criteria & verification

### Unchanged regions

This is the most important check.

- Snapshot test: before-and-after pixel diff of the Workspace tab top region (status bar → end of Recent rail) is zero non-anti-aliased difference.
- Snapshot test: before-and-after pixel diff of the FAB + bottom nav is zero.
- Behavioral: tapping search, sync pill, Recent rail items, the Zap FAB, and any nav tab routes to exactly the same destinations as before.

### Folders section

- All twelve folders render in the right tier with correct stripe colors.
- Doc counts match `SELECT COUNT(*) FROM documents WHERE folder_id = ?` for each folder.
- Tap on a row routes to `FolderDetailScreen` with the folder ID.
- Inbox row shows the system-managed indicator (lock glyph next to the name).

### Tag vocabulary section

- Seven bucket blocks render in the order in §4.2.
- Pill colors match the bucket hues in §4.3.
- Tap on a pill opens the tag-filtered list with that single tag applied.
- `controlled: true` buckets do not show a `+ add` pill.
- `auto_applied: true` Source bucket shows the "· auto-applied" marker on the bucket name.

### Migration

- Every legacy document is reachable through exactly one new folder after migration.
- Running the migration twice is a no-op (idempotent).
- Rolling back within 30 days restores the original `categories` rows.

### Verification commands

```bash
# iOS
cd ios && xcodebuild test -scheme QuickInk -destination 'platform=iOS Simulator,name=iPhone 15' \
  -only-testing:QuickInkTests/WorkspaceTabSnapshotTests \
  -only-testing:QuickInkTests/CategoriesMigrationTests

# Android
cd android && ./gradlew :app:testDebugUnitTest \
  --tests "app.quickink.mobile.features.home.WorkspaceTabSnapshotTest" \
  --tests "app.quickink.mobile.data.CategoriesMigrationTest"

# Visual regression (full Workspace tab)
./scripts/visual-diff.sh workspace-tab
```

The snapshot tests are new; add them in Phase 5. The migration test fixture
goes in `tests/fixtures/categories-migration/` with one input table per
edge case in §7.

---

## 9. Open questions

Resolve with the design owner before starting Phase 4:

1. Should the `Source` bucket be visible at all, given its tags are auto-applied? (Teaches the vocabulary vs. adds nine pills the user never types.)
2. Do the `#p/`, `#org/`, `#place/` prefixes show on the rendered pill, or are they implied by bucket color?
3. Does Inbox get a system-managed glyph next to its name in the row, or is the tier stripe sufficient?

Don't guess. If the design owner is unreachable, ship Phase 1–3 first; the
above only blocks Phase 4.

---

*Companion files: `design/WORKSPACE_SPEC.md`, `design/BRAND.md`,
`design/workspace-tab-A-v2.html`, `design/workspace-tab-prototypes.html`
(directions B and C are reference only — not shipping).*
