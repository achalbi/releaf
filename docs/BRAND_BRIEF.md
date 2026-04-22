# Releaf — brand & design brief

Copy-paste the block below into an image generator, Figma AI assistant, or
human designer brief. It's built around the existing visual language already
in the app (warm neutrals, dot-grid canvas, coral leaf accent) so anything
generated drops in coherently with the shipped UI.

---

**Project:** Releaf — a personal notebook app for iOS and Android.

**Tagline / statement:** *"The notebook that grows back."*

**What it is:** A journaling and capture app where notes, photos, voice memos,
scans, and to-dos live on a calm, paper-like canvas. Private by design — your
notebooks live on your device and back up to your own Google Drive. No social
feed, no algorithm.

**What the tagline means:** Every entry is a seed. The app gently brings old
ones back — on anniversaries, in search, in context — so your past self keeps
resurfacing and compounding. "Grows back" is about regeneration (like a leaf
returning each spring), resilience (nothing you write is lost), and living
memory (not a dead archive).

**Brand personality:** Warm, literary, grounded, quietly confident. Not
tech-bro, not clinical, not twee. Think independent bookshop stationery meets
modern productivity — a Field Notes notebook that knows what season it is.

## Visual direction

- **Color palette (locked):** warm off-white canvas `#F5EEDF`, warm brown ink
  `#463C31`, a softer brown `#5F5245`, coral primary `#E07856` with deeper
  coral `#C65A3E` for gradients, muted forest accent only if needed. Avoid
  pure white, pure black, or cool greys.
- **Texture:** subtle dot-grid pattern (warm brown dots at ~35% opacity on
  canvas) — feels like a Leuchtturm / Moleskine page. Can appear faintly
  behind hero compositions.
- **Key motif:** a single coral leaf. Already used as the app's primary
  action glyph — a small organic leaf shape, top-heavy, with a gentle
  gradient from coral down to deeper coral. The leaf is the "re-" in Releaf.
- **Typography cues:** serif display for "Releaf" wordmark (something with
  warmth and a slight editorial feel — Tiempos, Canela, or similar); clean
  humanist sans for supporting copy (Inter, General Sans).
- **Shape language:** soft rounded rectangles (12–16 pt radius), generous
  whitespace, floating editorial cards with 1 pt hairline borders rather than
  heavy shadows.
- **Avoid:** neon, glassmorphism, gradients beyond the coral leaf, stock
  "productivity app" tropes (checkmarks, clipboards, rocket ships), AI-slop
  symmetry, photorealistic leaves.

## Deliverables

1. A primary **logomark** — the coral leaf, refined enough to work as an app
   icon at 1024 px and as a favicon at 16 px.
2. A **wordmark** pairing the leaf with "Releaf" set in a warm serif.
3. A **lockup** combining mark + wordmark + tagline.
4. A **launch hero image** — a square or 16:9 composition showing an open
   dot-grid notebook page on the warm canvas, a coral leaf resting on the
   page, soft morning light, room for the tagline to be set in serif type.
   Feels like a still life, not a product render.
5. Three **social cards** (1200 × 630) — one each for the three tagline
   readings: regeneration, resilience, living memory.
6. An **app-icon sheet** at iOS and Android sizes, with and without the
   rounded-square container.

## Copy tone

Short, declarative, lowercase-friendly, never shouty.

- "A notebook for the long run."
- "Write it down. It'll find you again."
- "Your Drive. Your notebook. Your life."

## Token cross-reference (for designers handing files back to engineering)

The live design tokens are in `design-system/design-tokens.json` at repo
root; the two mirrors that ship in the app are
`ios/Releaf/DesignSystem/AppColors.swift` and
`android/app/src/main/java/app/releaf/mobile/ui/theme/AppColors.kt`.

| Brief color       | Token name       | Hex       |
| ----------------- | ---------------- | --------- |
| Warm canvas       | `canvas`         | `#F5EEDF` |
| Ink (primary)     | `text.primary`   | `#463C31` |
| Ink (secondary)   | `text.secondary` | `#5F5245` |
| Coral (primary)   | `coral`          | `#E07856` |
| Coral (deep)      | `coralDeep`      | `#C65A3E` |
| Dot-grid          | `pattern.dotGrid`| `#503E2D` @ 35% |

If any color above drifts from the tokens file, the tokens file wins —
update this brief, not the app.
