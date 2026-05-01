# Daily Capture UX

How a Releaf user captures, stores, and retrieves daily activity. Companion to [`PROMPT.md`](../PROMPT.md), [`docs/ARCHITECTURE.md`](./ARCHITECTURE.md), [`docs/DRIVE_SCHEMA.md`](./DRIVE_SCHEMA.md), and [`docs/NAV_GRAPH.md`](./NAV_GRAPH.md). This doc resolves four UX questions those specs leave open and pins down the capture / review loop the rest of the app is built around.

> **Visual reference.** Open [`design-system/daily-capture-mocks.html`](../design-system/daily-capture-mocks.html) (Home Today-hero · Daily Log · QuickCaptureSheet), [`design-system/daily-capture-mocks-2.html`](../design-system/daily-capture-mocks-2.html) (Empty Today · Voice recording active · Calendar month · Search results), and [`design-system/daily-capture-mocks-3.html`](../design-system/daily-capture-mocks-3.html) (Photo review · Scan + OCR overlay · Capture detail · Conflict resolver) in a browser. All three sets use the cream + dot-grid + coral palette from `design-tokens.json` and aren't a substitute for the spec — they show what the spec *looks like*.

> **One-liner.** Open the app, today is already there, the keyboard or mic is already up, the first keystroke commits. Find anything later by date, by tag, or by full-text search.

---

## 1. Three surfaces, three roles

The schema already encodes three places a user's content can live. Each has a distinct job — keeping them distinct is what makes the daily flow fast.

| Surface           | Role                                    | Lifetime              | Cardinality           |
| ----------------- | --------------------------------------- | --------------------- | --------------------- |
| **`DailyLog`**    | Calendar index for one date             | Permanent (per date)  | 1 per user per day    |
| **`NotepadEntry`**| The day's actual scrapbook content      | Days to weeks         | 0..1 per DailyLog     |
| **`Notebook → Chapter → Page`** | Long-lived structured content | Months to years       | Many per user         |

Rule of thumb:

- **Anything you'd write on a sticky note today** → NotepadEntry attached to today's DailyLog.
- **Anything you'd file in a project binder** → Page in a Notebook.
- **DailyLog itself holds nothing** — it's the index that lets the calendar, search, and "today" hero work.

This split is already in `PROMPT.md`. This doc just makes it the load-bearing UX assumption: the daily flow never asks the user "where should this go?"

---

## 2. The capture loop — sub-3-second target

### 2.1 Entry points (in priority order)

1. **App-icon long-press → Today.** The static home-screen quick action wires `releaf://today`. iOS via `UIApplicationShortcutItem`; Android via the static `<shortcut>` XML manifest. This is the **primary** launcher action — beats a normal app open by one tap.
2. **Cold launch.** Splash → AuthCheck → Home. Home's hero **is** today's log (see §3), so cold launch lands you one screen away from capture.
3. **Floating Leaf button.** Visible on every top-level screen. Tap → **Capture tab** (a real top-level destination per `CAPTURE_TAB_PLAN.md`; was a `ModalBottomSheet` pre-Phase-2). The lifted FAB shows a coral ring when Capture is the active tab. **Long-press → voice recording starts immediately** without leaving the current tab (see §2.4).
4. **Share extension.** iOS Share Sheet / Android `ACTION_SEND` → opens the **Capture tab** pre-filled with the shared content. Lands on today's DailyLog by default.
5. **Deep link.** `releaf://today`, `releaf://capture?kind=photo`, `releaf://search?q=…`.

### 2.2 Capture tab — six tiles, no picker

Sheet contents:

```
┌────────────────────────────────┐
│  [Text]   [Photo]   [Voice]    │
│  [Scan]   [Link]               │
└────────────────────────────────┘
```

Each tile commits without asking *where* the capture goes. Parent inference (already specced in `NAV_GRAPH.md` §"Capture flows"):

- If the sheet was opened from a **Page** or **NotepadEntry** screen → parent = that entity.
- Otherwise → parent = today's `DailyLog` (auto-created via `findOrCreate`).

**Picker as escape hatch, not default.** Long-pressing a tile opens "Capture into…" with three options: Today's log · Specific notebook page · Move later. Default behavior never shows this.

### 2.3 Text capture — keyboard up, first save on commit

Tapping `[Text]`:

