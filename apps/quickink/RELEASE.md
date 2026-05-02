# QuickInk — production release guide

Two storefronts to ship to: Apple App Store (iOS) and Google Play (Android).
Both have their own pre-flight checklists, signing setup, and review
processes; the sections below walk through each end-to-end.

The current repo state matters for ordering: **iOS is a Swift Package
right now with no Xcode app target**, so iOS release work starts with
creating the app target. Android has a working Gradle build but needs
signing + R8 + Play Console setup before it's shippable.

There are also a few **cross-platform pre-flights** (real Google OAuth
credentials, privacy policy hosting, app icon legal mark) that block
both stores. Do those first.

---

## 0. Cross-platform pre-flights (do these once)

### 0.1 Google OAuth credentials (blocking both platforms)

The app currently uses a stub auth client when
`R.string.google_web_client_id` (Android) / its iOS equivalent is the
placeholder `REPLACE_WITH_GOOGLE_WEB_CLIENT_ID`. Production needs a
real Google Cloud project tied to the QuickInk app.

1. **Create a new GCP project** named `quickink-prod` (or similar) at
   <https://console.cloud.google.com/projectcreate>. Don't reuse
   Releaf's project — QuickInk needs its own OAuth client tied to its
   own bundle ID + signing certificate fingerprints.
2. **Enable APIs**: Google Drive API, Google Sign-In API.
3. **OAuth consent screen**:
   - User type: External
   - App name: QuickInk
   - Logo: upload `design/exports/app-store-icon-1024.png`
   - Support email + developer email
   - Authorized domains: your privacy-policy host (see 0.2)
   - Scopes:
     - `openid`, `profile`, `email`
     - `https://www.googleapis.com/auth/drive.appdata` (for app-private
       Drive storage)
     - `https://www.googleapis.com/auth/drive.file` (only if the app
       writes user-visible files — depends on QuickInk's sync surface;
       check `:shared:drive` to confirm)
4. **Create OAuth 2.0 client IDs**:
   - **Web** client — required for the Android Credential Manager
     flow even though there's no web app. Copy the client ID.
   - **iOS** client — bundle ID `app.quickink.mobile`. Note the
     reversed client ID (used in Info.plist URL types).
   - **Android** client(s) — package `app.quickink.mobile`, plus
     SHA-1 fingerprints for both the upload key (you generate this in
     1.1) and the Play App Signing key (Google generates this when
     you opt into Play App Signing in 1.5). Both fingerprints must be
     registered or sign-in will fail in production builds.
5. **Verification**: if you request `drive.file` (any non-restricted
   scope), Google requires app verification before the OAuth consent
   screen leaves "testing" mode. Allow ~4–6 weeks for verification; the
   reviewer will ask for a privacy policy URL, demo video, and scope
   justification. While in "testing" mode, only the test users you list
   on the consent screen can sign in — fine for TestFlight / Play
   internal testing, not fine for public release.
6. **Wire the client ID into the app**:
   - Android: `app/src/main/res/values/strings.xml` — replace
     `REPLACE_WITH_GOOGLE_WEB_CLIENT_ID` with the **web** client ID.
     The `QuickInkAuthBinding` Compose hook already checks for the
     placeholder and falls back to the stub when it sees the default;
     replacing it flips to `RealGoogleAuthClient`.
   - iOS: equivalent string lookup once the Xcode app target lands.
     See `ios/QuickInk/Auth/QuickInkAuthBinding.swift` for the binding
     posture.

### 0.2 Privacy policy

Both stores require a publicly-hosted privacy policy URL. QuickInk
collects user-authored note content + scanned page images and stores
them in the user's own Drive — say so plainly. Host on whatever
public URL you control (GitHub Pages, your own domain, Notion public
page).

Mandatory disclosures:
- What data is collected (Google account email, scanned images, OCR
  text, note bodies, optional location/contact metadata if those
  features ship).
- Where it's stored (locally on device + the user's Google Drive; not
  on QuickInk-controlled servers).
- Third-party processors (Google Sign-In, Google Drive API, Google ML
  Kit on-device OCR).
- Children: QuickInk is not directed at children under 13.
- Contact email for data requests.

### 0.3 App store assets (already produced)

The brand pass shipped these — they map to specific store fields:

