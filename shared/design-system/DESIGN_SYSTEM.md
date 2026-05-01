# Releaf design system

Warm, paper-toned, editorial. One primary accent (coral), one secondary
(deep green), plus a small semantic palette for status + counts.

Source of truth: [`design-tokens.json`](./design-tokens.json) in
[Tokens Studio schema](https://tokens.studio/). The token file drives Figma
Variables, iOS `AppColors`/`AppTypography`, and Android theme files.

## Color

### Surface

| Token          | Hex        | Use |
| -------------- | ---------- | --- |
| `canvas`       | `#F5EEE3`  | App background — warm cream, paper feel |
| `cardSolid`    | `#FFFAF4`  | Opaque card fill |
| `card`         | `#FFFAF4E6`| Card over canvas (90% opacity) |
| `subtle`       | `#EFE7DA`  | Subtle alt surface (search, chips) |

### Text

| Token     | Hex        | Use |
| --------- | ---------- | --- |
| `primary` | `#241D17`  | Body, headings |
| `secondary` | `#5F5245`| Meta, labels |
| `tertiary` | `#8A7C6D` | Placeholder |
| `onAccent` | `#FFFFFF` | On coral / black fills |

### Accent

| Token        | Hex       | Use |
| ------------ | --------- | --- |
| `coral`      | `#E77850` | Primary CTA, FAB, active tab |
| `coralSoft`  | `#FCEAE0` | Chip backgrounds, active-tab tint |
| `coralDeep`  | `#C85A30` | Pressed coral |
| `green`      | `#1E5943` | Sync-success CTA |
| `greenSoft`  | `#D9EDE2` | Sync-success tint |

### Leaf theme variants

User-selectable accent palettes, one per season. Each variant exposes four
slots — `primary`, `primaryDeep`, `bgSoft` (10% alpha wash), `borderSoft`
(30% alpha). Tokens live under `color.theme.*` in `design-tokens.json` and
emit as `themeCoral*` / `themeGreen*` / `themeYellow*` / `themeDry*` on both
platforms.

| Theme   | Primary   | Deep      | Character                              |
| ------- | --------- | --------- | -------------------------------------- |
| coral   | `#E07856` | `#C65A3E` | Warm, energetic, creative (default)    |
| green   | `#7AA874` | `#5B8C52` | Natural, organic, growth-focused       |
| yellow  | `#F4C430` | `#E8B923` | Bright, optimistic, energizing         |
| dry     | `#B8956A` | `#8B7355` | Earthy, grounded, reflective           |

No dark-mode pair yet — these are flat until a dark variant is designed.

### Semantic

Pairs of text/background for status and tags:
`success / successSoft`, `info / infoSoft`, `warning / warningSoft`,
`neutral / neutralSoft`, plus `danger` for destructive.

### Action

| Token             | Hex       |
| ----------------- | --------- |
| `primary`         | `#1A1A1A` |
| `primaryPressed`  | `#000000` |
| `onPrimary`       | `#FFFFFF` |

## Typography

Two families:

- **Sans** — Manrope, for everything except editorial titles.
- **Serif** — Newsreader, for page titles and entry headers.

Sizes range `10 / 12 / 13 / 15 / 17 / 20 / 26 / 32`. Weights: `400 / 500 / 600 / 700`.

Roles:

| Role           | Size | Weight   | Family | Letter-spacing |
| -------------- | ---- | -------- | ------ | -------------- |
| eyebrow        | 10   | 600      | sans   | 0.08em, UPPERCASE |
| body           | 15   | 400      | sans   | 0 |
| meta           | 13   | 400      | sans   | 0 |
| button         | 13   | 600      | sans   | 0 |
| sectionTitle   | 20   | 700      | sans   | 0 |
| statNumber     | 32   | 700      | sans   | 0 |
| editorialTitle | 26   | 500      | serif  | 0 |
| tag            | 11   | 600      | sans   | 0 |

## Spacing

4-pt grid: `0 / 4 / 8 / 12 / 16 / 20 / 24 / 32 / 40`. Card inner padding defaults
to `16`. Between-sections gap defaults to `24`.

## Radii

`6` (inputs) · `12` (cards) · `16` (feature cards, hero surfaces) · `9999`
(pills, tags, buttons).

## Shadows

Four elevations — `none`, `sm` (hairline), `card` (subtle cream-toned lift),
`fab` (coral glow for the capture FAB).

## Motion

Durations `150ms / 220ms / 320ms`. Standard easing
`cubic-bezier(0.2, 0, 0, 1)` for most transitions.

## Component inventory (skeleton phase)

The skeleton ships the following in both platforms' design system modules:

- `AppColors`
- `AppTypography`
- `AppSpacing`
- `AppRadius`
- `Card` (surface container, `cardSolid` fill, `md` radius, hairline border)
- `AppButton` (primary, secondary/outline, text variants)

Future drops add: `CaptureFAB`, `CaptureTabBar`, `NotebookRow`,
`PagePreviewRow`, `StatGrid`, `QuickCaptureSheet`, `Tag/Pill`, `EmptyState`.

## Naming convention

- Semantic names everywhere (`textPrimary`, not `brown900`).
- `onAccent` / `onPrimary` for foreground-on-fill.
- `*Soft` suffix for the subtle tint companion of a bold color.
