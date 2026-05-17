# QuickInk Stories — Design & Plan

**Status:** Draft v2 — Chapbook direction
**Owner:** Product
**Audience:** Design, Product, Engineering
**Companion mockups:**
- `design/stories-mockup.html` — comparison landing page (all three)
- `design/stories-mockup-v1.html` — v1 (memories-style, original)
- `design/stories-mockup-v2.html` — v2 (chapbook, bold reinvention)
- `design/stories-mockup-v3.html` — **v3 (refined memories — recommended)**

> **Direction update.** After reviewing v2 (chapbook), we're shipping
> **v3** — a polished refinement of v1 — as the v1.0 build. v2's
> chapbook reinvention is parked as a possible v2.0 visual overhaul
> once the core flow is validated. See §3d below for v3 specifics.

> **What changed from v1.** The v1 draft treated Stories as a generic
> "memories / curated album" feature — a Stories tab with suggestion
> cards, a card-list editor, and a scrolling reader. It worked, but it
> looked like every other photo app's auto-album. v2 commits to a
> stronger concept that only QuickInk could ship: a **chapbook** — a
> small bound book of pages. The story object is a *book*; items are
> *pages*; reading is *page-turning*; the cover is composed, not
> photographed. Three small UX moves go with it: add-to-current-story
> from anywhere, first-class voice clips, and three-angle ("multi-cut")
> suggestions instead of a single chronological dump.

---

## 1. Summary

Stories is a curated, shareable narrative layer over the user's existing
scans, photos, and notes. Where folders are stable containers and Smart
Collections are dynamic queries, a **Story** is a *hand-shaped sequence*:
a chosen subset of captured items, ordered along a timeline or activity,
with the user's own notes woven between them.

The job-to-be-done sits one step beyond "find it again" — it is **"show
someone what happened"**: a trip, a cooking class, a renovation log, a
child's first school month. The output is something the user is proud
enough to send.

---

## 2. Why now

QuickInk already ingests the raw material: dated documents, dated photos,
handwritten quick-notes, location and entity metadata. Today that material
serves recall (re-finding). Stories turns the same metadata into
**narrative** — a small but high-value mode that competes with paper
scrapbooks, photo-book apps, and ad-hoc shared albums.

The feature also unlocks a *social* surface (public link, in-app feed) for
the first time, without breaking the calm/local-first principle: a Story
is opt-in, share-on-tap, and the underlying documents stay private.

---

## 3. Goals & non-goals

**Goals**

- Make a 12-item story in under three minutes from existing scans/photos.
- Three grouping affordances — *auto by date*, *auto by activity*,
  *user-curated* — so the user picks how much help they want.
- Four share targets — *PDF/image export*, *native share sheet*,
  *public web link*, *in-app feed* — without forcing account creation
  until the chosen target requires it.
- Preserve the brand voice: editorial layout, restrained color, room to
  breathe. Stories should *read*, not *bling*.

**Non-goals (v1)**

- Video clips. Stills + text only at launch.
- Collaborative authoring (multiple authors on one story). Read-only share
  is fine; co-editing is v3.
- Templates marketplace. Three built-in themes is enough at launch.
- Monetization. No paywall on Stories in v1.

---

## 3b. The Chapbook concept

QuickInk's identity is editorial paper-and-ink. A "story" in that voice
is not an Instagram carousel and not a memories slideshow — it's a small
hand-bound book. We commit to that metaphor end-to-end:

- **The story object is a chapbook.** A cover with a calligraphic Q
  monogram, a serif title, a thin coral rule, a Caveat subtitle, an
  italic "Q U I C K I N K  ·  N O . 14" badge. Always cream paper,
  always a stitched binding edge.
- **Items are pages, not cards.** Each item — photo, scan, note, voice
  clip — occupies a numbered page. Page numbers in italic Garamond at
  the corner. A facing-page spread is the natural unit in landscape; on
  phones we render one page at a time with a swipe-to-turn gesture.
