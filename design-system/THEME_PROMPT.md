# Releaf — Environmental Design Theme Prompt

Use this prompt as a self-contained brief for designing new pages, screens, or marketing surfaces that need to feel like they belong inside Releaf. Hand it to a designer, a Figma/Make session, or another AI as-is.

---

## One-paragraph mood

Releaf feels like a warm, paper-toned daily-capture notebook. It is editorial, calm, and tactile — the canvas reads as cream paper with a faint dot grid, content sits on slightly off-white cards with hairline warm-brown borders, and a single coral accent does almost all of the visual lifting. Headings can lean editorial (a Newsreader serif at the page title) over a Manrope sans body. Nothing shouts: shadows are soft and warm, corners are gently rounded, and motion is short and standard-eased. Think analog journal meets quiet productivity app — never glassy, neon, glossy, or corporate.

## Brand pillars (use these to break ties)

- **Warm paper, not white screen.** Every surface is a cream tone (`#F5EEDF` canvas, `#FFFAF4` card). Pure white and pure black are reserved for the primary action pill only.
- **Editorial restraint.** One coral accent. One serif (page titles only). No gradients, no glows, no glass.
- **Notebook texture.** A subtle dot grid runs under everything. It is decoration, not data — barely visible.
- **Hairline definition.** Surfaces are separated by ~12% warm-brown borders, not by heavy shadows or color blocks.
- **Quiet hierarchy.** Eyebrows (uppercase, 10pt, tracked, coral) introduce sections; serif titles anchor pages; sans body carries the content.

## Anti-patterns (do NOT do)

- Pure white backgrounds, gray drop-shadows, or blue links.
- Material You / iOS 17 frosted-glass surfaces, neumorphism, gradients.
- Saturated brand-banner stripes across the top of the screen.
- Coral used everywhere — coral is precious; it marks one CTA per screen, max.
- Drop shadows that are cool/blue-grey. Shadows here are warm and small.
- Sentence case in eyebrows; eyebrows are UPPERCASE with 0.08em tracking.
- Mixing serif into body, button, or meta text. Serif is page titles only.

---

## Color tokens (light mode, hex)

### Surface

| Role             | Hex        | Where it goes                                   |
| ---------------- | ---------- | ----------------------------------------------- |
| `canvas`         | `#F5EEDF`  | Outermost app background — always               |
| `cardSolid`      | `#FFFAF4`  | Card / list-row / sheet fill                    |
| `card` (90%)     | `#FFFAF4E6`| Card laid over canvas with the dot grid showing |
| `subtle`         | `#EFE7DA`  | Search field, chip wells, alt rows              |
| `muted`          | `#EBE4D3`  | Disabled buttons, very subtle fills             |
| `inputBg`        | `#463C310D`| Input field fill (5% of primary text)           |

### Text

| Role        | Hex       | Use                              |
| ----------- | --------- | -------------------------------- |
| `primary`   | `#463C31` | Body, headings (warm dark brown) |
| `secondary` | `#5F5245` | Meta, labels                     |
| `tertiary`  | `#8A7C6D` | Placeholders, faint timestamps   |
| `onAccent`  | `#F5EEDF` | Text on coral fills (cream — not white) |
| `onPrimary` | `#FFFFFF` | Text on the black action pill    |

### Border

| Role             | Hex / alpha   | Use                          |
| ---------------- | ------------- | ---------------------------- |
| `borderDefault`  | `#503E2D` @12%| All cards, inputs, chips     |
| `borderStrong`   | `#503E2D` @24%| Outline buttons, emphasis    |

### Accent (single primary)

| Role           | Hex       | Use                                              |
| -------------- | --------- | ------------------------------------------------ |
| `coral`        | `#E07856` | Primary accent — FAB, active tab, eyebrows, link |
| `coralSoft`    | `#FCEAE0` | Active-tab tint, chip wash                       |
| `coralDeep`    | `#C65A3E` | Pressed / hover coral                            |
| `coralOutline` | `#E07856` | Outline-button border + text                     |

### Action pill (one per screen)

