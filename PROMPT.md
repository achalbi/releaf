# Releaf mobile — build prompt

Copy-pasteable prompt for Claude Code (or any agent) to continue building the Releaf mobile app. Self-contained; can be dropped into a fresh session along with this repo.

---

## Mission

Build **Releaf**: a native iOS + Android mobile-native notebook, capture, and journaling app. It is the spiritual successor to Rails-backed **Inkcreate**, rebuilt to run entirely on-device with Google Drive as the only cloud touchpoint. No custom backend, no web server, no application database in the cloud — **all user data lives in on-device SQLite + file storage**, with an optional user-owned Google Drive folder as backup + restore target.

The product the user captures inside: daily journaling + capture workflows organized three ways — (1) **Daily Logs** that index a calendar date, (2) free-form **Notepad Entries** that act as the day's scrapbook page, and (3) structured **Notebook → Chapter → Page** hierarchies for long-lived content. Each surface holds notes, photos, voice notes, todos, scanned documents, contacts, and locations.

## Hard constraints (non-negotiable)

1. **Native only.** SwiftUI on iOS, Jetpack Compose on Android. No Flutter, React Native, Expo, Capacitor, or KMP UI. The app is already scaffolded in SwiftUI + Compose — do not migrate.
2. **No Releaf server.** No Rails, no Node, no serverless. The only remote system Releaf talks to is Google (Sign-In + Drive REST v3).
3. **No user accounts on our servers.** Google Sign-In is used only for Drive API authorization. No email/password auth, no magic links, no SMS.
4. **Local-first, offline-first.** Every action works without network; sync is a background activity, never blocking. The SQLite store is the source of truth.
5. **`drive.file` OAuth scope.** Releaf can only read files it created. It must never list, read, or touch any other content in the user's Drive.
6. **No hosted AI inference.** AI features either run on-device (Vision / ML Kit / Apple Intelligence / Gemini Nano) or are gated behind a **user-supplied API key** (BYO Gemini or Claude). Default off.
7. **No multi-user collaboration.** No sharing links, no real-time sync, no CRDTs. Single-user, single-device (multi-device restore via Drive is OK).
8. **No web push / VAPID.** Reminders use OS-level local notifications (`UNUserNotificationCenter` + `NotificationManager` + `AlarmManager`).
9. **MVVM.** One ViewModel per screen. Views bind to state, ViewModels drive repositories, repositories wrap storage + network.
10. **Every ViewModel previewable without a backend, a live Drive, or a real SQLite file.** In-memory repository fakes power all previews + tests.

**Targets:** iOS 17+, Android minSdk 26 / targetSdk 35. Phone portrait is the baseline; tablet layouts are a stretch goal.

## What's already built (don't redo — extend)

