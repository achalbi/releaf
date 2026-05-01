/*
 * ScanReviewScreen.kt
 *
 * Shown while OCR runs on a fresh capture, then while the user
 * acknowledges the completed result. Reads `ScanFlowController`'s
 * state and renders accordingly:
 *
 *   - Recognizing  → progress UI ("Recognizing page X of Y")
 *   - Complete     → summary + "Done" CTA returning to Home
 *   - Failed       → error + "Done" returning to Home
 *
 * Per-page editable text review (the user can edit the recognized
 * text before save) lands in Slice 4 alongside the notes editor
 * wrappers. For Slice 3 the screen is review-only — the OCR
 * result is auto-persisted as it lands; this screen just surfaces
 * progress and the final summary.
 *
 * Mirror of iOS `ScanReviewScreen.swift`.
 */

package app.quickink.mobile.features.scan

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.quickink.mobile.features.onboarding.OnboardingPrimaryButton
import app.releaf.mobile.ui.theme.AppColors
import app.releaf.mobile.ui.theme.AppSpacing
import app.releaf.mobile.ui.theme.AppTypography

@Composable
fun ScanReviewScreen(controller: ScanFlowController) {
    val state by controller.state.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColors.Canvas),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))

        when (val current = state) {
            is ScanFlowController.State.Idle -> {
                // Shouldn't render — `QuickInkRoot` swaps to
                // HomeScreen on Idle. Defensive empty body.
            }

            is ScanFlowController.State.Recognizing -> {
                RecognizingBody(
                    completed = current.completedPages,
                    total     = current.totalPages,
                )
            }

            is ScanFlowController.State.Complete -> {
                CompleteBody(success = current.successCount, total = current.totalPages)
            }

            is ScanFlowController.State.Failed -> {
                FailedBody(message = current.message)
            }
        }

        Spacer(Modifier.weight(1f))

        // Done button — visible only on terminal states. Blocks
        // dismissal mid-OCR so a half-recognized capture isn't
        // left dangling on Home; users wait for the pipeline.
        if (state !is ScanFlowController.State.Recognizing) {
            OnboardingPrimaryButton(
                label   = "Done",
                onClick = { controller.dismiss() },
            )
            Spacer(Modifier.size(AppSpacing.s5))
        }
    }
}

@Composable
private fun RecognizingBody(completed: Int, total: Int) {
    CircularProgressIndicator(
        color = AppColors.ThemeGreenPrimary,
        modifier = Modifier.size(48.dp),
    )

    Spacer(Modifier.size(AppSpacing.s5))

    Text(
        text  = "Recognizing text",
        style = AppTypography.PageTitle,
        color = AppColors.TextPrimary,
    )

    Spacer(Modifier.size(AppSpacing.s2))

    Text(
        text  = "Page $completed of $total",
        style = AppTypography.Meta,
        color = AppColors.TextSecondary,
    )
}

@Composable
private fun CompleteBody(success: Int, total: Int) {
    Icon(
        imageVector  = Icons.Filled.CheckCircle,
        contentDescription = null,
        tint         = AppColors.ThemeGreenPrimary,
        modifier     = Modifier.size(64.dp),
    )

    Spacer(Modifier.size(AppSpacing.s5))

    Text(
        text  = "Saved",
        style = AppTypography.PageTitle,
        color = AppColors.TextPrimary,
    )

    Spacer(Modifier.size(AppSpacing.s2))

    Text(
        text     = "Recognized text on $success of $total pages.",
        style    = AppTypography.Meta,
        color    = AppColors.TextSecondary,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = AppSpacing.s5),
    )
}

@Composable
private fun FailedBody(message: String) {
    Icon(
        imageVector  = Icons.Filled.Warning,
        contentDescription = null,
        tint         = AppColors.Warning,
        modifier     = Modifier.size(64.dp),
    )

    Spacer(Modifier.size(AppSpacing.s5))

    Text(
        text  = "Couldn't save",
        style = AppTypography.PageTitle,
        color = AppColors.TextPrimary,
    )

    Spacer(Modifier.size(AppSpacing.s2))

    Text(
        text     = message,
        style    = AppTypography.Meta,
        color    = AppColors.TextSecondary,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = AppSpacing.s5),
    )
}
