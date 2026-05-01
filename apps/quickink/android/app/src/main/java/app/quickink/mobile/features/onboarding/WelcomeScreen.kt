/*
 * WelcomeScreen.kt
 *
 * Onboarding step 1/3. Brand intro + "Get started" CTA. Uses a
 * Material icon as the hero glyph for now; the brand-pass
 * illustration lands when QuickInk's onboarding-illustration
 * scaffolding extracts from Releaf (Phase 4 polish item, see file
 * header on `OnboardingState.kt`).
 *
 * Mirror of iOS `WelcomeScreen.swift`.
 */

package app.quickink.mobile.features.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.releaf.mobile.ui.theme.AppColors
import app.releaf.mobile.ui.theme.AppSpacing
import app.releaf.mobile.ui.theme.AppTypography

@Composable
fun WelcomeScreen(onContinue: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColors.Canvas),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))

        Icon(
            imageVector  = Icons.Filled.DocumentScanner,
            contentDescription = null,
            tint         = AppColors.ThemeGreenPrimary,
            modifier     = Modifier.size(80.dp),
        )

        Spacer(Modifier.size(AppSpacing.s5))

        Text(
            text  = "Welcome to QuickInk",
            style = AppTypography.PageTitle,
            color = AppColors.TextPrimary,
        )

        Spacer(Modifier.size(AppSpacing.s2))

        Text(
            text     = "Scan documents, search the text, never lose a page.",
            style    = AppTypography.Body,
            color    = AppColors.TextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = AppSpacing.s5),
        )

        Spacer(Modifier.weight(1f))

        OnboardingPrimaryButton(
            label   = "Get Started",
            onClick = onContinue,
        )

        Spacer(Modifier.size(AppSpacing.s5))
    }
}
