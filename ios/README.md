# Releaf iOS

Swift Package + SwiftUI. Open `Package.swift` in Xcode (File → Open →
`Package.swift`) and SwiftPM does the rest — previews render immediately
from any file under `Releaf/DesignSystem/Components` or `Releaf/Features`.

## Target map

| SwiftPM target        | Path                 | Depends on |
| --------------------- | -------------------- | ---------- |
| `ReleafDesignSystem`  | `Releaf/DesignSystem`| (standalone) |
| `ReleafData`          | `Releaf/Data`        | (standalone) |
| `ReleafFeatures`      | `Releaf/Features`    | DesignSystem + Data |

The `ReleafFeatures` target owns the SwiftUI screens (`ReleafApp`, `RootView`,
`SignInScreen`, `HomeScreen`).

## Shipping an actual app

SwiftPM can't build an iOS app executable target by itself. To ship:

1. Create a new Xcode project → iOS → App → SwiftUI, bundle ID `app.releaf.mobile`.
2. Add this repo as a local Swift Package dependency.
3. Add `ReleafFeatures` to the app target's frameworks.
4. In your app's `@main` entry point, replace the generated body with
   `ReleafApp().body`.

## Info.plist usage strings

The notepad editor's feature sections trigger system permission prompts.
Add these keys to your app target's `Info.plist` — without them, iOS denies
the underlying API call immediately and the feature appears to silently
do nothing.

| Key | Section that needs it | Prompt text suggestion |
| --- | --------------------- | ---------------------- |
| `NSLocationWhenInUseUsageDescription` | LOCATION       | "Releaf needs your location to attach a place to this entry." |
| `NSCameraUsageDescription`            | SCAN DOCUMENTS | "Releaf uses the camera to scan documents into this entry." |

The PHOTOS section uses SwiftUI's `PhotosPicker`, which runs out-of-process
and doesn't require a photo-library permission string.

## Google Sign-In wiring (follow-up drop)

Replace `StubGoogleAuthClient` in `Releaf/Data/Auth/AuthStore.swift` with a
wrapper around [GoogleSignIn-iOS](https://github.com/google/GoogleSignIn-iOS).
You'll need to:

- Add the `GoogleSignIn` SPM package.
- Register an OAuth client ID in Google Cloud console for bundle `app.releaf.mobile`.
- Add the reversed client ID as a URL scheme in your `Info.plist`.
- Request scope `https://www.googleapis.com/auth/drive.file`.

The `GoogleAuthClient` protocol doesn't change — only the concrete class does.
