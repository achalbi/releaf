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

## Master sources

- `design/source/quickink-icon-1024.png` — original 1254×1254 PNG you provided.
- `design/exports/quickink-mark-{1024,512,256,128}.png` — mark only, transparent background, multi-size.
- `design/exports/quickink-mark-cream-1024.png` — mark on solid cream square.
- `design/exports/splash-{750x1334,1125x2436,1170x2532,1242x2688,1284x2778}.png` — pre-rendered iPhone portrait launch images (in case the Info.plist UILaunchScreen approach is too restrictive and you fall back to bitmap launch images).
- `design/exports/splash-android-1080x1920.png` — pre-rendered Android splash for marketing screenshots / Play Store.

## Cleanup reminder

From `ICON_REPLACE_NOTES.md` — five orphan placeholder PNGs at `app/src/main/res/drawable-{m,h,xh,xxh,xxxh}dpi/ic_launcher_foreground_bitmap.png` (each 1×1 transparent). Optional `rm` cleanup command in that file. Won't affect the splash or icon work.
