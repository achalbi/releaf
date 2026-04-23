/*
 * AppButton.kt
 * Primary / secondary (outline) / text button variants.
 */

package app.releaf.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.releaf.mobile.ui.theme.AppColors
import app.releaf.mobile.ui.theme.AppAccent
import app.releaf.mobile.ui.theme.AppRadius
import app.releaf.mobile.ui.theme.AppSpacing
import app.releaf.mobile.ui.theme.AppTypography

enum class AppButtonVariant { Primary, Secondary, Text }

@Composable
fun AppButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: AppButtonVariant = AppButtonVariant.Primary,
    fillWidth: Boolean = true,
) {
    val background = when (variant) {
        AppButtonVariant.Primary   -> AppColors.ActionPrimary
        AppButtonVariant.Secondary -> AppColors.CardSolid
        AppButtonVariant.Text      -> Color.Transparent
    }
    val foreground = when (variant) {
        AppButtonVariant.Primary   -> AppColors.OnPrimary
        AppButtonVariant.Secondary -> AppColors.TextPrimary
        AppButtonVariant.Text      -> AppAccent.primary
    }
    val borderWidth = if (variant == AppButtonVariant.Secondary) 1.dp else 0.dp
    val borderColor = if (variant == AppButtonVariant.Secondary) AppColors.BorderStrong else Color.Transparent

    val base = modifier
        .clip(RoundedCornerShape(AppRadius.lg))
        .background(background)
        .border(borderWidth, borderColor, RoundedCornerShape(AppRadius.lg))
        .clickable { onClick() }
        .padding(horizontal = AppSpacing.s6, vertical = AppSpacing.s3)

    val layout = if (fillWidth && variant != AppButtonVariant.Text) base.fillMaxWidth() else base

    Row(
        modifier = layout,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = AppTypography.Button,
            color = foreground,
        )
    }
}

/**
 * Primary-filled button with an explicit `enabled` state. Used by
 * editor section CTAs (e.g. "Move to notebook") that gate the action
 * on prior form completion. A disabled instance paints a greyed-out
 * pill and eats taps without firing [onClick].
 *
 * This is a thin wrapper around [AppButton] so call sites don't have
 * to thread `clickable(enabled = …)` + alpha by hand.
 */
@Composable
fun AppPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val background = if (enabled) AppColors.ActionPrimary else AppColors.BorderDefault
    val foreground = if (enabled) AppColors.OnPrimary else AppColors.TextSecondary

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(AppRadius.pill))
            .background(background)
            .then(if (enabled) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = AppSpacing.s4, vertical = AppSpacing.s3),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = AppTypography.Button,
            color = foreground,
        )
    }
}
