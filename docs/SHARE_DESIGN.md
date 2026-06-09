# Share — design

How content crosses the Releaf boundary in both directions: **share *into* Releaf**
from another app's system share sheet, and **share *out of* Releaf** to the OS
share sheet. Companion to [`PROMPT.md`](../PROMPT.md),
[`docs/DAILY_CAPTURE_UX.md`](./DAILY_CAPTURE_UX.md),
[`docs/NAV_GRAPH.md`](./NAV_GRAPH.md), and
[`docs/DRIVE_SCHEMA.md`](./DRIVE_SCHEMA.md). This doc resolves the share-sheet
one-liners those specs leave open and pins down the ingest / export loop.

> **One-liner.** Releaf is a polite citizen of the OS share sheet, both ways:
> anything you can share *to* it lands as a capture on today's log (after a quick
> review), and anything in it can be shared *out* as a file or text the OS hands
> off — never as a hosted link.

---

## 0. Why no links

Hard constraint #7 in `PROMPT.md` is non-negotiable: **no sharing links, no
real-time sync, no collaboration.** "Share via app" therefore means exactly one
thing — the **OS-level share sheet** (iOS `UIActivityViewController` /
`ShareLink` / Share Extension; Android `Intent.ACTION_SEND` chooser + receiver).
It never means a `releaf.app/p/abc123` URL, a collaborator invite, or a
server-rendered preview. Every outbound payload is a **file or a block of text**
the OS owns once handed off. Every inbound payload is **copied into the local
SQLite store** and treated like any other capture.

This keeps the whole feature inside the local-first, no-server, single-user
envelope the rest of the app lives in.

---

## 1. Two directions, two mechanisms

| | **Inbound — "Share to Releaf"** | **Outbound — "Share from Releaf"** |
| --- | --- | --- |
| Trigger | User is in Photos / Safari / Files / etc., taps **Share → Releaf** | User taps **Share** on a Page / NotepadEntry / capture / contact |
| iOS mechanism | **Share Extension** target → App Group inbox → deep-link to review | `UIActivityViewController` (file or text) / `ShareLink` |
| Android mechanism | `ACTION_SEND` / `ACTION_SEND_MULTIPLE` `<intent-filter>` on a thin activity → deep-link | `Intent.ACTION_SEND` chooser |
| Status | **Specced, unbuilt** — most of this doc | **Mostly built** — unify + extend |
| Lands on | Capture tab, pre-filled, today's `DailyLog` default | n/a (hands off to OS) |

The two share an idea — the **`ShareIntent` value type** already used outbound —
but otherwise have independent plumbing. Don't try to unify the transport.

---

## 2. Inbound — "Share to Releaf"

The existing specs already pin the destination
([`DAILY_CAPTURE_UX.md`](./DAILY_CAPTURE_UX.md) §2.1 entry-point 4,
[`NAV_GRAPH.md`](./NAV_GRAPH.md) line 310):

> Share Sheet / `ACTION_SEND` → opens the **Capture tab** pre-filled with the
> shared content, landing on **today's `DailyLog`** by default.

This section fills in the *how*.

### 2.1 Commit model — deep-link to review (decided)

When the user picks Releaf from another app's share sheet, the capture is **not**
committed silently. Releaf **deep-links into the Capture tab with the payload
pre-filled** and lets the user confirm (and optionally re-parent) before the row
is written.

**Why review, not silent-commit.** A silent "saved to today ✓" toast is faster,
but share is the one capture path where the user has *already left Releaf's
context* — they're acting on someone else's content (a friend's photo, a web
article) and the "where does this go and what is it" decision carries more
weight than an in-app capture. A one-tap review screen is cheap insurance against
junk piling up on today's log, and it's the only place the re-parent escape hatch
(§2.4) can surface. It also sidesteps the thorniest part of the silent path: an
iOS Share Extension writing to the live database from a separate process.

The review screen is the existing **Capture tab**, opened pre-filled — not a new
destination. It reuses the parent-inference and "Capture into…" affordances
already specced in `DAILY_CAPTURE_UX.md` §2.2.

### 2.2 The process boundary — App Group inbox (iOS)

The crux of the iOS design. An iOS **Share Extension runs in its own process**
and must not open the main app's GRDB database live — concurrent writes from two
processes against one SQLite file is the kind of corruption risk we don't take
for a convenience feature. So the extension does the *minimum* and hands off:

