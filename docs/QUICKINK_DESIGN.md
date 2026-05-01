# QuickInk — engineering design follow-up

Companion to [`QUICKINK_PROPOSAL.md`](./QUICKINK_PROPOSAL.md). The
proposal locks **what** moves where; this doc nails down **how** the
three highest-risk pieces work before any code moves:

1. `SyncDataSource` — the protocol that lets the shared `SyncRepository`
   serve both Releaf and QuickInk without knowing either app's tables.
2. The OCR pipeline — scan → recognize → persist → mirror to FTS → sync.
3. The schema-diff CI check — keeps shared columns aligned across the
   forked Releaf and QuickInk migrations.

These are the pieces where getting it wrong means re-doing work in
both apps. Worth a doc.

---

## 1. SyncDataSource — the per-app sync surface

### 1.1 Why this exists

Releaf's current `SyncRepository.swift` / `SyncRepository.kt` reaches
straight into the GRDB / Room database, knows the names of every table
(`notebooks`, `chapters`, `pages`, `notepad_entries`, `captures`, `tasks`,
…), and walks them looking for `dirty = 1` rows. That works when there's
exactly one app — the sync repo can be Releaf-shaped because Releaf
*is* the only consumer.

The moment QuickInk shows up, two things happen:

- QuickInk's schema is a strict subset (no notebooks/chapters/pages/tasks/
  reminders) plus one extra table (`ocr_results`). Hard-coded table
  names break.
- Even within the shared tables, each app may want to push different
  metadata to its own Drive folder (Releaf → `Releaf/`, QuickInk →
  `QuickInk/`).

`SyncDataSource` is the seam. The shared `SyncRepository` no longer
opens the database directly; it asks a protocol object for "what's
dirty, give me the bytes, here's what came back from Drive, please
apply." Each app implements that protocol against its own tables.

### 1.2 Protocol contract

Same shape on both platforms. iOS uses `protocol`, Android uses
`interface`. Async on both.

```swift
// shared/ios/ReleafCore/Sources/ReleafCoreSync/SyncDataSource.swift

public protocol SyncDataSource: Sendable {

    // ---- Identity / configuration ----

    /// Top-level Drive folder name ("Releaf", "QuickInk").
    var driveRootFolderName: String { get }

    /// Schema version this app speaks. Embedded into the manifest.
    /// Other-device sync refuses to apply a manifest written by a
    /// newer schema (versionBlocked path).
    var schemaVersion: Int { get }

    /// Human-readable app identifier ("releaf" / "quickink"). Tagged
    /// onto manifest entries so multi-app future debugging is easier.
    var appId: String { get }

    // ---- Outbound: collect dirty rows ----

    /// Snapshot of every locally-dirty entity the sync worker should
    /// push this round. Implementations stream from each entity's
    /// dirty index. Pagination: caller-bounded; the worker calls
    /// `nextDirtyBatch(after:limit:)` until it gets back fewer than
    /// `limit`.
    func nextDirtyBatch(after cursor: SyncCursor?, limit: Int) async throws -> DirtyBatch

    /// Tombstones to propagate this round. Same paging shape.
    func nextTombstoneBatch(after cursor: SyncCursor?, limit: Int) async throws -> TombstoneBatch

    // ---- Inbound: apply remote changes ----

    /// Apply a remote upsert. The data source is responsible for:
    ///   - decoding `payload` per its own canonical schema
    ///   - last-write-wins comparison against the local row
    ///   - clearing `dirty` if the remote write supersedes ours
    func applyRemoteUpsert(_ change: RemoteUpsert) async throws

    /// Apply a remote tombstone. Idempotent.
    func applyRemoteTombstone(_ tombstone: RemoteTombstone) async throws

    // ---- Bookkeeping ----

    /// Mark the given local IDs as cleanly synced (sets dirty = 0,
    /// stamps drive_file_id if newly uploaded).
    func markSynced(_ acks: [SyncAck]) async throws

    /// Most recent remote manifest etag the app has applied. Used by
    /// the worker to early-exit when Drive hasn't changed.
    func lastAppliedManifestEtag() async throws -> String?

    func setLastAppliedManifestEtag(_ etag: String) async throws
}

public struct DirtyBatch: Sendable, Equatable {
    public let entries: [DirtyEntry]
    public let nextCursor: SyncCursor?
}

public struct DirtyEntry: Sendable, Equatable {
    /// Stable, app-defined kind tag — "notepad_entry", "capture",
    /// "ocr_result", "notebook", … Used as the manifest bucket.
    public let kind: String
    /// UUIDv7. Not Drive's file ID.
    public let id: String
    /// Canonical-JSON bytes ready for upload. The data source did
    /// the serialisation; the sync worker just moves bytes.
    public let payload: Data
    /// `updated_at` from the local row. Lets the worker order
    /// uploads when batching.
    public let updatedAt: Date
}

public struct TombstoneBatch: Sendable, Equatable { … }
public struct SyncCursor: Sendable, Equatable { public let opaque: String }
public struct RemoteUpsert: Sendable, Equatable { let kind: String; let id: String; let payload: Data; let updatedAt: Date }
public struct RemoteTombstone: Sendable, Equatable { let kind: String; let id: String; let deletedAt: Date }
public struct SyncAck: Sendable, Equatable { let kind: String; let id: String; let driveFileId: String }
```

