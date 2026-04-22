# Drive schema — v2 (manifest-based)

Where every byte of a user's Releaf data lives in their Google Drive. **This document supersedes the v1 `Inkcreate/` folder-per-entity layout.** v1 is fossilized for users migrating forward; v2 is what the shipping app writes against.

Read this alongside:
- `design-system/migrations/v1_initial.sql` — the SQLite schema whose rows get serialized here.
- `docs/OPEN_QUESTIONS.md` — §5 (version policy), §6 (`drive.file` reinstall), §8 (EXIF), §9 (PDF sync).
- `docs/ARCHITECTURE.md` — the sync worker that writes against this schema.

---

## What changed from v1

| Concern               | v1 layout                                        | v2 layout                                                              |
| --------------------- | ------------------------------------------------ | ---------------------------------------------------------------------- |
| Root folder           | `Inkcreate/`                                     | `Releaf/`                                                              |
| Index of everything   | Nested `notebooks.json` → `chapters.json` → …    | Single `manifest.json` at root with checksums per entity               |
| Per-page container    | `pages/<id>/page.json` + sibling media folders   | `notebooks/{nb}/{ch}/{page}.json` + media lives under `media/` by capture ID |
| Notepad entries       | n/a — implicit as Pages                          | First-class, bucketed `notepad_entries/yyyy/mm/{id}.json`             |
| Daily logs            | n/a                                              | First-class, bucketed `daily_logs/yyyy/{yyyy-mm-dd}.json`             |
| Captures              | Embedded inside `page.json`                      | First-class files, parent resolved via `parent_*` FKs                  |
| Tombstones            | Delete-folder implicit                           | Explicit `tombstones/` entries so deletes propagate across devices     |
| Rich-text format      | HTML string                                      | Canonical CommonMark (see OPEN_QUESTIONS §4)                           |
| Schema version        | `.releaf/schema.json`                            | Field on `manifest.json` itself                                        |

The v1 layout was designed for single-device, single-sync-target use and tangled the *entity list* with the *folder tree*. v2 separates them: the manifest is the source of truth for "what exists and what version we have"; the folders are storage detail.

---

## Root layout

```
My Drive/
└── Releaf/                              ← user-visible, user-renameable (but don't)
    ├── manifest.json                    ← ★ the index — see below
    ├── notebooks/
    │   └── {notebook_id}/
    │       ├── notebook.json            ← notebook metadata
    │       └── {chapter_id}/
    │           ├── chapter.json         ← chapter metadata
    │           └── {page_id}.json       ← page payload (notes, todo, contacts, locations)
    ├── notepad_entries/
    │   └── {yyyy}/
    │       └── {mm}/
    │           └── {entry_id}.json      ← notepad entry payload
    ├── daily_logs/
    │   └── {yyyy}/
    │       └── {yyyy-mm-dd}.json        ← daily log payload (date is the primary axis)
    ├── captures/
    │   └── {capture_id}.json            ← capture metadata (kind, dimensions, extracted_text, …)
    ├── tasks/
    │   └── {task_id}.json               ← standalone task + subtasks + reminders
    ├── reference_links/
    │   └── {ref_id}.json                ← URL + OG metadata + caption
    ├── projects/
    │   └── {project_id}.json            ← project name + color + archived_at
    ├── tags/
    │   └── {tag_id}.json                ← tag name + color
    ├── page_templates/
    │   └── {template_id}.json           ← reusable page seed payload
    ├── media/
    │   ├── photos/{capture_id}.jpg
    │   ├── voice_notes/{capture_id}.m4a
    │   └── scans/
    │       ├── {capture_id}.jpg         ← required
    │       └── {capture_id}.pdf         ← optional (when app generated one)
    ├── exports/
    │   └── pdf/{entry_id}-{timestamp}.pdf   ← OFF by default — see §9 behavior below
    └── tombstones/
        └── {entity_id}.json             ← "this id was deleted at Z on device D"
```

### Design rules