1. **Extension** (`ReleafShareExtension`) receives the `NSExtensionItem`s.
2. It copies each attachment's bytes into a **shared App Group container**
   (`group.app.releaf.mobile`) under `inbox/<uuid>/`, and writes a small
   `pending.json` descriptor next to it:
   ```json
   {
     "id": "<uuidv7>",
     "received_at": "2026-06-09T14:03:22.118Z",
     "items": [
       { "kind": "photo", "filename": "IMG_0421.heic", "uti": "public.heic" },
       { "kind": "text",  "text": "Heron at the pier this morning" }
     ]
   }
   ```
3. Extension calls `extensionContext.open(URL(string: "releaf://share/<uuid>"))`
   (or `completeRequest` + the user re-opens) and finishes. **No DB write.**
4. **Main app**, on handling `releaf://share/<id>` (cold or warm), drains
   `inbox/<id>/` into the Capture tab as pre-filled, *uncommitted* draft items.
   The actual `captures` / `notepad_entries` / `reference_links` rows are written
   only when the user taps **Save** on the review screen.
5. On Save (or explicit discard), the main app deletes `inbox/<id>/`. A
   janitor sweep on launch reaps inbox entries older than 7 days (covers the
   "user shared, never opened the deep link" case).

> **Why an inbox file and not a direct DB write from the extension.** Two
> processes, one SQLite file = corruption surface. The inbox is a durable,
> single-writer hand-off: the extension only ever *creates* `inbox/<id>/`, the
> main app only ever *drains* it. No locking protocol, no shared GRDB handle.

The extension must stay lightweight (tight memory budget on iOS) — it copies
bytes and writes JSON, nothing more. No OCR, no thumbnailing, no Drive calls.

### 2.3 Android — same process, no inbox needed

Android's `ACTION_SEND` is delivered to an activity in the **same process** as
the app, so the App Group dance is unnecessary. A thin
`ShareReceiverActivity` (declared with the `ACTION_SEND` /
`ACTION_SEND_MULTIPLE` `<intent-filter>`) reads the `EXTRA_STREAM` /
`EXTRA_TEXT`, copies any content-URI streams into app-private storage (the URI
permission is transient), then delegates to the same `Dispatch` →
`releaf://share/...`-equivalent in-process route the deep-link table uses. Same
review screen, same Save semantics, no inbox file.

The intent-filter advertises the MIME types Releaf accepts:
`image/*`, `application/pdf`, `audio/*`, `text/plain`.

### 2.4 Content-type routing → CaptureMode

Map the incoming UTType (iOS) / MIME (Android) onto the existing capture model.
Releaf's `captures.kind` CHECK is `('photo','voice','scan')`
(`shared/design-system/migrations/v1_initial.sql`), with URLs and text going to
their own tables:

| Shared type | UTType / MIME | Lands as |
| --- | --- | --- |
| Photo / image | `public.image`, `image/*` | `captures` `kind='photo'` |
| Scanned doc / PDF | `com.adobe.pdf`, `application/pdf` | `captures` `kind='scan'` (+ on-disk pdf) |
| Audio clip | `public.audio`, `audio/*` | `captures` `kind='voice'` (+ on-disk m4a) |
| URL / web page | `public.url`, a URL in `text/plain` | `reference_links` (reuse the §2.5 clipboard-URL parse) |
| Plain text / selection | `public.plain-text`, `text/plain` | appended to today's `notepad_entries.notes` |
| Multiple items | `ACTION_SEND_MULTIPLE` | batched into the **same** inferred parent |

Anything Releaf doesn't recognize is dropped from the draft with a quiet "Can't
add that file type" note on the review screen rather than failing the whole share.

### 2.5 Parent inference — identical to in-app capture

No new rules. The shared draft obeys `DAILY_CAPTURE_UX.md` §5 / `NAV_GRAPH.md`
§"Capture flows" exactly:

- Default parent = **today's `DailyLog`** (`findOrCreate(today)`; the blank
  NotepadEntry shell already guarantees a uniform parent).
- The review screen's **"Capture into…"** long-press escape hatch lets the user
  send it to a specific notebook page instead, or defer with "Move later."
- Because share always enters from *outside* an entity context, there's no
  "current page" to inherit — it's always today unless the user re-parents. (A
  future "share to *this* notebook" path is out of scope; see §5.)

### 2.6 `drive.file` and sync

