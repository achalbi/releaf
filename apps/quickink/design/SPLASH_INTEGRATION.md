# QuickInk splash + launch — integration notes

Locked-in design choices (from the brand prototype board, v1):

- **Logo direction A** — calligraphic Q + ink-tail flourish + coral drop. Master at `design/source/quickink-icon-1024.png`. Mark-only exports under `design/exports/`.
- **App icon variant** — Cream alt (cream squircle, dark ink Q, coral drop). Already wired into both platforms.
- **Splash variant** — Minimal mark. No tagline, no chrome — just the Q centered on the cream canvas.

## What's already wired

### iOS

- `ios/QuickInk/Resources/Assets.xcassets/QuickInkMark.imageset/QuickInkMark.svg` — replaced with an SVG that embeds the new mark at 512×512 (preserves vector representation flag is on, so SwiftUI scales it crisply).
- `ios/QuickInk/Resources/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png` — flat cream square with Q centered. iOS applies its own squircle mask on the home screen.
- `ios/QuickInk/App/SplashView.swift` — SwiftUI splash view (cream `ignoresSafeArea` + centered mark at 36% short edge). Compiles against the existing library; ready to use the moment the Xcode app target lands.

### Android

- `app/src/main/res/drawable/ic_launcher_*.xml` and `drawable-*dpi/` — adaptive icon updated. Background = cream `#FAF7F2`, foreground = calligraphic Q + drop.
- `app/src/main/res/values/colors.xml` — new file, brand color tokens for the resource system (canvas, ink, accent, border).
- `app/src/main/res/values/themes.xml` — new file, two themes:
  - `Theme.QuickInk` — the real app theme (cream window background, light status/nav bars).
  - `Theme.QuickInk.Splash` — inherits from above, overrides `windowBackground` to the splash drawable.
- `app/src/main/res/drawable/splash_background.xml` — layer-list with cream + centered 160dp mark.
- `app/src/main/AndroidManifest.xml` — application now uses `Theme.QuickInk`; the launcher activity overrides to `Theme.QuickInk.Splash`.
- `app/src/main/java/app/quickink/mobile/MainActivity.kt` — calls `setTheme(R.style.Theme_QuickInk)` before `super.onCreate(...)` so the splash drawable doesn't leak into the in-app window.

The Android splash works on every API level the app targets (minSdk 26+) without adding any new Gradle dependency.

## What still needs the user / the app target

### iOS — when the Xcode app target lands

The repo is currently a Swift Package (`Package.swift`) with no Xcode app target. When it's created:

1. Add `Info.plist` with the modern launch screen dictionary:
   ```xml
   <key>UILaunchScreen</key>
   <dict>
       <key>UIColorName</key>
       <string>QuickInkCanvas</string>
       <key>UIImageName</key>
       <string>QuickInkMark</string>
   </dict>
   ```
   Both `QuickInkCanvas` (a color set) and `QuickInkMark` (already present) live in the asset catalog. Add a `QuickInkCanvas.colorset` if you don't have one — single value `#FAF7F2` (sRGB) for both light + dark.
2. In your `@main App` struct, gate the first frame on a brief in-process `SplashView()` if you want a smooth handoff between system splash teardown and Compose-side onboarding readiness — see the comment in `SplashView.swift` for the pattern.

### Android — optional upgrade to the modern SplashScreen API

The current setup uses the classic theme + windowBackground approach. If/when you want the Android 12+ animated splash:

1. Add to `gradle/libs.versions.toml` and the app's `build.gradle.kts` dependencies:
   ```kotlin
   implementation("androidx.core:core-splashscreen:1.0.1")
   ```
2. Change `Theme.QuickInk.Splash`'s parent in `themes.xml` to `Theme.SplashScreen` and replace its body with:
   ```xml
   <item name="windowSplashScreenBackground">@color/quickink_canvas</item>
   <item name="windowSplashScreenAnimatedIcon">@drawable/ic_launcher_foreground</item>
   <item name="postSplashScreenTheme">@style/Theme.QuickInk</item>
   ```
3. In `MainActivity.onCreate`, replace the `setTheme(R.style.Theme_QuickInk)` line with `installSplashScreen()` (called BEFORE `super.onCreate`).

Both approaches show the same visual — the modern API just animates the icon scale-in and integrates with the system's launch animation.

## Cinematic launch animation (native port)

