# QuickInk — survey + shared-package proposal

Status: **decisions locked, ready for Phase 1**. No new code has been
written yet — this doc proposes the repo restructure and module split for
spinning a sibling app ("QuickInk") off the Releaf codebase. All §8
open questions are now resolved (forked schemas, separate `My Drive/QuickInk/`
folder, `ReleafCore` shared-package name, Drive toggle on onboarding
screen 3, v1 ships rendered PDFs only with OCR data stored internally
behind a feature-flagged searchable-export prototype, bundle ID
`app.quickink.mobile`). Next pass extracts shared modules, scaffolds the
QuickInk apps, and wires the MVP flow (onboarding → camera → OCR → save).

---

## 1. What QuickInk needs from Releaf

QuickInk's MVP surface, mapped to capabilities that already exist somewhere
in Releaf:

| QuickInk MVP feature                | Releaf today                                                                                                  |
| ----------------------------------- | ------------------------------------------------------------------------------------------------------------- |
| Google Sign-In                      | `GoogleAuthClient` (protocol + stub + `RealGoogleAuthClient`), `AuthStore`, `KeychainTokenStore` (iOS) / `EncryptedSharedPreferences` (Android), Credential Manager + AuthorizationClient binding |
| Drive backup + sync                 | `DriveClient` (protocol + `URLSessionDriveClient` / `OkHttpDriveClient`), `SyncRepository`, `SyncWorker` / `SyncScheduler`, `Manifest`, `DrivePath`, `SyncStateStore`, `CanonicalJson` |
| Notes data model + storage          | GRDB / Room SQLite; `notepad_entries` table from `v1_initial.sql` is the *exact* shape we need (rich-text body, project, tags, FTS5 search, soft-delete, sync dirty bit) |
| Notes list + search                 | `NotepadListViewModel` (iOS) / `NotepadListViewModel` (Android) — already debounces FTS5 search, undo toast, soft delete. Reuse verbatim. |
| Notes editor                        | `NotepadEditorViewModel` + `NotesEditorSheet` + `RichTextEditor` + `RichTextFormatBar` — CommonMark round-trip already done |
| Multi-page scan w/ edge detection + perspective fix | iOS `DocumentScannerView` (VisionKit `VNDocumentCameraViewController` — multi-page, edge detection, perspective correction all built into the OS view); Android `EditorSections.kt`'s ML Kit `GmsDocumentScanning` block |
| OCR → editable text                 | Android: `TextRecognizer` (ML Kit Latin v2, on-device). **iOS: not present today** — needs to be added (Apple Vision `VNRecognizeTextRequest`) |
| Searchable PDF                      | Android scanner already produces a PDF (`RESULT_FORMAT_PDF`); iOS `DocumentScannerView` renders a multi-page PDF via `UIGraphicsPDFRenderer`. To make it searchable, we'd need to overlay the OCR text layer — new code (see §6) |
| Export: PDF, plain text, Markdown, image, share sheet | iOS `PdfExporter` is page-shaped (renders editorial page → PDF). Reusable as-is for the rendered-PDF path. Plain text + Markdown are trivial Data writes; share sheet is `UIActivityViewController` / Android `Intent.ACTION_SEND` — new but tiny |
| Settings: account, Drive sync, defaults | `DriveSettingsSection` (both platforms) — reuse verbatim. Account row is already wired to `AuthStore` |
| Onboarding (3 screens)              | `OnboardingWizard` is a 10-step Releaf-flavoured wizard (uses `OnboardingTokens`, `OnboardingIllustrations`, `OnboardingQuickGuideCard`). Tokens + illustration scaffolding reusable; **content + step count: rebuild for QuickInk** (3 screens vs 10) |
| Camera-first home                   | New screen (Releaf's home is a multi-section dashboard with shelves, timeline, quick-capture, Trees-Saved hero — nothing camera-first) |

### Things QuickInk does NOT need (and we want to leave behind)

- Plant catalogue: `design-system/plants.json`, `DailyPlants.swift`,
  `AyurvedicCatalog.swift`, the `plants.json` resource declared on the
  `ReleafData` SwiftPM target
- Eco branding: `TreesSavedHeroView`, `TreesSavedStripView`,
  `LeafDropletGlyph`, `ReleafImpact`, `NotepadGardenTiles`
- Notebook hierarchy: `Shelf → BookSeries → Notebook → Chapter → Page`,
  `ShelfRepository`, `NotebookRepository`, `ChapterRepository`,
  `PageRepository`, the seven `CaptureMode`s, the variant-1 shelves UI
- Non-scan capture surfaces: `VoicePageRecorder`, `VoiceTranscriber`,
  `SpeechTranscriber`, `Contacts*`, `LocationProbe`, `CallHistory*`,
  `Tasks*`, `Reminders*`, `PageTemplate`
- The 10-step onboarding wizard's content (we keep the framework + tokens)
- Releaf logo / splash / brand assets (need separate QuickInk brand pass)

These stay in Releaf and don't move into the shared package.

---

## 2. Reusable-module catalogue

For each module: **Reuse** (drop in as-is), **Extract** (move into shared
package, both apps depend on it), **Adapt** (extract with light edits to
remove Releaf-specific assumptions like the "Releaf/" Drive folder name),
**Leave** (Releaf-only, not pulled into QuickInk), **Build new** (doesn't
exist yet — write for QuickInk, then likely backport to Releaf).

### iOS

| Module / file                                                  | Disposition | Notes |
| -------------------------------------------------------------- | ----------- | ----- |
| `DesignSystem/AppColors.generated.swift`, `AppTypography`, `AppSpacing`, `AppShadow`, `AppRadius`, `AccentPalette`, `AppMetrics.generated.swift`, `AppFontWeight+Env.swift` | Extract → `ReleafCoreDesignSystem` | Generated from `design-system/design-tokens.json`. Move generator + tokens.json under `shared/design-system/`. Generator extended to honour per-app overrides (`app.releaf.color.*` / `app.quickink.color.*`) so QuickInk's locked `canvas = #F5EEDF` and `textPrimary = #463C31` flow through codegen — see QUICKINK_BRAND_BRIEF §4.1 |
| `DesignSystem/Components/Card.swift`, `AppButton.swift`, `Breadcrumbs.swift`, `DotGridBackground.swift`, `NotebookRow`, `PagePreviewRow`, `StatGrid`, `PageHeaderControls`, `ActivityTimeline`, `CaptureFAB` (the generic ones) | Extract → `ReleafCoreDesignSystem` | Drop `NotebookRow`, `PagePreviewRow`, `ShelfTheme` for QuickInk's bundle but keep available to Releaf |
| `DesignSystem/Components/ReleafLogo*`, `ReleafLogoRow`, `LeafDropletGlyph` | Leave | Releaf branding |
| `DesignSystem/CaptureMode.swift` | Leave | Releaf's 7-mode capture — QuickInk only has scans |
| `Data/Auth/GoogleAuthClient.swift`, `AuthStore.swift`, `KeychainTokenStore.swift`, `RealGoogleAuthClient.swift`, `GoogleSignInBinding.swift` | Extract → `ReleafCoreAuth` | Pull as-is. AuthStore's `state` and `session` are app-agnostic |
| `Data/Drive/DriveClient.swift`, `URLSessionDriveClient.swift`, `DriveClientPath.swift`, `PdfExporter.swift` | Extract → `ReleafCoreDrive` | `PdfExporter` currently renders Releaf `Page` model — refactor signature to take `(title, body, capturedAt)` so QuickInk can call with Note shape |
| `Data/Sync/CanonicalJson.swift`, `DrivePath.swift`, `Manifest.swift`, `DeviceIdentity.swift`, `SyncStateStore.swift`, `SyncEnvironment.swift`, `SyncScheduler.swift`, `SyncRepository.swift` | Extract → `ReleafCoreSync` | `SyncRepository` references `ReleafDatabase` directly. Refactor to take a per-app `SyncDataSource` protocol so each app exposes its own dirty rows + tombstones |
| `Data/Notepad/IsoClock.swift`, `Uuidv7.swift`, `FtsQuery.swift` | Extract → `ReleafCoreData` | Pure helpers, zero coupling |
| `Data/Notepad/NotepadRepository.swift` (notepad_entries CRUD + FTS) | Extract → `ReleafCoreNotes` | Already aligned with the shape QuickInk needs — flat rich-text notes with tags/FTS |
| `Data/Notepad/DailyPlants.swift`, `AyurvedicCatalog.swift` | Leave | Releaf-only |
| `Data/Notebook/*`, `Data/Shelf/*`, `Data/Domain/Models.swift` (Shelf/BookSeries/Notebook/Chapter/Page/PageCounts/PageTemplate) | Leave | Releaf hierarchy — QuickInk doesn't need it |
| `Data/Activity/*`, `Data/CallHistory/*`, `Data/Contact/*`, `Data/Task/*` | Leave | Releaf-specific |
| `Features/Notepad/NotepadListViewModel.swift` | Extract → `ReleafCoreNotes` | UI-agnostic VM (uses `@Published`, no `View` types). QuickInk's notes-list screen binds to this same VM |
| `Features/Notepad/NotepadEditorViewModel.swift`, `NotesEditorSheet.swift`, `RichTextEditor.swift`, `RichTextFormatBar.swift`, `EditorModeToggle.swift`, `EntryDateRow.swift` | Extract → `ReleafCoreNotes` (VM) + `ReleafCoreDesignSystem` (editor views) | The editor stack is the most valuable reuse — already does CommonMark round-trip |
| `Features/Notepad/Sections/AttachmentStorage.swift` | Extract → `ReleafCoreData` | Path is `Application Support/Releaf/attachments` — parameterize as `Application Support/<bundle>/attachments` |
| `Features/Notepad/Sections/DocumentScannerView.swift` | Extract → `ReleafCoreScan` | The VisionKit wrapper is app-agnostic. Keep `onComplete(pdfURL, previewURL)` signature; add new optional `onComplete(pages: [UIImage])` overload so the OCR pass can run before flattening to PDF |
| `Features/Settings/DriveSettingsSection.swift` | Extract → `ReleafCoreDesignSystem` (or a new `ReleafCoreSettings`) | Already platform-mirrored, app-agnostic. Folder name string ("Releaf/") needs to come from a config |
| `Features/Onboarding/OnboardingTokens.swift`, `OnboardingIllustrations.swift`, `OnboardingEnvironment.swift`, `OnboardingQuickGuideCard.swift` | Extract → `ReleafCoreDesignSystem` | The token + illustration scaffolding is reusable. The 10-step `OnboardingWizard` itself is content — QuickInk re-builds the 3 screens against this scaffolding |
| `Features/Auth/SignInScreen.swift` | Adapt → `ReleafCoreAuth` | Logo + copy is Releaf — extract with branding parameters |
| `Features/Home/*`, `Features/Page/*`, `Features/Activity/*`, `Features/Capture/*`, `Features/Tasks/*`, `Features/Contacts/*`, `Features/CallHistory/*`, `Features/NotebookTab/*` | Leave | Releaf's home dashboard, notebook hierarchy, and 7-mode page detail. QuickInk builds new camera-first Home and Notes-list screens against the extracted VMs |
| `App/ReleafApp.swift`, `App/MainShell.swift`, `App/RootView.swift`, `App/SplashScreen.swift`, `App/HideBottomBar.swift`, `App/DrawerMetricsViewModel.swift` | Leave | Releaf's app shell. QuickInk gets its own `QuickInkApp.swift` + `QuickInkRoot.swift` |
| **iOS OCR pipeline** | **Build new** | iOS doesn't have OCR yet. Add `VisionTextRecognizer.swift` under `ReleafCoreScan` (Apple `VNRecognizeTextRequest`, on-device). Mirror Android's `TextRecognizer` API: `recognize(imageURL: URL) async -> String?` |

### Android

| Module / file                                                  | Disposition | Notes |
| -------------------------------------------------------------- | ----------- | ----- |
| `ui/theme/AppTheme.kt`, `AppColors.kt` (generated), `AppTypography`, `AppSpacing`, `AppFontWeights`, `AppAccent` | Extract → `:shared:designsystem` | Generated from `design-system/design-tokens.json` |
| `ui/components/Card`, `AppButton`, `AppToast`, `Breadcrumbs`, `DotGridBackground`, `RoundIconButton`, `RelativeTime`, `ScreenHeader`, `DeleteConfirmationDialog`, `editor/*` (toolbar, format bar, eraser icon, drawing overlay, etc.) | Extract → `:shared:designsystem` | Per-component decision below in §3 |
| `ui/components/NotebookRow`, `PagePreviewRow`, `ShelfTheme`, `ThemePickerSection` | Leave | Releaf hierarchy / theme picker |
| `auth/GoogleAuthClient.kt`, `AuthStore.kt`, `RealGoogleAuthClient.kt`, `GoogleSignInBinding.kt` | Extract → `:shared:auth` | App-agnostic |
| `data/drive/DriveClient.kt`, `OkHttpDriveClient.kt`, `DriveClientPath.kt` | Extract → `:shared:drive` | App-agnostic |
| `data/sync/CanonicalJson.kt`, `DrivePath.kt`, `Manifest.kt`, `DeviceIdentity.kt`, `SyncStateEntity/Dao.kt`, `SyncScheduler.kt`, `SyncRepository.kt`, `SyncWorker.kt` | Extract → `:shared:sync` | Same `SyncDataSource` refactor as iOS |
| `data/common/IsoClock.kt`, `Uuidv7.kt`, `FtsQuery.kt`, `WaveformSamples.kt` | Extract → `:shared:data` | Pure helpers (drop `WaveformSamples` from QuickInk's bundle — voice-only) |
| `data/common/AttachmentStorage.kt` | Extract → `:shared:data` | Parameterize the `releaf/attachments` path |
| `data/common/PhotosToPdf.kt` | Extract → `:shared:scan` | Multi-image → PDF, app-agnostic |
| `data/common/TextRecognizer.kt` | Extract → `:shared:scan` | ML Kit on-device OCR — perfect for QuickInk |
| `data/common/SpeechTranscriber.kt`, `NotesExport.kt` | Leave | Voice / drawing-export concerns |
| `data/notebook/*`, `data/shelf/*`, `data/contact/*`, `data/callhistory/*`, `data/task/*`, `data/reminder/*`, `data/perspective/*` | Leave | Releaf-specific |
| `features/notepad/NotepadListViewModel.kt` | Extract → `:shared:notes` | UI-agnostic VM |
| `ui/components/editor/EditScanDialog.kt`, `PdfPageViewerDialog.kt`, `BackgroundPickerPopover.kt`, `MoveToNotebookSection.kt`, `RichTextFormatBar.kt`, `DrawingToolbar.kt`, `WordCountFooter.kt`, `RubberEraserIcon.kt`, `NotesBackground.kt`, `EditorModeToggle.kt`, `DrawingOverlay.kt` | Mostly Extract → `:shared:designsystem` (editor); `MoveToNotebookSection` Leave | The editor stack moves; the move-to-notebook picker stays Releaf-only |
| `features/onboarding/OnboardingTokens.kt`, `OnboardingPreferences.kt`, `OnboardingWizard.kt` (scaffolding parts) | Extract tokens + illustration; rebuild content for QuickInk | Same as iOS |
| `features/settings/DriveSettingsSection.kt` | Extract → `:shared:designsystem` | Same as iOS |
| `features/home/*`, `features/notebook/*`, `features/page/*`, `features/contacts/*`, `features/callhistory/*`, `features/tasks/*`, `features/reminder/*` | Leave | Releaf shell |
| **Scanner integration glue** | Adapt → `:shared:scan` | Releaf's `EditorSections.kt` calls `GmsDocumentScanning` inline inside a 2000+ line file. Extract to a `DocumentScanner` Composable + a `DocumentScannerLauncher` (ActivityResultContract wrapper) that QuickInk and Releaf both call |

---

## 3. Proposed shared-package structure

### Monorepo layout

```
releaf/                                       ← repo root (existing)
├── apps/
│   ├── releaf/
│   │   ├── ios/                              ← Releaf iOS app target (was ./ios)
│   │   │   ├── Package.swift                 ← depends on ReleafCoreFeatures
│   │   │   └── Releaf/                       ← Releaf-only Features + App shell
│   │   └── android/                          ← Releaf Android app module (was ./android)
│   │       └── app/                          ← :apps:releaf
│   └── quickink/
│       ├── ios/
│       │   ├── Package.swift                 ← depends on ReleafCoreFeatures
│       │   └── QuickInk/
│       │       ├── App/                      ← QuickInkApp.swift, QuickInkRoot.swift
│       │       ├── Onboarding/               ← 3-screen wizard
│       │       ├── Camera/                   ← camera-first home
│       │       ├── Scan/                     ← scan flow + OCR view
│       │       ├── Notes/                    ← notes list + editor (thin wrappers
│       │       │                                over ReleafCore VMs)
│       │       └── Settings/                 ← thin Settings screen
│       └── android/
│           └── app/                          ← :apps:quickink
│               └── … parallel structure
├── shared/                                   ← NEW — both apps depend on these
│   ├── design-system/                        ← was ./design-system; promoted to canonical
│   │   ├── design-tokens.json
│   │   ├── DESIGN_SYSTEM.md
│   │   ├── migrations/
│   │   ├── fixtures/
│   │   └── scripts/
│   ├── ios/
│   │   └── ReleafCore/                          ← SwiftPM package
│   │       ├── Package.swift
│   │       └── Sources/
│   │           ├── ReleafCoreDesignSystem/      ← tokens, components, editor UI
│   │           ├── ReleafCoreData/              ← UUIDv7, IsoClock, FtsQuery, AttachmentStorage
│   │           ├── ReleafCoreAuth/              ← GoogleAuthClient + AuthStore + Keychain
│   │           ├── ReleafCoreDrive/             ← DriveClient + URLSession impl + PdfExporter
│   │           ├── ReleafCoreSync/              ← SyncRepository + Manifest + scheduler
│   │           ├── ReleafCoreNotes/             ← NotepadRepository + ListVM + EditorVM
│   │           ├── ReleafCoreScan/              ← DocumentScannerView + VisionTextRecognizer
│   │           └── ReleafCoreFeatures/          ← umbrella product re-exporting the above
│   └── android/
│       ├── settings.gradle.kts               ← root build, includes both apps + libs
│       ├── build.gradle.kts
│       ├── gradle/libs.versions.toml         ← single version catalog
│       └── shared/
│           ├── designsystem/                 ← :shared:designsystem
│           ├── data/                         ← :shared:data
│           ├── auth/                         ← :shared:auth
│           ├── drive/                        ← :shared:drive
│           ├── sync/                         ← :shared:sync
│           ├── notes/                        ← :shared:notes
│           └── scan/                         ← :shared:scan
└── docs/                                     ← cross-app docs (existing)
```

### iOS — SwiftPM package layout

`shared/ios/ReleafCore/Package.swift` declares one package with N library
products. Both Releaf and QuickInk add `ReleafCore` as a local package
dependency (`.package(path: "../../shared/ios/ReleafCore")`) and pick the
products they need:

- **Releaf** depends on `ReleafCoreFeatures` (everything) so the existing
  `import ReleafDesignSystem` / `ReleafData` calls become
  `import ReleafCoreDesignSystem` / `ReleafCoreData` (one-time renamed import
  pass — sed-able).
- **QuickInk** depends on `ReleafCoreDesignSystem`, `ReleafCoreAuth`,
  `ReleafCoreDrive`, `ReleafCoreSync`, `ReleafCoreNotes`, `ReleafCoreScan` (skips
  the umbrella product so the dep graph stays explicit).

Why one package with multiple products instead of separate packages: keeps
SwiftPM resolution fast (single resolution pass), keeps cross-target
refactors atomic, mirrors the Android `:shared:*` modules 1:1.

### Android — Gradle multi-module layout

`shared/android/settings.gradle.kts` becomes the root build:

```kotlin
include(
    ":shared:designsystem",
    ":shared:data",
    ":shared:auth",
    ":shared:drive",
    ":shared:sync",
    ":shared:notes",
    ":shared:scan",
    ":apps:releaf",
    ":apps:quickink",
)
```

Each `:shared:*` module is a `com.android.library` (since they all use
Android types — Context, Compose, Room) sharing the single
`gradle/libs.versions.toml`. Both `:apps:releaf` and `:apps:quickink` are
`com.android.application` modules, each with its own `applicationId`
(`app.releaf.mobile`, `app.quickink.mobile`), Manifest, splash, app icon.

### Shared SQLite migrations — forked schemas (decided)

`shared/design-system/migrations/v1_initial.sql` stays as Releaf's
canonical schema. QuickInk gets its own forked file at
`shared/design-system/migrations/quickink/v1_initial.sql`, trimmed to
only the tables QuickInk needs:

- `projects`, `tags`, `notepad_entries`, `captures`, `sync_state`,
  `user_settings`
- Plus the **OCR results table** (new — see §6.2)
- Drop `notebooks`, `chapters`, `pages`, `shelves`, `book_series`,
  `tasks`, `task_subtasks`, `reminders`, `call_history`,
  `page_templates`, `page_tags`, `todo_lists`, `todo_items`,
  `reference_links`, `capture_revisions`

Forking accepts the small drift risk (column-rename in Releaf doesn't
auto-propagate) in exchange for two cleanly-readable schemas. The
shared columns (IDs, timestamps, dirty bits, soft-delete) are conventions
encoded in the migration's header comment, not in code, so drift is
visible at PR time. We'll add a CI check that diffs the *shared* tables
between the two files (`projects`, `tags`, `captures`, `sync_state`,
`user_settings`, `notepad_entries`) and fails if their column lists
diverge — keeps drift visible without forcing one-file plumbing.

### Versioning

- Both apps + the shared package live in **one git repo** and share the
  same version label per release. No internal SemVer between the apps and
  ReleafCore — they're version-locked. Behaves like a small Bazel-style
  monorepo, not a published SDK.
- Releases are tagged `releaf-vX.Y.Z` and `quickink-vX.Y.Z` independently
  (so the App Store / Play Store metadata lines up), but both tags point at
  the same commit, and the shared package code at that commit is what both
  ship.
- This avoids the SDK-versioning trap ("QuickInk needs ReleafCore 1.4 but
  Releaf is on 1.2") at the cost of having to re-test the other app when
  you change anything shared. Reasonable tradeoff while there are exactly
  two apps.

### Ownership

Each shared module gets a `OWNERS` file (or CODEOWNERS entry) — proposed
defaults: design-system → design + mobile leads; auth/drive/sync → mobile
infra; notes/scan → product mobile. Worth setting before the first PR
against a shared module so cross-app reviews aren't ad hoc.

---

## 4. The repo restructure as a sequence of moves

Phase 0 (this PR): land **only** this proposal doc. No code moves. Get
agreement on §3 + §6 before anything else.

Phase 1 — physical move (mechanical, one PR per platform):

1. `git mv ios apps/releaf/ios`
2. `git mv android apps/releaf/android`
3. `git mv design-system shared/design-system`
4. Fix-up imports / build paths so Releaf still builds & previews.
5. (No shared package yet — `shared/ios/ReleafCore`, `shared/android/shared/*`
   don't exist. Releaf still owns its own `ReleafData`/`ReleafFeatures`/
   `:app` modules at this point.)

Phase 2 — extract shared package, Releaf depends on it:

6. Create `shared/ios/ReleafCore` SwiftPM package with the targets listed in
   §3, populated by `git mv` from `apps/releaf/ios/Releaf/{Data,Design...}`.
7. Update `apps/releaf/ios/Package.swift` to `.package(path:)` ReleafCore
   and rename imports.
8. Same for Android: create `shared/android/shared/*` library modules,
   `git mv` source into them, update `:apps:releaf`'s dependencies.
9. Releaf builds + tests pass with everything sourced from ReleafCore.
10. Refactor `SyncRepository` to take a `SyncDataSource` protocol so it's
    not GRDB/Room-coupled to Releaf's tables. Same for `AttachmentStorage`
    (parameterize folder name) and Drive folder name (`Releaf/` vs
    `QuickInk/`).

Phase 3 — scaffold QuickInk:

11. Create `apps/quickink/ios/QuickInk/{App,Camera,Scan,Notes,Onboarding,
    Settings}` and `apps/quickink/ios/Package.swift` depending on ReleafCore.
12. Same for Android (`:apps:quickink`).
13. iOS: add `VisionTextRecognizer` to `ReleafCoreScan` (the one piece of
    genuinely new code in the shared package).
14. Wire up the MVP flow: onboarding → camera-first Home → scan +
    background OCR → notes list → editor → Drive backup.
15. First TestFlight / Internal track build.

Phase 4 — cleanup & polish (out of scope for the initial spike):

16. Backport the `SyncDataSource` refactor's improvements to Releaf if any
    were Releaf-only patches.
17. Brand pass for QuickInk (logo, splash, icon) — separate design ticket.

---

## 5. Stripped-out Releaf-isms — concrete delete list

These don't get extracted into ReleafCore and they don't appear in
`apps/quickink/`:

iOS:

- `ReleafImpact.swift`, `TreesSavedHeroView.swift`, `TreesSavedStripView.swift`
- `LeafDropletGlyph.swift`
- `NotepadGardenTiles.swift`
- `DailyPlants.swift`, `AyurvedicCatalog.swift`, the `plants.json` resource
- All `Shelf*`, `BookSeries*`, `Notebook*`, `Chapter*`, `Page*` (the
  hierarchy data types — *not* the `Page` SwiftUI views unrelated to it)
- `Capture/CaptureView.swift`, `CaptureMode.swift`, `QuickCaptureSheet`,
  `BottomNav` (QuickInk has a one-tab camera-first shell)
- All voice/contacts/locations/call-history/tasks/reminders feature code
- `OnboardingWizard` (10-step) — replaced with QuickInk's 3-step
- `ReleafLogo*`, splash, app icon — replaced

Android: parallel list (mirror of the iOS one — same module names
without the `.swift`).

---

## 6. New code QuickInk needs

The shared package covers most of it. Net-new code:

### 6.1 OCR recognizer (iOS-only — Android already has it)

**`VisionTextRecognizer.swift`** under `ReleafCoreScan`. Apple Vision
`VNRecognizeTextRequest` wrapper. ~120 lines. Mirrors Android's
`TextRecognizer` API but returns the richer payload defined in §6.2 below
(blocks + bounding boxes + confidence + language) — *not* just a flat
string. We'll widen the Android `TextRecognizer.recognize()` signature
at the same time so both platforms return `OcrResult`, and the
"flat string" callsite in any Releaf code that exists today gets
`.text` off the new struct.

### 6.2 OCR result storage (both platforms — decided)

OCR output is persisted to disk so the editable text, the per-page
positional data, and the searchable-PDF prototype can all draw from one
source of truth. New table on QuickInk's forked schema:

```sql
CREATE TABLE ocr_results (
    id              TEXT PRIMARY KEY NOT NULL,           -- UUIDv7
    capture_id      TEXT NOT NULL REFERENCES captures(id) ON DELETE CASCADE,
    page_index      INTEGER NOT NULL,                    -- 0-based, per multi-page scan
    language        TEXT,                                 -- BCP-47 (e.g. "en-US")
    confidence      REAL,                                 -- 0.0–1.0, mean across blocks
    text            TEXT NOT NULL,                       -- full recognized text, paragraph breaks preserved
    blocks_json     TEXT NOT NULL,                       -- JSON array of OcrBlock (see below)
    engine          TEXT NOT NULL,                       -- "apple-vision" | "mlkit-latin-v2"
    engine_version  TEXT,
    created_at      TEXT NOT NULL,
    updated_at      TEXT NOT NULL,
    dirty           INTEGER NOT NULL DEFAULT 1 CHECK (dirty IN (0, 1)),
    deleted_at      TEXT,
    UNIQUE (capture_id, page_index)
);

CREATE INDEX idx_ocr_results_capture ON ocr_results (capture_id);
CREATE INDEX idx_ocr_results_dirty   ON ocr_results (dirty) WHERE dirty = 1;
```

`blocks_json` payload shape (the canonical struct in `ReleafCoreScan`):

```swift
struct OcrBlock: Codable, Equatable, Sendable {
    let text: String
    let bbox: OcrBbox          // normalized 0..1 in image space, origin top-left
    let confidence: Double     // 0..1
    let language: String?      // BCP-47, nil if engine didn't classify
    let kind: Kind             // .line | .paragraph | .word
}
struct OcrBbox: Codable, Equatable, Sendable {
    let x: Double; let y: Double; let width: Double; let height: Double
}
```

This shape covers both engines without losing precision: Apple Vision
returns `VNRecognizedTextObservation` boundingBoxes and per-observation
confidences, ML Kit returns `Text.Element` rects + per-block confidence
scores. Both flatten cleanly onto `OcrBlock`.

The full text is *also* mirrored into `notepad_entries.body` so the
existing FTS5 index picks it up — that's how QuickInk's notes search
finds words from scanned docs without a second FTS table. The
`ocr_results` row is the canonical positional record; `notepad_entries.body`
is the searchable + editable surface.

### 6.3 Searchable-PDF export — feature-flagged prototype

v1 ships **rendered PDFs only** (high-quality flat PDFs from
`UIGraphicsPDFRenderer` on iOS / Android scanner's built-in `RESULT_FORMAT_PDF`).
No invisible text layer in the v1 default export.

But we lay the groundwork now so the prototype is one toggle away:

- Add a build-time / runtime feature flag `searchablePdfExportEnabled`
  exposed in Settings → "Experimental" (off by default, off in App Store
  builds via a config flag).
- When the flag is on, the export sheet adds a "Searchable PDF" option
  alongside "PDF". That code path reads `ocr_results.blocks_json` per
  page and renders an invisible text layer (iOS: `PDFDocument` +
  `PDFAnnotation`; Android: TBD — leading candidate is iText community
  edition, fallback is Android `PdfDocument` with custom transparent
  text painting).
- Code lives in `ReleafCoreScan/SearchablePdfExporter.swift|.kt` from
  day one but is unreached when the flag is off. Lets us iterate on
  the searchable path against real OCR data without shipping a
  half-baked feature.

### 6.4 App shell + screens (both platforms)

- **App shells** — `QuickInkApp.swift` / `QuickInkApplication.kt`,
  `RootView` / `MainActivity`, navigation graph (camera-first → scan →
  notes-list).
- **3-screen onboarding** (welcome / permissions / Google sign-in
  with the Drive backup toggle on screen 3, decided). Built against
  existing `ReleafCoreDesignSystem` onboarding tokens + illustration
  scaffolding.
- **Camera-first Home**: opens directly to `DocumentScannerView`. On
  cancel, drops to the notes list. ~150 lines per platform.
- **Export sheet**: PDF / plain text / Markdown / image / system share.
  ~50 lines per platform — `UIActivityViewController` /
  `Intent.ACTION_SEND`. The "Searchable PDF" row is gated on §6.3's
  feature flag.
- **Background OCR pipeline glue**: scan completes → write PDF + JPEG
  to AttachmentStorage → enqueue OCR job → write `ocr_results` rows +
  mirror text into `notepad_entries.body` → mark dirty → existing
  `SyncWorker` / `SyncScheduler` propagates to Drive.

### 6.5 Estimated size

~1800 lines net-new across both platforms (up from the initial ~1500
estimate to account for the OCR result schema + storage layer + the
flagged searchable-PDF stub). Everything else is reuse via ReleafCore.

---

## 7. Success criteria — feasibility check

| Criterion                                      | Feasible with this plan?                                                                                       |
| ---------------------------------------------- | -------------------------------------------------------------------------------------------------------------- |
| App opens to camera in <2 s                    | Yes. `VNDocumentCameraViewController` cold-launch is ~400ms on A13+. Camera-first Home means no extra screen between launch and the camera. |
| Single-page scan → editable OCR text in <5 s   | Yes. VisionKit scan ~2 s for one page; on-device OCR ~500 ms (Apple Vision) / ~1 s (ML Kit). Total well under budget. |
| Drive backup runs silently in background       | Yes. Reuses `SyncWorker` / `SyncScheduler` — same model as Releaf. Drive uploads happen on a CoroutineWorker / BGTaskScheduler tick. |
| Visual parity with Releaf design system        | Yes. Same token file, same generated `AppColors` / `AppTypography`, same components. |
| Shared modules versioned cleanly between apps  | Yes via repo-locked monorepo (§3 versioning). |

---

## 8. Resolved decisions

All open questions are now answered. Locked-in choices:

| #  | Decision                            | Notes |
| -- | ----------------------------------- | ----- |
| A  | **Forked QuickInk schema** at `shared/design-system/migrations/quickink/v1_initial.sql` | CI diff-checks shared columns to surface drift at PR time (§3). |
| B  | **Drive folder = `My Drive/QuickInk/`** (separate top-level folder) | Independent OAuth grant + manifest; no entanglement with Releaf's `Releaf/` folder. |
| C  | **Shared package name = `ReleafCore`** | iOS targets: `ReleafCoreDesignSystem`, `ReleafCoreData`, `ReleafCoreAuth`, `ReleafCoreDrive`, `ReleafCoreSync`, `ReleafCoreNotes`, `ReleafCoreScan`, `ReleafCoreFeatures`. Android modules: `:shared:designsystem`, `:shared:data`, `:shared:auth`, `:shared:drive`, `:shared:sync`, `:shared:notes`, `:shared:scan`. |
| D  | **Drive backup toggle on screen 3** of onboarding (the sign-in screen, secondary opt-in below the Google button) | No separate post-sign-in confirm screen. |
| E  | **v1 ships rendered PDFs only**; OCR data persisted to `ocr_results` table; searchable PDF lives behind a `searchablePdfExportEnabled` feature flag (off by default) | Full design in §6.2 + §6.3. |
| F  | **Bundle / app ID = `app.quickink.mobile`** | Mirrors `app.releaf.mobile`. |

---

## 9. What happens after this doc is approved

1. Land Phase 0 (this doc) — already proposed.
2. PR #1 — Phase 1 mechanical move (`apps/releaf/`, `shared/design-system/`).
3. PR #2 — Phase 2 iOS ReleafCore extract.
4. PR #3 — Phase 2 Android `:shared:*` extract.
5. PR #4 — Phase 3 QuickInk scaffolding both platforms.
6. PR #5 — MVP flow wired (onboarding → scan → OCR → save → Drive).
7. First QuickInk build, ship to Internal track / TestFlight.

I'll wait for your sign-off on §3, §6, and the §8 open questions before
opening any of those.
