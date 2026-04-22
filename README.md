# Releaf

Releaf is a native daily-capture app for iOS and Android. You open your day in a
notebook, open a chapter, open a page, and drop in whatever arrives: a photo,
a voice memo, a to-do, a scanned document, a contact, a location, or a note.

There is no Releaf server. Your data lives in your own Google Drive under a
user-visible `Inkcreate/` folder (renamed in-app to whatever you'd like). Sign
in with Google, and your notebooks are sitting there in Drive — you can open
them on the web, copy them elsewhere, or walk away at any time.

## Repo layout

```
releaf/
├── docs/
│   ├── ARCHITECTURE.md      System design, MVVM, Drive-backed storage
│   └── DRIVE_SCHEMA.md      Exact folder + JSON layout in Google Drive
├── design-system/
│   ├── design-tokens.json   Tokens Studio schema — colors / type / spacing
│   └── DESIGN_SYSTEM.md     Human-readable design system doc
├── ios/
│   ├── Package.swift        SwiftPM manifest — previews render from here
│   └── Releaf/              Swift sources (DesignSystem, Data, Features)
└── android/
    ├── settings.gradle.kts  Root + app project
    ├── build.gradle.kts
    ├── gradle/              Gradle wrapper + version catalog
    └── app/                 Android app module
```

## What's in this skeleton

- iOS SwiftPM + SwiftUI scaffold that compiles and previews
- Android Gradle + Jetpack Compose scaffold that compiles
- Google Sign-In placeholder screens (stub auth client — no SDK wired yet)
- Drive repository skeleton (protocol + in-memory fake, no real Drive calls yet)
- Design tokens ported into native theme files
- Domain model: `Notebook → Chapter → Page` with seven `CaptureMode`s
- Architecture + Drive schema docs

## What's **not** here yet

- Real Google Sign-In SDK integration (GoogleSignIn for iOS, Credential Manager for Android)
- Real Google Drive REST client (`GoogleAPIClientForREST_Drive` for iOS,
  `google-api-services-drive` for Android)
- Feature screens beyond a signed-in home stub
- Offline cache / conflict resolution

These come in follow-up phases — the skeleton is sized so each is a bounded drop-in.

## Getting started

### iOS

```bash
cd ios
open Package.swift          # opens in Xcode; previews work immediately
```

To ship a real app you'll create an Xcode app target that depends on the
`Releaf` SwiftPM library, or convert to an `.xcodeproj` — the code here is
structured so either path works.

Bundle ID: `app.releaf.mobile`.

### Android

```bash
cd android
./gradlew :app:assembleDebug
```

Application ID: `app.releaf.mobile`.

Open `android/` in Android Studio — it imports as a Gradle project.

## Philosophy

- **Your data is yours.** Drive-backed, user-visible folder, `drive.file` scope
  (Releaf can only see files it created, not your whole Drive).
- **No server.** No account system to babysit, no migrations, no downtime.
- **Native everywhere.** SwiftUI on iOS, Jetpack Compose on Android. No Capacitor,
  no Flutter, no React Native.
- **Offline-first.** Drive is the sync layer, not the runtime. The app should
  render and capture without a network.