| Store field                          | File                                                            |
|--------------------------------------|-----------------------------------------------------------------|
| App Store icon (1024×1024)           | `design/exports/app-store-icon-1024.png`                        |
| Play Store icon (512×512)            | `design/exports/play-store-icon-512.png`                        |
| Play Store feature graphic (1024×500)| `design/exports/play-store-feature-1024x500.png`                |
| Screenshots (5×, 1170×2532 portrait) | `design/exports/screenshots/screenshot-{1..5}-*.png`            |
| Open Graph share image (1200×630)    | `design/exports/social-share-og-1200x630.png`                   |

Store description / keywords / "what's new" copy is not yet drafted.
Suggested starting point lives in `design/BRAND.md` under "Voice" —
adapt the tagline `scan, jot, find again.` plus the App Store
screenshot headlines for the store description.

### 0.4 Crash reporting + analytics (recommended)

The current build pulls in no Crashlytics or analytics — fine for
internal beta, painful in production. Sibling app `:apps:releaf` has
Crashlytics wired up; same pattern will drop into QuickInk:
1. Register the app under the same Firebase project (or a new one).
2. Download `google-services.json` (Android) / `GoogleService-Info.plist`
   (iOS) and drop into `app/` and the Xcode app target respectively.
3. Add `firebase-crashlytics` + `firebase-analytics` to deps; apply the
   `com.google.firebase.crashlytics` Gradle plugin.
4. Initialize in `QuickInkApp.onCreate` / iOS `@main App.init`.

Skip if you're cool flying blind — but losing ANRs and crash stacks
once strangers start hitting the app is rough.

---

## 1. Android (Google Play)

### 1.1 Generate the upload keystore

ONE keystore for QuickInk, separate from Releaf. Lose it and you
can't ship updates that the same Play Store listing accepts.

```bash
keytool -genkey -v \
  -keystore quickink-upload.jks \
  -alias quickink-upload \
  -keyalg RSA -keysize 2048 -validity 10000
```

Store **outside** the repo (`~/.keystores/` or 1Password). Capture the
keystore password, key alias, and key password — all three are needed
in `gradle.properties` and CI secrets.

Get the SHA-1:
```bash
keytool -list -v -keystore quickink-upload.jks -alias quickink-upload \
  | grep SHA1
```
Register that SHA-1 with the Android OAuth client from 0.1.

### 1.2 Wire signing into Gradle

Edit `~/.gradle/gradle.properties` (NOT in the repo — keep secrets out
of git):

```
QUICKINK_UPLOAD_KEYSTORE=/Users/achalindiresh/.keystores/quickink-upload.jks
QUICKINK_UPLOAD_KEYSTORE_PASSWORD=...
QUICKINK_UPLOAD_KEY_ALIAS=quickink-upload
QUICKINK_UPLOAD_KEY_PASSWORD=...
```

Edit `apps/quickink/android/app/build.gradle.kts` — add to the
`android { ... }` block:

```kotlin
signingConfigs {
    create("release") {
        val ksFile = (project.findProperty("QUICKINK_UPLOAD_KEYSTORE") as String?)?.let { file(it) }
        if (ksFile != null && ksFile.exists()) {
            storeFile     = ksFile
            storePassword = project.findProperty("QUICKINK_UPLOAD_KEYSTORE_PASSWORD") as String
            keyAlias      = project.findProperty("QUICKINK_UPLOAD_KEY_ALIAS") as String
            keyPassword   = project.findProperty("QUICKINK_UPLOAD_KEY_PASSWORD") as String
        }
    }
}
buildTypes {
    release {
        // Currently `isMinifyEnabled = false` per the scaffold —
        // turn it on for production. R8 strips dead code from the
        // shared modules + Compose.
        isMinifyEnabled = true
        isShrinkResources = true
        proguardFiles(
            getDefaultProguardFile("proguard-android-optimize.txt"),
            "proguard-rules.pro"
        )
        signingConfig = signingConfigs.getByName("release")
    }
}
```

Create `apps/quickink/android/app/proguard-rules.pro` with:
```
# Keep Compose runtime + classes referenced by reflection.
-keep class androidx.compose.runtime.** { *; }
# Room entities + DAOs.
-keep class app.quickink.mobile.** { *; }
-keep class app.releaf.mobile.data.** { *; }
# Kotlinx serialization.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.SerializationKt
-keep,includedescriptorclasses class app.quickink.mobile.**$$serializer { *; }
-keepclassmembers class app.quickink.mobile.** {
    *** Companion;
}
-keepclasseswithmembers class app.quickink.mobile.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# Google Sign-In Credentials.
-keep class com.google.android.libraries.identity.googleid.** { *; }
```