The Kotlin sibling is the same shape with `suspend` instead of `async
throws` and `Result<…>` for paging cursors.

### 1.3 Why this contract

- **Bytes-not-rows boundary.** The sync repo never sees a row, only an
  opaque `(kind, id, canonical-JSON)` triple. That's deliberate — it's
  what Drive sees, and it lets each app evolve its row schema without
  touching the shared sync code.
- **Kind is a string, not an enum.** Releaf has `notebook`/`chapter`/
  `page`/`notepad_entry`/`capture`/`task`/…; QuickInk has
  `notepad_entry`/`capture`/`ocr_result`. An enum here would force
  the shared module to know about every app's kinds. A string keeps
  the contract open and the manifest layout uniform.
- **Per-batch cursor, not per-table.** The worker doesn't know how
  many tables there are. The data source linearizes them however it
  wants and returns an opaque cursor for paging.
- **Last-write-wins lives in the data source.** It's per-row policy
  and it touches columns the shared layer can't introspect (e.g.
  Releaf's `archived_at` vs QuickInk's `deleted_at`-only soft delete).
  The sync repo just hands the data source `RemoteUpsert` and asks
  "is this newer than what you have?" implicitly.

### 1.4 Releaf's implementation

`apps/releaf/ios/Releaf/Sync/ReleafSyncDataSource.swift`. Walks the
existing `notebooks`, `chapters`, `pages`, `notepad_entries`,
`captures`, `tasks`, `reminders`, `contacts`, `call_history`,
`projects`, `tags`, `page_templates` tables in `dirty = 1` order
keyed by `updated_at`. Already-existing `CanonicalJson` serialisation
is reused; the per-table encoder lives next to the entity in
`apps/releaf/ios/Releaf/Data/<entity>/<Entity>Codec.swift` (split out
of the current monolithic codec).

### 1.5 QuickInk's implementation

`apps/quickink/ios/QuickInk/Sync/QuickInkSyncDataSource.swift`. Walks
`projects`, `tags`, `notepad_entries`, `captures`, `ocr_results`
only. Smaller, simpler, fewer tables — and notably, when a
`notepad_entry` is uploaded its mirrored OCR text is already in
`body`, so we don't need a separate "OCR upload" code path on the
sync side. The `ocr_results` row carries the structured positional
data for searchable-PDF reconstruction; the human-readable body is
in the entry.

### 1.6 Refactor sequence (stepwise so Releaf never breaks)

1. Land `SyncDataSource` protocol + supporting types in
   `ReleafCoreSync` with **no** implementation.
2. Add `ReleafSyncDataSource` inside Releaf and rewire `SyncRepository`
   to take it as a constructor parameter. Default-construct
   `ReleafSyncDataSource` at `SyncEnvironment.shared.install(...)`
   so existing call sites are unchanged.
3. Run Releaf's existing manual sync flows + the canonical-JSON unit
   tests against the refactored path. Sync-failure-rate metric should
   be unchanged.
4. Strip the now-dead "reach into the DB directly" code from
   `SyncRepository`.