- **Project skeletons** on both platforms — Gradle + Compose BOM 2024.09.03 on Android, Xcode + SwiftPM on iOS. Structure: `auth/`, `data/`, `features/<screen>/`, `ui/` (design system).
- **Design system.** Warm editorial aesthetic: cream canvas (`#F5EEE3`) with dot-grid texture, coral accent (`#E77850`), warm-neutral 50→950 ramp. Tokens live in `AppColors`, `AppSpacing` (s1=4 → s12=96), `AppRadius` (sm/md/lg/nav), `AppTypography` / `AppText`. Canvas rule: **never repaint `AppColors.canvas` on a screen root** — it covers the dot-grid texture; only the root `ReleafCanvas` / `DotGridBackground` paints it. Dark mode not yet wired — add it as part of the token pipeline work below.
- **Shared components, ported 1:1 from Inkcreate web.** `StatGrid`, `NotebookRow`, `PagePreviewRow`, `CaptureTabBar`, `CaptureFAB`, `QuickCaptureSheet`, `BottomNav` (floating editorial card — see `HANDOFF.md` for current spacing values; don't replace with Material `NavigationBar` / iOS `TabView`).
- **Feature screens (UI shells with fixture data).** Home, Notebook list, Notebook detail, Page detail, Notepad, Settings, Login.
- **Auth skeleton.** `GoogleAuthClient` protocol on both platforms with in-memory stub. Real Google Sign-In SDK wiring is a known follow-up.
- **Architecture docs.** [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) and [`docs/DRIVE_SCHEMA.md`](docs/DRIVE_SCHEMA.md) — **read these first.**

## Domain model

```
User (signed in with Google)
 ├── DailyLog          one per calendar date; find-or-create on open
 │    ├── ↔ NotepadEntry (0..1)   the day's scrapbook page
 │    ├── ↔ Capture     (0..N)   standalone captures associated to the day
 │    └── ↔ Task        (0..N)   standalone tasks due that day
 │
 ├── Notebook          long-lived topic / project / year
 │    └── Chapter
 │         └── Page    structured page within a chapter
 │              ├── notes (rich HTML)
 │              ├── ↔ Capture (0..N)  — photos / voice / scans live here
 │              ├── todo list (inline, enabled / hide_completed / positioned items)
 │              ├── contacts / locations (JSON columns)
 │              ├── reference_links
 │              └── tags (M:N)
 │
 ├── NotepadEntry      ad-hoc daily surface; can be promoted to a Page
 │    └── (same child shape as Page)
 │
 ├── Capture           ★ unified table ★ — one row type for all media captures
 │    ├── kind          photo | voice | scan   (+ future: note | video)
 │    ├── parent        exactly one of {page_id, notepad_entry_id, daily_log_id}
 │    │                 (standalone captures parent to a daily_log; page/entry
 │    │                  captures parent directly)
 │    ├── media_path    on-disk blob (photo jpg / voice m4a / scan jpg + pdf)
 │    ├── extracted_text (FTS5 indexed — populated for scan kind via OCR;
 │    │                   also available for voice kind via transcription)
 │    ├── CaptureRevision (last N versions of extracted_text — undo history)
 │    └── tags (M:N via capture_tags), project (optional FK)
 │
 ├── Task              standalone task with hierarchical subtasks
 │    ├── TaskSubtask
 │    └── Reminder (0..N)
 │
 ├── Project           cross-cutting label for captures + pages
 └── Tag               user-defined, M:N to captures and pages
```

**Why one `captures` table.** Photos, voice notes, and scans all share the same structural needs: a media blob on disk, optional extracted text, revisions, tags, a project FK, a polymorphic parent, and the same sync/dirty/deleted_at fields. Three near-identical tables fork the sync worker and the UI for no gain. One table with a `kind` discriminator keeps queries simple: "give me all voice captures for this page" is `WHERE parent_page_id = ? AND kind = 'voice'`. Kind-specific metadata (voice duration, photo dimensions, scan confidence, scan pdf_path) lives in nullable columns — see DDL section.

**Capture modes** within a Page or NotepadEntry — seven tabs in the existing `CaptureTabBar`:
`OVERVIEW · PHOTOS · VOICE · TODO · SCANS · CONTACTS · LOCATION`. `OVERVIEW` is read-only and shows counts across the other six. The `PHOTOS`, `VOICE`, and `SCANS` tabs all query the unified `captures` table, scoped by `kind`. `TODO` hits `todo_lists` + `todo_items`. `CONTACTS` and `LOCATION` read JSON columns on the parent page/entry.

Every row gets:
- **UUIDv7** primary key (time-sortable).
- `created_at`, `updated_at` (ISO-8601 UTC).
- `dirty` flag for sync tracking (set on every mutation, cleared when the sync worker confirms upload).
- `deleted_at` for soft delete (so deletions propagate to Drive).

## Feature set

### Core organization

- **Notebook → Chapter → Page** hierarchy with reusable **page templates** (e.g. "Morning pages", "Meeting notes", "Weekly review" — user-definable, stored as seed `page.json` payloads).
- **Daily Logs** — one row per user per calendar date. Opening a date with `findOrCreate` semantics. Acts as the day's index; associations to captures and tasks are **nullified, not cascaded** on delete (so deleting a Daily Log never orphans a capture or task).
- **Notepad Entries** — the day's scrapbook page. One entry can hold rich HTML notes, multiple photos, voice notes, a todo list, scanned documents, contacts, and locations. At least one content element is required unless `allow_blank_content` is set (for explicit "empty shell" creation by the launcher continue-scope flow).
- **Projects** — cross-cutting labels for captures and pages. Simple name + color.
- **Tags** — user-defined, M:N to captures and pages via `capture_tags` + `page_tags` join tables.

### Capture & OCR

All media captures (photos, voice notes, scanned documents) are **rows in the unified `captures` table** discriminated by `kind`. The write path is identical across kinds: insert a `captures` row with `dirty=1`, write the media blob to disk at `releaf/media/<kind>/<capture_id>.<ext>`, and the sync worker picks it up.

- **Document Capture** via native camera with edge detection (produces `kind='scan'` captures).
  - iOS: VisionKit `VNDocumentCameraViewController`.
  - Android: ML Kit Document Scanner (`com.google.mlkit:document-scanner`).
- **On-device OCR** runs inline after capture and writes the result into `captures.extracted_text`.
  - iOS: Vision framework (`VNRecognizeTextRequest`).
  - Android: ML Kit Text Recognition v2 (`com.google.mlkit:text-recognition`).
  - **No Tesseract, no cloud OCR.**
- **Image preprocessing** — rotation, crop, contrast, deskew, all on-device (applies to `kind='photo'` and `kind='scan'`).
- **Capture revisions** — keep the last N (suggest N=10) versions of `extracted_text` per capture so user edits are reversible. Stored in `capture_revisions(id, capture_id, extracted_text, created_at)`. Applies to any capture kind whose `extracted_text` the user can edit.
- **AI summaries (optional).** Gated behind a user-supplied Gemini or Claude API key pasted in Settings. Default off. When enabled, a "Summarize" button on a capture POSTs to the provider's REST API using the user's key stored in Keychain / EncryptedSharedPreferences. Never ship a default key.

### Rich media

All three media kinds are `captures` rows; the only thing that differs is how they're produced and what columns they populate.

- **Photos** (`kind='photo'`). Originals stored in app storage (`FileManager.documentDirectory/releaf/media/photos/<capture_id>.jpg` on iOS, `filesDir/releaf/media/photos/<capture_id>.jpg` on Android). Compress on ingest: longest side ≤ 2560px, JPEG q=0.85. Strip EXIF location unless the user opts in. `photo_width` and `photo_height` populated from EXIF. Thumbnails generated lazily on first view and cached on disk (in a separate `releaf/cache/thumbs/` folder that is **not** synced to Drive). Respect the Settings "capture quality" slider (low / standard / high).
- **Voice notes** (`kind='voice'`) up to 120 min via native mic; format AAC / m4a, 64 kbps mono. Stored at `releaf/media/voice_notes/<capture_id>.m4a`. `voice_duration_seconds` + `voice_recorded_at` populated on save.
  - iOS: `AVAudioRecorder`.
  - Android: `MediaRecorder`.
  - Auto-transcription into `captures.extracted_text` (also FTS5-indexed):
    - iOS: `SFSpeechRecognizer` (request on-device variant where available).
    - Android: `SpeechRecognizer` (prefer on-device via `EXTRA_PREFER_OFFLINE`).
    - For recordings longer than the live-recognizer budget, chunk audio and transcribe each chunk independently, concatenating the results.
- **Scans** (`kind='scan'`). JPEG at `releaf/media/scans/<capture_id>.jpg`; optional PDF at `releaf/media/scans/<capture_id>.pdf` (path stored in `scan_pdf_path`). `extracted_text` populated by OCR; `scan_confidence` from the OCR engine.
- **Reference links.** Separate table (`reference_links`) — URL + resolved metadata (title, description, preview image) fetched **client-side** (no proxy). Render an inline link card. Kept outside `captures` because the produce/sync path is different (metadata fetch + thumbnail cache, no user-captured blob on disk).
- **PDF export** — render a print-optimized view of a Notepad Entry or Page and emit a PDF.
  - iOS: `UIGraphicsPDFRenderer` wrapping a SwiftUI → UIKit render pass, or `UIPrintInteractionController` if the user wants system-print UI.
  - Android: `PrintManager` + `PdfDocument`, or Compose → bitmap → `PdfDocument` pages.
  - Store exports at `releaf/exports/pdf/<entry-id>-<timestamp>.pdf`.

### Tasks & reminders

- **Inline todo lists** within Notepad Entries and Pages. Properties: `enabled`, `hide_completed`, and `todo_items` with stable `position`, `content`, `completed`.
- **Standalone tasks** with hierarchical subtasks (`task_subtasks`), completion toggle, due dates, search (FTS5 on `title`), and linking back to captures.
- **Reminders** scheduled via OS-level local notifications. Support **snooze** (user picks +10m / +1h / +tomorrow) and dismiss.
  - iOS: `UNUserNotificationCenter` with category actions for snooze/dismiss.
  - Android: `NotificationManager` + `AlarmManager` (or `WorkManager` for non-exact reminders) with action buttons.
  - **No Firebase Cloud Messaging, no remote push.**

### Merge — notepad-entry merge (port from Inkcreate)

Append-only, deterministic, locked in ID order to prevent local races.

1. Acquire a SQLite write transaction with the two entry IDs sorted lowest-first (prevents deadlock if two merges race).
2. Build the merged `notes` HTML: `<primary.notes>` + `<hr><h2>Merged from {secondary.title} ({secondary.updated_at})</h2>` + `<secondary.notes>`.
3. Re-parent **all** secondary `captures` rows (of every `kind`: photo, voice, scan) to the primary entry by updating `parent_notepad_entry_id` and bumping `position` to continue after the primary's max-per-kind. Preserve each capture's `created_at` ordering within its kind. Do **not** move the files on disk — the `media_path` column is keyed by `capture_id`, which doesn't change.
4. **Todo list merge.** If both entries have a todo list: append `secondary.items` to `primary.items` with `position` continuing from primary's max. Boolean flags (`enabled`, `hide_completed`) OR-merged. If only one has a list, copy it over.
5. Append `secondary.contacts` and `secondary.locations` JSON arrays to primary (dedup contacts by signature hash of `(name + primaryPhone + email)` lowercase-trimmed).
6. Re-parent `reference_links` rows from secondary to primary.
7. Soft-delete secondary (`deleted_at = now`).
8. Commit; mark primary `dirty = true`, secondary `dirty = true` (so the sync worker pushes the deletion). Captures that were re-parented are already `dirty = true` from the parent-FK update.

Ship unit tests for this — it's the highest-risk algorithm in the app.

### Move-to-notebook

Convert a Notepad Entry into a Notebook → Chapter → Page while preserving every nested artifact.

1. User picks a target Notebook + Chapter (or creates a new Chapter inline).
2. Insert a new `pages` row; copy the entry's notes, todo list, contacts (JSON), locations (JSON).
3. Re-parent every `captures` row by swapping `parent_notepad_entry_id = <entry>` for `parent_page_id = <new_page>` and zeroing the other parent FK. **No file moves on disk** — `media_path` is keyed by `capture_id`, which is stable across the re-parent.
4. Re-parent `reference_links` rows the same way (notepad FK → page FK).
5. Soft-delete the source entry.
6. Single transaction; mark all touched rows `dirty`.

### Search

- **SQLite FTS5** virtual tables mirror: `notepad_entries.notes`, `captures.extracted_text`, `pages.body`, `tasks.title`.
- Keep FTS rows in sync via `AFTER INSERT/UPDATE/DELETE` triggers in the same transaction as the write.
- Search screen filters: tag chips, date range, project chip. Combine filters with AND.
- Results list: tap jumps to the entity with the matching entry highlighted and the right capture mode selected.
- **List vs gallery view** for Notepad Entries; gallery paginated at 9 per page.

### Locations & contacts

- Stored as **JSON columns** on the entry (`locations`, `contacts`) rather than separate tables — they're small, bounded, entry-local.
- **Location.** `{name, address, latitude, longitude, label, mapsUrl, source, secondaryLine}`. Source: `gps | apple-maps | google-maps | manual`. Validate lat/lng ranges client-side.
- **Contact.** `{name, primaryPhone, secondaryPhone, email, website}`. Validate email format. **Dedup** within an entry by hashing `(name + primaryPhone + email)` lowercase-trimmed.
- Tap-to-open: `UIApplication.shared.open` / `Intent.ACTION_VIEW` to Apple Maps or Google Maps with the stored `mapsUrl`. Never our own proxy.
- **Static map thumbnail** rendered by MapKit snapshot (iOS) / Maps SDK lite mode or a static `https://maps.googleapis.com/maps/api/staticmap` request (Android; user's own Maps API key in Settings if they want thumbnails, otherwise show an address card).

### Launcher continue-scope & shortcuts (PWA-equivalent UX)

- **Home-screen shortcuts** for Quick Capture and Today.
  - iOS: Home Screen Quick Actions (static in `Info.plist` + dynamic via `UIApplicationShortcutItem`).
  - Android: App Shortcuts (static XML + dynamic via `ShortcutManager`).
- **Launcher continue-scope** preference (port from Inkcreate's `launcher_continue_scope`). Values:
  - `append_to_latest` — quick-capture appends to the most recently edited Notepad Entry.
  - `today` — appends to today's entry (find-or-create).
  - `new_shell` — always creates a fresh blank entry (respecting `allow_blank_content = true`).
- **Offline-first.** Every action must work without network; sync is always background.

### Settings

- **Profile** — name + local avatar image.
- **Drive connection** — connect / disconnect / force-sync; last-sync timestamp.
- **Capture quality** — image compression level (low / standard / high).
- **Notification preferences** — per-category toggles (reminders, daily-log nudge, long-recording warnings).
- **Launcher continue-scope** — see above.
- **AI provider** — off / Gemini (paste key) / Claude (paste key). Keys stored in Keychain / EncryptedSharedPreferences only; never logged, never synced to Drive.
- **Data export** — manual full backup to Drive; export all as ZIP to device storage (then the OS share sheet).
- **Data deletion** — wipe local; optionally wipe the Drive `Releaf/` folder (two-step confirmation).
- **Privacy policy + ToS** — bundled markdown files rendered by a Compose/SwiftUI markdown view.

## Storage architecture

### Local (source of truth)

- **SQLite** with FTS5.
  - iOS: **GRDB** (explicit, previewable).
  - Android: **Room** (canonical in the Android ecosystem).
- **App-scoped file storage** for media.
  - iOS: `FileManager.default.urls(for: .documentDirectory)/releaf/media/...`.
  - Android: `context.filesDir/releaf/media/...`.
- **Encrypted key-value** for Drive tokens + user preferences.
  - iOS: Keychain via `SecItem`.
  - Android: `EncryptedSharedPreferences` (androidx.security-crypto — already in `libs.versions.toml`).

### Tables (SQLite)

`notebooks`, `chapters`, `pages`, `daily_logs`, `notepad_entries`, `captures`, `capture_revisions`, `capture_tags`, `page_tags`, `tasks`, `task_subtasks`, `todo_lists`, `todo_items`, `reminders`, `tags`, `reference_links`, `projects`, `page_templates`, `sync_state`, `user_settings`.

**Every row:** `id UUIDv7 PK`, `created_at`, `updated_at`, `dirty BOOLEAN DEFAULT 1`, `deleted_at NULL`.

**`captures` (unified) columns:**
- `id UUIDv7 PK`
- `kind TEXT NOT NULL CHECK (kind IN ('photo', 'voice', 'scan'))`
- `parent_page_id UUIDv7 NULL FK → pages(id)`
- `parent_notepad_entry_id UUIDv7 NULL FK → notepad_entries(id)`
- `parent_daily_log_id UUIDv7 NULL FK → daily_logs(id)`
- `project_id UUIDv7 NULL FK → projects(id)`
- `position INTEGER NOT NULL DEFAULT 0` (ordering within a parent)
- `media_path TEXT NULL` (relative path under `releaf/media/<kind>/`)
- `drive_file_id TEXT NULL` (populated after sync upload)
- `byte_size INTEGER NULL`, `mime_type TEXT NULL`
- `extracted_text TEXT NULL` (OCR for scans; transcript for voice)
- **Photo-only:** `photo_width INTEGER NULL`, `photo_height INTEGER NULL`
- **Voice-only:** `voice_duration_seconds INTEGER NULL`, `voice_recorded_at TIMESTAMP NULL`
- **Scan-only:** `scan_title TEXT NULL`, `scan_confidence REAL NULL`, `scan_pdf_path TEXT NULL`, `scan_pdf_drive_file_id TEXT NULL`
- `created_at`, `updated_at`, `dirty`, `deleted_at` (as above)
- `CHECK ((parent_page_id IS NOT NULL) + (parent_notepad_entry_id IS NOT NULL) + (parent_daily_log_id IS NOT NULL) = 1)` — **exactly one parent**.

Indexes: `(parent_page_id, kind, position)`, `(parent_notepad_entry_id, kind, position)`, `(parent_daily_log_id, kind)`, `(project_id)`, `(dirty) WHERE dirty = 1`.

**FTS5 mirrors:** `fts_notepad_notes(notepad_entry_id UNINDEXED, notes)`, `fts_capture_text(capture_id UNINDEXED, kind UNINDEXED, extracted_text)`, `fts_page_body(page_id UNINDEXED, body)`, `fts_task_title(task_id UNINDEXED, title)`. Keep in sync with AFTER INSERT/UPDATE/DELETE triggers in the same transaction as the base write.

### Cloud (optional, user-owned)

Google Drive, `drive.file` scope. Dedicated `Releaf/` folder with manifest + structured hierarchy.

```
Releaf/
├── manifest.json                  ← {version, last_sync_at, entity_checksums{id → sha256}}
├── notebooks/
│   └── {notebook_id}/
│       └── {chapter_id}/
│           └── {page_id}.json
├── notepad_entries/
│   └── {yyyy}/{mm}/{entry_id}.json
├── daily_logs/
│   └── {yyyy}/{yyyy-mm-dd}.json
├── media/
│   ├── photos/{capture_id}.jpg
│   ├── voice_notes/{capture_id}.m4a
│   └── scans/{capture_id}.jpg       ← + optional {capture_id}.pdf alongside
└── exports/
    └── pdf/{entry_id}-{timestamp}.pdf
```

This extends (and supersedes) the older layout in `docs/DRIVE_SCHEMA.md`. When implementing, update `DRIVE_SCHEMA.md` to match the manifest-based layout above.

### Sync strategy

- **User-initiated full backup** + **automatic incremental sync** on app foreground (debounced) and **30s-debounced after each mutation**.
- Incremental sync compares local `dirty=1` rows against manifest checksums in `manifest.json`. Uploads:
  - Changed JSON rows → PUT to Drive REST v3 (multipart upload for metadata + file).
  - New/changed media → binary upload to the media folder.
- **After each upload**, update the local row's `driveFileId` + `dirty=0`, then update `manifest.json` (upload last — matches the ordering rule: blobs durable before indexes).
- **Conflict resolution.**
  - Scalar fields: **last-write-wins** by `updated_at`.
  - `notes` HTML (NotepadEntry / Page): store a `{local, remote}` conflict stub in the local row; **never silently overwrite edited text**. Surface a one-line banner on the entry: "Conflicting version from another device — tap to resolve." User picks local / remote / merge-manually.
- **Restore flow.** First-run "Sign in and restore from Drive" path: download manifest → iterate `entity_checksums` → download each JSON row → materialize into SQLite → download media lazily on first view (placeholder until hydrated).
- **Drive REST v3 directly.** No server proxy, no SDK wrapper beyond Google Sign-In itself. OkHttp on Android, `URLSession` on iOS.

## Auth

Replace the in-memory `GoogleAuthClient` stub with real Google Sign-In.

- **iOS:** [GoogleSignIn-iOS SDK](https://github.com/google/GoogleSignIn-iOS) + Keychain for token storage.
- **Android:** Credential Manager + `play-services-auth` `AuthorizationClient` + `EncryptedSharedPreferences`.
- Scopes requested: `openid email profile https://www.googleapis.com/auth/drive.file`.
- Intercept 401s, silently refresh the access token, retry once.
- Sign-out: drop tokens; local data is preserved. "Sign out and wipe" is a separate Settings button.

## Native SDK map (no cross-platform libs)

| Capability            | iOS                                        | Android                                                   |
| --------------------- | ------------------------------------------ | --------------------------------------------------------- |
| UI                    | SwiftUI                                    | Jetpack Compose                                           |
| SQLite                | GRDB                                       | Room                                                      |
| Rich-text editor      | `UITextView` + `NSAttributedString` wrap   | Compose `AnnotatedString` + custom editor / Compose-Richtext |
| Rich-text storage     | HTML string                                | HTML string                                               |
| Doc scanner           | VisionKit `VNDocumentCameraViewController` | ML Kit Document Scanner                                   |
| OCR                   | Vision `VNRecognizeTextRequest`            | ML Kit Text Recognition v2                                |
| Voice record          | `AVAudioRecorder`                          | `MediaRecorder`                                           |
| Voice transcribe      | `SFSpeechRecognizer`                       | `SpeechRecognizer` (offline-preferred)                    |
| Local notifications   | `UNUserNotificationCenter`                 | `NotificationManager` + `AlarmManager` / WorkManager       |
| Home-screen shortcuts | `UIApplicationShortcutItem`                | `ShortcutManager`                                         |
| Maps thumbnail        | MapKit snapshot                            | Maps SDK lite / static-maps HTTP                          |
| Open in maps          | `UIApplication.shared.open(mapsUrl)`       | `Intent.ACTION_VIEW`                                      |
| PDF export            | `UIGraphicsPDFRenderer`                    | `PdfDocument` + `PrintManager`                            |
| Sign-In               | `GoogleSignIn-iOS`                         | Credential Manager + play-services-auth                   |
| Secure KV             | Keychain                                   | `EncryptedSharedPreferences`                              |
| Drive REST            | `URLSession`                               | OkHttp                                                    |
| On-device AI (opt)    | Apple Intelligence / Core ML               | Gemini Nano via AICore (where available)                  |

## Design system & tokens

- Tokens already live in `design-system/` as a cross-platform source of truth, reflected into `ui/theme/AppColors.kt` (Android) and `DesignSystem/` (iOS).
- **Add a token pipeline** (Node or Kotlin script; keep it simple) that generates both platform theme files from `design-system/design-tokens.json`. Commit the generated files; run the script in CI and fail on drift.
- **Add dark mode** during the token pipeline work. Follow system; no user override in v1.
- **Accessibility.** Minimum 4.5:1 contrast; 44pt / 44dp touch targets; `accessibilityLabel` / `contentDescription` on every actionable element; Dynamic Type on iOS, font-scale respect on Android.

## Deliverables

1. **Migration plan** — before any feature code, produce:
   - Full **SQLite DDL** for every table above (both platforms), including FTS5 virtual tables + triggers.
   - **Navigation graph additions** — Daily Log view, Notepad Entry editor (with merge/move actions), Capture detail, Task detail, Project list, Tag management, Search, Settings sub-screens.
   - **Drive schema update** to `docs/DRIVE_SCHEMA.md` reflecting the manifest-based layout.
   - **Design-token pipeline** proposal (input format, generator script, output files).
2. **Feature implementations** in the build order below — each landing on iOS + Android in the same session (parallel, not serial).
3. **Unit tests** on the two highest-risk algorithms: **merge** and **sync** (including conflict resolution). These are where data is lost if we're wrong.
4. **README** covering build, run, sign, and first-run Drive setup on both platforms.

## Build order (suggested)

Ship a working **local-only** app by step 8; Drive is the last slice.

1. **SQLite schema + repository layer + FTS5.** Include `CaptureRepository`, `NotepadRepository`, `DailyLogRepository`, `TaskRepository`, `PageRepository`, `NotebookRepository`, `TagRepository`, `ProjectRepository`. In-memory fakes for previews.
2. **Token pipeline + dark mode.** Runs before further screen work so new screens pick up dark automatically.
3. **Navigation shell updates** — Daily Log, Notepad Entry editor, Capture detail, Task detail, Search routes wired into the existing `BottomNav`.
4. **Notepad Entry CRUD (no media)** — prove the rich-text editor, FTS5 wiring, and local persistence.
5. **Photo + voice-note + scan attachments.**
6. **Daily Logs + Today view** — `findOrCreate(today)`, associations to captures + tasks (nullify-on-delete).
7. **Tasks + subtasks + reminders + local notifications.**
8. **Capture + on-device OCR pipeline + capture revisions.**
9. **Merge + move-to-notebook + PDF export.** Unit-test merge first.
10. **Drive sign-in + manifest + incremental sync + restore flow.** Unit-test the conflict resolver.
11. **Settings, polish, accessibility pass, empty / error / loading states everywhere.**

## Acceptance for "done"

- User can sign in with their Google account, create a notebook, create a chapter, create a page; or capture straight into today's Notepad Entry; or create a standalone Capture — **all without network access**.
- On reconnect, local data syncs to Drive under `Releaf/` matching the manifest schema.
- Uninstall → reinstall → sign in: data restores from Drive (manifest → rows → media-on-view).
- Merging two Notepad Entries with all seven capture modes populated produces a lossless, deterministic primary entry; the secondary is soft-deleted; the deletion propagates to Drive on next sync.
- Moving a Notepad Entry to a Notebook/Chapter preserves every nested artifact; the file paths on disk are updated; the source entry is soft-deleted.
- Airplane mode: every screen renders, every capture works, edits persist.
- No screen exceeds 16ms frame time on a Pixel 6a / iPhone 13 mini.
- Every screen has a working preview with an in-memory repository — no preview needs the network, Drive, or a real SQLite file.
- Dark mode renders correctly end-to-end; tokens pipeline runs green in CI.
- Unit tests for merge + sync pass, including conflict-stub creation + resolver.

## Repo layout to respect

```
releaf/
├── android/            Gradle + Compose app
│   └── app/src/main/java/app/releaf/mobile/
│       ├── auth/       GoogleAuthClient + tokens
│       ├── data/       repositories, Room DB, Drive client, sync worker
│       ├── features/   one folder per screen (home, notebook, page, notepad, daily, capture, task, search, settings, auth)
│       └── ui/         design system + shared composables
├── ios/Releaf/         Xcode + SwiftUI app
│   ├── DesignSystem/   tokens + shared views
│   ├── Features/       one folder per screen
│   └── Data/           repositories, GRDB, Drive client, sync scheduler
├── design-system/      cross-platform tokens (source of truth) + token-pipeline generator
├── docs/
│   ├── ARCHITECTURE.md
│   └── DRIVE_SCHEMA.md
├── HANDOFF.md          in-flight work notes from the last Cowork session
└── PROMPT.md           this file
```

Keep the two platforms structurally parallel. If you add a repository on Android, add the iOS twin in the same PR. If you add a screen on iOS, mirror it on Android.

---

## Before writing any feature code

Produce and confirm with the user:

1. **SQLite DDL** for all tables + FTS5 virtuals + triggers. Both platforms; call out any divergence (Room-specific type converters, GRDB-specific migrations).
2. **Navigation graph** additions as a diagram (text or `mermaid`).
3. **Drive schema update** — edit `docs/DRIVE_SCHEMA.md` to match the new manifest-based layout.
4. **Token pipeline proposal** — input format, generator, output shape, dark-mode handling.
5. **Open questions list** — anything where the spec is ambiguous, flag it and propose an answer rather than guessing silently.

Do not scaffold any of the new tables or screens until the DDL + nav graph are reviewed. Keep the existing UI shell (Home, Notebook list / detail, Page detail, Notepad, Settings, Login with fixture data) working throughout the migration — every PR should land the app in a buildable, previewable state on both platforms.