Test the release build on device:
```bash
./gradlew :apps:quickink:assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

### 1.3 Bump versionCode / versionName

In `app/build.gradle.kts`:
```kotlin
versionCode = 1
versionName = "0.1.0"
```

For each Play release, **bump `versionCode` by at least 1**. Play
rejects uploads with a versionCode ≤ the current production value.
Convention: `versionCode = MAJOR * 10000 + MINOR * 100 + PATCH`, so
v0.1.0 → 100, v0.2.0 → 200, v1.0.0 → 10000. Or just monotonically
increment.

`versionName` is the marketing string users see ("0.1.0", "1.0",
"1.0 (build 42)"). Keep it semver.

### 1.4 Build the AAB

Play Store wants Android App Bundles, not APKs:
```bash
./gradlew :apps:quickink:bundleRelease
```
Output: `app/build/outputs/bundle/release/app-release.aab`.

### 1.5 Play Console setup

<https://play.google.com/console>

1. **Create app** — name "QuickInk", default language, app type "App",
   free, declare it's not a game, accept developer policy.
2. **App content** — fill out:
   - Privacy policy URL (from 0.2)
   - Ads: select "Contains ads" only if true (we don't have any)
   - App access: "All functionality is available without restrictions"
     unless you gate features behind sign-in
   - Content rating: questionnaire — for QuickInk's note-taking surface
     it'll come back as Everyone / PEGI 3
   - Target audience: 13+ (note: Drive scope requires this for Family
     program eligibility; if you want under-13 you need extra COPPA
     compliance — skip for v1)
   - News app: No
   - COVID-19 contact tracing: No
   - Data safety: this is the big one — declare every piece of data
     QuickInk collects. Match what's in the privacy policy. Highlights:
     - Photos and videos: Yes (scanned page images), stored in the
       user's own Drive, optional, never shared off-device by QuickInk.
     - Files and docs: Yes (note text + OCR'd content), same disposition.
     - Personal info: email (from Google Sign-In).
     - Encryption in transit: Yes (HTTPS to Google APIs).
     - User can request deletion: Yes (sign out + delete app removes
       local; user can revoke Drive scope from Google Account).
   - Government apps: No
   - Financial features: No
3. **Main store listing**:
   - Short description (80 char): suggested — "Scan, jot, find again. Your paper, searchable."
   - Full description (4000 char): adapt from `design/BRAND.md` Voice
     section + screenshot headlines.
   - App icon: `design/exports/play-store-icon-512.png`
   - Feature graphic: `design/exports/play-store-feature-1024x500.png`
   - Phone screenshots (2–8): `design/exports/screenshots/screenshot-1..5-*.png`
   - Tablet screenshots: optional; reuse phone screenshots if you
     don't have tablet UI yet (Play accepts the same files).
   - Video: optional; skip for v1.
4. **Internal testing track**: create a new release.
   - Upload `app-release.aab`.
   - Release notes: "v0.1.0 — initial QuickInk release. Camera-first
     scanning, OCR text search, Drive sync."
   - Add yourself + a handful of testers by email; testers see the app
     within ~10 minutes via the test track URL.
5. **Closed testing → open testing → production** — promote between
   tracks once internal testing is green. Production release goes
   through Play review (typically a few hours, sometimes a day for
   first submission).

### 1.6 Per-release checklist (Android)

- [ ] Bump `versionCode` and `versionName`.
- [ ] Update release notes file (e.g. `app/src/main/play/release-notes/en-US/production.txt`).
- [ ] `./gradlew :apps:quickink:bundleRelease`.
- [ ] Test on a real device (signed install, not debug):
      `./gradlew :apps:quickink:installRelease`.
- [ ] Smoke test: cold launch, sign-in, scan, OCR, search, sync, sign-out.
- [ ] Upload AAB to Play Console internal track.
- [ ] Verify pre-launch report passes (Play runs the app on a fleet of
      devices; rejects builds with crashes on launch).
- [ ] Promote to production once internal testers confirm.

---

## 2. iOS (App Store)

### 2.1 BLOCKER: create the Xcode app target

Right now `apps/quickink/ios/Package.swift` declares a single library
product (`QuickInkFeatures`). There's no `@main App` struct, no
Info.plist, no `LaunchScreen.storyboard`. **You can't archive or
upload to App Store Connect from a SwiftPM-only repo.**

Options:

1. **Add an Xcode project alongside the package** (matches Releaf):
   - In Xcode: File → New → Project → iOS App → name `QuickInk`,
     bundle ID `app.quickink.mobile`, language Swift, interface
     SwiftUI, no Core Data, no tests (the package has its own).
   - Save inside `apps/quickink/ios/` as `QuickInk.xcodeproj`
     (sibling of `Package.swift`).
   - In the new app target, File → Add Package Dependencies →
     Add Local → select `apps/quickink/ios` (the directory containing
     `Package.swift`). Add `QuickInkFeatures` to the target.
   - Replace the auto-generated `QuickInkApp.swift` with:
     ```swift
     import SwiftUI
     import QuickInkFeatures

     @main
     struct QuickInkApp: App {
         init() {
             // Register bundled Cormorant + Caveat fonts at process start
             // so SwiftUI's Font.custom(...) calls resolve to the real
             // family throughout the app.
             QuickInkFont.registerAll()
         }
         var body: some Scene {
             WindowGroup { QuickInkRoot() }
         }
     }
     ```
   - Drop `QuickInkMark` / `QuickInkWordmark` / `IconScan` etc. into
     the app target's Asset Catalog by adding the SwiftPM resource
     bundle, OR re-import the same `Assets.xcassets` from the package
     path.

2. **Convert `Package.swift` to an executable iOS app** — possible
   with SwiftPM 5.9, but rough edges around Info.plist, launch
   screen, code signing, and Asset Catalog discovery. Not recommended
   unless you're committed to a SwiftPM-only stack.

The rest of this section assumes Option 1.

### 2.2 Apple Developer account + bundle ID

- Pay the $99/yr Apple Developer Program enrollment if not already.
- <https://developer.apple.com/account/resources/identifiers/list> →
  register App ID `app.quickink.mobile`. Enable capabilities:
  - Sign in with Apple (only if you add it; not required for v1)
  - Associated Domains (only if universal links)
- App Store Connect → Apps → New App → bundle ID
  `app.quickink.mobile`, primary language English, SKU `quickink-ios-v1`.

### 2.3 Info.plist

Required keys (the Xcode template generates most; verify these):

```xml
<key>CFBundleDisplayName</key>
<string>QuickInk</string>
<key>CFBundleIdentifier</key>
<string>app.quickink.mobile</string>
<key>CFBundleShortVersionString</key>
<string>0.1.0</string>
<key>CFBundleVersion</key>
<string>1</string>

