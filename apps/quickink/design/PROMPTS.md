# QuickInk — design prompts

Copy-paste prompts for Figma AI / Figma Make, Canva Magic Design, Midjourney, DALL·E, or any AI image generator. Each prompt is self-contained — it carries the brand context so you can paste a single block and get on-brand output.

---

## Brand cheat sheet (paste at top of any prompt)

```
QuickInk is a camera-first document scanning + note-taking iOS/Android app.
Tagline: "scan, jot, find again."

Visual identity:
- Mood: warm editorial, calm, paper-and-ink, not techy.
- Canvas: off-white cream  #FAF7F2
- Accent: warm coral        #D97757
- Ink (text/marks):         #2C2826  (very dark warm brown, not pure black)
- Soft border / paper tone: #EDE4D2
- Secondary surface white:  #FFFFFF
- Headings font: Cormorant Garamond (serif, weight 500)
- Handwritten accents: Caveat
- UI body: system sans (SF / Roboto)
- Two font weights only: regular (400) and medium (500). No 600+.

Avoid: gradients, drop shadows, neon, glow, 3D, gloss, emoji, generic "tech" blue, sans-serif logos, stock-photo backgrounds, gimmicky AI-glow.
```

---

## 1. Logo — primary mark

```
Design a minimalist editorial logo for "QuickInk", a document-scan + notes app.
Concept: a capital letter Q drawn as an open ink-pen stroke — a thin circular
loop for the bowl, and an ink-tail that flows out to the lower right and
finishes in a single round drop.

Style: hand-inked, calligraphic, single weight, geometric but warm. Reads as
both a Q letterform and the gesture of writing. Think Japanese enso meets a
fountain-pen flourish.

Colors:
  - Stroke: #2C2826 (dark warm brown, not pure black)
  - Ink-drop accent: #D97757 (warm coral)
  - Background: transparent (or #FAF7F2 cream)

Wordmark below the symbol (optional variant):
  - Word: QuickInk (one word, capital Q and capital I, no space)
  - Font: Cormorant Garamond, weight 500, slightly tightened tracking
  - Color: #2C2826

Output: clean vector, balanced inside an 84×84 grid with 12px padding.
Must remain legible scaled down to 20×20 px (toolbar size).

Avoid: gradients, drop shadows, glow, sans-serif type, multiple colors, 3D,
gloss, decorative serifs in the symbol, anything that looks "tech-startup".
```

---

## 2. App icon — iOS / Android

```
Design an iOS app icon for "QuickInk" at 1024×1024 px, optimized for the
iOS rounded-square (squircle) mask.

Background: solid #D97757 (warm coral), edge-to-edge, no gradient, no
texture, no inner border.

Foreground mark (centered, ~55% of icon size):
  - An open ring forming the bowl of a capital Q, drawn in #FAF7F2 (cream),
    stroke 60 px at 1024 size.
  - An ink-tail curving from the lower right of the ring outward and ending
    in a small filled circular drop.
  - The drop itself is filled #2C2826 (dark ink) — a single accent against
    the cream.
  - Stroke style: rounded line caps and joins.

Composition: visually centered on the full 1024 canvas (the icon mask will
crop ~10%). No text, no frame, no background pattern.

Also generate two alt versions on the same grid:
  - Cream variant: background #FAF7F2, mark in #2C2826, drop in #D97757.
  - Ink variant: background #2C2826, mark in #FAF7F2, drop in #D97757.

Avoid: gradients, drop shadows, gloss, inner highlight, 3D bevel,
photo-realistic ink splatter, multiple objects, letterforms other than Q.
```

---

## 3. Splash screen — mobile

```
Design a mobile app splash screen for "QuickInk", 1170×2532 px (iPhone 14
portrait). Pure brand moment, no UI controls.

Layout:
  - Full-bleed background #FAF7F2 (cream).
  - Vertically centered logo mark: open Q ring + ink-tail, stroke #2C2826,
    coral drop #D97757. Mark height ~120 px.
  - 16 px below the mark: wordmark "QuickInk" in Cormorant Garamond,
    weight 500, size 40 px, color #2C2826.
  - 8 px below the wordmark: tagline "scan, jot, find again." in Caveat,
    size 22 px, color #7a6f60 (warm muted brown).
  - At the bottom 60 px from the safe-area edge: a small horizontal
    accent line, 24×2 px, color #D97757, centered.
  - Status bar area left clean.

Mood: stillness, paper, pause-before-action. No animation hints, no
splash gradient. Generate a second variant where the entire background is
#D97757 (coral) and the mark is #FAF7F2 (cream) with the drop in #2C2826.

Avoid: loading spinner, version number, footer text, drop shadow, blur,
particle effects.
```

