/*
 * QuickInkColors.kt
 *
 * QuickInk's local color palette — refreshed brand pass introducing
 * a user-pickable primary color (Coral / Leaf Green / Leaf Yellow /
 * Leaf Dry) plus a fixed canvas/text triplet that doesn't change
 * with the picker. The accent token consumed by every screen is
 * resolved at the [QuickInkTheme] entry point from:
 *
 *   - the user's picked PrimaryColor (default Coral)
 *   - the effective theme mode (system / light / dark)
 *
 * Resolution rule: light mode uses the DEEP variant of the picked
 * hue (better contrast on cream canvas); dark mode uses the BASE
 * variant (better contrast on dark stone). `accentDeep` always
 * resolves to the deep variant for hover / pressed states.
 *
 * Mirror of iOS `QuickInkColors` enum in `QuickInkTheme.swift`.
 *
 * Token table (fixed):
 *   Canvas         #F5EEDF  app background
 *   TextPrimary    #463C31  primary text
 *   TextSecondary  #5F5245  secondary text
 *   Surface        #FFFFFF  cards
 *   Border         #EDE4D2  dividers, card borders
 *   BorderSoft     #F0E9DD  pill backgrounds, search bar fill
 *   Muted          #A8A29E  tertiary text, inactive nav
 *   AccentSoft     hue-tinted at 0.18 — derived from picked hue
 *
 * Token table (hue families — base / deep):
 *   Coral         #E07856 / #C65A3E
 *   Leaf Green    #7AA874 / #5B8C52
 *   Leaf Yellow   #F4C430 / #E8B923
 *   Leaf Dry      #B8956A / #8B7355
 */

package app.quickink.mobile.ui.theme

import androidx.compose.ui.graphics.Color

object QuickInkColors {
    // Fixed canvas + text triplet — does NOT change with the picker.
    val Canvas        = Color(0xFFFBF6EE)
    val TextPrimary   = Color(0xFF463C31)
    val TextSecondary = Color(0xFF5F5245)

    // Backwards-compat aliases — every existing screen reads `Bg`,
    // `Ink`, `InkSoft`. Keep the names; point them at the refreshed
    // values so the brand pass propagates without screen-by-screen
    // edits.
    val Bg          = Canvas
    val Ink         = TextPrimary
    val InkSoft     = TextSecondary

    val Surface     = Color(0xFFFFFFFF)
    val Border      = Color(0xFFEDE4D2)
    val BorderSoft  = Color(0xFFF0E9DD)
    val Muted       = Color(0xFFA8A29E)
    val TextOnAccent = Color(0xFFFFFFFF)

    // Hue families — each pair is (base, deep). The picked family
    // determines `accent` / `accentDeep` at the theme entry point.
    val CoralBase       = Color(0xFFE07856)
    val CoralDeep       = Color(0xFFC65A3E)
    val LeafGreenBase   = Color(0xFF7AA874)
    val LeafGreenDeep   = Color(0xFF5B8C52)
    val LeafYellowBase  = Color(0xFFF4C430)
    val LeafYellowDeep  = Color(0xFFE8B923)
    val LeafDryBase     = Color(0xFFB8956A)
    val LeafDryDeep     = Color(0xFF8B7355)

    // Default accent values — what every screen sees BEFORE the
    // user has picked anything (or when the theme provider is
    // skipped, e.g. in previews). Coral default matches the
    // pre-picker baseline.
    val Accent      = CoralDeep
    val AccentDeep  = CoralDeep
    val AccentSoft  = Color(0xFFF5EDE0)

    // Paper tones for note thumbnails — separate from the picker so
    // the wall of cards looks varied even after the user picks Leaf
    // Green or Leaf Yellow as their primary. Same warm palette the
    // mock spec called out.
    val Paper1      = Color(0xFFE8DCC4)
    val Paper2      = Color(0xFFF0E4D7)
    val Paper3      = Color(0xFFEADFCF)

    val Success     = Color(0xFF6B8E5A)
    val Warning     = Color(0xFFC97A2C)
    val Danger      = Color(0xFFB54B3F)

    // ─── Workspace taxonomy ───────────────────────────────────
    //
    // Tag-bucket hues — seven brand-fixed accents used by the
    // Workspace tab's tag vocabulary section. Each bucket answers
    // one question (`design/WORKSPACE_TAB_HANDOFF.md` §4.2) and
    // carries a single hue across pill border, pill text, and
    // bucket-bar fill. Values are preserved across light/dark
    // (same treatment as `AccentDeep`) — readability on the
    // canvas is maintained by the 12 %-opacity pill fill.
    val BucketStatus    = Color(0xFF4F46E5)
    val BucketPeople    = Color(0xFF0F9F6E)
    val BucketOrgPlace  = Color(0xFF0891B2)
    val BucketEnergy    = Color(0xFFC2570A)
    val BucketTime      = Color(0xFFD33B4D)
    val BucketKind      = Color(0xFF7C3AED)
    val BucketSource    = Color(0xFF0E7FB8)

    // Tier stripes — the 3-dp vertical bar on the left of every
    // folder row. Tier 2 reuses `Ink` so the folder list reads
    // as a single editorial column with the "workflow" /
    // "creative" tiers as accent moments.
    val Tier1           = Color(0xFF6366F1)
    val Tier3           = Color(0xFF10B981)

    /**
     * Rotate through paper tones for note thumbnails so a wall of
     * cards doesn't look monotonous. Keyed by note ID hash so each
     * note gets a stable tone across sessions.
     */
    fun paper(seed: Int): Color = when (((seed % 3) + 3) % 3) {
        0    -> Paper1
        1    -> Paper2
        else -> Paper3
    }
}

/**
 * The four hue families the user can pick from in Settings →
 * Appearance. Each family carries its base + deep variant; the
 * theme entry point picks one based on the active mode.
 */
enum class PrimaryColor(
    val displayName: String,
    val base: Color,
    val deep: Color,
) {
    Coral     ("Coral",       QuickInkColors.CoralBase,      QuickInkColors.CoralDeep),
    LeafGreen ("Leaf Green",  QuickInkColors.LeafGreenBase,  QuickInkColors.LeafGreenDeep),
    LeafYellow("Leaf Yellow", QuickInkColors.LeafYellowBase, QuickInkColors.LeafYellowDeep),
    LeafDry   ("Leaf Dry",    QuickInkColors.LeafDryBase,    QuickInkColors.LeafDryDeep);

    companion object {
        /** Round-trip the persisted string. Unknown values fall back to Coral. */
        fun fromKey(key: String?): PrimaryColor =
            values().firstOrNull { it.name == key } ?: Coral
    }

    /** Pixel value to write into prefs — the enum's name is stable. */
    val key: String get() = name
}

/**
 * User-pickable theme override. `System` follows the OS setting;
 * `Light` / `Dark` force the corresponding mode regardless of OS.
 */
enum class ThemeMode(val displayName: String) {
    System("System"),
    Light ("Light"),
    Dark  ("Dark");

    companion object {
        fun fromKey(key: String?): ThemeMode =
            values().firstOrNull { it.name == key } ?: System
    }

    val key: String get() = name
}
