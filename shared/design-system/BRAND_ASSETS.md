# Releaf brand assets

Non-code half of the Releaf Branding spec — app name, tagline, description,
keywords, and every platform asset size worth exporting. The color/type/space
tokens this sits beside live in [`design-tokens.json`](./design-tokens.json);
this file captures everything that doesn't compile.

Sourced from the Figma Make "Releaf Branding" export (April 2026).

## Identity

| Field         | Value                                                                   |
| ------------- | ----------------------------------------------------------------------- |
| App name      | Releaf                                                                  |
| Tagline       | Write. Erase. Repeat.                                                   |
| Description   | Reusable notebook + smart app companion                                 |
| Keywords      | reusable notebook, scanning, sustainability, notes, organization        |

## April 2026 mobile asset kit

The mobile app now uses the attached April 24, 2026 marketing board as the
source for launch and signed-out brand surfaces. Editable sources and PNG
exports live in [`mobile-brand-assets/`](./mobile-brand-assets/):

| Asset | Source |
| --- | --- |
| Logo lockup | `mobile-brand-assets/source/releaf-logo-lockup.svg` |
| App icon | `mobile-brand-assets/source/releaf-app-icon.svg` |
| Splash screen | `mobile-brand-assets/source/releaf-splash-screen.svg` |
| Landing page | `mobile-brand-assets/source/releaf-landing-page.svg` |
| Feature icons | `mobile-brand-assets/source/releaf-mobile-icons.svg` |

## App icons

### iOS

| Purpose        | Size            | Notes                       |
| -------------- | --------------- | --------------------------- |
| App Store      | 1024 × 1024 px  | No alpha channel            |
| iPhone @3x     | 180 × 180 px    |                             |
| iPhone @2x     | 120 × 120 px    |                             |
| iPad           | 76 × 76 px      |                             |
| Settings       | 60 × 60 px      |                             |

### Android

| Purpose      | Size            |
| ------------ | --------------- |
| Play Store   | 512 × 512 px    |
| xxxhdpi      | 192 × 192 px    |
| xxhdpi       | 144 × 144 px    |
| xhdpi        | 96 × 96 px      |
| hdpi         | 72 × 72 px      |
| mdpi         | 48 × 48 px      |

## Splash screens

### iOS

| Device                     | Size            |
| -------------------------- | --------------- |
| iPhone 15 Pro Max          | 1170 × 2532 px  |
| iPhone X / XS / 11 Pro     | 1125 × 2436 px  |
| iPad Pro 12.9"             | 2048 × 2732 px  |

### Android

| Bucket   | Size            |
| -------- | --------------- |
| xhdpi    | 1080 × 1920 px  |
| xxhdpi   | 1440 × 2560 px  |
| xxxhdpi  | 1920 × 3840 px  |

## Marketing

| Placement                   | Size            |
| --------------------------- | --------------- |
| Play Store feature graphic  | 1024 × 500 px   |
| App Store screenshot        | 1290 × 2796 px  |
| Social — square             | 1200 × 1200 px  |
| Social — landscape          | 1200 × 630 px   |
| Website hero (min.)         | 1920 × 1080 px  |

## Design principles

1. **Warm & welcoming** — soft neutrals create an inviting, comfortable space.
2. **Literary aesthetic** — editorial typography and generous spacing.
3. **Grounded in nature** — leaf iconography and organic color themes.
4. **Touch-optimized** — 44 × 44 px minimum tap targets, smooth interactions.
5. **Accessible** — high contrast ratios and clear visual hierarchy.

## See also

- [`DESIGN_SYSTEM.md`](./DESIGN_SYSTEM.md) — color, type, spacing, components.
- [`design-tokens.json`](./design-tokens.json) — machine-readable source of truth.
- `color.theme.{coral,green,yellow,dry}` in `design-tokens.json` — the four
  user-selectable leaf themes (primary / deep / bgSoft / borderSoft each).
