# Recents tab redesign — implementation brief

You are implementing a redesigned **Recents** tab inside the existing Notepad feature of the Releaf app. Releaf is a daily journaling / notepad app that uses a garden metaphor — each day is a "plot," each entry within a day is a "page," days "bloom" when they have content. This brief is self-contained: read it end-to-end before writing code.

The codebase has both **native Android (Kotlin + Jetpack Compose)** and **native iOS (Swift + SwiftUI)** implementations and you are expected to keep them in lockstep. Same data model, same component breakdown, same visual fidelity. Build for whichever platform the user has open; if both, do them in parallel.

## Repo layout you'll touch

```
releaf/
  android/app/src/main/java/app/releaf/mobile/features/notepad/
    NotepadScreen.kt                       # entry point — has Day/Recents toggle
    recents/                                # ⬅ everything new lives here
      theme/{Colors,Typography}.kt
      model/Models.kt
      data/{MockData,DayStatsRepo}.kt
      ui/{RecentsScreen,StatsStrip,TagChips,TodayHero,
           WeekPulse,EarlierGrid,RecentsBottomNav}.kt
  ios/Releaf/Features/Notepad/
    NotepadView.swift                       # entry point — has Day/Recents toggle
    Recents/                                # ⬅ everything new lives here
      Theme/{Colors,Typography}.swift
      Models/Models.swift
      Data/{MockData,DayStatsRepo}.swift
      Views/{RecentsScreen,StatsStrip,TagChips,TodayHero,
              WeekPulse,EarlierGrid,BottomNav}.swift
```

The new screen is a swap-in for the existing `RecentsView(...)` (Android, in `NotepadScreen.kt`) and the `recentsView` computed property (iOS, in `NotepadView.swift`). Keep both legacy implementations available for rollback — comment the old call out, don't delete it.

## What the screen looks like, top to bottom

1. **Brand strip**: small caps `RELEAF · NOTEPAD` with a leaf glyph, brand-green letter-spaced.
2. **H1**: `Recent garden`, serif, 34sp/pt, regular weight.
3. **Day / Recents pill toggle**: full-width segmented pill. Recents selected by default. Active = dark green fill + cream text; inactive = textPrimary on muted-cream background.
4. **Stats strip**: rounded muted-cream card with three stats divided by thin verticals:
   - `12` / `Day streak`
   - `22` + small `/30` suffix in muted green / `Bloomed in Apr`
   - `Personal` (word) / `Top theme`
   Stats are computed per-month, not all-time. "Bloomed" = days with ≥1 page in the current calendar month. "Top theme" = most-used tag this month.
5. **Tag chips**: horizontal scroll: `All / Home / Work / Recipes / Personal`. Selected = green fill + cream text; unselected = light-green fill + green text.
6. **Section label "TODAY"**.
7. **Today hero card** — the centerpiece. Dark-green card, cornerRadius 20. It is a **swipeable carousel of today's pages plus one trailing "new entry" slot**. Six bands inside, top to bottom:
   - Header row: `SUN · APR 26 · 8:15 PM` (date + time of currently-shown page) on the left; `5 pages` on the right.
   - Title row: theme name `jatamansi` (serif, 24pt) + **page indicator pill** (see below).
   - **Inset card**, three render modes:
     - *Media (Photo / Scan / Imported)*: 16:9 media preview on top, then body with `PAGE · time` micro-label, type tag (top right; amber if Imported or Scan), title, 1–2 line description.
     - *Text-only (Journal / Voice / Mood)*: same body, no media tile.
     - *New-entry slot*: dashed amber border, 5-cell type-picker row (Photo / Journal / Voice / Mood / Import — Import uses amber), microcopy "Tap any type to plant a new page in today's garden".
   - **Capture pip row**: tiny rounded chips with counts, one per type that has ≥1 page today (skip zero counts). Order: photo, journal, mood, voice. Day-level data — does NOT change as the user swipes.
   - **Day timeline**: thin horizontal bar from `6a` to `12a`. Dots positioned by each page's clock time. The dot for the *currently-shown* page is enlarged and brighter. The new-entry slot adds an extra amber dot at the right edge.
   - **CTA footer**: top divider line, then a row with bold `Open page X →` and a circular arrow button. On the new-entry slot, the label becomes `Add a page →` and the button becomes a `+` in amber.
