/*
 * QuickInkColors.kt
 *
 * QuickInk's local color palette — the warm, editorial Claude-style
 * palette specified in the mockup brief. Mirror of iOS
 * `QuickInkColors` enum in `QuickInkTheme.swift`.
 *
 * Why this lives here, not in :shared:designsystem: the shared
 * design system is owned by Releaf and used by multiple apps;
 * bending its tokens to QuickInk's editorial direction would either
 * fight Releaf's needs or force a multi-theme indirection across
 * every shared surface. Instead, QuickInk introduces its own
 * concrete tokens here. Shared tokens stay available for any
 * surface reaching into shared components (NotepadEditorViewModel
 * etc.).
 *
 * Token shape mirrors `app.releaf.mobile.ui.theme.AppColors` so the
 * mental model carries over; values come from the mockup tokens
 * table:
 *
 *   Bg          #FAF7F2  app background
 *   Surface     #FFFFFF  cards
 *   Border      #EDE4D2  dividers, card borders
 *   BorderSoft  #F0E9DD  pill backgrounds, search bar fill
 *   Accent      #D97757  coral — CTAs, active state, FAB
 *   AccentSoft  #F5EDE0  category tag backgrounds
 *   Ink         #2C2826  primary text
 *   InkSoft     #5C4A38  secondary text
 *   Muted       #A8A29E  tertiary text, inactive nav
 *   Paper1/2/3  #E8DCC4 / #F0E4D7 / #EADFCF  note thumbnail bg
 */

package app.quickink.mobile.ui.theme

import androidx.compose.ui.graphics.Color

object QuickInkColors {
    val Bg          = Color(0xFFFAF7F2)
    val Surface     = Color(0xFFFFFFFF)
    val Border      = Color(0xFFEDE4D2)
    val BorderSoft  = Color(0xFFF0E9DD)
    val Accent      = Color(0xFFD97757)
    val AccentSoft  = Color(0xFFF5EDE0)
    val AccentDeep  = Color(0xFFB85F42)
    val Ink         = Color(0xFF2C2826)
    val InkSoft     = Color(0xFF5C4A38)
    val Muted       = Color(0xFFA8A29E)
    val TextOnAccent = Color(0xFFFFFFFF)

    val Paper1      = Color(0xFFE8DCC4)
    val Paper2      = Color(0xFFF0E4D7)
    val Paper3      = Color(0xFFEADFCF)

    val Success     = Color(0xFF6B8E5A)
    val Warning     = Color(0xFFC97A2C)
    val Danger      = Color(0xFFB54B3F)

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