1. Open a sheet that takes ~85% of the screen.
2. **Bring the keyboard up immediately** — `becomeFirstResponder()` on iOS, `requestFocus()` on Android, both during the present animation.
3. No title field. No project picker. No tag chips. The body is the only thing on screen besides a small "Save" pill in the corner.
4. Markdown autocomplete is on (already part of the editor work) — `- ` for lists, `# ` for heading, `[ ] ` for todo items.
5. Save commits to the parent (inferred above) and dismisses the sheet. The next time the sheet opens (within 60 seconds) it reopens to the same buffer for a quick continuation.

Editor → SQLite is one INSERT into `notepad_entries.notes` (or append to existing) inside one transaction. `dirty=1`, sync is opportunistic.

### 2.4 Voice — hold-to-record, transcribe-on-release

The Leaf button has two gestures:

- **Tap** — navigate to the Capture tab.
- **Long-press (≥ 250 ms)** — start recording. Visual: button fills with coral pulse, time elapsed shown above button. Release → recording stops, transcription begins, capture is committed with `kind='voice'` to the inferred parent. No review screen on the happy path; the capture detail is reachable from the new entry's tile.

For longer-form review, the `[Voice]` tile on the Capture tab routes through the standard record-then-review flow with chunked transcription per OPEN_QUESTIONS §7.

This makes voice the fastest non-text capture: from any screen, hold the button, talk, release, done. Two thumb actions.

### 2.5 Photo / Scan / Link

Standard system flows; no novel UX. Each lands a `captures` row with the right `kind`, parent inferred as above. `[Link]` parses the system clipboard for a URL on tile open and pre-fills if found, so paste isn't a separate step.

---

## 3. Today as the Home hero

`NAV_GRAPH.md` lists `daily_log/{date}` as a drill-in under Home. **This doc upgrades it to Home's primary content.**

### 3.1 Layout

Home is two visual zones:

```
┌────────────────────────────────────────┐
│  ◷ Tuesday, April 28                   │  ← header strip
│  Apr 26  27  ●28  29  30  May 1        │  ← horizontal day strip, today centered
├────────────────────────────────────────┤
│                                        │
│  ❝ Today's notes                       │
│  Crisp morning. Heron at the pier...   │  ← inline NotepadEntry editor
│  - [ ] Refill bird feeder              │
│  - [x] Stretch                         │
│                                        │
│  📷 3 photos · 🎙 1 voice · 📄 0 scans │  ← capture strip, taps to mode
│                                        │
│  ▦ Saved this week                     │
│   • Sabbatical 2026 → Week 4           │  ← recent notebooks/pages
│   • Notepad → Apr 27, Apr 26           │
└────────────────────────────────────────┘
                                          ◉  ← floating Leaf (capture FAB)
```

### 3.2 Rationale

The current spec leaves Home as a "dashboard" — stats, recent activity, conflict badge, and a "Today →" card you tap to drill in. That's fine if the user opens the app to *review*, but the daily-capture user opens it to *write*. One extra tap to get there is one extra tap of friction we don't need.

Putting the NotepadEntry editor inline on Home means:

- First keystroke after cold launch = first character of today's content. No drill-in.
- Side-swipe on the day strip jumps between days without leaving Home — back stack stays at depth 1.
- Stats / shelves / activity drop into a "below the fold" zone that the user scrolls into, not the loading state.

### 3.3 Alternative — segmented `Dashboard | Today` toggle

If the team isn't ready to commit to Today-as-Home, the cheap fallback is a segmented control in the Home header that swaps the body without changing the route. Both states own the BottomNav and the FAB. This keeps the existing route structure intact and lets us A/B which framing wins. `NAV_GRAPH.md` §"Open routing questions deferred" already flags this as a revisable choice.

### 3.4 What about `daily_log/{date}` as a route?

Still exists. Used for:

- Date-jump from the day strip when going more than a few days back.
- Calendar tap (see §4.1).
- Deep links: `releaf://today/2026-04-15`.

It's a drill-in under Home, replacing on prev/next so the back stack doesn't grow. Today specifically renders inside Home; *other* dates render inside `daily_log/{date}`.

---

## 4. Retrieval — three handles, in priority order

Capture is one half. Finding what you captured two weeks later is the other half. Three retrieval surfaces, ordered by how often a user reaches for them:

### 4.1 Calendar (most frequent)

A month-grid view. Each day is a small cell with **density indicators** — a dot per capture kind present that day, color-coded:

- ◐ note (cream)
- ● photo (coral)
- ▲ voice (info blue)
- ▢ scan (warning amber)

Tap a day → `daily_log/{date}`. Long-press → quick-preview popover with the entry's first 3 lines + capture thumbnails.

