# QuickInk brand guide

QuickInk's identity is warm and editorial — paper and ink, not silicon. The
mark is calligraphic. The wordmark is a Garamond. Color is restrained: cream,
coral, dark ink. Typography is layered: serif for content, handwritten for
quick notes, system sans for UI.

## Color tokens

| Token             | Hex       | Usage                                            |
|-------------------|-----------|--------------------------------------------------|
| Canvas            | `#FAF7F2` | App background, splash, page backgrounds         |
| Surface           | `#FFFFFF` | Cards, modals, primary input fills               |
| Border            | `#EDE4D2` | Dividers, card borders                           |
| Border soft       | `#F0E9DD` | Pill backgrounds, search bar fill                |
| Border softer     | `#F5EDE0` | Category tag backgrounds                         |
| Accent (coral)    | `#D97757` | CTAs, active state, FAB, drop on the mark        |
| Ink (primary)     | `#2C2826` | Body text, mark stroke, dominant ink             |
| Ink soft          | `#5C4A38` | Secondary text                                   |
| Muted             | `#A8A29E` | Tertiary text, inactive nav                      |
| Paper warm 1      | `#E8DCC4` | Note thumbnail bg, variant 1                     |
| Paper warm 2      | `#F0E4D7` | Note thumbnail bg, variant 2                     |
| Paper warm 3      | `#EADFCF` | Note thumbnail bg, variant 3                     |

The token table is the source of truth. iOS surfaces it through
`QuickInkColors` (`QuickInkTheme.swift`); Android through `QuickInkColors.kt`
(Compose) and `res/values/colors.xml` (XML system).

Coral is preserved across light + dark mode. Canvas and ink invert.

## Typography

| Family                | Use                                                   |
|-----------------------|-------------------------------------------------------|
| Cormorant Garamond    | Headings, hero copy, wordmark, editorial body         |
| Caveat                | Handwritten previews, taglines, quick-note callouts   |
| System sans           | UI labels, chips, nav, status pills, microcopy        |

Two weights only across all UI surfaces — regular (400) and medium (500).
Never 600+. Never all-caps. Sentence case throughout.

Both Cormorant Garamond and Caveat are bundled in
`ios/QuickInk/DesignSystem/Fonts/` and registered via `QuickInkFont.registerAll()`
on iOS. The Android side currently uses pre-rendered PNGs for marketing
assets that need Cormorant; bundling the TTF as a font resource is a
follow-up.

## The mark

The QuickInk mark is a calligraphic Q — an open ink-pen ring, a flowing tail,
and a single coral drop at the tail's end. The mark uses ink `#2C2826` for the
strokes and accent `#D97757` for the drop.

Master at `design/source/quickink-icon-1024.png`. Centered exports under
`design/exports/quickink-mark-{1024,512,256,128}.png`.

### Clear space

Reserve at least one bowl-diameter of empty space on every side of the mark.
On the splash and onboarding screens the mark sits at 28% of the device's
short edge with the wordmark below it.

### Don't

- Don't recolor the mark (cream → coral, etc.). The brand is the cream/coral/ink
  combination; recoloring breaks recognition.
- Don't add a stroke around the mark.
- Don't stretch or skew. The aspect ratio of the source PNG is the mark's
  proportion.
- Don't drop a shadow under the mark.

## The wordmark

`QuickInk` set in Cormorant Garamond Medium with default tracking and a tiny
positive kerning. Master at
`design/exports/quickink-wordmark-{1024,512}.png`.

## Lockups

Two canonical compositions:

- Stacked — mark above wordmark, used on splash, onboarding, and any tall
  format. `design/exports/quickink-lockup-stacked{,-cream}.png`.
- Horizontal — mark left of wordmark, vertically centered on the wordmark's
  visual midpoint. Used in headers, email signatures, narrow banners.
  `design/exports/quickink-lockup-horizontal{,-cream}.png`.

Tagline (when used): `scan, jot, find again.` in Caveat, ink-soft `#5C4A38`,
size proportional to the wordmark's x-height.

## App icon

Cream-alt variant — flat cream square with the calligraphic Q and coral drop
centered. iOS applies its own squircle mask; Android uses the adaptive icon
system with cream background + Q foreground.

- iOS: `ios/QuickInk/Resources/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png`
- Android adaptive: `android/app/src/main/res/drawable/ic_launcher_*.xml` +
  `drawable-nodpi/ic_launcher_foreground.png`