<key>UILaunchScreen</key>
<dict>
    <key>UIColorName</key>
    <string>QuickInkCanvas</string>
    <key>UIImageName</key>
    <string>QuickInkMark</string>
</dict>

<key>NSCameraUsageDescription</key>
<string>QuickInk uses the camera to scan pages and pick up your handwritten notes.</string>
<key>NSPhotoLibraryUsageDescription</key>
<string>QuickInk lets you import photos of pages to scan and search.</string>
<key>NSPhotoLibraryAddUsageDescription</key>
<string>QuickInk saves scanned pages to your photo library when you choose to export.</string>
<key>NSContactsUsageDescription</key>
<string>QuickInk links contacts to notes when you tag them.</string>
<key>NSLocationWhenInUseUsageDescription</key>
<string>QuickInk attaches a location to scans you choose to tag with a place.</string>

<key>UIAppFonts</key>
<array>
    <string>CormorantGaramond-Medium.ttf</string>
    <string>Caveat-Medium.ttf</string>
</array>

<!-- Google Sign-In URL scheme — replace REVERSED_CLIENT_ID with the
     reversed iOS OAuth client ID from 0.1. -->
<key>CFBundleURLTypes</key>
<array>
    <dict>
        <key>CFBundleURLSchemes</key>
        <array>
            <string>REVERSED_CLIENT_ID</string>
        </array>
    </dict>
</array>
```

Add a `QuickInkCanvas.colorset` to the app target's `Assets.xcassets`
with sRGB `#FAF7F2` for both light and dark modes (per the brand
guide, the splash is always cream).

### 2.4 Code signing

- Xcode → Signing & Capabilities tab on the app target.
- Team: your Apple Developer team.
- Bundle ID: `app.quickink.mobile`.
- "Automatically manage signing" — fine for a solo dev; switch to
  manual profiles if you have a CI signing setup.
- Add capability: "Sign in with Apple" only if needed; otherwise leave
  default.

### 2.5 Archive and upload

