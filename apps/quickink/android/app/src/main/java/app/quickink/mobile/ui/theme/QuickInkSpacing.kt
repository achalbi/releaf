/*
 * QuickInkSpacing.kt
 *
 * 4-dp-based spacing scale + corner radius tokens. Mirror of iOS
 * `QuickInkSpacing` / `QuickInkRadius` / `QuickInkLetterSpacing`
 * enums in `QuickInkTheme.swift`.
 *
 * Uses the same s1–s8 shape as `:shared:designsystem`'s
 * `AppSpacing` so screens that read both can compose without
 * surprise.
 */

package app.quickink.mobile.ui.theme

import androidx.compose.ui.unit.dp

object QuickInkSpacing {
    val s1 = 4.dp
    val s2 = 8.dp
    val s3 = 12.dp
    val s4 = 16.dp
    val s5 = 20.dp
    val s6 = 24.dp
    val s7 = 32.dp
    val s8 = 40.dp
}

object QuickInkRadius {
    val sm = 8.dp
    val md = 12.dp
    val lg = 18.dp
    val xl = 24.dp

    /** Pill shape — used for CTAs, chips, filter selectors. */
    val pill = 999.dp
}