The repo already has scaffolding under `features/notepad/NotepadCalendarBloom.kt` and `features/calendar/CalendarScreen.kt` — this is finishing those, not starting new.

Entry points: a calendar icon in the Home header, and the day strip's "go to date" tap.

### 4.2 Search

The FTS5 plumbing is fully specced (`fts_notepad_notes`, `fts_capture_text`, `fts_page_body`, `fts_task_title`). UX:

- **Search icon in header on Home and Notepad.** Tap → full-screen search.
- **Type-as-you-go**, debounced 150 ms. Results group by kind with per-kind counts.
- **Filters as chips below the input:** date range, kind, tag, project. Combinable with AND.
- **Voice transcription is searchable** — voice captures appear in results scored on `extracted_text`.
- **Tap result → detail screen with parent back stack rebuilt** so the back button returns to the list. (Standard behavior per `NAV_GRAPH.md`.)

### 4.3 Tags + Projects

Cross-cutting. Two surfaces:

- **Tag chips at the bottom of the daily review screen** — applies the tag to the whole day's entry in one tap. Useful for triage ("tag this whole day as 'travel'").
- **Tag list** under Settings → Manage tags. Each tag opens a list of entities with that tag, scoped by date range.

Projects work the same way but appear as colored bands on the day strip and calendar grid for quick visual scanning.

---

## 5. Storage map — what hits which table

This is mostly already specced; making it explicit so the daily-capture flow's reads/writes are unambiguous.

