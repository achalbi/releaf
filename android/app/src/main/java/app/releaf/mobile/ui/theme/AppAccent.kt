/*
 * AppAccent.kt
 *
 * Accent-color API. The four primary palettes (Coral / Green / Yellow
 * / Dry) live on [AccentPalettes]; the active one is provided via
 * [LocalAccent] by `ReleafTheme` and read through the [AppAccent]
 * facade — which mirrors the read-site shape of `AppColors.Coral*`
 * so the migration is a straight rename.
 *
 * Mapping (old → new):
 *   AppColors.Coral        → AppAccent.primary
 *   AppColors.CoralDeep    → AppAccent.deep
 *   AppColors.CoralSoft    → AppAccent.soft
 *   AppColors.CoralOutline → AppAccent.primary   (outline = primary)
 */

package app.releaf.mobile.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Roles needed by call sites that used to read Coral*. Constructed
 * per-palette once; hot-swapped via [LocalAccent] without touching
 * call sites.
 */
data class AccentPalette(
    /** Main accent, the old `AppColors.Coral`. */
    val primary: Color,
    /** Pressed / deep variant, the old `AppColors.CoralDeep`. */
    val deep: Color,
    /** Soft tint background, the old `AppColors.CoralSoft`. Kept as a
     *  10%-alpha overlay on the primary so it works on both light and
     *  dark canvases; call sites that layered it on a solid card fill
     *  still read as a pale accent wash. */
    val soft: Color,
    /** ~30%-alpha border variant, for outlined pills / chips. */
    val border: Color,
)

object AccentPalettes {
    val Coral = AccentPalette(
        primary = AppColors.ThemeCoralPrimary,
        deep    = AppColors.ThemeCoralDeep,
        soft    = AppColors.ThemeCoralBgSoft,
        border  = AppColors.ThemeCoralBorderSoft,
    )
    val Green = AccentPalette(
        primary = AppColors.ThemeGreenPrimary,
        deep    = AppColors.ThemeGreenDeep,
        soft    = AppColors.ThemeGreenBgSoft,
        border  = AppColors.ThemeGreenBorderSoft,
    )
    val Yellow = AccentPalette(
        primary = AppColors.ThemeYellowPrimary,
        deep    = AppColors.ThemeYellowDeep,
        soft    = AppColors.ThemeYellowBgSoft,
        border  = AppColors.ThemeYellowBorderSoft,
    )
    val Dry = AccentPalette(
        primary = AppColors.ThemeDryPrimary,
        deep    = AppColors.ThemeDryDeep,
        soft    = AppColors.ThemeDryBgSoft,
        border  = AppColors.ThemeDryBorderSoft,
    )

    fun forId(id: AccentPaletteId): AccentPalette = when (id) {
        AccentPaletteId.Coral  -> Coral
        AccentPaletteId.Green  -> Green
        AccentPaletteId.Yellow -> Yellow
        AccentPaletteId.Dry    -> Dry
    }
}

/** The active palette for the current composition subtree. Default
 *  is Coral so previews / tests that forget to wrap in ReleafTheme
 *  still paint correctly. */
val LocalAccent = compositionLocalOf { AccentPalettes.Coral }

/**
 * Composable-only facade. Each getter reads `LocalAccent.current`, so
 * toggling the palette via `CompositionLocalProvider` hot-swaps every
 * call site on the next recomposition with zero additional plumbing.
 */
object AppAccent {
    val primary: Color
        @Composable @ReadOnlyComposable
        get() = LocalAccent.current.primary

    val deep: Color
        @Composable @ReadOnlyComposable
        get() = LocalAccent.current.deep

    val soft: Color
        @Composable @ReadOnlyComposable
        get() = LocalAccent.current.soft

    val border: Color
        @Composable @ReadOnlyComposable
        get() = LocalAccent.current.border
}