- **The reader is a book, not a feed.** Horizontal page-turn (Apple
  Books model) is the default. A peek of the next page sits at the
  right edge. Tap the top of the screen to reveal a progress bar of
  dots, and a button to jump to the auto-generated table of contents.
- **The cover is composed, not photographed.** A cover is generated
  from the story's metadata — title, date, page count, a monogram, a
  rule. Users can swap to a "with hero photo" variant, but the default
  cover is *typographic*. This is the move that most cleanly separates
  Stories from photo-book apps.
- **The library is a shelf.** The Stories tab renders existing stories
  as actual book covers laid out in rows, with shelf bars beneath. A
  draft has a different paper colour and a "DRAFT" badge in the
  top-stripe slot where the issue number sits on a published book.

The chapbook concept is the *default look*. Field Journal (tea-stained
naturalist pages, tilted polaroids, marginalia) is a v2.1 themed
alternative for memoir/travel use cases — see §16.

## 3c. Three UX moves that ship with the chapbook

These are small but compound. None requires backend work; all three ship
in Phase 1 alongside the chapbook chrome.

1. **Add-to-current-story, from anywhere.** Story is a *side-car*, not
   a destination. Long-press on any scan, photo, note, or entity card
   surfaces an "Add to *Tokyo, May 2026*" row at the top of the
   contextual menu (with the current draft's name in italic Garamond).
   Users grow a story as they capture, not after the trip.

   When no draft is current, the first option becomes "Start a story
   with this." Tapping creates a new draft seeded with this item and
   opens the editor.

2. **Voice clips as first-class items.** A page can hold a 10-second
   voice clip rendered as an inked waveform on a paper-warm tile, with
   the duration in italic Garamond. A 10-second voice memo is far more
   personal than a typed caption — it sounds like the author. Captures
   are tap-and-hold (matches WhatsApp / iMessage muscle memory).

   Implementation: AVAudioRecorder on iOS, MediaRecorder on Android,
   AAC-LC at 64 kbps. Stored as a blob alongside the StoryItem.
   Transcript is generated on-device (Speech framework / Android
   SpeechRecognizer) and stored as the item's searchable text.

3. **Multi-cut suggestions.** Instead of one suggestion per cluster
   ("Tokyo trip"), we offer the same source material in three angles:
   - *as a food diary* — receipts + meal photos in eating order
   - *as one image per day* — top-scored photo per day, single caption
   - *as a receipt log* — just the paper, totaled and dated

   These are creative seeds, not chronological dumps. Each angle is a
   pre-arranged starting draft the user can edit or publish as-is.
   Angle generation rules are deterministic and documented in
   `shared/algorithms/story-cuts.md`. v1 ships with five angle
   templates; we'll add more based on which ones get accepted.

## 3d. v3 — the shipping direction (refined memories)

v3 takes the v1 memories-style structure unchanged — same data model,
same five screens, same share targets — and applies six small but
high-impact refinements. The result reads as a careful version of a
familiar pattern rather than as a new pattern.

Why v3 over v2: v1's flow is what most users will recognize on first
open; the chapbook reinvention pays off in distinctiveness but at a
real engineering cost (page-turn gestures, voice capture infra,
auto-ToC) and a learning-curve risk. v3 lets us ship the calmer flow
in the same 4-week MVP window as v1, then revisit the chapbook
treatment as a *theme* in v2.0 if user research validates the appetite.

### The six refinements

1. **Calmer Stories tab.** The v1 horizontal suggestion row becomes
   a single focused **"Suggested · today"** card with:
   - a small preview strip (one hero photo + two complementary
     thumbnails),
   - the full reason line in italic Garamond
     (*"12 photos and 3 receipts. We've arranged them in the order
     you took them."*),
   - a soft "Not interested" dismiss and a coral "Open preview →" CTA.

   At most one suggestion card per session, and only when a
   high-confidence cluster exists. The library list breathes again.

2. **Smart covers.** Instead of always-coral fallback, a story's
   cover image **pulls the dominant color from its hero photo** (via
   `UIImage.dominantColor` on iOS, Palette API on Android). A
   one-tap **Cover** affordance in the editor lets the user swap to:
   - the photo with a darken-overlay (default),
   - a typographic cream cover (Cormorant Garamond title on
     `#FAF7F2`, optional small monogram),
   - a paper-warm gradient with the title in ink.

   Three presets, one tap each. No color picker, no canvas.

3. **Sample-page suggestion preview.** The v1 preview screen — a
   flat 4×2 thumbnail grid — becomes a **"What it'll look like"**
   render: the actual cover the story will get plus the rendered
   first page (item + caption). Below that, a horizontal strip of the
   remaining items in order. Two CTAs unchanged ("Edit first" /
   "Make story").

   Users dismiss auto-grouped suggestions when they distrust the
   output; showing the actual rendered result closes the loop.

4. **Cleaner editor.** Three small changes:
   - Each "+" slot becomes a single rounded "Add" button with a
     dashed border, opening a bottom-sheet picker
     (photo · scan · note · text · divider · location · voice).
     Replaces the v1 inline icon-row label which read as control text.
   - Each item card gets a `⋯` overflow menu on the right (replace,
     remove, change layout, set as cover) and drops the always-visible
     drag handle. Long-press anywhere on the card enters drag mode.
   - The currently-tapped item gets a 1px coral border and a soft
     elevation. This makes the editor feel responsive on a small
     screen where state changes are otherwise invisible.

   A persistent **cover strip** sits at the top of the editor showing
   the current cover thumbnail, title in serif, subtitle in Caveat,
   and a coral "Cover" link to swap.

5. **Sticky day markers + end-card in the reader.** The reader keeps
   the v1 cover and scrolling layout but adds:
   - **Sticky day markers** as the reader scrolls
     (*"— MAY 4 · EVENING —"*) in italic Garamond, coral-deep
     `#993C1D`. Markers carry a time-of-day hint (morning, noon,
     evening, night) inferred from item timestamps. They're light,
     not loud — a sense of journey, not a header.
   - **Attribution line** under the cover (*"by Achal · 15 items"*).
   - An **end card** after the last item with *"— THE END —"* in
     italic, plus two soft CTAs: **Reply with a note** (sends a note
     back to the author via the link infrastructure) and **Make your
     own** (deep-links into the app's onboarding for non-users, or
     into the editor for existing users).

6. **Share sheet with a preview thumbnail.** The sheet opens with a
   compact **preview row** at the top: a 48×56 chapbook-style
   thumbnail rendering of what the recipient will actually see, plus
   *"15 items · cream cover with title overlay · ~3 min read."* A
   small "Change" link toggles cover style without leaving the sheet.

   The public-link option, when active, expands inline with a
   passcode toggle instead of opening a separate screen. The v1
   "Share in QuickInk" fifth tile is removed — it's an in-app feed
   feature that depends on the friend graph and ships in v1.1, not
   the MVP.

### What stays exactly as v1

The data model (§6), the auto-grouping engine (§8 — only one cut per
cluster in v3; the multi-cut idea from v2 ships in v1.1), the share
target set (§9), the phased rollout (§10), the engineering breakdown
(§12), and the success metrics (§14). v3 is a polish pass, not a
rewrite of the spec.

---

## 4. User stories

1. *As a traveler*, I want to assemble photos, ticket stubs, and a few
   restaurant receipts from my Tokyo week into a story I can send to my
   parents via WhatsApp.
2. *As a parent*, every month I want the app to suggest "September at
   school" pre-grouped, so I just tap and add a sentence per week.
3. *As a homeowner*, I want a renovation log with my contractor — a
   public link they can bookmark, updated as I add more receipts and
   site photos.
4. *As a cook*, I want to bundle a single Saturday's market receipts,
   prep photos, and recipe notes into one shareable page.

---

## 5. Information architecture

Stories slot in as a peer to Folders / Tags / Smart Collections in the
workspace IA. They reference items by ID; they never *own* the underlying
document or photo, so a story can be deleted without losing scans.

```
Workspace
├── Folders            (where it lives — one home per doc)
├── Tags               (flexible retrieval)
├── Entities           (people, orgs, dates, amounts)
├── Smart Collections  (saved queries — dynamic)
└── Stories            (curated narratives — static order, references)
```

A new top-level tab **Stories** appears in the bottom nav alongside
Scan / Library / Search. (Replaces the rarely-used Archive tab; Archive
moves into the Library overflow.)

---

## 6. Data model

```ts
Story {
  id: ULID
  title: string                       // e.g. "Tokyo, May 2026"
  subtitle?: string                   // optional dek
  coverItemId?: ULID                  // a StoryItem chosen as cover
  coverStyle: "photo" | "wordmark" | "calligraphic"
  themeStyle: "editorial" | "scrapbook" | "minimal"
  groupingMode: "timeline" | "activity" | "custom"
  timeRange?: { start: ISODate, end: ISODate }
  status: "draft" | "published"
  shareMode: "private" | "publicLink" | "inApp" | "exported"
  shareSlug?: string                  // only if publicLink
  createdAt: ISODate
  updatedAt: ISODate
  items: StoryItem[]                  // ordered
}

StoryItem {
  id: ULID
  storyId: ULID
  position: int                       // for stable ordering
  kind: "document" | "photo" | "note" | "textBlock"
        | "divider" | "location"
  refId?: ULID                        // documentId / photoId / noteId
  text?: string                       // for textBlock + caption overrides
  caption?: string
  occurredAt?: ISODate                // override; defaults to source date
  layout: "full" | "half" | "grid"
}

StorySuggestion {                     // ephemeral, regenerated on open
  id: ULID
  kind: "byDate" | "byTimeRange" | "byActivity"
  reason: string                      // human label, e.g.
                                      // "12 photos & 3 receipts · May 4–7 · Tokyo"
  candidateRefs: ItemRef[]
  score: float
}
```

Storage: stories sit in the same local-first store (GRDB on iOS, Room on
Android). Sync to Drive backup as a single JSON manifest per story plus
the existing item blobs.

---

## 7. Screens

The mockup (`design/stories-mockup.html`) renders all five screens
interactively. This is the narrative version.

### 7.1 The Shelf (Stories tab)

The landing surface renders existing stories as **actual book covers**
laid out in rows of two, with a thin shelf bar (dark ink) beneath each
row. Each cover shows the calligraphic Q monogram, the title in
Cormorant Garamond medium, a thin coral rule, a Caveat subtitle, and
issue/page metadata in small italic. Drafts use a warmer paper
(`#F0E4D7` or `#EADFCF`) and a "DRAFT" badge in place of the issue
number.

Above the shelf sits a single **suggestion banner** — not a row of
cards, just one banner per most-promising cluster, with the *three
angles* as small coral pills. Tapping a pill opens that angle's
pre-arranged draft directly; tapping the banner opens the three-angle
chooser (§7.2). The banner appears at most once per session and only
when the suggestion engine has produced a high-confidence cluster.

Coral FAB bottom-right opens the user-curated picker. Bottom nav now
shows Stories as the third tab.

### 7.2 The Three Angles chooser

Replaces the v1 "Suggestion preview." Same cluster, three creative
cuts of the same material, each a tap away from a working draft.

Header is the cluster's reason line in italic Garamond — *"May 4–7,
near Tokyo. 12 photos, 3 receipts, 2 notes."* Below: three angle
cards stacked vertically, each with the angle name (e.g. *"As a food
diary"*), expected page count, a one-line description, and a mini-mosaic
preview. The most diverse angle is marked as the feature (coral border).

Two bottom CTAs: **Start blank** (skip suggestions, go to picker) and
**Open this cut** (creates a draft from whichever angle is highlighted).

### 7.3 Page editor

The core authoring surface, redesigned around the page concept rather
than a card stack.

The viewport is split into three regions, top to bottom:

- **Title strip** — story title in italic Garamond on the left, page
  indicator on the right ("page 3 of 14"). Both editable inline.
- **Page canvas** — a single page rendered as a real chapbook page: a
  thin stitched binding line at the left edge, a leading heading in
  Garamond, the page's photo / scan / note / voice clip content, an
  italic caption underneath, and the page number ("— 3 —") in the
  bottom-right corner. Long-press anywhere on the page enters edit
  mode for that element.
- **Page strip** — a horizontal scrollable navigator at the bottom
  showing every page as a tiny thumbnail with its page number. The
  current page has a coral 1.5px border. The last slot is a dashed
  "+" tile that appends a new blank page. Drag thumbnails to reorder.

Voice clips render inline as inked waveforms on a paper-warm tile with
the clip duration in italic Garamond — tap to play, long-press to
re-record.

The "+" insertion model from v1 is preserved but reframed: it now
*adds a new page* rather than splicing between cards. The new-page
sheet offers: text page, photo from library, scan now, voice memo,
chapter divider, location pin.

Bottom action bar: **Preview** (slides into the reader) and **Share**.

### 7.4 Page-turn reader

What recipients see. A book opened to a single page, swipe horizontally
to turn. A subtle peek of the next page sits at the right edge. The
top of the screen reveals a thin progress strip of dots and a "ToC"
button when tapped.

Each page renders with the chapbook chrome: a small italic chapter
header ("— MAY 5 —"), the page content, an optional drop cap at the
start of text pages, a pull-quote treatment for notes that the user has
flagged as a callout, and an italic page number bottom-right.

The auto-generated **Table of Contents** is itself a chapter — a list
of every chapter divider and its starting page, in classic two-column
ToC layout. Tap any row to jump.

For public-link sharing, the same reader is rendered server-side as a
static page at `share.quickink.app/s/{slug}`. Desktop falls back to a
scrolling mode (the book metaphor doesn't pay off at a 1440-wide
viewport); mobile web keeps page-turn.

### 7.5 Share sheet

Bottom sheet, five options arranged in a 2×2 grid plus a full-width
fifth:

1. **Save as PDF** — chapbook-formatted PDF, one page per page.
2. **Save as image** — a tall portrait PNG (Instagram-story-ish),
   capped at 12 pages; longer stories export a numbered series.
3. **Share via app** — native share sheet (WhatsApp, Messages, Mail).
4. **Public link** — generates a slug, copies to clipboard.
5. **Share in QuickInk** *(v1.1)* — posts to followers' feed.

Below the grid, a dashed coral box shows the current link and a Copy
control if a public link is active.

### 7.6 Add-to-current-story (new)

Not a separate screen — a *contextual menu invocation* available on
every item-rendering surface in the app: Library tiles, document
detail, note detail, search results.

Long-press an item → bottom sheet menu opens with the first row
highlighted in coral:

> **+** &nbsp; Add to *Tokyo, May 2026*

Subsequent rows: *Add to another story…*, *Add a voice note*, *Add
to a tag, folder, or collection*. The current-draft row updates live —
when no draft is in progress, it becomes "Start a story with this."

This is the highest-leverage change in v2: it turns Story from a
destination into an ambient capture mode.

---

## 8. The two auto-grouping modes

Both modes run against the same indexed metadata; they differ in the
clustering heuristic.

### 8.1 Auto by date

Greedy clustering by capture timestamp:

1. Sort all items in the user's library by `capturedAt`.
2. Walk the list; start a new cluster whenever the gap to the previous
   item exceeds **18 hours** *and* the previous cluster has at least
   3 items.
3. Drop clusters with fewer than 4 items (low signal).
4. Score each cluster by `(itemCount × diversity)` where diversity is
   the unique count of `{ photo, document, note }` kinds present.

Cheap, deterministic, works offline. Ships in v1.

### 8.2 Auto by activity

Layered on top of date clustering, this groups by **what** rather than
**when**. Heuristics, in order of preference:

- **Co-location** — items whose EXIF / extracted location falls within a
  ~500m radius of each other.
- **Entity overlap** — shared organization or person entities (e.g.
  multiple receipts from "Blue Bottle Coffee" + photos taken the same
  day).
- **Tag overlap** — same user-applied tag.
- **Folder coherence** — all items in one folder, captured close in time.

Each candidate group is assigned a one-line `reason` string (see §7.2).
Activity mode ships in v1.1 once we've validated the date clustering
quality.

### 8.3 User-curated

Always available via the "+" FAB on the Stories tab. Opens a multi-select
picker over the Library with the same filters Library uses (folder, tag,
entity, date range). On confirm, jumps to the editor.

---

## 9. Sharing — detail per target

| Target | Backend needed | Login required | v1 status |
|---|---|---|---|
| PDF export | No | No | Ships |
| Long image export | No | No | Ships |
| Native share sheet | No | No | Ships |
| Public web link | Yes (small) | Yes (anon ok) | Ships behind flag |
| In-app feed share | Yes (larger) | Yes | v1.1 |

**Public link infra (minimum viable):**

- Endpoint `POST /v1/stories/publish` — receives the Story manifest +
  uploads referenced media to a CDN bucket, returns `{ slug, url }`.
- Static-rendered reader at `share.quickink.app/s/{slug}` (Next.js or
  similar; the page is the same layout as §7.4).
- Slug is 8 chars, unguessable; passcode is optional and stored hashed.
- Author can **unpublish** at any time — manifest + blobs are tombstoned
  within 60 seconds.

Privacy guardrails: the link shares *only* the items the user dragged
into the story. We never expose the source folder, sibling docs, or any
entity metadata that wasn't surfaced on the page. The recipient cannot
walk back to the user's library.

---

## 10. Phased rollout

**Phase 1 — MVP (4 weeks)**

- Stories tab, story editor, story reader.
- Manual creation (user-curated) + auto-by-date.
- PDF export, image export, native share sheet.
- Local-only storage; no public link, no in-app feed.

**Phase 1.5 — Public link (3 weeks)**

- Backend publish endpoint, static reader page, unpublish flow.
- Passcode option.
- Feature flag gated to TestFlight cohort first.

**Phase 2 — Activity grouping + in-app feed (5 weeks)**

- Activity-based suggestions on top of the date clustering.
- Friend graph, follow / accept, in-app feed of friends' stories.
- Push notifications on new shared story (opt-in).

**Phase 3 — Polish & richer formats (open)**

- More themes (currently three; aim for six).
- Co-editing (real-time on the same Story).
- Short video clips inline.
- "Reshare to story" deep links from external apps.

---

## 11. Visual language

Stays inside the existing brand. Reference: `design/BRAND.md`.

- **Canvas** `#FAF7F2` for app surfaces; **Surface** `#FFFFFF` for
  story cards.
- **Coral** `#D97757` only for primary CTAs (Make story, Share, FAB)
  and the active-state nav dot.
- Titles in **Cormorant Garamond** medium 28pt; subtitles in **Caveat**
  18pt; body in system sans 15pt.
- Story cards use the existing paper-warm pastels (`#E8DCC4`,
  `#F0E4D7`, `#EADFCF`) for note slots so they read as ink-on-paper.
- 24×24 line icons match the existing UI icon set (1.7px stroke). Two
  new icons needed: **story** (stacked-pages glyph) and **share-link**
  (chain link). Style notes go to design — same family.

---

## 12. Engineering notes

- iOS: a new `StoriesFeature` Swift package, sibling to existing
  feature packages. SwiftUI screens, GRDB tables `story`, `story_item`,
  `story_suggestion_cache`.
- Android: a new `:feature:stories` Gradle module. Jetpack Compose
  screens, Room entities mirroring iOS.
- Suggestion engine: shared algorithm spec in `shared/algorithms/
  story-suggestions.md`; each platform implements natively in v1 (no
  shared Rust/KMP yet).
- Drag-reorder: SwiftUI `List` with `.onMove` on iOS;
  `reorderable-lazy-column` lib on Android.
- PDF export: `UIGraphicsPDFRenderer` on iOS; Android's native
  `PrintedPdfDocument`.
- Public-link backend: Cloudflare Workers + R2 for media, KV for slug
  index. Stateless render of the reader page from manifest.

---

## 13. Risks & open questions

1. **Auto-grouping quality** — date clustering will misfire (e.g.
   late-night flights). Plan: surface the *reason* line so users know
   what triggered the grouping, and always let them edit before
   publishing. Measure: % of suggestions accepted vs dismissed.
2. **Privacy of shared content** — users may not realize an entity
   (a person's name, an address) is visible on a public page. Plan:
   on first publish, show a one-time review screen that previews
   exactly what's leaving the device.
3. **Performance on long stories** — 100+ item stories. Plan: cap at 50
   items in v1; lazy-load thumbnails in the editor and reader.
4. **Open: should we let users share a *draft*?** A draft has no
   public-link slug; today it just lives on-device. There's a request
   pattern for "send to my own email so I can preview on a laptop" —
   could be solved by treating Save-as-PDF + share-sheet as the
   draft-preview path, no separate flow needed.
5. **Open: how does Stories interact with Smart Collections?** A Smart
   Collection result set could seed a Story ("Make a story from this
   query"). Easy to ship later; mention in editor's import menu.

---

## 14. Success metrics

- **Activation:** % of weekly-active users who create ≥1 story in their
  first 14 days post-launch. Target: 18% by week 6.
- **Completion:** % of started stories that reach `published` (or
  shared). Target: 60%.
- **Share-out:** average shares per published story. Target: 1.4.
- **Suggestion acceptance:** % of suggestion cards that result in a
  draft (tap-through × kept). Target: 25%.
- **Retention proxy:** D30 retention of users who published ≥1 story
  vs cohort baseline. Hypothesis: +8 points.

---

## 15. Companion files

- `design/stories-mockup.html` — landing page comparing v1 and v2 side
  by side, with a diff table of what changed.
- `design/stories-mockup-v1.html` — the v1 memories-style direction,
  preserved for reference. Five screens: Stories tab, suggestion
  preview, card-list editor, scrolling reader, share sheet.
- `design/stories-mockup-v2.html` — the v2 chapbook direction
  (recommended). Five screens: shelf, three-angle chooser, page editor
  with voice clip, page-turn reader, add-from-anywhere.
- `design/BRAND.md` — color, type, voice, icon set.
- `design/WORKSPACE_SPEC.md` — IA this slots into.

---

## 16. Themes — chapbook is the default, journal is the first alt

The chapbook chrome is built into every story. A small number of
**themes** swap the page treatment without changing the data model.

- **Chapbook** *(default)* — cream pages, ink + coral, calligraphic Q
  monogram cover, stitched binding edge, italic page numbers.
  Universal — works for trips, renovations, meeting recaps, school
  months.
- **Field Journal** *(v2.1 alt)* — paper-warm pages with a subtle
  tea-stain wash, photos rendered as slightly-tilted polaroids with
  a thin white border, captions in Caveat, occasional marginalia,
  date stamps in coral. Best for memoir and travel.
- **Minimal** *(v2.1 alt)* — white pages, system sans only, no
  monogram. For users whose stories are functional (invoice logs,
  renovation receipts) and who want them to read as a clean report.

A theme is a per-story property; the user can switch themes any time
without losing structure. The Issue/magazine direction explored in
the v2 design sprint is intentionally *not* on this list — it was
beautiful but its rigid section structure fought against quick
capture, and its drop-caps + pull-quotes pattern only paid off for
text-heavy stories that aren't the median QuickInk use case.