1. **IDs are UUIDv7, client-generated, stable.** Never Drive file IDs — users can move or rename files, and we resolve by querying the manifest, not by walking folders.
2. **One entity per file.** A `pages/.../{page_id}.json` contains one Page's scalars; the captures attached to it live in separate `captures/{capture_id}.json` files. Rationale: sync granularity matches SQLite row granularity, so `dirty=1` rows become single PUTs.
3. **`captures/` is flat.** Every capture is `captures/{capture_id}.json` regardless of parent. Flat lookup trades a layer of folder overhead for simpler sync: re-parenting a capture during merge is a scalar field change on one file, not a cross-folder move.
4. **Date-bucketing on `notepad_entries/` and `daily_logs/`** keeps any one folder under a few hundred files. A power user with 5 years of daily entries = ~1800 entries total; spread across 60 monthly buckets = ~30 per bucket. Drive's directory listing performance stays flat.
5. **No CRDTs, no patches.** Every change to an entity is a full file rewrite. Simpler sync logic, tiny payloads.
6. **Media is binary, not base64-in-JSON.** Media files live under `media/` and are referenced by `capture_id`.

---

## `manifest.json`

The single index of everything Releaf has written to this Drive account.

```json
{
  "schema_version": {
    "major": 2,
    "minor": 0
  },
  "migration_version": 1,
  "app_version": "0.3.0+build.214",
  "device_id": "018f5c9d-3a64-7b30-bf71-0f8a9c0b1d92",
  "last_sync_at": "2026-04-21T14:23:07.442Z",
  "client_generated_at": "2026-04-21T14:23:07.440Z",
  "entity_checksums": {
    "018f7b10-...": { "kind": "notebook",       "path": "notebooks/018f7b10-.../notebook.json", "sha256": "a1b2…", "updated_at": "2026-04-20T08:14:03.002Z" },
    "018f7b11-...": { "kind": "chapter",        "path": "notebooks/018f7b10-.../018f7b11-.../chapter.json", "sha256": "…", "updated_at": "…" },
    "018f7b12-...": { "kind": "page",           "path": "notebooks/018f7b10-.../018f7b11-.../018f7b12-....json", "sha256": "…", "updated_at": "…" },
    "018f7c90-...": { "kind": "notepad_entry",  "path": "notepad_entries/2026/04/018f7c90-....json", "sha256": "…", "updated_at": "…" },
    "018f7d01-...": { "kind": "daily_log",      "path": "daily_logs/2026/2026-04-21.json", "sha256": "…", "updated_at": "…" },
    "018f7e22-...": { "kind": "capture",        "path": "captures/018f7e22-....json", "sha256": "…", "updated_at": "…",
                      "media": { "photos/018f7e22-....jpg": "c1d2…" } },
    "018f7f33-...": { "kind": "task",           "path": "tasks/018f7f33-....json", "sha256": "…", "updated_at": "…" }
  },
  "tombstones": {
    "018f8044-...": { "kind": "capture", "deleted_at": "2026-04-19T22:10:14.780Z", "device_id": "…" }
  }
}
```

### Field reference

