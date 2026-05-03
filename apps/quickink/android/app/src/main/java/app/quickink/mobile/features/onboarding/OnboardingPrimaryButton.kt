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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.dp
import app.quickink.mobile.ui.theme.LocalQuickInkColors
import app.quickink.mobile.ui.theme.LocalQuickInkTypography
import app.quickink.mobile.ui.theme.QuickInkRadius
import app.quickink.mobile.ui.theme.QuickInkSpacing

/**
 * Shared onboarding CTA — coral rounded-rectangle (radius xl, NOT
 * a full pill) with white label, optional trailing arrow icon, and
 * a soft coral drop-shadow. Mirrors the JSX mockup's
 * `rounded-2xl py-4 shadow-md` button. Pass `showArrow = false`
 * for surfaces where a trailing arrow would feel wrong (e.g. a
 * static "Continue with Google" CTA that already has a leading
 * provider icon).
 */
@Composable
fun OnboardingPrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showArrow: Boolean = true,
) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current

    Box(
        modifier = modifier
            .padding(horizontal = QuickInkSpacing.s5)
            .fillMaxWidth()
            .shadow(
                elevation    = 14.dp,
                shape        = RoundedCornerShape(QuickInkRadius.xl),
                ambientColor = colors.accent,
                spotColor    = colors.accent,
            )
            .clip(RoundedCornerShape(QuickInkRadius.xl))
            .background(colors.accent)
            .clickable(onClick = onClick)
            .padding(vertical = QuickInkSpacing.s4),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2),
            verticalAlignment     = Alignment.CenterVertically,
        ) {
            Text(
                text  = label,
                style = type.ctaSerif,
                color = colors.textOnAccent,
            )
            if (showArrow) {
                Icon(
                    imageVector       = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint              = colors.textOnAccent,
                    modifier          = Modifier.size(16.dp),
                )
            }
        }
    }
}
