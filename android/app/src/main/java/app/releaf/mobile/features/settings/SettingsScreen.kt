/*
 * SettingsScreen.kt
 * Placeholder for the "Settings" tab. Hosts the Sign Out action for now —
 * the rest of settings (sync, appearance, account) are not yet designed.
 */

package app.releaf.mobile.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import app.releaf.mobile.ui.components.AppButton
import app.releaf.mobile.ui.components.AppButtonVariant
import app.releaf.mobile.ui.theme.AppColors
import app.releaf.mobile.ui.theme.AppSpacing
import app.releaf.mobile.ui.theme.AppTypography

@Composable
fun SettingsScreen(
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(AppSpacing.s6),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("SETTINGS", style = AppTypography.Eyebrow, color = AppColors.Coral)
        Text(
            "Preferences",
            style = AppTypography.EditorialTitle,
            color = AppColors.TextPrimary,
            textAlign = TextAlign.Center,
        )
        Text(
            "Sync, appearance, and account settings are coming soon.",
            style = AppTypography.Body,
            color = AppColors.TextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = AppSpacing.s3),
        )
        Spacer(Modifier.height(AppSpacing.s6))
        AppButton(
            text = "Sign out",
            onClick = onSignOut,
            variant = AppButtonVariant.Secondary,
            fillWidth = false,
        )
    }
}