- Select target device: "Any iOS Device (arm64)".
- Product → Archive.
- Organizer → Distribute App → App Store Connect → Upload.
- Wait for the upload to process in App Store Connect (~5–30 min
  depending on Apple's queue).

### 2.6 App Store Connect setup

- **App Information**: bundle ID, primary language, category
  (Productivity), content rights, age rating.
- **Pricing & Availability**: Free, all territories (or restrict).
- **App Privacy**: privacy "nutrition labels" — declare every
  data type collected. For QuickInk:
  - Contact Info → Email Address (linked to user, used for app
    functionality, not used for tracking).
  - User Content → Photos, Other User Content (notes), linked to user,
    used for app functionality.
  - Data is not used for tracking.
- **App Privacy Policy URL**: from 0.2.
- **Version 0.1.0 (preparing for submission)**:
  - Description, keywords, support URL, marketing URL.
  - Screenshots: upload `design/exports/screenshots/screenshot-1..5-*.png`.
    App Store wants 6.7" device screenshots (1290×2796 for iPhone 15
    Pro Max) — our exports are 1170×2532; either re-render at the
    larger size (`magick splash.png -resize 1290x2796\! ...`) or
    accept the smaller resolution and let App Store auto-scale.
  - App preview videos: optional.
  - "What's New": "v0.1.0 — initial release. Camera-first scanning,
    OCR text search, Drive sync."
  - Build: select the build that finished processing.
  - Sign-in info for review: provide a test Google account so the
    Apple reviewer can sign in. Note in "Notes" that the app uses
    Google Sign-In.
  - Submit for review.

### 2.7 TestFlight (recommended before App Store submission)

- App Store Connect → TestFlight tab → enable for the latest build.
- Internal testers: anyone in your Developer team.
- External testers: up to 10,000 via email or public link; requires
  a quick Apple beta review (~24 hr first time).

### 2.8 Per-release checklist (iOS)

- [ ] Bump `CFBundleShortVersionString` (marketing version, e.g. "0.2.0").
- [ ] Bump `CFBundleVersion` (build number; must increment per upload).
- [ ] Archive for "Any iOS Device".
- [ ] Validate the archive (Xcode Organizer → Validate App).
- [ ] Distribute App → Upload.
- [ ] In App Store Connect, create a new version, attach the build,
      update "What's New", submit for review.
- [ ] App Review: 1–3 days typical for first review, often <24 hr for
      updates. Have the test Google account credentials handy in
      "App Review Information".

---

## 3. CI / release automation (later)

For now, manual releases off your dev machine are fine.

When the team grows, candidates:
- **Android**: Fastlane `supply` for Play Console upload, or GitHub
  Actions with the Play Publisher Plugin. Either way, store the
  keystore + service account JSON as encrypted secrets.
- **iOS**: Fastlane `match` for shared signing certs, `gym` for
  archives, `pilot` for TestFlight. Or Xcode Cloud (built into Xcode,
  $$).

---

## 4. Quick reference — first production release

```
[ ] 0.1  Google OAuth credentials (web + iOS + Android client IDs)
[ ] 0.2  Privacy policy hosted publicly
[ ] 0.3  Screenshots + store listing copy reviewed
[ ] 0.4  Crashlytics wired up (optional but recommended)

ANDROID
[ ] 1.1  Generate quickink-upload.jks keystore
[ ] 1.2  Add signingConfigs + R8 rules to build.gradle.kts
[ ] 1.3  Verify versionCode = 1, versionName = "0.1.0"
[ ] 1.4  Build AAB: ./gradlew :apps:quickink:bundleRelease
[ ] 1.5  Play Console: create app, fill content, upload AAB to internal track
[ ] 1.6  Promote internal → closed → production after smoke tests pass

iOS
[ ] 2.1  Create Xcode app target (QuickInk.xcodeproj alongside Package.swift)
[ ] 2.2  Register bundle ID in developer portal + App Store Connect
[ ] 2.3  Fill Info.plist (URL types, usage strings, UIAppFonts, UILaunchScreen)
[ ] 2.4  Configure code signing (team + bundle ID)
[ ] 2.5  Archive → Upload to App Store Connect
[ ] 2.6  Fill App Privacy, screenshots, description; submit for review
[ ] 2.7  TestFlight beta with the same build before going live
```

If you want me to draft the actual Play Store description copy, the
App Store description copy, or stub the `signingConfigs` block into
`build.gradle.kts` directly — say the word.
