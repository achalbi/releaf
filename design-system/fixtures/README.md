# Fixtures

Cross-platform sample data for previews, in-memory repository fakes, and designer mocks. Pure JSON; no platform-specific bindings — both the iOS and Android preview layers parse the same files.

## Files

| File                                | Purpose                                                                                         |
| ----------------------------------- | ----------------------------------------------------------------------------------------------- |
| `canonical-json-fixture.json`       | Test input for the SHA-256 canonical-JSON serializer (per `docs/DRIVE_SCHEMA.md` §canonical).   |
| `sample-daily-logs.json`            | Seven days of realistic daily-log content. Use this in previews, screenshots, and demo seeding. |

---

## `sample-daily-logs.json`

Seven days, **April 22 → April 28, 2026**, with `2026-04-28` as "today" (matching the day shown in `design-system/daily-capture-mocks.html`).

### What's in it

| Day            | Character of the day             | What it exercises                                              |
| -------------- | -------------------------------- | -------------------------------------------------------------- |
| Wed Apr 22     | Busy creative day                | Notes + 3 photos + todo list with mixed completion state       |
| Thu Apr 23     | Meetings + business card         | Voice memo (transcribed), scan with OCR, contact, task w/ subtasks + reminder |
| Fri Apr 24     | Quiet                            | Notes-only entry + a single reference link                     |
| Sat Apr 25     | Outdoor / travel                 | Voice memo, photos with EXIF location, two locations, contact, task with reminder, `hide_completed: true` |
| Sun Apr 26     | Weekly review                    | Long structured note (markdown headings + lists), no captures  |
| Mon Apr 27     | Errands + work                   | Scan with PDF, voice memo, contact, location, task linked to capture |
| Tue Apr 28     | "Today" — matches Home mock      | Notes + 3 photos + voice memo + 3 todo items + 1 location      |

Cross-cutting: 2 projects (`Sabbatical 2026`, `Health`), 5 tags (`travel`, `writing`, `wildlife`, `errands`, `weekly`).

### Schema conformance

Each entity in `days[]` carries the **canonical Drive v2 payload shape** from `docs/DRIVE_SCHEMA.md`. Field names, nullability, and value types match the schema exactly so the data round-trips through `JSONDecoder` / `Json.decodeFromString` without translation. The wrapping `days[]` structure is a fixture-ergonomics convenience — it groups by date rather than by entity kind, which makes the data easier to read and edit by hand.

To map fixture entries onto canonical Drive paths:

```
sample-daily-logs.json
├── projects[]                                 →  Releaf/projects/{id}.json
├── tags[]                                     →  Releaf/tags/{id}.json
└── days[]
    ├── daily_log                              →  Releaf/daily_logs/2026/{log_date}.json
    ├── notepad_entry                          →  Releaf/notepad_entries/2026/{mm}/{id}.json
    ├── captures[]                             →  Releaf/captures/{id}.json
    ├── tasks[]                                →  Releaf/tasks/{id}.json
    └── reference_links[]                      →  Releaf/reference_links/{id}.json
```

The `_meta` and `_label` keys are fixture annotations only — strip them before validating against the canonical-JSON contract or feeding to the SHA-256 canonicalizer.

### How to use

**iOS (GRDB previews).** Add a `Bundle.main.url(forResource: "sample-daily-logs", withExtension: "json")` lookup in the in-memory `*Repository.previewFake` factories. Decode into `[Day]`, flatten by entity kind, hand to the in-memory store. The `#Preview` macros pull from the same factory.

**Android (Room previews).** Drop the JSON into `android/app/src/main/assets/fixtures/`. Load via `context.assets.open(...)` in a `previewModule` provider (or the equivalent Hilt scope) and seed the in-memory fakes. `@Preview` composables consume the same store.

**Designer mocks / screenshots.** The HTML mocks in `daily-capture-mocks.html` and `daily-capture-mocks-2.html` were drawn from this data — Apr 28's notes, photos, and capture counts on the Home hero match `days[6]` here exactly, and the search results in mock 7 cite Apr 27's voice memo (`days[5].captures[1]`) and Apr 22's morning-walk page. Editing a snippet here propagates naturally to anyone re-rendering the mocks against the data.

**Demo seeding.** A `--seed-demo` debug flag on each platform can wipe local SQLite and replay this fixture into the real Room/GRDB tables — useful for video walkthroughs and TestFlight builds. Don't ship the flag.

### Editing rules

- **IDs are time-sortable UUIDv7-style placeholders.** The first segment encodes the date (e.g. `018f9d22-...` = Apr 22). Keep this scheme when adding rows; it makes diffs greppable.
- **Always update `updated_at`** when you change a field on an existing row — otherwise sync logic that hits this fixture in tests behaves wrong.
- **Don't add fields that aren't in `docs/DRIVE_SCHEMA.md`.** If you need a new field, update the schema first.
- **Don't include real PII.** All names, phone numbers, emails, and addresses here are fictional. Keep them that way.
- **Leave `dirty` flags off.** This fixture represents post-sync state; live SQLite rows in dev set their own `dirty=1` on insert.

### What's intentionally not in here

- **Pages and notebooks.** This file is the *daily* fixture. Notebook → Chapter → Page content lives in a separate fixture (TBD: `sample-notebooks.json`) so each fixture can be loaded independently.
- **Conflict stubs.** Conflict resolution has its own fixture under `fixtures/conflict-cases.json` (TBD).
- **Tombstones.** Soft-delete propagation is exercised by `fixtures/sync-cases.json` (TBD), not here.
- **Media binaries.** The fixture references `media_filename` paths but doesn't ship the JPGs / M4As. Previews substitute the procedurally-generated thumbnail gradients from `daily-capture-mocks.html`.
