# Open Questions — v2 build kickoff

Companion to `PROMPT.md`. Twelve items where the spec is ambiguous or under-specified. Each has a proposed answer; flag any you disagree with and we'll revise before DDL lands.

> "Propose an answer rather than guessing silently." — `PROMPT.md` deliverable (6)

Status legend: **🟢 proposed** (awaiting user review) · **🟡 deferred** (depends on a pending decision elsewhere) · **🔴 blocker** (must resolve before DDL).

---

## Schema-critical

### 1. 🟢 Merge re-parent + exactly-one-parent CHECK

**Question.** The `captures` CHECK constraint requires exactly one of `{parent_page_id, parent_notepad_entry_id, parent_daily_log_id}` to be non-null. The merge algorithm (step 3) says "update `parent_notepad_entry_id`" but doesn't explicitly zero out a former page/daily-log parent. Does a re-parent write all three columns, and is the CHECK deferrable?

**Proposed answer.** Every re-parent — in both merge and move-to-notebook — writes a full triple in a single UPDATE statement: target FK set, other two FKs NULL. The CHECK is **not deferred**; it holds row-by-row during the transaction. Any mid-transaction state that would violate the CHECK is an algorithm bug.

```sql
UPDATE captures
   SET parent_page_id          = ?,  -- or NULL
       parent_notepad_entry_id = ?,  -- or NULL
       parent_daily_log_id     = ?,  -- or NULL
       updated_at              = CURRENT_TIMESTAMP,
       dirty                   = 1
 WHERE id = ?;
```

---

### 2. 🟢 `project_id` behavior on merge

**Question.** When merging two `NotepadEntry` rows with different `project_id` values, which project does the surviving (primary) entry keep? And what happens to re-parented captures' own `project_id` values?

**Proposed answer.** Primary entry's `project_id` wins (unchanged by merge). Re-parented captures **retain their own `project_id`** — captures were always independently project-taggable, so a merge shouldn't silently retag them. Same policy applies on move-to-notebook: page inherits source entry's project; captures keep their own.

---

### 3. 🟢 Shared migration numbering — enforcement mechanism

**Question.** The spec says "reviewers verify v<N> on iOS == v<N> on Android." That's a human-checked invariant. How do we enforce it in CI?

**Proposed answer.** Single source-of-truth SQL file per migration under `design-system/migrations/vN_<slug>.sql`. Both platforms ship a thin adapter:

- **iOS (GRDB):** a migration loader reads the `.sql` file at build time and calls `migrator.registerMigration("vN_<slug>") { db in db.execute(sql: ...) }`.
- **Android (Room):** a `RoomDatabase.Callback` (or a custom `Migration(N-1, N)` via `execSQL`) reads the same file.

CI check (`scripts/check-migrations.sh`): asserts that the set of `vN_<slug>.sql` files in `design-system/migrations/` is identical to the migrations registered on each platform (parse the loaders). Fails if any platform is missing a migration or has an extra one.

FTS5 tokenizer + trigger SQL lives in the same file — one `.sql` per migration, no platform-specific forks. Platform-specific bits (e.g. Room-only annotations) stay in the adapter layer, not in the migration SQL.

---

### 4. 🟢 Markdown round-trip contract

**Question.** "Byte-identical where round-trip is defined" hedges but doesn't pin down the contract. `__bold__` and `**bold**` produce the same HTML — should the round-trip preserve the source form or normalize it?

**Proposed answer.** **Normalize to canonical CommonMark on save.** Pick one CommonMark canonicalizer per platform (`cmark-gfm` on both — it has Swift and JVM bindings). On every editor save:

1. Parse input → AST.
2. Render AST back to CommonMark (canonical form).
3. Persist canonical form. This is what FTS indexes and what PDF export consumes.

The round-trip contract then reads: **canonical-form Markdown → store → render → re-import produces byte-identical canonical-form Markdown.** Non-canonical input is *permitted but normalized on first save.*

Ship a 30-sample fixture covering every CommonMark node (heading levels 1–6, emphasis, strong, inline code, fenced code blocks with/without language, ordered and unordered lists, nested lists, block quotes, HR, link, image, escaped characters, trailing whitespace handling). Each sample round-trips byte-identical; add to the test suite.

---

### 5. 🟢 Manifest version bump policy

**Question.** Major vs minor bump — who decides, what counts as which?

**Proposed answer.** Fixed rules, no judgment calls:

**Bump major** (blocks older readers on restore):
- Column or field **removed**.
- Column/field **renamed** (treated as remove + add).
- Semantic of an existing field **changed** (e.g. storing dates in a new timezone convention).
- Drive **file-layout** change (e.g. moving `notepad_entries/yyyy/mm/` to a different path).
- Media **file-format** change (e.g. photos moving from JPEG to AVIF).

**Bump minor** (forward-compatible):
- New column or JSON field **added**. Readers must ignore unknown keys.
- New file or folder added to the Drive tree.
- New optional manifest top-level key.

Never silently downgrade a manifest. On major mismatch: full block with "Please update Releaf to restore this backup." No partial-restore path.

---

## Implementation-critical

### 6. 🟢 `drive.file` scope across reinstall

**Question.** After uninstall + reinstall + same Google account, can the app still list and read the files it created in the previous install?

