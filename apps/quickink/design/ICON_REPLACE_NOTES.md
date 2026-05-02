# QuickInk app icon — replacement notes

**Source:** `design/source/quickink-icon-1024.png` (1254×1254, calligraphic Q + ink-tail flourish + coral drop, cream squircle).

## What was changed

### iOS
- `ios/QuickInk/Resources/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png` — replaced.
  Source was processed: trimmed white outer margin, resized to 1024×1024, and the
  area outside the original squircle was filled with brand cream `#FAF7F2` so iOS's
  own squircle mask produces clean edges. Verified all corner pixels are now `#FAF7F2`.

### Android (adaptive icon, API 26+)
- `android/app/src/main/res/drawable/ic_launcher_background.xml` — solid `#FAF7F2` (was solid `#D97757` coral).
- `android/app/src/main/res/drawable-nodpi/ic_launcher_foreground.png` — the calligraphic Q at 1024×1024 with cream made transparent (Q + tail + coral drop only). This is what the launcher actually renders, because the `-nodpi` qualifier outranks the unqualified vector at runtime.
- `android/app/src/main/res/drawable/ic_launcher_foreground.xml` — geometric Q vector kept as a fallback (only used if `-nodpi` lookup fails for some reason). Updated colors to `#2C2826` strokes + `#D97757` drop to match the cream background.
- `android/app/src/main/res/drawable/ic_launcher_monochrome.xml` — geometric Q in pure black, used by Android 13+ Material You themed icons.

## Cleanup (manual, recommended)

The build sandbox couldn't delete files, so a few unused orphans were left behind. They're each ≤ 300 bytes (1×1 transparent placeholders), so they won't visibly affect anything, but you can clean them up from your terminal:

```bash
cd /Users/achalindiresh/workspace/releaf/apps/quickink/android/app/src/main/res
rm drawable-mdpi/ic_launcher_foreground_bitmap.png
rm drawable-hdpi/ic_launcher_foreground_bitmap.png
rm drawable-xhdpi/ic_launcher_foreground_bitmap.png
rm drawable-xxhdpi/ic_launcher_foreground_bitmap.png
rm drawable-xxxhdpi/ic_launcher_foreground_bitmap.png
# the drawable-Xdpi folders themselves can stay — they may hold other density-specific assets later
```

These were created during an earlier attempt that I had to abandon. They're declared as `@drawable/ic_launcher_foreground_bitmap` but nothing references that name.

## Verifying the replacement

**iOS:** open `ios/QuickInk.xcodeproj` (or workspace) in Xcode → Assets.xcassets → AppIcon → confirm 1024×1024 preview shows the calligraphic Q on cream.

**Android:** `./gradlew :app:assembleDebug` and install on device/emulator. The home-screen launcher will show:
- On Pixel-style rounded-square mask: full Q on cream, similar to iOS.
- On circular mask launchers: Q centered, with cream visible at the corners.
- On Material You themed icons (Android 13+): geometric Q silhouette in the user's wallpaper-derived color.

If the build complains about duplicate resources for `ic_launcher_foreground`, run the cleanup above and rebuild.

## Master sources

- `design/source/quickink-icon-1024.png` — the original at full res. Keep this; future regeneration of density-specific assets (or marketing exports at 2048+, App Store 1024 marketing icon, Google Play 512×512 feature graphic) should regenerate from this.