| User intent                              | Table(s) written                                              | Parent FK                                          |
| ---------------------------------------- | ------------------------------------------------------------- | -------------------------------------------------- |
| "Type a thought today"                   | `notepad_entries.notes` (canonical CommonMark)                | `daily_logs.notepad_entry_id` linked back          |
| "Add a todo to today"                    | `todo_lists` + `todo_items`                                   | `notepad_entry_id` (the day's entry)               |
| "Drop a photo into today"                | `captures` (`kind='photo'`)                                   | `parent_notepad_entry_id` if entry exists, else `parent_daily_log_id` |
| "Hold-to-record voice"                   | `captures` (`kind='voice'`) + on-disk m4a                     | same parent inference                              |
| "Scan a receipt"                         | `captures` (`kind='scan'`) + on-disk jpg + optional pdf       | same                                               |
| "Save a link"                            | `reference_links`                                             | `parent_notepad_entry_id`                          |
| "Remind me at 3pm"                       | `tasks` + `reminders`                                         | `tasks.due_at` set; surfaced on day via query, not FK |
| "Tag this day 'travel'"                  | `page_tags` join row on the entry's id                        | —                                                  |
| "Move this entry into Notebook"          | `pages` insert + capture re-parent (move-to-notebook flow)    | spec'd in PROMPT.md                                |

### 5.1 `findOrCreate(today)` semantics

When Home opens (or `releaf://today`):

1. Look up `daily_logs WHERE user_id=? AND entry_date = CURRENT_DATE`.
2. If absent → INSERT a `daily_logs` row.
3. Look up its `notepad_entry_id`.
4. If null → INSERT a `notepad_entries` row with `allow_blank_content = true`, blank notes, and SET `daily_logs.notepad_entry_id = <new>`.
5. Render the editor against that entry.

Single transaction. The blank-shell creation is what lets the first keystroke of the day commit immediately to a real row, with no "creating entry…" intermediary state.

### 5.2 Why a blank shell, not lazy-create on first save

Two reasons:

- **Captures need a parent at the moment they're committed.** If today's entry doesn't exist yet, the parent has to fall back to the DailyLog itself. That works, but produces a split between "today's text" (NotepadEntry) and "today's media" (DailyLog-parented Captures) that the move-to-notebook flow has to reconcile later. Always-create-shell keeps the parent uniform.
- **`updated_at` is meaningful.** A row that's been "touched" but holds no content still represents the user opening their day. Some downstream views (recent activity, conflict aggregation) treat presence-of-row as signal. Lazy-create loses that.

The cost is a row per day per user even on days with no content. At ~365/year that's negligible — `notepad_entries` will stay well under any meaningful index size.

---

## 6. Decisions pinned down

This doc fixes four things the surrounding specs left ambiguous.

### 6.1 Default `launcher_continue_scope` = `today`

`PROMPT.md` lists three values for the Quick Capture continuation behavior: `append_to_latest`, `today`, `new_shell`. The default should be **`today`**.

**Why.** Most users think calendrically — when they open the app at 11pm and capture something, they expect it to attach to today, not to "the entry I last opened" (which might be 3 days old). `append_to_latest` is genuinely useful for power users with running multi-day projects, but it's surprising as a default.

`new_shell` is for users who want every capture to start a new entry (flashcard / spaced-repetition style). Niche.

`today` matches the mental model of every other daily journal app on the market.

### 6.2 Today opens with a blank NotepadEntry shell, not an empty day

Already justified in §5.1–5.2. Concretely: the shell carries `allow_blank_content = true` so it satisfies the `notepad_entries` content-required CHECK without holding any text.

### 6.3 `releaf://today` is the primary launcher shortcut

Both home-screen quick actions on iOS (`UIApplicationShortcutItem`) and Android (static shortcut XML) ship two actions:

1. **Today** → `releaf://today` (default / first slot).
2. **Quick capture** → `releaf://capture` (opens the Capture tab; was a modal sheet pre-`CAPTURE_TAB_PLAN.md`).

Today as the *first* shortcut means a long-press on the app icon is one tap shorter than a normal app open for the most common user goal.

### 6.4 Long-press on Leaf button = voice capture

Detailed in §2.4. The Leaf FAB carries two gestures because voice is the highest-leverage capture type for daily logging — "I had a thought walking to the bus" — and switching to the Capture tab and tapping a tile is too long a round-trip.

Implementation: on iOS, `LongPressGesture(minimumDuration: 0.25)` composed with `TapGesture` via `.simultaneousGesture`. On Android, `pointerInput { detectTapGestures(onLongPress = ..., onTap = ...) }` with the same threshold.

Edge case: if voice permission isn't granted, long-press shows the permission prompt and falls back to tap-to-open-sheet on dismiss.

---

## 7. What this doc does **not** decide

Out of scope, deferred to the build steps that own them:

- **Visual design of the day strip** — left to the design system pass; this doc just claims its existence and behavior.
- **Calendar density-dot color mapping** — sketched in §4.1 but final colors come from the dark-mode token pipeline (per OPEN_QUESTIONS "not asked but worth flagging").
- **Tablet / landscape layout** — phone portrait baseline only. Tablet is a stretch goal in `PROMPT.md`.
- **Widget content** — PROMPT.md flags Siri Shortcuts and home-screen widgets as v2 stretch.
- **Empty state copy** — content design pass post-token-pipeline.

---

## 8. Acceptance criteria

This UX is "shipped" when:

- Cold launch → Home → first keystroke into the day's content takes **≤ 1.5 s on a Pixel 6a / iPhone 13 mini** (90th percentile).
- Long-press Leaf button → recording starts within **300 ms** of touch-down.
- App-icon long-press → "Today" → first keystroke takes **≤ 2.5 s** (one tap saved vs. cold launch).
- Calendar tap on a day with content opens that day's log with capture counts visible **before the keyboard slides in**.
- Search returns results **≤ 100 ms** for a corpus of 1000 entries (FTS5 budget; tested in build step 1).
- Every screen on this path renders in airplane mode without spinners.
- Every screen has a working preview backed by an in-memory repository — no network, no Drive, no real SQLite file.

---

## 9. Build-order implications

Reordering of `PROMPT.md` build steps to support this UX:

| Step | What                                                           | Notes                                          |
| ---- | -------------------------------------------------------------- | ---------------------------------------------- |
| 1    | SQLite schema + repository layer + FTS5                        | Unchanged — the bedrock                        |
| 2    | Token pipeline + dark mode                                     | Unchanged                                      |
| 3    | **Home Today-hero + day strip + NotepadEntry inline editor**   | Reordered up — this is the primary daily flow  |
| 4    | Notepad list + entry editor (full-screen drill-in)             | Reordered down                                 |
| 5    | Photo + voice + scan attachments                               | Voice gets the hold-to-record gesture wired here |
| 6    | Daily Logs `findOrCreate` + association queries                | Unchanged                                      |
| 7    | Tasks + reminders                                              | Unchanged                                      |
| 8    | OCR + capture revisions                                        | Unchanged                                      |
| 9    | Calendar density dots + search header affordance               | New as an explicit step                        |
| 10   | Merge + move-to-notebook + PDF export                          | Unchanged                                      |
| 11   | Drive sync + restore                                           | Unchanged                                      |
| 12   | Settings polish + accessibility pass                           | Unchanged                                      |

Steps 3, 4, 9 are where this doc most affects the existing plan. Everything else either stays put or is purely additive.
