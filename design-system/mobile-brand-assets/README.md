# Releaf Mobile Brand Asset Kit

Mobile-facing assets derived from the April 24, 2026 Releaf marketing board.
The kit centers the app story on the reusable notebook loop:

- `Write. Erase. Repeat.`
- reusable notebook + smart app companion
- scan, save, share, and backup
- one notebook, one tree, one future

## Source SVGs

| File | Purpose |
| --- | --- |
| `source/releaf-logo-lockup.svg` | Primary green logo lockup with tagline. |
| `source/releaf-app-icon.svg` | 1024 px app icon source. |
| `source/releaf-splash-screen.svg` | Portrait mobile splash artwork source. |
| `source/releaf-landing-page.svg` | Portrait mobile landing / sign-in hero source. |
| `source/releaf-mobile-icons.svg` | Six in-app brand feature icons. |

## Generated PNGs

The generated PNGs in `generated/` are exported from the SVG sources with
`rsvg-convert`. Regenerate from the repo root with:

```sh
./design-system/mobile-brand-assets/render.sh
```

## In-App Surfaces

The app uses native SwiftUI and Compose versions of the same brand system for:

- launch splash
- signed-out landing page
- reusable/sustainable/smart feature icons
- scan/save/share product story

