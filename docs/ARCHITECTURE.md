# Releaf architecture

## One-line summary

Native SwiftUI + Jetpack Compose clients, MVVM, **local-first** SQLite store
with the user's own Google Drive as a backup / sync target. No Releaf server.

## Layers

```
   UI layer (SwiftUI / Compose)
       │   binds to
   ViewModel (state + actions, one per screen)
       │   talks to
   Repository  (NotebookRepository, PageRepository, AuthStore, …)
       │   reads + writes
   Local store (SQLite via Room / GRDB + media on disk)   ← source of truth
       │
       └── SyncWorker ──▶ Drive client (Drive REST)  ← backup / eventual consistency
                                │
                          Google's servers
```

Each arrow points one way. Views don't know about the DB or Drive.
ViewModels don't know about SQL or HTTP. Repositories don't know about the
UI. This makes every layer swappable: each repository is implemented once
with an in-memory fake (for previews + tests) and once with the real local
store, and the Drive client is a separate collaborator that never sits in
the UI's critical path.

## Domain model

```
User (signed in with Google)
 └── Notebook          one per topic / project / year
      └── Chapter      sub-division within a notebook
           └── Page    a single day / event / thought
                ├── notes          (rich text)
                ├── photos         (ActiveStorage-style binary attachments)
                ├── voice notes    (audio + optional transcript)
                ├── to-do list     (ordered, toggleable items)
                ├── scanned docs   (enhanced image + optional OCR text + PDF)
                ├── contacts       (name, phones, email, website)
                └── locations      (name, address, lat/lng, maps URL)
```

Seven `CaptureMode`s map 1:1 to the page sub-sections:
`OVERVIEW · PHOTOS · VOICE · TODO · SCANS · CONTACTS · LOCATION`.

`OVERVIEW` is a read-only summary that shows counts across the other six modes.

## Storage model

**Local store is the source of truth.** Every write hits local SQLite (+
filesystem for media) first and is immediately durable on the device. The
UI reads from local streams (Flow / AsyncSequence) and never waits on the
network. The app is fully usable offline — capture, edit, browse, search.

- **Android:** Room (SQLite) for structured rows; media blobs in
  `filesDir/releaf/media/<page-id>/…`.
- **iOS:** GRDB (SQLite) for structured rows; media blobs in
  `.documentDirectory/releaf/media/<page-id>/…`.

Every row carries a client-generated UUID, `updatedAt`, a `syncState`
(`local_only · syncing · synced · sync_failed`), and a nullable
`driveFileId` that's populated after the first successful upload. Soft
delete via `archivedAt`; rows are never hard-deleted on the device until
the user empties archive.

**Drive is a sync / backup target.** A `SyncWorker` (WorkManager on
Android, `BGTaskScheduler` on iOS) runs on launch, on network reconnect,
and on a periodic timer. It uploads `local_only` rows, then updates the
Drive index files last (so a failure mid-sync leaves the blobs durable
and the indexes consistent). On a fresh install + sign-in, the worker
runs in reverse: pull `notebooks.json`, walk the tree, populate the
local DB. Media is lazy-loaded on view (placeholder until first download).

Drive layout is **file-per-entity** with a small amount of cross-linking
via stable client-generated IDs (not Drive file IDs). Full details in
[`DRIVE_SCHEMA.md`](./DRIVE_SCHEMA.md) — that's the contract the sync
worker writes against.

Conflict policy for v1: last-write-wins. The app is effectively
single-device for now; three-way merge is a future follow-up.

Scope: **`drive.file`** — Releaf can only read/write files it itself
created. It never sees the rest of the user's Drive.

## Auth

Google Sign-In produces an OAuth access token with the `drive.file` scope.
Releaf stores the ID token (for user identity) and refreshes the access token
as needed. Sign-out simply drops the tokens; the Drive folder is left intact
so a future sign-in picks up where we left off.

- iOS: [GoogleSignIn-iOS SDK](https://github.com/google/GoogleSignIn-iOS) + Keychain for token storage.
- Android: Credential Manager + AuthorizationClient (play-services-auth) + EncryptedSharedPreferences.

The skeleton in this repo ships a `GoogleAuthClient` *protocol* on both
platforms with an in-memory stub. Wiring the real SDK is a bounded follow-up.

## ViewModels

Each screen has its own ViewModel. Pattern:

**iOS** — `@MainActor final class FooViewModel: ObservableObject` with
`@Published private(set) var state: FooState`. States are enums
(`idle / loading / loaded(...) / failed(String)`). Actions are `async` methods.

**Android** — `class FooViewModel : ViewModel()` with
`MutableStateFlow<FooUiState>` exposed as `StateFlow`. Factories live on a
companion object so we can pass IDs (e.g. `PageDetailViewModel.factory(pageId)`).

## Previews

Previews must not require a running backend, a live Drive, **or** a real
SQLite file. Every ViewModel accepts its repositories in its initializer;
the `#Preview` / `@Preview` passes in the in-memory fake (e.g.
`PageRepository.inMemoryFake`). Fakes are the same type the real
repository implements, so the UI is identical to production.

## Testing

The repo ships no tests yet. When it does: per-repository contract tests
(fake + real implementations exercise the same suite) and per-ViewModel state
tests (fire an action, assert the emitted state transitions).

## Why these choices

- **Native, not cross-platform.** Capacitor and Flutter prototypes were tried
  (see old `inkcreate/mobile/app/` and `mobile/flutter_shell/`); native gives
  a better feel for a capture app that leans hard on camera, voice, maps.
- **Local-first, Drive as sync.** A journaling app must work offline — on a
  plane, in the woods, on spotty hotel wifi. SQLite on-device means every
  write is instant and every read is local. Drive as a sync target gives us
  durability and cross-device restore without running a server.
- **Drive, not our own server.** No accounts to manage, no data sovereignty
  headaches, and the user can read their notebooks without us.
- **`drive.file` scope.** Most private option — we can't see anything we
  didn't create. Costs us nothing: we know every file we write, so we can
  find them again without listing the whole Drive.
- **MVVM.** Standard on both platforms. Avoids "smart views" that are hard to
  preview and easy to break.