5. Now QuickInk can implement its own `SyncDataSource` and reuse the
   same `SyncRepository`.

This is the highest-risk refactor in the whole QuickInk plan, hence
the staging — Releaf must never go a release without working sync.

---

## 2. OCR pipeline — end to end

### 2.1 Full flow

```
            ┌──────────────────────────────────────┐
            │  User taps Scan on the camera-first  │
            │  Home screen.                        │
            └─────────────────┬────────────────────┘
                              │
    iOS: VNDocumentCameraViewController            Android: GmsDocumentScanning
    (multi-page, edge detection,                   (multi-page, RESULT_FORMAT_PDF +
    perspective correction, all OS)                 RESULT_FORMAT_JPEG, all OS)
                              │
                              ▼
            ┌──────────────────────────────────────┐
            │  onComplete(pages: [UIImage|Uri],    │
            │             pdfURL, previewURL)      │
            └─────────────────┬────────────────────┘
                              │
                              │ persist via AttachmentStorage
                              │ (PDF, per-page JPEGs)
                              ▼
            ┌──────────────────────────────────────┐
            │  Insert capture row                  │
            │    captures(id, kind='document',     │
            │             pdf_uri, page_count,     │
            │             dirty=1)                 │
            │  Insert notepad_entry row            │
            │    notepad_entries(id, body='',      │
            │             capture_id=…, dirty=1)   │
            └─────────────────┬────────────────────┘
                              │
                              │ enqueue OCR job
                              │ (per-page parallelism, see 2.3)
                              ▼
            ┌──────────────────────────────────────┐
            │  OcrPipeline (in ReleafCoreScan)     │
            │  for each page i:                    │
            │    1. recognize(pageImage[i])        │
            │       → OcrResult(text, blocks,      │
            │                   confidence, lang)  │
            │    2. INSERT ocr_results(            │
            │         capture_id=…, page_index=i,  │
            │         text, blocks_json, …)        │
            └─────────────────┬────────────────────┘
                              │
                              │ all pages done →
                              ▼
            ┌──────────────────────────────────────┐
            │  Mirror full text into entry.body    │
            │    UPDATE notepad_entries            │
            │       SET body = joinedText,         │
            │           dirty = 1                  │
            │     WHERE id = entry_id              │
            │                                      │
            │  FTS5 trigger picks up the update    │
            │  and re-indexes. Search now finds    │
            │  scanned content.                    │
            └─────────────────┬────────────────────┘
                              │
                              │ mark capture clean for-OCR
                              │ (dirty bit on captures stays set
                              │  until Drive uploads the PDF)
                              ▼
            ┌──────────────────────────────────────┐
            │  SyncWorker / SyncScheduler ticks    │
            │  on next opportunity (mutation-      │
            │  triggered or 15-min periodic).      │
            │  QuickInkSyncDataSource hands the    │
            │  worker:                             │
            │    - notepad_entries row             │
            │    - captures row                    │
            │    - ocr_results rows                │
            │  All upload to My Drive/QuickInk/.   │
            └──────────────────────────────────────┘
```

### 2.2 The shared `OcrPipeline` API

Lives in `ReleafCoreScan/OcrPipeline.swift|kt`. Engine-agnostic so
each platform's recognizer plugs in:

```swift
public protocol OcrEngine: Sendable {
    var engineId: String { get }              // "apple-vision" | "mlkit-latin-v2"
    var engineVersion: String? { get }
    func recognize(image: PlatformImage) async throws -> OcrResult
}

public struct OcrResult: Codable, Equatable, Sendable {
    public let text: String                   // joined paragraphs, '\n\n' between blocks
    public let blocks: [OcrBlock]
    public let language: String?              // mean-confidence-weighted dominant lang
    public let confidence: Double             // mean across blocks
}

public actor OcrPipeline {
    public init(engine: OcrEngine, store: OcrResultStore)

    /// Recognize a multi-page scan. Pages run with bounded
    /// concurrency (default 2; iOS Vision is CPU-bound, ML Kit is
    /// GPU-assisted but still benefits from queueing). On any
    /// per-page failure the row is written with text="" and
    /// confidence=0 so the entry exists in the DB and sync still
    /// captures the (empty-text) record — better than silently
    /// dropping a page.
    public func recognize(
        captureId: String,
        pageImages: [PlatformImage],
        concurrency: Int = 2
    ) async -> OcrPipelineResult
}

public struct OcrPipelineResult: Sendable {
    public let capturedAt: Date
    public let pageCount: Int
    public let succeeded: Int
    public let failed: Int
    public let joinedText: String
    public let elapsedMs: Int
}

public protocol OcrResultStore: Sendable {
    /// Persist one page's result. Implementations write to the
    /// app's `ocr_results` table. Idempotent on
    /// (capture_id, page_index) — re-running OCR overwrites.
    func upsert(captureId: String, pageIndex: Int, result: OcrResult, engineId: String, engineVersion: String?) async throws
}
```

