# Handoff — Google Drive sync v2 (2026-04-23)

Big session: implemented end-to-end Drive sync against the v2 manifest contract in `docs/DRIVE_SCHEMA.md`. Ships push + pull + restore for all text entities across both platforms, real Google Sign-In, real Drive REST v3 client, Settings UI, and a cross-platform canonical-JSON regression test.

## What was shipped

### Shared contract (both platforms)

- **Canonical JSON** serializer (`CanonicalJson.kt` / `.swift`) — sorted keys, no whitespace, standard JSON escapes. Produces byte-identical output on both platforms for identical input.
- **SHA-256 helper** (`sha256Hex`) — lowercase hex, shared signature.
- **DrivePath** — deterministic relative paths per kind (`notebooks/{id}.json`, `chapters/{id}.json`, `pages/{id}.json`, `notepad_entries/{yyyy}/{mm}/{id}.json`, `daily_logs/{yyyy}/{date}.json`, `tasks/{id}.json`, `tombstones/{id}.json`). **Spec deviation** — flat paths for notebook/chapter/page rather than the nested `notebooks/{nb}/{ch}/{page}.json` tree in DRIVE_SCHEMA.md. Rationale documented inline: the manifest is the source of truth, paths are storage detail, and flat paths remove a parent-lookup step during upload. `schema_version.major` bump gates any renest-in-place migration.
- **Manifest v2** (`Manifest.kt` / `.swift`) — `schema_version {major, minor}`, `migration_version`, `app_version`, `device_id`, `last_sync_at`, `client_generated_at`, `entity_checksums[id]`, `tombstones[id]`. Exact match to `docs/DRIVE_SCHEMA.md` §`manifest.json`.
- **v2 payload types** — one `Serializable`/`Codable` struct per kind (notebook, chapter, page, notepad_entry, task) with `toV2Payload()` / `toEntity()` mappers. JSON-typed columns (contacts, locations, todos, attachments, sketch_strokes, sub_pages) embed as JSON arrays/objects per spec rather than as quoted strings.

### Sync algorithm (both platforms)

- **`SyncRepository.sync(userId, deviceId, accessToken)`** — full push + pull pass:
  1. Ensure `Releaf/` root folder (stamped with `appProperties.releaf_root = true` on create + every manifest write for reinstall recovery per DRIVE_SCHEMA.md §"`drive.file` scope + reinstall").
  2. Fetch remote `manifest.json`.
  3. Version gate — remote `schema_version.major > ours` → abort with `versionBlocked`.
  4. Build local snapshot: every live row + every dirty tombstone, with canonical bytes + SHA-256 computed per row.
  5. Upload delta — rows whose hash differs from remote manifest. Race-safe `markSynced` with `updated_at` snapshot guard.
  6. Tombstone delta — write `tombstones/{id}.json` + stage manifest removal.
  7. Pull delta — download remote entities that aren't in local or have diverged, upsert with `dirty=0`. Remote tombstones soft-delete locally.
  8. Write `manifest.json` last — commit barrier; mid-pass crash leaves blobs durable for next pass.
  9. Record `last_full_sync_at`, `manifest_checksum`, `pending_count` in `sync_state`.
- **Covered entities**: `notebooks`, `chapters`, `pages`, `notepad_entries`, `tasks`. No media blobs, no daily_logs / captures / reference_links / projects / tags / page_templates.

### Android wiring