| Role             | Hex       | Use                              |
| ---------------- | --------- | -------------------------------- |
| `actionPrimary`  | `#1A1A1A` | The single black "Continue" pill |
| `actionPressed`  | `#000000` | Pressed state                    |

### Semantic (status, tags — never decorative)

| Role           | Text      | Background |
| -------------- | --------- | ---------- |
| `success`      | `#4C9A6A` | `#E3F1E8`  |
| `info`         | `#2E6FB5` | `#E1ECF8`  |
| `warning`      | `#A87418` | `#FBEECD`  |
| `neutral`      | `#5F5245` | `#EFE7DA`  |
| `danger`       | `#C8432E` | `#FDEEE9`  |

### Leaf-theme variants (user-selectable accent — replaces `coral` only)

| Theme  | Primary   | Deep      | Mood                              |
| ------ | --------- | --------- | --------------------------------- |
| coral  | `#E07856` | `#C65A3E` | Warm, energetic (default)         |
| green  | `#7AA874` | `#5B8C52` | Natural, organic, growth          |
| yellow | `#F4C430` | `#E8B923` | Bright, optimistic                |
| dry    | `#B8956A` | `#8B7355` | Earthy, grounded, reflective      |

Each theme also exposes `bgSoft` (primary @10%) and `borderSoft` (primary @30%).

### Dark mode (sketch)

Canvas flips to `#241D17`, cardSolid to `#332B22`, primary text to `#EFE7DA`. Coral stays coral. The dot grid drops to ~5% cream so the texture fades back rather than reading as noise.

---

## Pattern: dot grid

App-wide background texture rendered on top of the canvas color.

- Spacing between dot centers: **24px**
- Dot diameter: **1px**
- Color (light): warm brown `#503E2D` at **35% alpha**
- Color (dark): cream `#FAF6F0` at **5% alpha**

It should feel like graph paper noticed only when you look for it. If you can see the dots from across the room, they're too dark.

---

## Typography

Two families. Do not introduce a third.

- **Sans** — `Manrope, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif`
- **Serif** — `Newsreader, Georgia, serif` (editorial titles only)

| Role             | Size | Weight | Family | Notes                            |
| ---------------- | ---- | ------ | ------ | -------------------------------- |
| `eyebrow`        | 10   | 600    | sans   | UPPERCASE, 0.08em tracking, coral |
| `tag`            | 11   | 600    | sans   | Inside pills/chips               |
| `meta`           | 13   | 400    | sans   | Timestamps, labels               |
| `button`         | 13   | 600    | sans   | Button labels                    |
| `body`           | 15   | 400    | sans   | Default body                     |
| `sectionTitle`   | 20   | 700    | sans   | Card / list section heading      |
| `pageTitle`      | 24   | 500    | serif  | Top-of-screen page title         |
| `editorialTitle` | 26   | 500    | serif  | Notebook / entry hero titles     |
| `statNumber`     | 32   | 700    | sans   | Big numerics in stat grids       |

Line heights: `tight 1.2 / normal 1.45 / loose 1.6`.

---

## Spacing — 4pt grid

`0 / 4 / 8 / 12 / 16 / 20 / 24 / 32 / 40`.

- Card inner padding: **16**
- Gap between sections: **24**
- Inline icon gap (icon → label): **8**

## Radii

`6` inputs · `12` cards (default) · `16` feature/hero/floating-nav · `9999` pills, tags, buttons.

## Elevation (warm shadows, two-layer)

| Level | Shadow                                                                 | Use                       |
| ----- | ---------------------------------------------------------------------- | ------------------------- |
| `xs`  | `0 1px 1px rgba(36,29,23,.05)`                                          | Hairline lift             |
| `sm`  | `0 1px 2px rgba(36,29,23,.06), 0 2px 3px rgba(36,29,23,.04)`           | Cards, list rows          |
| `md`  | `0 2px 4px rgba(36,29,23,.08), 0 4px 8px rgba(36,29,23,.05)`           | Popovers, raised surfaces |
| `lg`  | `0 4px 8px rgba(36,29,23,.10), 0 8px 16px rgba(36,29,23,.06)`          | Sheets                    |
| `fab` | `0 6px 10px rgba(200,90,48,.28), 0 2px 4px rgba(36,29,23,.10)`         | Coral-tinted FAB only     |

