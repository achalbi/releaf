/*
 * OnboardingPrimaryButton.kt
 *
 * Shared CTA button shape — coral pill, white label. Used by
 * surfaces that aren't using `OnboardingScaffold` directly (e.g.,
 * the legacy HomeScreen scan CTA before its full redesign).
 *
 * Reads from `LocalQuickInkColors` / `LocalQuickInkTypography` so
 * a brand pass tweak in `QuickInkTheme.kt` lands here without a
 * direct edit.
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
import app.quickink.mobile.ui.theme.LocalQuickInkColors
import app.quickink.mobile.ui.theme.LocalQuickInkTypography
import app.quickink.mobile.ui.theme.QuickInkRadius
import app.quickink.mobile.ui.theme.QuickInkSpacing

@Composable
fun OnboardingPrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current

    Box(
        modifier = modifier
            .padding(horizontal = QuickInkSpacing.s5)
            .fillMaxWidth()
            .clip(RoundedCornerShape(QuickInkRadius.pill))
            .background(colors.accent)
            .clickable(onClick = onClick)
            .padding(vertical = QuickInkSpacing.s3),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text  = label,
            style = type.label,
            color = colors.textOnAccent,
        )
    }
}