**Proposed answer (subject to confirmation).** Yes. Google Drive's `drive.file` scope grants access to files **created by this OAuth client ID** for the authenticated user. Reinstalling the app does not change the client ID. Revoking OAuth consent in the user's Google account page and re-granting does not clear the app's historical file ownership — those files remain accessible on re-authorization.

**Caveat.** This is our read of the Drive docs; it's worth a manual test early in build step 10 (sync implementation). Test: install app, sign in, create a test file in `Releaf/`, sign out, uninstall, reinstall, sign in, assert the file is listable. Log as a story-level task.

If it turns out reinstall breaks access (it shouldn't), the fallback is to document that restore requires the prior install's token to have been exported — which is effectively a non-starter for the "restore on reinstall" UX. We'd need `drive.appdata` or the broader `drive` scope, both of which violate the `drive.file`-only constraint.

---

### 7. 🟢 Voice chunking — silence detection

**Question.** "Chunk audio at silence boundaries and transcribe each chunk independently." What's the silence detector? And what's the hard upper bound when no silence is found?

**Proposed answer.**

- **iOS:** `AVAudioEngine` tap on the input node computes RMS over a 100 ms window. Silence = RMS < **−45 dBFS** for ≥ **800 ms**. At a silence boundary, submit the previous chunk to `SFSpeechRecognizer` and open a new chunk.
- **Android:** `AudioRecord` with the same 100 ms RMS + threshold + duration logic.
- **Hard cap:** if no silence is found for **55 seconds**, force a cut regardless. iOS `SFSpeechRecognizer` live budgets are typically ~60 s; 55 s leaves headroom.

Tuning knobs kept in a single constants file so the thresholds can be adjusted without touching recognizer code.

---

### 8. 🟢 EXIF location strip — UX placement

**Question.** Where does the opt-in live, and does it apply to both photos and scans?

**Proposed answer.** Single toggle at **Settings → Capture → "Include photo location"**, defaults to **ON**. Applies identically to `kind='photo'` and `kind='scan'` (scans rarely carry GPS but consistency > surprise). Stored in `user_settings`.

When OFF: EXIF GPS tags (and iOS HEIF location) are stripped before the capture blob is written to disk. No round-trip to settings; apply once at capture-time so downstream Drive uploads never carry location accidentally.

When ON: EXIF preserved unmodified. Surfaced on the capture detail screen as a small location chip when present, tap-to-open-maps.

---

### 9. 🟢 PDF export sync scope

**Question.** Are `releaf/exports/pdf/` files synced to Drive, or local-only?

**Proposed answer.** **Local-only by default.** PDFs are derivatives — the source Markdown + captures are already in the manifest, so the PDF can always be regenerated. Synced PDFs waste Drive quota.

**Settings → Export → "Back up PDF exports to Drive"** toggle for users who want historical export archival. Default OFF.

---

### 10. 🟢 Conflict resolver — "merge-manually" UX

**Question.** The spec says the user picks "local / remote / merge-manually" but doesn't describe merge-manually.

**Proposed answer.** Two-option sheet on banner tap:

1. **Keep local** — clear `conflict_stub`, do nothing else. Remote changes discarded on this device.
2. **Keep remote** — overwrite `notes` with `conflict_stub.remote_notes`, clear stub.


---

### 11. 🟢 Sparse positions + concurrent-insert race

**Question.** `COALESCE(max(position), 0) + 1024` inside a transaction — can two concurrent inserts hand out duplicate positions?

**Proposed answer.** No, given a single-writer model:

- **GRDB (iOS):** writes are serialized on the database queue. Two concurrent inserts execute sequentially.
- **Room (Android):** the repository layer confines all writes to a single writer coroutine (`Dispatchers.IO` with a dedicated single-thread context for the DAO). Two concurrent callers suspend until the write slot is free.

Positions are therefore unique within `(parent, kind)` by construction. No UNIQUE constraint needed. If a future change introduces a multi-writer model (unlikely for a local app), revisit with a `SELECT ... FOR UPDATE` equivalent.

---

## UX-shaped

### 12. 🟢 Batch conflict resolution after long offline

**Question.** If a user edits locally while another device edits remotely, and they reconcile after a week, there could be 20+ conflict stubs. How is the UX?

**Proposed answer.** Aggregated banner — "20 entries have remote changes. Review →" — opens a dedicated **Conflicts** screen (listed under Settings as well). The screen is a list of entries with unresolved `conflict_stub`, grouped by date, each row tappable to the per-entry resolver from (10). A "Keep all local" / "Keep all remote" bulk action is available with a single hold-to-confirm gesture.

No per-entry banner spam. Conflicts count also appears as a subtle badge on the Settings tab in the BottomNav.

---

## Meta

### Recommended resolution path

1. User reads this doc, flags disagreement inline (comment-style edits or a short chat message).
2. Once all items are 🟢 / 🟡 with confirmed answers, the DDL deliverable proceeds against them.
3. Items may reopen mid-implementation if the answer turns out to be wrong — flag in HANDOFF.md and revise here.

### Not asked but worth flagging

- **Dark-mode color pairs** are undefined. Each color token needs a dark counterpart. Propose during the token-pipeline step, not here.
- **Reference-link metadata fetch fallback** (some sites reject non-browser UAs or lack OG tags). Deferred to build step 6 — default to "show URL only" when fetch fails, no error banner.
- **Android 14+ `SCHEDULE_EXACT_ALARM` first-prompt copy.** Deferred to build step 8; copy lives in `strings.xml`.