The minimal-mark splash above is the brand's static fallback. The cinematic launch animation handed off in `design_handoff_quickink_launch/` (5-second tree-planting / Tree-points reveal) ships on top of it as a **native port** of the React/SVG prototype — no Lottie, no After Effects. Design did not produce an AE source; the prototype is implemented in React + custom animation primitives, which we ported directly to SwiftUI Canvas (iOS) and Compose Canvas (Android).

### Why a native port

The handoff at `design_handoff_quickink_launch/source/` is a runnable React prototype (`scene.jsx` + `animations.jsx`), not an AE composition. Three options were considered:

1. **Render the React prototype to MP4** — pixel-exact but bakes in `target`, ~1–2 MB asset.
2. **Recreate in AE then export to Lottie** — faithful but requires building an AE comp from scratch (~2 weeks design effort).
3. **Native port** ← **chosen**: SwiftUI Canvas + Compose Canvas, structurally 1:1 with the JSX. Dynamic `target`, no asset to ship, no third-party dep.

### What's wired

Both platforms structure the port the same way — a Canvas-rendered SVG scene plus three React-style overlays (Tree-points pill, logo lockup, home-feed transition):

- **Android** — `features/splash/QuickInkLaunchAnimation.kt` (host) drives time via `withFrameNanos`. The scene lives in `features/splash/launch/`:
  - `LaunchEasing.kt` — easings, between, lerp, clamp.
  - `LaunchPalette.kt` — Dawn / Morning Mist / Golden Hour palettes.
  - `LaunchScene.kt` — Canvas with sky, sun, clouds, mountains, birds, ground, pollen, stumps, growing tree, water stream.
  - `LaunchSceneFamily.kt` — the four family members (mother / daughter / son / father with watering can).
  - `LaunchOverlays.kt` — Tree-points pill, logo lockup, home-feed transition.

- **iOS** — `App/LaunchAnimationView.swift` (host) drives time via `TimelineView(.animation)`. Mirror file structure under `App/LaunchAnimation/`:
  - `LaunchEasing.swift`, `LaunchPalette.swift`, `LaunchScene.swift`, `LaunchSceneFamily.swift`, `LaunchOverlays.swift`.

Files mirror each other layer-by-layer with the same magic numbers, easings, and parameter names so cross-platform parity audits are trivial — search for a token name, compare iOS vs Android vs the prototype line by line.

### Reduced motion

Both platforms honor the system reduced-motion preference (iOS `Environment(\.accessibilityReduceMotion)`, Android `Settings.Global.TRANSITION_ANIMATION_SCALE == 0`). When on, time is pinned at t=2.5s (mid-bloom — tree visible, family present, counter mid-tick) and the dismissal is shortened to ~1.4s.

### Wiring `target`

`target` is the user's lifetime Tree-points balance, fed into the counter pill and the home-feed hero. Both hosts default to `247` (the prototype's preview value) until `QuickInkRoot` (iOS) / `MainActivity` (Android) wires the live page count. The host signature already accepts a `target` parameter, so the wiring is a one-line change once we settle on whether to read the page total synchronously at splash-time (opens the database before first frame) or async-with-restart.

### Removed

- Lottie SPM dep (`lottie-spm 4.5.0`) — removed from `ios/Package.swift`.
- Lottie Compose dep (`com.airbnb.android:lottie-compose:6.5.2`) — removed from `android/app/build.gradle.kts`.
- `ios/QuickInk/Resources/Animations/` — directory + `.gitkeep` deleted.
- `Resources/Animations` resource processing entry — removed from `Package.swift`.

## Master sources

- `design/source/quickink-icon-1024.png` — original 1254×1254 PNG you provided.
- `design/exports/quickink-mark-{1024,512,256,128}.png` — mark only, transparent background, multi-size.
- `design/exports/quickink-mark-cream-1024.png` — mark on solid cream square.
- `design/exports/splash-{750x1334,1125x2436,1170x2532,1242x2688,1284x2778}.png` — pre-rendered iPhone portrait launch images (in case the Info.plist UILaunchScreen approach is too restrictive and you fall back to bitmap launch images).
- `design/exports/splash-android-1080x1920.png` — pre-rendered Android splash for marketing screenshots / Play Store.

## Cleanup reminder

From `ICON_REPLACE_NOTES.md` — five orphan placeholder PNGs at `app/src/main/res/drawable-{m,h,xh,xxh,xxxh}dpi/ic_launcher_foreground_bitmap.png` (each 1×1 transparent). Optional `rm` cleanup command in that file. Won't affect the splash or icon work.
