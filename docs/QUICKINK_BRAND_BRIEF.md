# QuickInk — brand brief

A working brief for design. QuickInk is a sibling product to Releaf,
not a redesign — visual DNA stays in the family, but the surface
character shifts to match what the app *does*: capture a page, get
text out, file it away, move on.

This doc describes *what* we want the brand to feel like and *what
assets* we need. It does not prescribe specific marks. Design has
final say on every visual decision below.

---

## 1. Product positioning (one paragraph)

QuickInk is the fastest way to turn a page of writing into searchable
text. Open the app, point the camera, the page becomes a clean PDF
plus editable text in your library — and quietly backs itself up to
your own Google Drive while you keep working. It is built for people
who carry a reusable notebook and want their handwritten or printed
pages to stop being trapped on paper. Not a journaling app. Not a
notes app trying to do everything. A capture utility that gets out
of the way.

Sibling to Releaf in the same way Things is to OmniFocus, or Bear is
to iA Writer — same household, different temperature.

---

## 2. Tone vs Releaf

| Dimension       | Releaf                                              | QuickInk                                                  |
| --------------- | --------------------------------------------------- | --------------------------------------------------------- |
| Pace            | Slow, considered, journaling                        | Fast, transactional, "snap and go"                        |
| Mood            | Warm, editorial, paper-toned, reflective            | Crisp, kinetic, precise — still warm but more focused     |
| Voice           | First-person, gentle ("your day, your notebook")    | Second-person imperative ("scan a page", "find a note")   |
| Density         | Spacious, lots of breathing room                    | Slightly tighter — utility tools want efficient surfaces  |
| Hero metaphor   | A field journal, leaves, growing things             | An ink stroke, a captured page, motion of pen on paper    |

The two products should feel like they were made by the same studio
on the same Tuesday. Not twins; siblings.

---

## 3. Naming

- **Product name**: QuickInk (single word, capital Q + I)
- **Tagline (working)**: "From page to library in three seconds." —
  open to alternatives. Constraint: must read in under a beat,
  must mention speed *and* the from→to motion.
- **Voice attribution**: "QuickInk", not "the QuickInk app", not
  "Quick Ink", not "QI". Always one word.

---

## 4. Visual system

### 4.1 Color — locked palette

QuickInk's color palette (decided):

| Role                | Token suggestion           | Hex         | Notes                                            |
| ------------------- | -------------------------- | ----------- | ------------------------------------------------ |
| Canvas              | `canvas`                   | `#F5EEDF`   | Slight warm-yellow shift vs Releaf's `#F5EEE3`   |
| Text Primary        | `textPrimary`              | `#463C31`   | Softer than Releaf's `#241D17` (= `neutral900`); matches `neutral700` |
| Text Secondary      | `textSecondary`            | `#5F5245`   | Same as Releaf (= `neutral600`)                  |
| Coral               | `themeCoral.primary`       | `#E07856`   | Same as Releaf — primary accent / scan FAB / CTA |
| Coral Deep          | `themeCoral.primaryDeep`   | `#C65A3E`   | Same as Releaf — pressed coral                   |
| Leaf Green          | `themeGreen.primary`       | `#7AA874`   | Same as Releaf                                   |
| Leaf Green Deep     | `themeGreen.primaryDeep`   | `#5B8C52`   | Same as Releaf                                   |
| Leaf Yellow         | `themeYellow.primary`      | `#F4C430`   | Same as Releaf                                   |
| Leaf Yellow Deep    | `themeYellow.primaryDeep`  | `#E8B923`   | Same as Releaf                                   |
| Leaf Dry            | `themeDry.primary`         | `#B8956A`   | Same as Releaf                                   |
| Leaf Dry Deep       | `themeDry.primaryDeep`     | `#8B7355`   | Same as Releaf                                   |

**Coral stays the primary accent** for scan FAB / primary CTA / active
states (overrides my earlier proposed cool-blue direction). All four
"leaf" theme palettes carry over so QuickInk users get the same
seasonal theme-picker affordance as Releaf.

**Two divergences** from Releaf's `design-tokens.json` defaults:

1. `canvas` shifts `#F5EEE3 → #F5EEDF` — marginally warmer / yellower.
2. `textPrimary` shifts `#241D17 → #463C31` — substantially softer; reads
   less like printed ink, more like aged pencil. Still passes WCAG AA on
   the new canvas (contrast ratio ~9.1:1).

These two are app-level overrides, not edits to the shared token file.
Implementation: extend `design-tokens.json` to expose a per-app override
group (`app.releaf.color.*` and `app.quickink.color.*`) with `canvas` +
`textPrimary` slots. The token generator picks the override group for
each app at codegen time, falling back to the shared default for
everything else. This keeps a single `design-tokens.json` (no fork) while
letting the two apps diverge cleanly on the small set of values they
actually disagree on.

The semantic palette (`success/info/warning/danger`), spacing, radii,
shadows, motion, and typography all inherit unchanged.