The pipeline doesn't write to `notepad_entries.body` itself — that's
a QuickInk policy that lives in the QuickInk app code. ReleafCore
provides the mechanism (recognize + store positional data); QuickInk
glues the joined text onto the entry.

### 2.3 Concurrency + cancellation

- Per-scan concurrency capped at 2 by default. Pages within one scan
  are independent so this is just a CPU/throughput tradeoff. iOS
  Vision benchmarks show diminishing returns past 2 on A14+; Android
  ML Kit similar.
- Cancellation: if the user navigates away from the scan-result screen
  before OCR completes, we **don't** cancel — the pipeline runs to
  completion in the background. The scan happened; the user expects
  the text to be there when they next open the entry. A canceled OCR
  pass would mean a half-OCRed entry, which is worse than a 30-second
  delay.
- Background runtime budget: iOS BGProcessing task / Android WorkManager
  expedited work with a 10-minute budget. A typical 5-page scan
  finishes in ~3 s; the budget is for pathological cases (long PDFs,
  cold-start models).
- Failure isolation: per-page try/catch, no whole-scan failure on a
  single bad page. Failed pages get a row written with text=""
  and a `processing_failed_at` annotation in `blocks_json` so the
  user-facing UI can show "page 3 couldn't be read" without crashing.

### 2.4 The "rendered PDF on disk + structured text in DB" split

For every scan we end up with:

- `<attachments>/<capture-id>.pdf` — the rendered PDF, owned by
  AttachmentStorage. Sync uploads this byte-for-byte to Drive under
  `My Drive/QuickInk/captures/<capture-id>.pdf`.
- `captures` row pointing at the PDF on disk.
- N × `ocr_results` rows, one per page, with text + blocks JSON.
- 1 × `notepad_entries` row whose `body` is the joined text and whose
  `capture_id` links back.

This split means the *exported* PDF (when the user shares to system
share-sheet or to email) defaults to the rendered PDF — clean, no
weird text-layer artifacts. The OCR data is for in-app search and
for the searchable-PDF prototype behind the feature flag.

### 2.5 iOS-specific notes

`VisionTextRecognizer` will use `VNRecognizeTextRequest` with:

- `recognitionLevel = .accurate` (slower than `.fast`, much higher
  quality for noisy scans).
- `usesLanguageCorrection = true`
- `automaticallyDetectsLanguage = true` (iOS 16+ — already our floor)
- Bounding boxes from `VNRecognizedTextObservation.boundingBox`,
  normalized 0..1 with origin bottom-left → flipped to top-left in
  our `OcrBbox` to match Android's convention.

Per-observation `confidence` from `topCandidates(1).first?.confidence`.
Language guess from `topCandidates(1).first?.string`'s
`NSLinguisticTagger` (or just the `recognizedLanguages` list if
language detection succeeded).

### 2.6 Android-specific notes

`TextRecognizer.kt` widens its return type from `String?` to
`OcrResult?`. Existing Releaf callsites get `.text` off the result.
Bounding boxes from `Text.Element.boundingBox` → `OcrBbox` (already
top-left origin, no flip needed). Confidence from `Text.Element`
(API 24+, present in v2). Language detection: ML Kit Latin recognizer
doesn't expose language directly — we run a quick
`com.google.mlkit.nl.languageid` pass on the joined text after
recognition. Adds ~50ms; cheap.

---

## 3. Schema-diff CI check

### 3.1 What it enforces