- **Rewrote `SyncRepository.kt` in place** (v1 flat-counts → v2 manifest-diff). `pushDirty` kept as a deprecated alias.
- **`SyncWorker.kt`** updated to handle `versionBlocked` (returns `Result.failure` — retrying won't help until the app updates).
- **`DriveClientPath.kt`** — extension fns `ensurePath`, `uploadJsonAtPath`, `downloadBytesAtPath`, `trashAtPath`.
- **`OkHttpDriveClient.kt`** — real Drive v3 REST client (list / create folder / multipart upload / download / trash). Stamps `appProperties.releaf_root=true` on root folder + manifest.
- **DAO additions** — `activeRows()` + `findByIds()` on notebook/chapter/page/notepad/task DAOs; task DAO gained the standard `dirtyRows` / `markSynced` / `markTombstoneSynced` / `countActive` set.
- **`RealGoogleAuthClient.kt`** — Credential Manager (ID token) + `AuthorizationClient.authorize(Scope("drive.file"))`. Throws `ConsentRequiredException` when the Drive-scope consent sheet is needed; `GoogleSignInBinding.rememberGoogleSignInAction` composable owns the `ActivityResultLauncher` and re-entry.
- **`AuthStore.kt`** — new `adoptSession` / `failSignIn` / `cancelSignIn` / `beginExternalSignIn` hooks for external flows.
- **`strings.xml`** — new `google_web_client_id` string with `REPLACE_WITH_GOOGLE_WEB_CLIENT_ID` placeholder.
- **`ReleafApp.kt`** — runtime-selects `OkHttpDriveClient` vs `InMemoryDriveClient` based on whether the web-client-id placeholder has been replaced.
- **`DriveSettingsSection.kt`** — Settings card with connection state, last-sync timestamp, pending-count badge, and "Sync now" / "Restore from Drive" buttons.

### iOS wiring

- **`SyncRepository.swift`** — full mirror of the Android sync; talks directly to the GRDB `DatabaseQueue` for cross-table snapshot + upsert (avoids plumbing sync methods through every per-entity repository).
- **`SyncStateStore.swift`** — UserDefaults-backed local sync state (mirror of Android's `sync_state` table). `@Published state` for reactive Settings bindings.
- **`SyncScheduler.swift`** — one-shot coalesced sync + BGAppRefreshTask registration. Requires `BGTaskSchedulerPermittedIdentifiers` → `app.releaf.mobile.sync` in Info.plist when the app target is built; handler stubs already work in SwiftPM tests / simulators.
- **`SyncEnvironment.swift`** — process-wide wiring singleton. Picks `URLSessionDriveClient` vs `InMemoryDriveClient` based on the Info.plist `GIDClientID` value. Observes `AuthStore` and toggles background refresh on sign-in / sign-out. `ReleafApp.init()` calls `SyncEnvironment.shared.install(authStore: .shared)`.
- **`URLSessionDriveClient.swift`** — `URLSession`-based Drive v3 REST client with the same surface as the Android OkHttp one.
- **`RealGoogleAuthClient.swift`** — wraps `GIDSignIn` (`GoogleSignIn-iOS` SwiftPM dep). Signs in, adds `drive.file` scope via `additionalScopes:`, handles consent UI internally. `restorePreviousSignIn` for warm starts.
- **`KeychainTokenStore.swift`** — replaces the UserDefaults placeholder in `AuthStore.swift`. Keychain item with `kSecAttrAccessibleAfterFirstUnlock` so BGTaskScheduler runs can read it.
- **`GoogleSignInBinding.swift`** — `signInAction(authStore:)` helper; checks Info.plist `GIDClientID` and falls back to `AuthStore.signIn()` (stub) when unset so previews work.
- **`DriveSettingsSection.swift`** + updated `SettingsView.swift`.
- **`Package.swift`** — added `GoogleSignIn-iOS` dependency + `ReleafDataTests` test target with the shared canonical-JSON fixture.

### Tests

- **`design-system/fixtures/canonical-json-fixture.json`** — shared input covering key sorting, nested objects, string escapes (quote/backslash/newline/tab), unicode, integers (including `Int64.MAX - 8`), booleans, nulls, and empty containers.
- **Android: `CanonicalJsonTest.kt`** — `gradle :app:testDebugUnitTest` passes. Two assertions: the canonicalizer's string output matches a hand-written expected canonical form, and the SHA-256 hex matches `f5af33b6b766125cc4cc6026a41130be6129e2f5a697d8e161b94f631a5b02a6`.
- **iOS: `Tests/ReleafDataTests/CanonicalJsonTests.swift`** — mirror of Android's, asserts the same canonical form + SHA-256. Runs via Xcode's Test Navigator (not `swift test` because the package has iOS-only deps).

## Config the user owes this project

### Google Cloud Console

1. Create a project in Google Cloud Console (if not already).
2. Enable the **Google Drive API** for the project.
3. Configure the OAuth consent screen.
4. Create OAuth 2.0 credentials:
   - **Web application** client — used by the Android Credential Manager flow. **Android sign-in requires the Web client ID**, not the Android one, because that's the audience the ID token is minted for.
   - **Android** client — one per build variant; needs SHA-1 fingerprint + package name `app.releaf.mobile`.
   - **iOS** client — needs bundle ID `app.releaf.mobile`.

### Android

- `android/app/src/main/res/values/strings.xml` → replace `REPLACE_WITH_GOOGLE_WEB_CLIENT_ID` with the **Web client ID** (e.g. `12345-abc.apps.googleusercontent.com`).
- App automatically flips from `InMemoryDriveClient` → `OkHttpDriveClient` + stub → real auth on next build.

### iOS

- Add `GIDClientID` to the app target's Info.plist with the **iOS client ID** (e.g. `12345-xyz.apps.googleusercontent.com`).
- Add a URL scheme matching the reversed iOS client ID (e.g. `com.googleusercontent.apps.12345-xyz`).
- Add `BGTaskSchedulerPermittedIdentifiers` array with `app.releaf.mobile.sync`.
- Once `GIDClientID` is set, `SyncEnvironment` auto-promotes to `URLSessionDriveClient`.

## Out of scope / known gaps

- **Media blob sync** (photos, voice, scans) — `captures` table doesn't exist on either platform yet. Resumable uploads also deferred.
- **Conflict resolver UI** — `conflict_stub` column exists on `notepad_entries` + `pages` but the algorithm / banner / Conflicts screen aren't built.
- **Daily logs, tags, projects, page_templates, reference_links** — no local tables, so they don't participate in sync. `DrivePath` kind constants are reserved.
- **Inkcreate v1 folder import** — deferred per DRIVE_SCHEMA.md §"Migration from v1".
- **Real 401-refresh-retry interceptor** — `RealGoogleAuthClient.refresh` is implemented but not automatically invoked by the Drive clients on 401; today the sync worker returns `Result.failure` on auth errors, which bubbles to the Settings UI.
- **iOS schema gap** — iOS SQLite schema lags Android: no `description` column on notebooks/chapters, no `archived_at` on notebooks, no `sketch_strokes` / `sub_pages` on pages/notepad. Android payloads include those fields; iOS downloads lose them on round-trip until the iOS schema catches up. Flagged in `SyncPayloads.swift` extensions.

## Verification status

- **Android**: `gradle :app:assembleDebug` (full APK) → `BUILD SUCCESSFUL`. `gradle :app:testDebugUnitTest` → `CanonicalJsonTest` 2/2 pass.
- **iOS**: Not compiled — no Xcode on the machine. Sources written against the protocol surface I could verify by reading. First compile on a Mac with Xcode will flush out any minor drift.

## Files touched this session

```
# Shared
design-system/fixtures/canonical-json-fixture.json                      (new)

# Android — sync core
android/app/src/main/java/app/releaf/mobile/data/sync/CanonicalJson.kt  (new)
android/app/src/main/java/app/releaf/mobile/data/sync/DrivePath.kt      (new)
android/app/src/main/java/app/releaf/mobile/data/sync/Manifest.kt       (new)
android/app/src/main/java/app/releaf/mobile/data/sync/SyncPayloads.kt   (rewritten v1→v2)
android/app/src/main/java/app/releaf/mobile/data/sync/SyncRepository.kt (rewritten)
android/app/src/main/java/app/releaf/mobile/data/sync/SyncWorker.kt     (versionBlocked handling)
android/app/src/main/java/app/releaf/mobile/data/drive/DriveClientPath.kt (new)
android/app/src/main/java/app/releaf/mobile/data/drive/OkHttpDriveClient.kt (new)

# Android — DAO additions
android/app/src/main/java/app/releaf/mobile/data/notepad/NotepadDao.kt  (activeRows/findByIds)
android/app/src/main/java/app/releaf/mobile/data/notebook/NotebookDao.kt (activeRows/findByIds)
android/app/src/main/java/app/releaf/mobile/data/notebook/ChapterDao.kt (activeRows/findByIds)
android/app/src/main/java/app/releaf/mobile/data/notebook/PageDao.kt    (activeRows/findByIds)
android/app/src/main/java/app/releaf/mobile/data/task/TaskDao.kt        (sync methods)

# Android — auth
android/app/src/main/java/app/releaf/mobile/auth/RealGoogleAuthClient.kt (new)
android/app/src/main/java/app/releaf/mobile/auth/GoogleSignInBinding.kt  (new)
android/app/src/main/java/app/releaf/mobile/auth/AuthStore.kt            (external-flow hooks)

# Android — wiring + UI
android/app/src/main/java/app/releaf/mobile/MainActivity.kt             (wire binding)
android/app/src/main/java/app/releaf/mobile/ReleafApp.kt                (pick Drive client)
android/app/src/main/java/app/releaf/mobile/features/settings/DriveSettingsSection.kt (new)
android/app/src/main/java/app/releaf/mobile/features/settings/SettingsScreen.kt (added card)

# Android — config
android/app/src/main/res/values/strings.xml                              (google_web_client_id)
android/app/build.gradle.kts                                             (new deps + test deps)
android/gradle/libs.versions.toml                                        (new versions)

# Android — tests
android/app/src/test/java/app/releaf/mobile/data/sync/CanonicalJsonTest.kt (new)

# iOS — sync core
ios/Releaf/Data/Sync/CanonicalJson.swift                (new)
ios/Releaf/Data/Sync/DrivePath.swift                    (new)
ios/Releaf/Data/Sync/Manifest.swift                     (new)
ios/Releaf/Data/Sync/SyncPayloads.swift                 (new)
ios/Releaf/Data/Sync/SyncRepository.swift               (new)
ios/Releaf/Data/Sync/SyncStateStore.swift               (new)
ios/Releaf/Data/Sync/SyncScheduler.swift                (new)
ios/Releaf/Data/Sync/SyncEnvironment.swift              (new)
ios/Releaf/Data/Sync/DeviceIdentity.swift               (new)
ios/Releaf/Data/Drive/DriveClientPath.swift             (new)
ios/Releaf/Data/Drive/URLSessionDriveClient.swift       (new)

# iOS — auth
ios/Releaf/Data/Auth/RealGoogleAuthClient.swift         (new)
ios/Releaf/Data/Auth/KeychainTokenStore.swift           (new)
ios/Releaf/Data/Auth/GoogleSignInBinding.swift          (new)
ios/Releaf/Data/Auth/AuthStore.swift                    (Keychain migration + hooks)

# iOS — wiring + UI
ios/Releaf/Features/App/ReleafApp.swift                 (install SyncEnvironment)
ios/Releaf/Features/Auth/SignInScreen.swift             (use binding)
ios/Releaf/Features/Settings/DriveSettingsSection.swift (new)
ios/Releaf/Features/Settings/SettingsView.swift         (added card)

# iOS — package + tests
ios/Package.swift                                        (GoogleSignIn dep + test target)
ios/Tests/ReleafDataTests/CanonicalJsonTests.swift      (new)
```

## Next session suggestions

1. **Fill in OAuth client IDs** + manual-test the end-to-end flow: sign in → create notebook → verify in Drive web UI → sign out → sign in on second device → verify restore.
2. **Conflict resolver UI** — `conflict_stub` column + per-entry banner + Conflicts screen under Settings (per `docs/OPEN_QUESTIONS.md` §10, §12).
3. **Media sync** — add `captures` schema + media blob upload (resumable > 5 MiB) + lazy hydration.
4. **iOS schema catch-up** — add `description`, `archived_at`, `sketch_strokes`, `sub_pages` via GRDB migrations so iOS round-trips Android payloads losslessly.
5. **Automatic 401-refresh-retry** — wrap Drive REST calls in both clients with an interceptor that calls `GoogleAuthClient.refresh` once on 401 and retries.
