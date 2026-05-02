/*
 * OnboardingScaffold.kt
 *
 * Shared layout shell for the 3 onboarding screens — hero
 * illustration area, editorial heading, supporting copy, 3-dot
 * page indicator, and a CTA. Mirror of iOS
 * `OnboardingScaffold.swift`.
 *
 * The illustration is provided as a `@Composable` slot so each
 * step can substitute its own (notebook+scan-line, camera, cloud).
 */

package app.quickink.mobile.features.onboarding

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.quickink.mobile.ui.theme.LocalQuickInkColors
import app.quickink.mobile.ui.theme.LocalQuickInkTypography
import app.quickink.mobile.ui.theme.QuickInkRadius
import app.quickink.mobile.ui.theme.QuickInkSpacing
import app.quickink.mobile.ui.theme.quickInkDotGridBackground

@Composable
fun OnboardingScaffold(
    title: String,
    subtitle: String,
    ctaLabel: String,
    stepIndex: Int,
    totalSteps: Int = 3,
    onContinue: () -> Unit,
    illustration: @Composable () -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .quickInkDotGridBackground(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))

        // Illustration area.
        Box(
            modifier         = Modifier
                .fillMaxWidth()
                .height(320.dp),
            contentAlignment = Alignment.Center,
        ) {
            illustration()
        }

        Spacer(Modifier.size(QuickInkSpacing.s4))

        Text(
            text     = title,
            style    = type.display,
            color    = colors.ink,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = QuickInkSpacing.s5),
        )

        Spacer(Modifier.size(QuickInkSpacing.s3))

        Text(
            text     = subtitle,
            style    = type.body,
            color    = colors.inkSoft,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = QuickInkSpacing.s7),
        )

        Spacer(Modifier.weight(1f))

        // Page-indicator dots — animate from 8.dp circle to 24.dp
        // pill on the active step.
        Row(
            horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2),
            verticalAlignment     = Alignment.CenterVertically,
            modifier              = Modifier.padding(bottom = QuickInkSpacing.s5),
        ) {
            for (i in 0 until totalSteps) {
                val width by animateDpAsState(
                    targetValue = if (i == stepIndex) 24.dp else 8.dp,
                    label       = "indicator-width-$i",
                )
                Box(
                    modifier = Modifier
                        .width(width)
                        .height(8.dp)
                        .clip(if (i == stepIndex) RoundedCornerShape(QuickInkRadius.pill) else CircleShape)
                        .background(if (i == stepIndex) colors.accent else colors.border),
                )
            }
        }

        // CTA — coral pill.
        Box(
            modifier = Modifier
                .padding(horizontal = QuickInkSpacing.s5)
                .fillMaxWidth()
                .clip(RoundedCornerShape(QuickInkRadius.pill))
                .background(colors.accent)
                .padding(vertical = QuickInkSpacing.s3)
                .clickableNoIndication(onClick = onContinue),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text  = ctaLabel,
                style = type.label,
                color = colors.textOnAccent,
            )
        }

        Spacer(Modifier.size(QuickInkSpacing.s7))
    }
}

// Tiny helper so the CTA's coral fill doesn't get a default ripple
// (which Material3 paints in primary color and looks off on coral).
@Composable
private fun Modifier.clickableNoIndication(onClick: () -> Unit): Modifier =
    this.clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication        = null,
        onClick           = onClick,
    )
