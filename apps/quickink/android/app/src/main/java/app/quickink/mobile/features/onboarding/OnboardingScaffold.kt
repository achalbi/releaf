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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.quickink.mobile.ui.theme.QuickInkFonts
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
    /**
     * Optional italic + coral clause appended to the title on a new
     * line, mirroring the mock's "Your notebook,\n*digitised.*"
     * pattern. When null the title renders as a single upright
     * serif block.
     */
    titleAccent: String? = null,
    onContinue: () -> Unit,
    illustration: @Composable () -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current

    // Build the title with an optional italic+coral accent on a
    // new line. AnnotatedString lets the two clauses share a single
    // wrapping/centering pass — splitting them into two Text views
    // would force a fixed line break and lose the centered-block
    // multi-line feel from the mock.
    val titleAnnotated: AnnotatedString = remember(title, titleAccent, colors.accent) {
        buildAnnotatedString {
            append(title)
            if (titleAccent != null) {
                append("\n")
                withStyle(
                    SpanStyle(
                        fontFamily = QuickInkFonts.serif,
                        fontStyle  = FontStyle.Italic,
                        fontWeight = FontWeight.Normal,
                        color      = colors.accent,
                    )
                ) {
                    append(titleAccent)
                }
            }
        }
    }

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

        // Title uses `onboardingTitle` (30sp) rather than the
        // app-wide `display` (40sp) so the two-line tagline doesn't
        // dominate the screen on a 390-wide phone frame — matches
        // the JSX mockup's `text-[30px]` heading. The `titleAccent`
        // clause (if any) renders inline as italic coral via the
        // AnnotatedString built above.
        Text(
            text     = titleAnnotated,
            style    = type.onboardingTitle,
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

        // CTA — coral rounded-rectangle (not a full pill) with a
        // trailing arrow icon and a soft coral drop-shadow. Mirrors
        // the JSX mockup's `rounded-2xl … shadow-md` button.
        // Label uses the same serif family as the hero so the
        // editorial type carries through to the action.
        Box(
            modifier = Modifier
                .padding(horizontal = QuickInkSpacing.s5)
                .fillMaxWidth()
                .shadow(
                    elevation = 14.dp,
                    shape     = RoundedCornerShape(QuickInkRadius.xl),
                    ambientColor = colors.accent,
                    spotColor    = colors.accent,
                )
                .clip(RoundedCornerShape(QuickInkRadius.xl))
                .background(colors.accent)
                .clickableNoIndication(onClick = onContinue)
                .padding(vertical = QuickInkSpacing.s4),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2),
                verticalAlignment     = Alignment.CenterVertically,
            ) {
                Text(
                    text  = ctaLabel,
                    style = type.ctaSerif,
                    color = colors.textOnAccent,
                )
                Icon(
                    imageVector       = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint              = colors.textOnAccent,
                    modifier          = Modifier.size(16.dp),
                )
            }
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
