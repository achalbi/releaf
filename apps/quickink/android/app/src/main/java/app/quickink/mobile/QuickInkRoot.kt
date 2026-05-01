/*
 * QuickInkRoot.kt
 *
 * QuickInk's top-level Compose entry point. Counterpart to iOS's
 * `QuickInkRoot.swift`. Body intentionally exercises shared design
 * tokens (`AppColors.Canvas`, `AppTypography.PageTitle`,
 * `AppSpacing.s4`) so a regression in the
 * `:shared:designsystem` ↔ QuickInk wiring shows up at first build
 * rather than at MVP-feature time.
 *
 * Real screens (per QUICKINK_PROPOSAL.md §6.4):
 *   - 3-screen onboarding (welcome / permissions / Google sign-in
 *     with Drive backup toggle on screen 3)
 *   - Camera-first Home opening directly to DocumentScannerLauncher
 *   - Scan + OCR result review surface
 *   - Notes list + editor (thin wrappers over :shared:notes VMs)
 *   - Settings
 */

package app.quickink.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.releaf.mobile.ui.theme.AppColors
import app.releaf.mobile.ui.theme.AppSpacing
import app.releaf.mobile.ui.theme.AppTypography

@Composable
fun QuickInkRoot() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColors.Canvas),
        contentAlignment = Alignment.TopStart,
    ) {
        Column(
            modifier = Modifier.padding(AppSpacing.s5),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.s2),
        ) {
            Text(
                text  = "QuickInk",
                style = AppTypography.PageTitle,
                color = AppColors.TextPrimary,
            )
            Text(
                text  = "Phase 3 scaffold",
                style = AppTypography.Meta,
                color = AppColors.TextSecondary,
            )
        }
    }
}