### 4.2 Typography

Inherit unchanged. Manrope (sans) + Newsreader (serif). Same role
table — eyebrow / body / meta / button / sectionTitle / statNumber /
editorialTitle / tag.

### 4.3 Iconography

Inherit Releaf's icon system (the SVG sheet at
`shared/design-system/mobile-brand-assets/source/releaf-mobile-icons.svg`).
QuickInk needs **two new icons** added to the sheet:

- `scan-page` — the camera-first FAB / Home icon.
- `searchable-text` — the OCR-complete checkmark / "text ready"
  state indicator.

### 4.4 Motion

Inherit Releaf's duration tokens (150 / 220 / 320 ms) and standard
ease. QuickInk uses these on the same surfaces.

The one new motion piece: the **scan-completion confirmation** —
when OCR finishes and the entry is saved, a brief inline animation
under the just-scanned page tile (probably a writing-pen sweep, ~320
ms). Replaces an explicit "Saved" toast. Design call.

---

## 5. Brand assets needed

For first-build readiness:

| Asset                       | Format         | Sizes / variants                             | Notes |
| --------------------------- | -------------- | -------------------------------------------- | ----- |
| App icon                    | SVG → PNG      | iOS: 1024×1024 master + asset catalog set    | Same generation pipeline as Releaf (`design-system/mobile-brand-assets/render.sh`) |
|                             |                | Android: adaptive icon (foreground + background SVG) |  |
| Wordmark / logo lockup      | SVG            | Horizontal lockup, monogram-only variant     | Used on splash, sign-in, share-sheet header, marketing |
| Splash screen               | SVG → PNG      | iOS: 1290×2796, 1170×2532 (and downscales)   | Cold-launch frame; consistent with Android splash |
|                             |                | Android: 1290×2796 master                    |  |
| Onboarding illustrations    | SVG            | 3 illustrations (welcome / permissions / sign-in) | Same flat-line style as Releaf's onboarding illustrations; no new style language |
| Scan-page icon              | SVG            | Single source, exported alongside other UI icons | Goes in the icon sheet |
| Searchable-text icon        | SVG            | Same                                         |  |
| Empty-state illustration    | SVG            | One image: "no notes yet, tap the camera"    | Matches Releaf onboarding line-art tone |

Out of scope for v1 brand pass — handled in a follow-up if QuickInk
ships beyond Internal:

- App Store / Play Store screenshots and copy
- Marketing site / landing page
- Press kit
- Animated demo (the README-embedded GIF / video)

---

## 6. Asset delivery

Match Releaf's pipeline:

- Source SVGs land in
  `shared/design-system/mobile-brand-assets/source/quickink-*.svg`.
- The existing `render.sh` script grows a section that exports
  QuickInk PNGs into `mobile-brand-assets/generated/quickink/`.
- iOS asset catalog import script and Android `res/drawable` /
  `res/mipmap` copy script extended to handle the QuickInk paths.

Single source SVG, generated PNGs, both platforms pull from the
generated directory — same shape as Releaf's existing setup. No new
build infrastructure.

---

## 7. What design needs from product before kickoff

Color is locked (§4.1). Remaining open questions for design:

A. **The wordmark direction** — proposed: a clean sans-serif
   wordmark with a single ink-stroke flourish on the K. Open to
   alternatives. Two-three concept directions before commitment.

B. **App icon concept** — proposed directions to evaluate:
   1. A single ink stroke forming a stylized "Q" or document corner
   2. A page with a corner being lifted (the "capture" gesture)
   3. A pen-tip with a small pulse / motion line
   Pick one direction to develop after concept review. Coral on the
   warm canvas is the locked color frame.

C. **Splash treatment** — full-bleed coral with centered wordmark?
   Cream canvas (`#F5EEDF`) with the icon? Animated reveal or static?

D. **Whether the QuickInk icon should reference Releaf at all** —
   sister-product visual cue (e.g. the small leaf glyph somewhere
   subtle) vs total visual independence. Marketing call as much as
   design call.

These four questions block the first concept review. Engineering
proceeds in parallel with the locked color tokens — placeholder
wordmark / icon / splash until the brand work converges.

---

## 8. Review cadence

- **Concept review** — design presents 2-3 directions for icon +
  wordmark + accent, week 1.
- **Convergence review** — picked direction comes back as full asset
  set (icon at all sizes, wordmark variants, splash, onboarding
  illustrations, two new UI icons), week 2.
- **Build-ready handoff** — assets land in
  `shared/design-system/mobile-brand-assets/source/`, generated PNGs
  produced, iOS asset catalog and Android `res/` populated.
  Engineering swaps out placeholders and ships.

This timeline overlaps PR #5–#7 from QUICKINK_DESIGN.md §4 — final
brand assets go in around PR #7 (MVP screens) so we ship the
Internal build with real branding rather than placeholder.