Nothing special. Inbound share writes to **local SQLite first** (constraint #4),
and the existing opportunistic `SyncRepository` pass picks the new rows up like
any other capture. Two honest caveats inherited from current gaps:

- **Media blobs don't sync yet** (`HANDOFF.md` "Out of scope"). A shared photo /
  pdf / m4a lives on-device until media-blob sync lands; its text/metadata row
  participates in sync, the bytes don't.
- **`drive.file` scope is irrelevant to ingest** — we're writing locally, not
  reading the user's Drive. No scope change.

---

## 3. Outbound — "Share from Releaf"

The plumbing largely exists; the work is **coverage and consistency**, not new
transport.

### 3.1 What's already there

- **iOS Page** — `PageDetailViewModel.presentShareSheet()` builds a `ShareIntent`
  (title + body, or a PDF file URL from `exportPDF()`); `PageDetailView` presents
  it via a `UIActivityViewController` wrapper (`ShareSheetView`).
- **iOS Voice** — the voice card in `EditorSections.swift` wraps `ShareLink` over
  the m4a file URL (Save to Files / AirDrop / Messages).
- **iOS Contacts** and **QuickInk `StoryShareSheet`** — additional one-off share
  surfaces.
- **Android Page** — `PageDetailScreen` fires `ACTION_SEND` with either a
  `fileUri` + mime (PDF path, with `FLAG_GRANT_READ_URI_PERMISSION`) or
  `text/plain` (title + body) via `Intent.createChooser`.

### 3.2 The gap — one share menu, everywhere

Today share is bolted onto Page, voice, and contacts ad hoc. The design target
(from `QUICKINK_PROPOSAL.md`'s export set: PDF · plain text · Markdown · image ·
share sheet) is a **single, consistent share/export menu** available on every
shareable surface:

- **Surfaces:** Page, NotepadEntry, a whole **DailyLog** (the day's content as one
  document), individual captures (photo/scan image, voice m4a), and contacts.
- **Menu entries:**
  - **Share…** → OS share sheet with the default payload (see §3.3).
  - **Export PDF** → rendered PDF → share sheet (Page / NotepadEntry / DailyLog).
  - **Copy as Markdown** → canonical CommonMark (`OPEN_QUESTIONS.md` §"Markdown
    round-trip") to the clipboard.
  - **Copy plain text** → Markdown stripped to text.
- **One value type.** Everything funnels through the existing **`ShareIntent`**
  (`title`, `body`, optional `fileURL`/`fileUri` + mime). Android keeps the
  `LaunchedEffect(pendingShare)` consume-once pattern; iOS keeps the
  `.sheet(item:)` binding. Lift these into a shared component so a new surface
  gets share for free instead of re-implementing the wrapper.

### 3.3 Default payload — rendered, not raw

- **PDF default is the *rendered* page PDF**, not the OCR searchable-text PDF.
  Per `QUICKINK_DESIGN.md` §"exported PDF": the rendered PDF is clean (no
  text-layer artifacts); the OCR layer is for in-app search and the
  feature-flagged searchable-PDF prototype only.
- **Text share** sends canonical-form Markdown (the stored form), so what the
  user pastes round-trips losslessly.
- **A capture shares its underlying file** (the photo, the scan jpg/pdf, the m4a)
  — the bytes, not a description.

### 3.4 Exports stay local by default

Inherits `OPEN_QUESTIONS.md` §9: generated PDFs are **derivatives**, written to
`releaf/exports/pdf/` and **local-only by default** — the source Markdown +
captures already round-trip through the manifest, so a PDF can always be
regenerated. **Settings → Export → "Back up PDF exports to Drive"**
(`releaf://settings/export`) is the opt-in for archival; default **OFF**. Share
hands the local file to the OS sheet regardless of this toggle.

---

## 4. Deep-link + routing additions

One new route, wired through the existing `Dispatch` (`NAV_GRAPH.md`
§"Deep-link dispatch"):

| URL | Lands on | Back returns to |
| --- | --- | --- |
| `releaf://share/:inboxId` | Capture tab, pre-filled from `inbox/<id>/`, **uncommitted** | Previously-active tab |

Dispatch notes, consistent with the existing table:

- **Signed-out state.** If a share arrives while signed out, stash
  `releaf://share/<id>` and route through `SignIn`; resume on success. The inbox
  file is durable, so the draft survives the detour. (Sign-in is for Drive auth
  only — the local capture works offline either way.)
- **Missing / reaped inbox.** If `<id>` was already drained or janitor-swept,
  fall back to the Capture tab empty with a one-shot "That share expired" toast —
  same hard-error-to-Home discipline the table already uses for tombstoned deep
  links.
- **iOS** registers the custom scheme only (no Universal Links in v2, per
  `NAV_GRAPH.md`); the Share Extension `open()`s `releaf://share/<id>`.
- **Android** adds the `ACTION_SEND` intent-filter to the existing single
  `LAUNCHER` activity that delegates to `Dispatch` before the NavHost.

The `NAV_GRAPH.md` line 310 row ("Share-sheet to Releaf → QuickCaptureSheet")
should be updated to point at this route and the Capture tab (the sheet became a
tab in `CAPTURE_TAB_PLAN.md`).

---

## 5. Decisions pinned down

1. **Share means the OS share sheet, never a link.** Enforces constraint #7.
   Outbound = file/text handoff; inbound = local copy.
2. **Inbound commits via a review screen, not silently.** The Capture tab opens
   pre-filled and uncommitted; Save writes the rows. (§2.1)
3. **iOS uses a Share Extension + App Group inbox; Android uses an in-process
   `ACTION_SEND` receiver.** No extension writes the live DB. (§2.2–2.3)
4. **Default parent is today's `DailyLog`**, with the existing "Capture into…"
   re-parent escape hatch — no new inference rules. (§2.5)
5. **One `ShareIntent`-based share/export menu on every surface**, replacing the
   current per-screen bolt-ons. (§3.2)
6. **Default PDF is the rendered PDF; exports are local-only by default.**
   (§3.3–3.4, inherits `QUICKINK_DESIGN.md` + `OPEN_QUESTIONS.md` §9)

---

## 6. Out of scope / deferred

- **Silent-commit share** (no review) — revisit only if review friction shows up
  in usage; would still need the inbox hand-off, just an auto-Save on drain.
- **"Share to *this* notebook" targeted extensions** (iOS multiple extension
  actions / Android `ShortcutInfo` share targets) — v2; today everything lands on
  today's log and re-parents from there.
- **Media-blob sync of shared files** — blocked on the `captures` blob-sync gap
  in `HANDOFF.md`, not on this design.
- **Receiving rich/HTML web content** — v1 takes the URL (→ `reference_links`)
  and plain text; no readability extraction or article snapshotting.
- **Outbound multi-entity export** (a whole notebook → one PDF) — Page /
  NotepadEntry / DailyLog granularity only for v1.
- **Universal Links / App Links** — custom scheme only, per `NAV_GRAPH.md`.

---

## 7. Acceptance criteria

Share is "shipped" when:

- From Photos, **Share → Releaf** on a photo opens Releaf's Capture tab with the
  image pre-filled and uncommitted; **Save** writes one `captures` `kind='photo'`
  row parented to today, visible on the day's log.
- Sharing a **URL** from Safari lands a `reference_links` row; sharing **selected
  text** appends to today's NotepadEntry.
- Sharing **multiple photos** at once batches them under one parent.
- An iOS share performed while Releaf is **force-quit** survives: the inbox file
  is drained on next cold launch via the stashed deep link; nothing is lost.
- The Share Extension never opens the app database (verified by inspection: it
  only writes `inbox/<id>/`).
- **Share** from a Page produces a rendered PDF (or text) in the OS sheet;
  **Copy as Markdown** puts canonical CommonMark on the clipboard.
- The same share/export menu is present on Page, NotepadEntry, and DailyLog with
  identical entries.
- Every inbound path works in **airplane mode** (local write; sync later).
- A shared item whose inbox entry was reaped routes to an empty Capture tab with
  the "expired" toast, never a crash.

---

## 8. Build-order implications

Additive to `DAILY_CAPTURE_UX.md` §9. Suggested sequencing:

| Step | What | Depends on |
| --- | --- | --- |
| A | Lift outbound share into a shared `ShareIntent` menu component (both platforms) | existing Page/voice share |
| B | Extend the menu to NotepadEntry + DailyLog + Copy-as-Markdown | A, Markdown canonical form |
| C | `releaf://share/:id` route + `Dispatch` handling + janitor sweep | `NAV_GRAPH.md` Dispatch |
| D | iOS Share Extension target + App Group + inbox writer | C |
| E | Android `ACTION_SEND` receiver + intent-filter | C |
| F | Capture-tab review screen drain + content-type routing + Save | C, D, E |

A–B can land independently of inbound (they only touch already-built outbound
surfaces); C–F are the new ingest path and should land together behind the
review screen.
