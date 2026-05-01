/*
 * PermissionsScreen.kt
 *
 * Onboarding step 2/3. Educates the user about the camera
 * permission QuickInk will request the first time they tap "scan."
 *
 * Doesn't actually request the permission here — that lands at
 * scan time (the system permission prompt appears in-context when
 * ML Kit's `GmsDocumentScanning` first launches). Pre-empting it
 * on this screen would just produce an awkward out-of-context
 * prompt; the screen's job is to set expectation.
 *
 * Mirror of iOS `PermissionsScreen.swift`.
 */

package app.quickink.mobile.features.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.releaf.mobile.ui.theme.AppColors
import app.releaf.mobile.ui.theme.AppSpacing
import app.releaf.mobile.ui.theme.AppTypography

@Composable
fun PermissionsScreen(onContinue: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColors.Canvas),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))

        Icon(
            imageVector  = Icons.Filled.CameraAlt,
            contentDescription = null,
            tint         = AppColors.ThemeGreenPrimary,
            modifier     = Modifier.size(64.dp),
        )

        Spacer(Modifier.size(AppSpacing.s5))

        Text(
            text  = "Camera access",
            style = AppTypography.PageTitle,
            color = AppColors.TextPrimary,
        )

        Spacer(Modifier.size(AppSpacing.s2))

        Text(
            text     = "QuickInk needs the camera to scan documents. We'll ask the first time you tap a scan — you can decline anytime in Settings.",
            style    = AppTypography.Body,
            color    = AppColors.TextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = AppSpacing.s5),
        )

        Spacer(Modifier.weight(1f))

        OnboardingPrimaryButton(
            label   = "Continue",
            onClick = onContinue,
        )

        Spacer(Modifier.size(AppSpacing.s5))
    }
}