---

## 4. Onboarding / landing screen — mobile first-run

```
Design a first-run onboarding screen for "QuickInk", 390×844 px (iPhone 14
portrait, points). Layout flows top to bottom:

1. Status bar safe area (44 px), then a 40 px pad.
2. Top-left brand row: 20 px QuickInk mark (Q + ink-tail) + wordmark
   "QuickInk" in Cormorant Garamond 14 px / 500.
3. Hero headline (36 px below brand row), 2 lines:
   "A pocket notebook"
   "that remembers."
   - Font: Cormorant Garamond, 32 px, weight 500, color #2C2826,
     line-height 1.1, slight negative tracking (-0.5).
4. Subhead (12 px below hero):
   "Scan a page, jot a thought, find it later by any word."
   - Font: system sans, 15 px, weight 400, color #7a6f60.
5. Feature card (24 px below subhead): white surface #FFFFFF, border 0.5 px
   #EDE4D2, corner radius 12 px, padding 16 px. Three feature rows:
     - 14×14 coral icon + "One-tap scan, OCR every page"
     - 14×14 coral icon + "Notes alongside scans"
     - 14×14 coral icon + "Search by any word, instantly"
   - Row text: 14 px, weight 400, color #2C2826, 10 px vertical gap.
6. Primary CTA (32 px below card): pill button, full width minus 32 px
   side padding, height 50 px, background #D97757, text "Continue with
   Google" in 16 px / 500, color #FAF7F2.
7. Trust micro-copy (10 px below CTA), centered: "Synced privately to
   your Drive." 12 px sans, color #a59a89.
8. Home-indicator safe area at bottom.

Mood: calm, literary, inviting. Like opening a Moleskine, not a SaaS
landing page.

Avoid: stock illustration, hero image, carousel dots, sign-up form,
multiple CTAs, gradient buttons, drop shadow on the card.
```

---

## 5. UI icon set — line icons

```
Design a set of 6 line icons for "QuickInk" at 24×24 px, on a 22 px
optical grid with 1 px padding. Style is consistent across all:

  - Stroke weight: 1.7 px
  - Stroke color: #2C2826
  - Caps and joins: round
  - No fills (line-only)
  - Geometric, slightly handcrafted feel — not perfectly mechanical

The 6 icons:
  1. Scan      — 4 corner brackets framing a small rounded square in the
                 center (camera viewfinder framing a page).
  2. Note      — A document with one folded corner and 2 horizontal text
                 lines inside.
  3. Search    — A circle (lens) with a short diagonal handle from lower
                 right.
  4. Sync      — A cloud with a small downward arrow inside it (Drive sync).
  5. Tag       — A classic bookmark/price-tag shape with a small dot for
                 the eyelet.
  6. Archive   — A box with a horizontal lid and a short slot in the body.

Output as 6 separate SVGs and a single contact-sheet PNG (3×2 grid,
240×160 px, on #FAF7F2 background, each icon in a 46×46 white tile with
0.5 px #EDE4D2 border, radius 12 px).

Avoid: filled glyphs, two-tone, drop shadows, multi-color, mismatched
visual weight across icons.
```

---

## 6. Bonus — Midjourney / DALL·E "moodboard" prompt

If you want generated reference imagery (not final assets) for pitching:

```
A still-life photograph: a cream-colored handmade paper notebook, slightly
worn corners, lying open on a warm beige linen surface. A black ink
fountain pen rests across the page; a single coral-orange ink drop sits
beside it. Soft window daylight from upper left, no harsh shadows.
Editorial, minimal, muted, restrained. Color palette limited to cream
#FAF7F2, coral #D97757, dark warm brown #2C2826.
--ar 4:5 --style raw --no text, no people, no glow
```

---

## How to use these in Figma

1. Open the file you want to design in.
2. Run **Figma AI → Make Design** (or the Make plugin).
3. Paste the relevant prompt block. Add the brand cheat sheet as a second
   message if Figma asks for clarification on style.
4. Iterate by replying with tweaks, e.g. *"keep everything but make the
   ink-tail thicker and shorten it"*.

## How to use these in Canva

1. Search **Magic Design** (Canva AI tool).
2. Paste the prompt block. Canva will pick a template — override its
   color choices by pasting the brand cheat sheet.
3. For app icon and splash, set canvas size manually before generating
   (1024×1024 for icon, 1170×2532 for splash).

## How to use these for stock-style references (Midjourney, DALL·E,
   Imagen, Adobe Firefly)

Use prompt 6 (moodboard) for generating texture / hero photography. Don't
use AI image gen for the logo or icons — vector tools (or Figma's vector
features) will give you cleaner, scalable output.