| Field                     | Semantics                                                                                             |
| ------------------------- | ----------------------------------------------------------------------------------------------------- |
| `schema_version.major`    | Integer. Bumped only for breaking manifest changes — see [Version bump policy](#version-bump-policy). |
| `schema_version.minor`    | Integer. Bumped for forward-compatible additions. Resets to 0 on major bump.                          |
| `migration_version`       | Integer. Matches the highest `vN` file in `design-system/migrations/` that the client has applied.   |
| `app_version`             | SemVer + build metadata. Informational only; not used for compatibility gating.                       |
| `device_id`               | UUIDv7 for the device that last wrote the manifest. Used to disambiguate which device raced.          |
| `last_sync_at`            | ISO-8601 UTC. Timestamp the manifest itself was committed to Drive.                                  |
| `client_generated_at`     | ISO-8601 UTC. Local device time when the manifest JSON was serialized (pre-upload).                   |
| `entity_checksums`        | Object keyed by entity UUID. One entry per live (non-tombstoned) row in the user's local DB.          |
| `entity_checksums[id].kind` | One of `notebook`, `chapter`, `page`, `notepad_entry`, `daily_log`, `capture`, `task`, `reference_link`, `project`, `tag`, `page_template`. |
| `entity_checksums[id].path` | Drive path relative to `Releaf/`. Deterministic from `kind` + `id` + parent.                         |
| `entity_checksums[id].sha256` | SHA-256 of the canonical-JSON serialization of the payload file.                                   |
| `entity_checksums[id].updated_at` | ISO-8601 of the entity's `updated_at` at the time the checksum was taken.                      |
| `entity_checksums[id].media` | Optional. For `kind: capture` only. Object mapping media-file path → SHA-256 of the blob.          |
| `tombstones`              | Object keyed by entity UUID. Every deleted entity's ID, kind, and when/where it was deleted.         |

### Canonical JSON for checksumming

SHA-256 is computed over a canonical serialization:

1. UTF-8 encoding.
2. Keys sorted lexicographically within every object.
3. No insignificant whitespace (no newlines, no trailing spaces).
4. Numbers serialize as JSON integers when integer-valued, otherwise shortest round-tripping decimal.
5. Strings use JSON default escaping (no `\u0000` unless literal).

Platforms must agree. The iOS and Android implementations each run their canonicalizer on identical input and assert identical output in a shared test fixture.

---

## Entity payload shapes

Every payload is JSON, UTF-8, canonicalized on write (above). Fields marked `—` are optional / nullable.

### `notebooks/{id}/notebook.json`

```json
{
  "id": "018f7b10-...",
  "title": "Sabbatical 2026",
  "color_token": "coral",
  "position": 1024,
  "archived_at": null,
  "created_at": "2026-04-01T09:02:01.123Z",
  "updated_at": "2026-04-15T14:30:22.455Z"
}
```

### `notebooks/{nb}/{ch}/chapter.json`

```json
{
  "id": "018f7b11-...",
  "notebook_id": "018f7b10-...",
  "title": "Week 1",
  "position": 1024,
  "archived_at": null,
  "created_at": "2026-04-01T09:03:00.001Z",
  "updated_at": "2026-04-14T17:00:00.000Z"
}
```

### `notebooks/{nb}/{ch}/{id}.json` — page

```json
{
  "id": "018f7b12-...",
  "chapter_id": "018f7b11-...",
  "project_id": null,
  "template_id": null,
  "title": "Tuesday morning walk",
  "captured_on": "2026-04-20",
  "position": 1024,
  "notes": "# Tuesday morning walk\n\nCrisp and cold. Saw the heron again.\n\n- Stretch\n- Write\n",
  "todo_list": {
    "enabled": true,
    "hide_completed": false,
    "items": [
      { "id": "018f7b13-...", "content": "Stretch", "completed": true,  "position": 1024, "updated_at": "2026-04-20T08:30:10.000Z" },
      { "id": "018f7b14-...", "content": "Write",   "completed": false, "position": 2048, "updated_at": "2026-04-20T08:30:10.000Z" }
    ]
  },
  "contacts": [
    { "name": "Jo Park", "primary_phone": "+1-555-0101", "email": "jo@example.com", "website": null, "secondary_phone": null }
  ],
  "locations": [
    {
      "name": "Heron Point",
      "address": "Harbor Park, Dock 3",
      "latitude": 47.6101,
      "longitude": -122.3421,
      "label": "Walk start",
      "maps_url": "https://maps.apple.com/?ll=47.6101,-122.3421",
      "source": "apple-maps",
      "secondary_line": null
    }
  ],
  "tag_ids": ["018f8100-...", "018f8101-..."],
  "created_at": "2026-04-20T08:12:03.000Z",
  "updated_at": "2026-04-20T09:47:11.000Z"
}
```

**`notes` is canonical CommonMark**, per OPEN_QUESTIONS §4. Not HTML. The iOS and Android editors both normalize on save via `cmark-gfm`.

Captures attached to this page are **not** embedded — they live in `captures/{capture_id}.json` and carry `parent_page_id = 018f7b12-...`. This is the big v2 shift from v1.

### `notepad_entries/{yyyy}/{mm}/{id}.json`

```json
{
  "id": "018f7c90-...",
  "user_id": "018f5c9d-...",
  "project_id": null,
  "entry_date": "2026-04-20",
  "title": "Morning scrapbook",
  "notes": "Coffee, heron, the ducks are back.\n\n- [ ] Refill feeder\n",
  "todo_list": { "enabled": false, "hide_completed": false, "items": [] },
  "contacts": [],
  "locations": [],
  "tag_ids": [],
  "allow_blank_content": false,
  "conflict_stub": null,
  "created_at": "2026-04-20T07:02:00.000Z",
  "updated_at": "2026-04-20T18:14:33.820Z"
}
```

`conflict_stub` is populated on the device that detected the conflict; serialized here only if the user hasn't resolved it yet. Shape:

```json
"conflict_stub": {
  "remote_notes": "Coffee, heron, the ducks are back. And the new swans!\n",
  "remote_updated_at": "2026-04-20T17:50:12.002Z",
  "remote_device_id": "018f5c9d-other-device",
  "detected_at": "2026-04-20T18:14:33.820Z"
}
```

### `daily_logs/{yyyy}/{yyyy-mm-dd}.json`

```json
{
  "id": "018f7d01-...",
  "user_id": "018f5c9d-...",
  "log_date": "2026-04-21",
  "notepad_entry_id": "018f7c90-...",
  "created_at": "2026-04-21T00:00:00.000Z",
  "updated_at": "2026-04-21T14:02:11.000Z"
}
```

The file name is `{log_date}.json` — **keyed by date, not by UUID** — so that the "find-or-create for today" operation is a single deterministic Drive GET on `daily_logs/2026/2026-04-21.json`. The UUID `id` is preserved inside the file for SQLite's sake.

Captures and tasks associated with this daily log are not embedded. They're in `captures/` / `tasks/` respectively with `parent_daily_log_id` / `daily_log_id` set.

### `captures/{id}.json`

```json
{
  "id": "018f7e22-...",
  "kind": "photo",
  "parent_page_id": "018f7b12-...",
  "parent_notepad_entry_id": null,
  "parent_daily_log_id": null,
  "project_id": null,
  "position": 1024,
  "caption": null,
  "extracted_text": null,
  "ai_summary": null,
  "media_filename": "018f7e22-....jpg",
  "byte_size": 2348491,
  "mime_type": "image/jpeg",
  "photo_width": 2560,
  "photo_height": 1920,
  "voice_duration_seconds": null,
  "voice_recorded_at": null,
  "scan_title": null,
  "scan_confidence": null,
  "scan_pdf_filename": null,
  "revisions": [
    { "id": "018f7e23-...", "field": "extracted_text", "value": "Previously extracted text …", "created_at": "2026-04-20T08:13:00.000Z" }
  ],
  "tag_ids": [],
  "created_at": "2026-04-20T08:12:04.000Z",
  "updated_at": "2026-04-20T08:12:15.000Z"
}
```

Polymorphic parent: **exactly one** of the three `parent_*` keys is non-null. Mirrors the CHECK constraint in `v1_initial.sql`.

Kind-specific columns are all serialized even when null, so the payload shape is stable regardless of kind. That keeps the JSON schema singular and CI-validatable.

### `tasks/{id}.json`

```json
{
  "id": "018f7f33-...",
  "title": "Call the vet",
  "description": "Follow up on bloodwork results\n",
  "project_id": null,
  "due_at": "2026-04-22T15:00:00-07:00",
  "completed_at": null,
  "position": 1024,
  "linked_capture_id": null,
  "subtasks": [
    { "id": "018f7f34-...", "title": "Get file number", "completed_at": null, "position": 1024, "updated_at": "..." }
  ],
  "reminders": [
    { "id": "018f7f35-...", "fire_at": "2026-04-22T14:45:00-07:00", "snoozed_until": null, "dismissed_at": null, "updated_at": "..." }
  ],
  "tag_ids": [],
  "created_at": "2026-04-21T10:00:00.000Z",
  "updated_at": "2026-04-21T10:00:00.000Z"
}
```

Subtasks and reminders are embedded because they're bounded (handful per task) and entity-local. This is the same rationale the schema uses for `contacts` and `locations` on pages/entries.

### `reference_links/{id}.json`

```json
{
  "id": "018f8000-...",
  "parent_page_id": null,
  "parent_notepad_entry_id": "018f7c90-...",
  "url": "https://example.com/article",
  "title": "The long walk home",
  "description": "How slow trips changed us.",
  "preview_image_url": "https://example.com/og.jpg",
  "caption": null,
  "position": 1024,
  "created_at": "2026-04-20T12:02:00.000Z",
  "updated_at": "2026-04-20T12:02:00.000Z"
}
```

### Small metadata payloads

`projects/{id}.json`, `tags/{id}.json`, `page_templates/{id}.json` — small key-value blobs. Full shapes in the DDL; the Drive form is a direct JSON dump of those table rows minus `dirty`, `deleted_at`.

---

## Media files

Naming and placement:

| Kind    | Path                                     | Format           | Notes                                              |
| ------- | ---------------------------------------- | ---------------- | -------------------------------------------------- |
| photo   | `media/photos/{capture_id}.jpg`           | JPEG, q=0.85     | Longest side ≤ 2560 px; EXIF location **stripped per OPEN_QUESTIONS §8 only when user toggles OFF.** Default ON preserves location. |
| voice   | `media/voice_notes/{capture_id}.m4a`      | AAC, 64 kbps mono | Duration in `voice_duration_seconds` on `captures.json` |
| scan    | `media/scans/{capture_id}.jpg`            | JPEG, preprocessed | Deskewed, contrast-adjusted                        |
| scan    | `media/scans/{capture_id}.pdf`            | PDF              | Optional; only written when the platform scanner produced one |
| export  | `exports/pdf/{entry_id}-{timestamp}.pdf`  | PDF              | **Not uploaded by default — see §9 below.**        |

Thumbnails are **not** synced. They live only in `filesDir/releaf/cache/thumbs/` (Android) / `.documentDirectory/releaf/cache/thumbs/` (iOS) and are regenerated from originals on demand. A restored device regenerates thumbs lazily on first view of each media item.

### EXIF policy (OPEN_QUESTIONS §8)

Default: **location included** in EXIF on photo and scan captures. The Settings toggle (Settings → Capture → Include photo location) strips EXIF GPS + iOS HEIF location tags at **capture time** if off — so the blob that lands on disk, and therefore in Drive, never carries location when the user has opted out. Previously uploaded media is not retroactively stripped.

---

## Tombstones

Every soft-delete (`deleted_at IS NOT NULL` on a SQLite row) produces a tombstone file:

```
tombstones/{entity_id}.json
```

Payload:

```json
{
  "id": "018f8044-...",
  "kind": "capture",
  "deleted_at": "2026-04-19T22:10:14.780Z",
  "device_id": "018f5c9d-...",
  "hard_delete_at": null
}
```

And a matching entry is added to `manifest.tombstones[id]`. The entity's original payload file and media blob stay on Drive until `hard_delete_at` is reached or the user manually empties the archive.

### Hard-delete policy

v1 (this release) leaves `hard_delete_at = null` — nothing is ever actually removed from Drive. The "Empty archive" action in Settings walks the tombstones, stamps `hard_delete_at = now`, and the next sync run physically deletes the payload + media files.

### Why tombstones, not "just remove from manifest"

Two devices synced with an old manifest must learn that an entity was deleted elsewhere. If device A deletes an entity offline, device B opens Drive first, and device B doesn't see the entity in `entity_checksums` — device B has no way to distinguish "deleted" from "never existed on device B yet" from "uploaded to Drive before device B last synced." The tombstone is the unambiguous signal.

---

## Sync algorithm

Every sync is triggered by one of:
- App foreground (debounced 5 s).
- 30 s after the last local mutation (debounced).
- Explicit user tap: Settings → Drive → Sync now.
- Network reconnect.

The worker (`SyncWorker` — WorkManager on Android, `BGTaskScheduler` on iOS) runs:

### Upload path (local → Drive)

1. **Collect dirty rows** (`WHERE dirty = 1`) and collect locally-observed tombstones not yet reflected in Drive's manifest.
2. **Fetch remote manifest** (`manifest.json`). If its `schema_version.major` > ours, abort with "Please update Releaf to restore this backup" per §5 — no partial sync under version mismatch.
3. **Compute diff** per entity:
   - Local row SHA differs from remote? → upload.
   - Local row exists; remote tombstone exists? → conflict (treat like a version mismatch on `updated_at`; see Conflict resolution below).
   - Local tombstone exists; remote checksum exists? → delete path (step 4).
4. **For each entity to upload:**
   1. Upload the payload JSON to its deterministic path.
   2. For captures, upload any changed media blobs to `media/<kind>/<capture_id>.<ext>`.
   3. Compute the new SHA-256 and stage a manifest update.
5. **For each local tombstone:**
   1. Upload the tombstone file to `tombstones/{id}.json`.
   2. Stage removal of `entity_checksums[id]` and insertion into `manifest.tombstones[id]`.
6. **Upload `manifest.json` last.** This is the commit; any failure before this leaves the blobs durable but the manifest unchanged, so the next sync retries cleanly.
7. **On success:** mark all touched local rows `dirty = 0`; record `last_sync_at` locally.

Resumable uploads are used for any single blob > 5 MB (Drive REST `uploadType=resumable`). Smaller files use `uploadType=multipart`. Both are Drive v3 on the raw REST API — no SDK wrapper.

### Download path (Drive → local)

Reverse of upload. Fetch remote manifest, diff against local checksum table, pull changed rows and new tombstones.

Media blobs are **lazy-loaded**: only when the user views a capture is its `media/.../{id}.{ext}` fetched and written to local storage. A placeholder is shown in the UI until hydration completes.

### Conflict resolution

For **scalar fields** (everything except `notes`): last-write-wins by `updated_at`. Newer timestamp wins; this is safe because scalar fields are usually UI toggles (`completed`, `enabled`, `hide_completed`) with no merge value.

For the `notes` field on NotepadEntry and Page:

1. Both sides have changed since last common ancestor → conflict detected.
2. The losing side (older `updated_at`) writes its own `notes` into `conflict_stub.remote_notes` in its own local row. The winning side's `notes` value is pulled in as canonical.
3. A banner is shown on the affected entry/page: "Conflicting version from another device — tap to resolve."
4. User taps → conflict resolver screen (see `docs/NAV_GRAPH.md` and OPEN_QUESTIONS §10). Two options: **Keep local** (clears stub, discards remote) or **Keep remote** (overwrites `notes` with `conflict_stub.remote_notes`, clears stub).
5. If the user has been offline for a while and accumulated > 5 stubs, they see the aggregated banner instead (§12): "20 entries have remote changes. Review →" opening the Conflicts list under Settings.

### Restore flow

First run after sign-in + "Restore from Drive":

1. Download `manifest.json`.
2. If `schema_version.major` > our reader's max, hard-block with "Please update Releaf to restore this backup." No partial paths.
3. For each entry in `entity_checksums`: download payload JSON → insert into SQLite with `dirty = 0` and the remote `sha256` cached locally so we can skip re-uploading unchanged rows on next sync.
4. For each entry in `tombstones`: insert a local row with `deleted_at` set; no payload.
5. Media stays on Drive — pulled on first view per the lazy-load rule above.

A restore is resumable on crash: every entity written to SQLite commits its own row with `dirty = 0`, so re-running the flow only hits entities that didn't land.

---

## Version bump policy

Per OPEN_QUESTIONS §5 (no judgment calls, fixed rules).

### Bump `schema_version.major`

Blocks older readers on restore. Reader hard-errors, displays the "Please update" block.

- Column or field **removed** from a payload shape.
- Column/field **renamed** (treated as remove + add).
- Semantic of an existing field **changed** (e.g. storing dates in a new timezone convention).
- Drive **file-layout change** (e.g. moving `notepad_entries/yyyy/mm/` elsewhere).
- Media **file-format change** (e.g. photos moving from JPEG to AVIF).

### Bump `schema_version.minor`

Forward-compatible. Older readers ignore unknown fields; new readers get full value.

- New column or JSON field **added** to a payload shape.
- New file or folder **added** to the Drive tree.
- New optional manifest top-level key.

### Coupling with SQLite migration numbers

`migration_version` on the manifest tracks the highest SQLite migration file the writing device has applied. It's an advisory hint, not a gate: two devices at the same `schema_version.major.minor` can be on different `migration_version`s temporarily, and that's fine — SQLite migrations are per-device concerns.

A reader only gates on `schema_version.major`. It uses `migration_version` to log "this backup was written by a device at migration v7; we're at migration v9, so we have superset columns available" but never refuses a restore on that basis alone.

### Never silently downgrade

A device running schema version 2.3 that encounters a manifest at 2.5 **reads** the manifest (all fields it understands are valid under 2.x) but **does not write back at 2.3** until the app updates to 2.5. If the user forces a write while out-of-date, block with the update prompt.

---

## `drive.file` scope + reinstall

Per OPEN_QUESTIONS §6, the `drive.file` scope grants the app access to files created by **this OAuth client ID** for the authenticated user. Reinstalling the app doesn't change the client ID; the user reauthorizes on first sign-in of the new install and regains access to the prior install's files. Sign-out + sign-in on the same install also retains access.

**What breaks access:**
- Revoking the app in the user's Google account page → next sign-in reauthorizes, access is restored.
- Changing the OAuth client ID (we bump bundle ID, ship a fork, etc.) → old files are invisible. Don't do this.
- User manually moves / renames the `Releaf/` folder → the app still owns the files, can list them by mime-type query, but can't assume path stability. Mitigation: on first sign-in of a reinstall, query by `appProperties.releaf_root = true` on `manifest.json` (we set it on upload) and reconstruct the tree from paths inside the manifest.

**Caveat:** this is our read of Google's docs; a manual test is scheduled during build step 10 — install, sign in, write a test capture, sign out, uninstall, reinstall, sign in, assert the capture is visible. If it turns out reinstall breaks access, we have to either pivot to `drive.appdata` (same semantics, invisible folder) or the broader `drive` scope (violates our constraint). Neither is expected.

---

## PDF exports (OPEN_QUESTIONS §9)

Default: **local-only.** `exports/pdf/*.pdf` files are **not uploaded** by the sync worker.

Settings → Export → "Back up PDF exports to Drive" toggle flips the behavior. When on, each new PDF emitted by the in-app export flow is uploaded to `exports/pdf/` after generation; a manifest `minor` version bump records the added content.

When off (default), PDFs live only in `filesDir/releaf/exports/pdf/` (Android) / `.documentDirectory/releaf/exports/pdf/` (iOS) and are reachable via the system share sheet. Deleting one is a local file-system delete; no tombstone, no manifest update.

Rationale: PDFs are derivatives of entries that already round-trip losslessly through the manifest, so backing them up wastes Drive quota in the common case.

---

## What is **not** in Drive

Deliberately kept local-only (never serialized to Drive):

- **Thumbnails** (`cache/thumbs/*`).
- **Full-text index** (`fts_*` SQLite virtual tables rebuilt from payloads on restore).
- **User preferences** that are device-local: launcher continue-scope, dark-mode override (none in v1 anyway), capture quality slider.
- **Secure credentials**: Google access tokens, user-supplied AI API keys. These live in Keychain / EncryptedSharedPreferences **only** and never touch Drive.
- **AI-generated summaries** — captured per OPEN_QUESTIONS §8 only store the latest `ai_summary` field in the capture payload; raw provider request/response logs stay local.
- **Sync state internals** — `sync_state` table rows are per-device bookkeeping; another device reconstructs them from its own fetches.

---

## Migration from v1 (Inkcreate) layout

Users upgrading from an Inkcreate Drive folder to Releaf v2:

1. On first v2 launch, detect `Inkcreate/` folder presence in the user's Drive (via search by exact name, since we own the file via `drive.file`).
2. If found: offer a one-time "Import from Inkcreate" flow in Settings.
3. The importer walks the v1 tree, maps each `page.json` to the v2 page + captures + reference_links shape, serializes fresh, writes under `Releaf/`.
4. The v1 tree is left untouched — if the import fails or the user wants to bail, the old data is still there.
5. Once the v2 tree is populated, bump `schema_version.major` stays at 2.0; the import isn't a schema migration, it's a data migration.

This is scoped as a stretch feature for v2 and is not in the v1 shipping plan. Flagged in HANDOFF.md if we pick it up.

---

## FAQ

**Q. Why canonical Markdown in `notes` and not HTML?**
Per OPEN_QUESTIONS §4. HTML round-trips badly (formatting drift, XSS surface on render). Canonical CommonMark via `cmark-gfm` round-trips byte-identical once normalized; FTS5 indexes the raw Markdown; PDF export pipes it through the same renderer used on screen.

**Q. Why is `manifest.json` a single file rather than an append-only log?**
Drive has no atomic append. Every mutation is a full overwrite anyway. A single file with all entity checksums lets us diff in a single read; an append log would require tail-walking on every sync.

**Q. What if `manifest.json` gets corrupted?**
The sync worker reconstructs it from the folder tree by listing `notebooks/`, `notepad_entries/`, etc. under `drive.file` ownership and re-hashing each entity's payload. Slow (one GET per entity) but bounded — a user with 5000 entities would take 1-2 minutes on typical home broadband. Treated as a recovery path, not steady-state.

**Q. Won't Drive's rate limits bite us on a big first sync?**
Drive v3 allows 1000 queries per 100 seconds per user by default. Batch uploads are gated at 10 concurrent; exponential backoff on 429. Our heaviest case — restoring 10 years of daily entries with a capture per day — is ~3600 entities + ~3600 media files = ~7200 requests. At 10 concurrent with ~200 ms average round-trip, that's ~2.5 minutes. Reasonable.

**Q. Where do we put the device ID?**
`device_id` is a per-install UUIDv7 generated on first launch, stored in Keychain / EncryptedSharedPreferences, and written to the manifest and tombstones. Uninstall → reinstall = new `device_id`, which is fine; the manifest doesn't care about device continuity, only about which device wrote the last mutation.