8. **Section label "THIS WEEK"**.
9. **Week pulse**: row of 7 day cells. Each cell is a small vertical track (translucent green) with a coloured fill whose height = pageCount / 6 (clamped). Color tier: 1–2 pages = light green, 3–5 = mid green, 6+ = dark green. Below the bar: weekday letter (`M T W T F S S`) and date number. Today's labels are bolded in dark green.
10. **Section label "EARLIER IN APRIL"**.
11. **Earlier grid**: 2-column grid of past days, with the richest day in the visible window auto-promoted to a tall card spanning two rows. Three card variants:
    - *Tall* (featured): light-green background, date label, title, 16:10 photo preview, dot pips (green = native, amber = imported).
    - *Regular*: cream background, faint border, date + title + dot pips.
    - *Empty*: transparent fill, dashed border, italic muted "no entry". Tappable — opens capture sheet pre-targeted at that date so the user can backfill.
12. **Bottom nav**: 5-cell rounded card. `Home / Library / [center leaf] / Notepad (active) / Settings`. The center leaf is a 48dp/pt dark-green circle with a cream leaf glyph, offset upward to sit above the nav line (use a negative top offset of ~22). The center leaf is *the* primary capture entry point — there is no floating action button.

## Three interaction details that matter

1. **Page indicator pill** uses a "fill from the left" pattern: `●○○○○ 1 of 5`, `●●●○○ 3 of 5`, `●●●●● 5 of 5`. On the new-entry slot the pill becomes `●●●●●⊕ new` with the trailing dot in amber and an amber-tinted background.
2. **Hero never dead-ends.** The carousel always has `pages.length + 1` slots — even on a brand-new zero-page day, the hero shows just the new-entry card as `1 of new`. Single-page day still gets the swipe (page 1 + new-entry slot).
3. **CTA opens the *current* page**, not always the latest. The label is computed from the carousel index: `idx < pages.length ? "Open page ${idx+1}" : "Add a page"`. Tapping the footer routes into Day view scrolled to that page (or to the capture sheet for the new-entry slot).

## Capture entry points (no FAB)

There is **no** floating action button. Three contextual entry points:

1. The center leaf in the bottom nav (ambient capture).
2. The new-entry slot at the end of the today carousel (capture into today specifically).
3. Tapping any empty/dashed `DayCard` in the Earlier grid (backfill that date).

## Data model (replicate exactly on both platforms)

```
Tag         = home | work | recipes | personal
TagFilter   = all | Tag
CaptureType = photo | journal | voice | mood
PageSource  = camera | library | scan | native      // library/scan = "imported"

Page {
  id, dayId, type, source, createdAt,
  title, description, tags,
  // type-specific:
  mediaUri?, durationSec?, moodRating? (1-5), moodWord?
}

Day {
  id (yyyy-MM-dd), date, theme, pages: [Page]
}

WeekDay { date, pageCount, isToday }

Totals {
  dayStreak,
  bloomedThisMonth, daysInMonth,
  topTheme: Tag?
}

DayStats {
  today: Day?,
  weekPulse: [WeekDay] (length 7, ending today),
  earlier: [Day],
  totals: Totals
}
```

Helpers:
- `Page.isImported` = source == library || source == scan
- `Day.richness` = pages.count + (hasPhoto ? 1 : 0) + (hasVoice ? 1 : 0)

The screen reads everything from a single `DayStatsRepo` interface that returns a `DayStats` snapshot. Provide a `MockDayStatsRepo` for development (today = 2026-04-26, theme `jatamansi`, 5 pages spanning 7:32 AM photo / 9:18 AM journal / 1:04 PM scan / 4:47 PM mood (rating 4 word "calm") / 8:15 PM voice (42s); earlier = Apr 25 rich, 24, 23, 22 modest, 21+20 empty; weekPulse counts `[1,2,1,4,5,3,5]`; totals streak=12, bloomed=22/30, topTheme=personal). Wire the real repo on top of `NotepadRepository` later.

## Naming-conflict guardrails

The existing codebase already declares `Page` and `Day` types (`Releaf/Data/Domain/Models.swift` on iOS; `app.releaf.mobile.data.domain.Models` on Android). It also has an existing `BottomNav` component on both platforms. When you create the new feature module, **rename** to avoid collisions:

- `Day` → `RecentsDay`
- `Page` → `RecentsPage`
- `WeekDay` → `RecentsWeekDay`
- `Totals` → `RecentsTotals`
- `DayStats` → `RecentsDayStats`
- `BottomNav` → `RecentsBottomNav`

Keep `CaptureType`, `PageSource`, `Tag`, `TagFilter` as their bare names — they're unique to this feature.