- App Store marketing: `design/exports/app-store-icon-1024.png`
- Play Store icon: `design/exports/play-store-icon-512.png`
- Play Store feature graphic: `design/exports/play-store-feature-1024x500.png`

## Splash

Minimal mark variant — cream canvas, mark + wordmark stacked at the optical
center. No tagline, no chrome.

- iOS SwiftUI: `ios/QuickInk/App/SplashView.swift`
- Android theme + drawable: `Theme.QuickInk.Splash`,
  `drawable/splash_background.xml`, `drawable-nodpi/splash_mark.png`,
  `drawable-nodpi/splash_wordmark.png`
- Pre-rendered references: `design/exports/splash-{750x1334,1125x2436,1170x2532,1242x2688,1284x2778}.png`
- Android marketing: `design/exports/splash-android-1080x1920.png`

## UI icon set

Six line icons, 24×24, 1.7 px stroke, ink `#2C2826`, round caps and joins,
no fills. SVGs at `design/exports/icons/{name}.svg` — masters. Contact sheet
at `design/exports/icons/icon-set-contact-sheet.png`.

| Icon    | Use                                       |
|---------|-------------------------------------------|
| scan    | Camera-first scan home, scan capture button |
| note    | Notes list, note editor entry              |
| search  | Search bar, search nav tab                 |
| sync    | Drive sync status, manual sync trigger     |
| tag    | Tag chip, tag filter                       |
| archive | Archive folder, archive action            |

In-app:

- iOS: `ios/QuickInk/Resources/Assets.xcassets/Icon{Name}.imageset` —
  template-rendering, tint via `.foregroundStyle(...)`.
- Android: `android/app/src/main/res/drawable/ic_{name}.xml` — vector drawable,
  tint via `app:tint` or `android:tint`.

## Social

- Open Graph (OG) link preview, 1200×630:
  `design/exports/social-share-og-1200x630.png`
- Twitter header, 1500×500:
  `design/exports/social-share-twitter-1500x500.png`

Both use the stacked lockup + tagline on cream.

## Asset index — design/exports/

```
design/source/
  quickink-icon-1024.png             ← original input from designer

design/exports/
  Mark
    quickink-mark-1024.png           ← mark only, transparent
    quickink-mark-512.png
    quickink-mark-256.png
    quickink-mark-128.png
    quickink-mark-cream-1024.png     ← mark on cream square

  Wordmark
    quickink-wordmark-1024.png       ← Cormorant Garamond Medium
    quickink-wordmark-512.png

  Lockups
    quickink-lockup-stacked.png      ← mark above wordmark
    quickink-lockup-stacked-cream.png
    quickink-lockup-horizontal.png   ← mark left of wordmark
    quickink-lockup-horizontal-cream.png

  App icons
    app-store-icon-1024.png
    play-store-icon-512.png
    play-store-feature-1024x500.png

  Splash
    splash-750x1334.png              ← iPhone SE
    splash-1125x2436.png             ← iPhone 11 Pro / 12 mini
    splash-1170x2532.png             ← iPhone 14 / 15
    splash-1242x2688.png             ← iPhone 11 Pro Max
    splash-1284x2778.png             ← iPhone 14 / 15 Pro Max
    splash-android-1080x1920.png

  UI icons
    icons/scan.svg / scan-256.png
    icons/note.svg / note-256.png
    icons/search.svg / search-256.png
    icons/sync.svg / sync-256.png
    icons/tag.svg / tag-256.png
    icons/archive.svg / archive-256.png
    icons/icon-set-contact-sheet.png

  Social
    social-share-og-1200x630.png
    social-share-twitter-1500x500.png
```

## Voice

Editorial, not energetic. The two-beat pattern: a quiet promise, then a one-line
proof. Avoid exclamation, jargon, and engagement-funnel verbs ("crush", "level
up", "supercharge"). Examples:

- A pocket notebook that remembers.
- Scan a page, jot a thought, find it later by any word.
- Your paper, searchable.

## Companion docs

- `design/SPLASH_INTEGRATION.md` — wiring notes for iOS launch screen + Android
  splash theme.
- `design/ICON_REPLACE_NOTES.md` — history of the app icon replacement, plus
  cleanup commands for orphan files left by the build sandbox.
- `design/PROMPTS.md` — Figma / Canva / Midjourney prompts for generating
  on-brand assets without breaking the design system.