Shadows are warm-brown, not blue-grey. The FAB shadow is intentionally coral-tinted.

## Motion

Durations `150ms` (fast) · `220ms` (base) · `320ms` (slow).
Standard easing `cubic-bezier(0.2, 0, 0, 1)` for most transitions.
Accelerate `cubic-bezier(0.4, 0, 1, 1)` for exits; decelerate `cubic-bezier(0, 0, 0.2, 1)` for entries.

---

## Component recipes

### Page scaffold

1. `canvas` background.
2. `DotGridBackground` overlay (24/1, 35% warm brown).
3. Content in 16pt horizontal padding, sections separated by 24pt.
4. Page title: serif 24, primary text. Optionally preceded by an UPPERCASE coral eyebrow.

### Card

- Fill `cardSolid` (`#FFFAF4`).
- 1px `borderDefault` stroke.
- `12pt` radius (corners continuous if the platform supports it).
- `16pt` inner padding.
- `sm` shadow.

### Eyebrow → Title pattern (the Releaf signature)

```
RELEAF                ← eyebrow: 10pt sans 600 UPPERCASE coral, tracking 0.08em
Today's notebook      ← page/section title: serif 24 OR sans 20/700, primary text
Capture what arrived. ← body: 15pt sans 400, secondary text
```

### Buttons

- **Primary** — Black `#1A1A1A` pill, white text, 16pt radius, full width, 13/600 sans label. Used **once** per screen.
- **Secondary (outline)** — `cardSolid` fill, `borderStrong` 1px stroke, primary text.
- **Text** — Coral label, no background, no border.

### Tag / pill

- Background = the soft semantic (`successSoft`, `coralSoft`, etc.).
- Text = the matching strong semantic.
- 11pt sans 600, pill radius, 8pt horizontal padding, 4pt vertical.

### FAB (capture)

Coral fill, cream icon, full pill, `fab` shadow (coral-tinted glow). One per app, never duplicated per screen.

### Bottom nav

Floats above content with 16pt radius, `cardSolid` fill, `sm` shadow. Active item uses `coralSoft` background + coral icon/label.

### Input field

`inputBg` fill (5% warm-brown wash on the cream), 1px `borderDefault`, 6pt radius, 13pt sans body, tertiary placeholder.

---

## Voice & copy posture

Short, declarative, lower-stakes. Releaf is a journal, not a productivity tracker — copy should sound like you're talking to yourself, not a manager. Empty states are encouraging without being cute. Eyebrow words are 1–2 short uppercase tokens (`RELEAF`, `TODAY`, `CHAPTER 03`).

---

## Drop-in prompt for designers / generative tools

> Design a new screen for **Releaf**, a warm, paper-toned daily-capture notebook app. The canvas is cream `#F5EEDF` with a faint dot-grid texture (24px spacing, 1px dots, warm brown @35% alpha). Content sits on `#FFFAF4` cards with 12pt radius, hairline `#503E2D` borders at 12% alpha, and small warm shadows. Body text is **Manrope** in warm dark brown `#463C31`; page titles use **Newsreader** serif at 24pt. Section headers are introduced by an UPPERCASE 10pt coral eyebrow tracked at 0.08em. The single accent is **coral `#E07856`** — used sparingly for the FAB, active tabs, links, and eyebrows. Status pills use the semantic palette (success green, info blue, warning amber, danger red) only when meaning is involved — never for decoration. The primary CTA is a black pill `#1A1A1A` with white text, used at most once per screen. Spacing follows a 4pt grid; sections are 24pt apart; cards have 16pt inner padding. Avoid: gradients, glass/blur, neon, pure white surfaces, cool grey shadows, drop shadows on text, more than one accent color per screen. The mood is editorial, calm, tactile — analog journal meets quiet productivity app.

---

## Source

Tokens are exported from `design-system/design-tokens.json` (Tokens Studio schema). iOS reads them as `AppColors` / `AppText` / `AppSpacing` / `AppRadius`; Android mirrors via `AppTheme`. If a value here drifts from `design-tokens.json`, the JSON wins — regenerate before designing.