On iOS, `Releaf/DesignSystem/AppColors.generated.swift` already defines `Color(hex: UInt32, alpha: Double = 1.0)`. **Don't** add another `Color(hex: String)` initializer — overload-resolution risk. Use a private file-scoped helper `Color(hexString:)` instead, or call the existing `AppColors.…` tokens where possible.

## Color tokens

```
Background:
  bgCanvas        #F4EDDC     page background (cream)
  bgSurface       #FBF5E2     raised cards
  bgSurfaceMuted  #EFE7CD     toggle / stats strip background
  bgChip          #DDEACD     unselected pill
  bgFeatured      #DDEACD     promoted (tall) day card
Greens:
  green900        #2C4520
  green800        #3F5C2C     primary brand green — hero, selected pills, leaf
  green600        #5B7A3F
  green400        #7AA055
  green200        #C0DD97
  green100        #DDEACD
Cream / sand:
  cream300        #EDE3CC     scan placeholder
Text:
  textPrimary     #1F1E18
  textSecondary   #5C5C50
  textMuted       #6B6B5E
  textOnDark      #F4EDDC
  textOnDarkMuted #DDEACD
  textOnDarkSubtle#C0DD97
  textGreen       #3F5C2C
  textGreenMuted  #6B8A4F
Accents:
  accentImport    #BA7517     amber — imported photos / scans / new-entry slot
  accentImportBg  #F0E0B8     amber outline / soft fill
On-dark overlays (for tinting on the green hero):
  onDark10  rgba(244,237,220, 0.10)
  onDark14  rgba(244,237,220, 0.14)
  onDark16  rgba(244,237,220, 0.16)
  onDark25  rgba(192,221,151, 0.25)
  onDark30  rgba(244,237,220, 0.30)
Borders:
  borderFaint    rgba(63,92,44, 0.10)
  borderDashed   rgba(63,92,44, 0.20)
  borderDivider  rgba(63,92,44, 0.20)
```

The amber token (`accentImport`/`accentImportBg`) is reserved exclusively for: imported-photo outlines, scan type tags, the new-entry pill/dot/CTA. Don't reuse it for other states.

## Typography

Use the system sans-serif for almost everything; reach for serif only for the H1 (`Recent garden`), the day theme name (`jatamansi`), and the stat numbers (`12`, `22`).

```
display    34 / regular / serif         — H1
h2         24 / regular / serif         — theme name
statNum    18 / medium  / serif         — streak, bloom count
body       14 / regular
bodySmall  13 / regular
caption    12 / regular
micro      10 / medium  / kerned 1.3   — uppercase eyebrows
microWide   9 / medium  / kerned 1.5   — date stamps, ALL CAPS labels
```

Two weights only: `regular` (400) and `medium` (500). No semibold or bold. Sentence case everywhere except eyebrow / date / "TODAY" labels which are uppercase.

## Layout details

- Outer screen: padding 14 horizontal at the screen container, 6 horizontal extra on individual cards.
- Radii: pill 999, hero / featured 20, regular cards 16, stats / chips 14, inset card 13.
- Cards on the dark green hero use translucent overlays (`onDark10/14/16/25/30`) for sub-elements, never new colors.
- The new-entry inset uses a dashed border (1.5px, amber-low-opacity).
- All icons can be drawn with `Canvas` + `Path` on Android (no Material Icons dependency required) and SF Symbols on iOS (`leaf.fill`, `house`, `books.vertical`, `note.text`, `gearshape`, `camera`, `text.alignleft`, `mic`, `circle.fill`, `square.and.arrow.down`).

## Integration into existing Notepad screens

**Android — `app/src/main/java/app/releaf/mobile/features/notepad/NotepadScreen.kt`**

Find the `when (selected)` branch around line ~252 that calls `RecentsView(...)`. Replace with:

```kotlin
app.releaf.mobile.features.notepad.recents.ui.RecentsScreen(
    onOpenPage      = { /* TODO: route to page detail */ },
    onAddPage       = { /* TODO: open capture sheet for type */ },
    onOpenDay       = { _ -> /* TODO: open day view */ },
    onBackfillDay   = { _ -> /* TODO: open capture sheet for date */ },
    onHomeTab       = { /* host handles tab nav */ },
    onLibraryTab    = { /* host handles tab nav */ },
    onCenterCapture = { /* TODO: open quick-capture sheet */ },
    onNotepadTab    = { /* no-op — already on Notepad */ },
    onSettingsTab   = { /* host handles tab nav */ },
)
```