Tables that exist in **both** `shared/design-system/migrations/v1_initial.sql`
and `shared/design-system/migrations/quickink/v1_initial.sql` must
have the same column list, types, defaults, and constraints. The
intent: shared types like `notepad_entries`, `captures`, `projects`,
`tags`, `sync_state`, `user_settings` move in lockstep.

Tables that exist in only one schema are fine — they're app-specific
by design.

### 3.2 Mechanism

A small Node script at
`shared/design-system/scripts/check-schema-drift.mjs`, invoked from
GitHub Actions on every PR that touches either migration file.

```js
// Pseudocode shape — actual implementation parses with sqlite-parser.
const releaf   = parseSchema('shared/design-system/migrations/v1_initial.sql');
const quickink = parseSchema('shared/design-system/migrations/quickink/v1_initial.sql');

const sharedTables = intersect(releaf.tables, quickink.tables);
const drifts = [];

for (const table of sharedTables) {
  const r = releaf.tableDef(table);
  const q = quickink.tableDef(table);

  if (!columnsEqual(r.columns, q.columns)) {
    drifts.push({ table, kind: 'columns', releaf: r.columns, quickink: q.columns });
  }
  if (!constraintsEqual(r.constraints, q.constraints)) {
    drifts.push({ table, kind: 'constraints', … });
  }
  if (!indexesEqual(r.indexesFor(table), q.indexesFor(table))) {
    drifts.push({ table, kind: 'indexes', … });
  }
}

if (drifts.length > 0) {
  console.error('Schema drift in shared tables:');
  console.error(formatHumanReadable(drifts));
  process.exit(1);
}
```

Output is a humans-can-read diff: "table `notepad_entries` columns
differ — Releaf has `archived_at TEXT`, QuickInk does not. Either add
it to QuickInk or move it to a Releaf-only table."

### 3.3 Workflow integration

- `.github/workflows/schema-drift.yml` (or whatever CI substrate is
  in use — repo currently has no CI config; we add this as part of
  this work).
- Runs on every PR that modifies `shared/design-system/migrations/**`.
- Failure is a hard block — no merge until the drift is justified
  (either via fixing the drift, or by removing the table from one
  schema if it was never meant to be shared).

### 3.4 Escape hatch

Some drift is intentional (e.g. Releaf's `notepad_entries` may grow a
`shelf_id` column QuickInk doesn't need). The script honours an
allowlist file at `shared/design-system/migrations/drift-allowlist.yaml`:

```yaml
notepad_entries:
  releaf_only_columns:
    - shelf_id            # Releaf-specific binding to shelf hierarchy
    - book_series_id
  reason: "Hierarchy fields not used by QuickInk; safe to diverge."
```

Allowlist entries require a `reason:` line and a code-owner approval
on the PR that adds them — keeps drift visible and intentional, not
silent.

---

## 4. Build sequence after this doc lands

This doc unblocks the same physical work that QUICKINK_PROPOSAL.md's
§4 described, with the protocol/pipeline/CI-check designs now nailed
down. Recap, in PR order:

1. **PR #1** — Phase 1 mechanical move
   (`apps/releaf/`, `shared/design-system/`).
2. **PR #2** — Add the schema-diff CI check (it has no consumers yet —
   runs against just the Releaf migration).
3. **PR #3** — Phase 2a: extract `ReleafCoreSync` with `SyncDataSource`
   protocol + `ReleafSyncDataSource` implementation. Releaf still
   builds and syncs; no behaviour change.
4. **PR #4** — Phase 2b: extract the rest of `ReleafCore` (DesignSystem,
   Data, Auth, Drive, Notes, Scan).
5. **PR #5** — Phase 3a: scaffold `apps/quickink/` (both platforms),
   stub screens, no real behaviour.
6. **PR #6** — Phase 3b: wire OCR pipeline — `VisionTextRecognizer`,
   `OcrPipeline`, `ocr_results` storage, FTS mirror. Searchable-PDF
   stub behind the feature flag.
7. **PR #7** — Phase 3c: MVP screens (onboarding, camera-first Home,
   notes list, editor, settings, export sheet).
8. **PR #8** — First QuickInk Internal/TestFlight build.

Each PR is reviewable on its own and rolls back independently. The
SyncDataSource refactor (PR #3) is the only one that touches Releaf's
critical path — the rest is purely additive.
