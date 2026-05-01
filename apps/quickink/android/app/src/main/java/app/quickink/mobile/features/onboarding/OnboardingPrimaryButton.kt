/*
 * OnboardingPrimaryButton.kt
 *
 * Shared CTA button shape for the 3 onboarding screens. Pulled
 * out so the pill / fill / typography / padding choices live in
 * one place — when the brand pass redesigns the CTA, one edit
 * here updates all three screens.
 *
 * Lives in the QuickInk onboarding feature folder rather than in
 * :shared:designsystem because the shape is QuickInk-specific
 * and only used by onboarding. If/when more QuickInk surfaces
 * adopt the same CTA shape, promote it.
 */

package app.quickink.mobile.features.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.releaf.mobile.ui.theme.AppColors
import app.releaf.mobile.ui.theme.AppSpacing
import app.releaf.mobile.ui.theme.AppTypography

@Composable
fun OnboardingPrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .padding(horizontal = AppSpacing.s5)
            .fillMaxWidth()
            .clip(RoundedCornerShape(percent = 50))
            .background(AppColors.ThemeGreenPrimary)
            .clickable(onClick = onClick)
            .padding(vertical = AppSpacing.s3),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text  = label,
            style = AppTypography.Body,
            color = AppColors.TextOnAccent,
        )
    }
}