Comment the legacy `RecentsView(...)` call below it, don't delete.

**iOS — `Releaf/Features/Notepad/NotepadView.swift`**

Find the `switch tab` block around line ~127 inside `NotepadDayRecentsContent.body`. Change `case .recents: recentsView` to `case .recents: RecentsScreen()`. Leave the `private var recentsView` computed property intact below — it's untouched dead code, available for rollback by reverting one line.

## Stack constraints

**Android**:
- Compose foundation primitives only — no Material library inside the new feature module (the host project already pulls in MD3, but our module shouldn't depend on it).
- `androidx.compose.foundation.pager.HorizontalPager` for the carousel. The 1.7+ `rememberPagerState(initialPage = ...) { totalSlots }` lambda-pageCount overload is what the project's compose-bom (2024.09.03 or later) provides.
- `mutableStateOf` is enough — no ViewModel for this screen.
- Java 17, Kotlin, target SDK 35, min SDK 26.

**iOS**:
- SwiftUI only, iOS 16+ deployment target.
- `TabView(selection:).tabViewStyle(.page(indexDisplayMode: .never))` for the carousel.
- No UIKit bridges, no third-party deps.
- The new code lives inside the `ReleafFeatures` SwiftPM target which already depends on `ReleafDesignSystem` and `ReleafData`. Adding files under `Releaf/Features/Notepad/Recents/` is auto-discovered by SwiftPM — `Package.swift` does **not** need editing.
- Make `RecentsScreen` `public`. Other types stay internal unless they need to cross module boundaries.

## Mock data fixed values

```
today.id           = "2026-04-26"
today.theme        = "jatamansi"
today.pages        = [
  7:32 AM   photo (camera)   "Morning leaf"
  9:18 AM   journal (native) "Three lines on stillness"
  1:04 PM   photo (scan)     "Recipe card — kanji"            ← amber tag
  4:47 PM   mood (native)    "Calm, rooted" rating=4 word=calm
  8:15 PM   voice (native)   "Evening reflection" 42s
]
earlier            = [
  Apr 25 "daily capture"      richness 6 → tall featured card
  Apr 24 "hello"              5 pages
  Apr 23 "xoriant games day"  2 pages
  Apr 22 "twak"               1 page
  Apr 21 empty               → ghost card
  Apr 20 empty               → ghost card
]
weekPulse counts   = [1, 2, 1, 4, 5, 3, 5]   // Mon → Sun, today = Sun
totals.dayStreak   = 12
totals.bloomed     = 22/30
totals.topTheme    = personal
```

## Acceptance criteria

When you're done, the user should see:

1. The Notepad tab opens, `Recents` is selected by default in the segmented toggle.
2. The stats strip reads `12 / 22/30 / Personal`.
3. The today hero shows page `5 of 5` (Voice — Evening reflection) by default. Swiping right walks back through 4, 3, 2, 1 and then a sixth amber-bordered "new entry" card with five type pickers. Swiping left from page 5 reveals the new-entry slot directly.
4. The page indicator pill fills left-to-right; the day timeline's active dot tracks the swipe; the CTA label updates to "Open page X →" or "Add a page →" accordingly.
5. The week pulse shows 7 bars whose heights match `[1,2,1,4,5,3,5]`; today's labels are bolded.
6. The earlier grid shows Apr 25 promoted to a tall card; Apr 21 and 20 are dashed empty cards.
7. Tapping the bottom-nav center leaf or the new-entry slot or any empty card calls into the appropriate capture flow (host-wired, your hooks just need to fire).

## Non-goals

- Don't implement the Day view. That's a separate screen; `Open page X →` and `Open in Day view →` are TODO routes.
- Don't replace the host app's design tokens. The new feature ships its own palette in `theme/Colors` so it can be feature-flagged on without rippling brand changes elsewhere.
- Don't add a floating action button. The center leaf in the bottom nav is the only "ambient" capture entry, by design.
- Don't add Material Design 3 components inside the feature module. Foundation primitives only.

When the user reports compile errors after a build, the two most likely culprits are: (1) iOS `Color.*` token names colliding with `AppColors.*` extensions — fix by renaming our tokens or moving them under a private namespace; (2) Android `HorizontalPager` signature mismatch if compose-bom is below 1.7 — fall back to the older `rememberPagerState(pageCount = { … })` form. Both are one-line patches.

Read every file you write back before declaring done; re-read the touched spots in `NotepadScreen.kt` and `NotepadView.swift` to confirm the swap landed where you intended.
