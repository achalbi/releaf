/*
 * AppSpacing.kt + AppRadius.kt
 * 4-pt grid. Four radii.
 */

package app.releaf.mobile.ui.theme

import androidx.compose.ui.unit.dp

object AppSpacing {
    val s0  = 0.dp
    val s1  = 4.dp
    val s2  = 8.dp
    val s3  = 12.dp
    val s4  = 16.dp
    val s5  = 20.dp
    val s6  = 24.dp
    val s8  = 32.dp
    val s10 = 40.dp
}

object AppRadius {
    val sm   = 6.dp
    val md   = 12.dp
    val lg   = 16.dp
    val pill = 9999.dp

    /**
     * Floating BottomNav radius. Currently aliases `lg` (16dp). Swappable so
     * shape changes to the floating nav stay in one place.
     */
    val nav  = lg
}
