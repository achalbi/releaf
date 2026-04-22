# Releaf Android

Jetpack Compose app targeting Android 8.0+ (API 26). Open the `android/`
folder in Android Studio — Gradle sync generates the wrapper and resolves
the version catalog in `gradle/libs.versions.toml`.

## Module map

| Gradle module | Path   | Contains |
| ------------- | ------ | -------- |
| `:app`        | `app/` | Everything — UI theme, design system composables, auth, data layer, features |

The source tree under `app/src/main/java/app/releaf/mobile` is split by
concern: `ui/theme`, `ui/components`, `auth`, `data/domain`, `data/drive`,
`features/auth`, `features/home`.

## Build & run

```sh
# From android/
./gradlew :app:assembleDebug       # build
./gradlew :app:installDebug        # install on a connected device / emulator
```

If `./gradlew` is missing (fresh clone before the wrapper JAR is generated),
run `gradle wrapper --gradle-version 8.9` once, or just open the folder in
Android Studio and let it sync — the IDE writes `gradle/wrapper/gradle-wrapper.jar`
for you.

## Google Sign-In wiring (follow-up drop)

Replace `StubGoogleAuthClient` in `auth/GoogleAuthClient.kt` with a real
implementation backed by Credential Manager + AuthorizationClient:

- Add the dependencies to `gradle/libs.versions.toml`:
  - `androidx.credentials:credentials`
  - `androidx.credentials:credentials-play-services-auth`
  - `com.google.android.libraries.identity.googleid:googleid`
  - `com.google.android.gms:play-services-auth`
- Register an OAuth 2.0 Web client ID + Android client ID in Google Cloud
  console for package `app.releaf.mobile` (SHA-1 from your signing config).
- Request the `https://www.googleapis.com/auth/drive.file` scope during the
  authorization step (separate from the ID-token sign-in).
- Store refresh / access tokens through `AuthStore`'s existing
  `EncryptedSharedPreferences` — the schema already has slots for them.

The `GoogleAuthClient` interface doesn't change — only the concrete class does.

## Drive wiring (follow-up drop)

`data/drive/DriveClient.kt` ships an `InMemoryDriveClient`. The real drop
should speak Drive v3 over OkHttp (or `google-api-services-drive`) and keep
the same interface: `ensureRootFolder`, `ensureFolder`, `listChildren`,
`uploadJSON`, `downloadBytes`, `trash`. `FakeDriveRepository` becomes
`RealDriveRepository` using that client — nothing above the data layer
should need to change.
