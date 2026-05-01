/*
 * AppFontWeights.kt
 *
 * Single source of truth for the global typographic weight. The user
 * picks one of [AppFontWeight] in Settings; `ReleafTheme` resolves it
 * to a Compose [FontWeight] and provides it via [LocalFontWeight].
 *
 * Every role on [AppTypography] reads `LocalFontWeight.current`, and
 * every inline `fontWeight = …` reference across the app does the
 * same — so flipping the setting recomposes the entire typographic
 * surface with zero call-site changes.
 */

package app.releaf.mobile.ui.theme

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.text.font.FontWeight

fun AppFontWeight.toFontWeight(): FontWeight = when (this) {
    AppFontWeight.Light    -> FontWeight.Light
    AppFontWeight.Regular  -> FontWeight.Normal
    AppFontWeight.Medium   -> FontWeight.Medium
    AppFontWeight.SemiBold -> FontWeight.SemiBold
}

/** Active typographic weight for the current composition subtree.
 *  Default is Light so previews / tests that forget to wrap in
 *  ReleafTheme still paint with the same starting weight as users get
 *  on a fresh install. */
val LocalFontWeight = compositionLocalOf { FontWeight.Light }
