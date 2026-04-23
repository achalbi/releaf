/*
 * SignInScreen.kt
 * "Sign in with Google" — placeholder UI. Tapping the button calls into
 * AuthStore.signIn(), which today drives the StubGoogleAuthClient.
 */

package app.releaf.mobile.features.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import app.releaf.mobile.auth.AuthState
import app.releaf.mobile.ui.components.AppButton
import app.releaf.mobile.ui.components.ReleafLogoRow
import app.releaf.mobile.ui.components.ReleafLogoSize
import app.releaf.mobile.ui.theme.AppColors
import app.releaf.mobile.ui.theme.AppSpacing
import app.releaf.mobile.ui.theme.AppTypography

@Composable
fun SignInScreen(
    state: AuthState,
    onSignIn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Canvas + dot-grid come from the parent `ReleafCanvas` wrapper;
    // don't repaint here or it'll cover the texture.
    Box(
        modifier
            .fillMaxSize()
            .padding(horizontal = AppSpacing.s6, vertical = AppSpacing.s8),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier.padding(top = AppSpacing.s10),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(AppSpacing.s3),
            ) {
                ReleafLogoRow(size = ReleafLogoSize.Md)
                Text(
                    "Capture your day",
                    style = AppTypography.EditorialTitle,
                    color = AppColors.TextPrimary,
                    textAlign = TextAlign.Center,
                )
                Text(
                    "Notes, photos, voice, to-dos, scans, contacts, places — all in one page. Stored in your own Google Drive.",
                    style = AppTypography.Body,
                    color = AppColors.TextSecondary,
                    textAlign = TextAlign.Center,
                )
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(AppSpacing.s3),
            ) {
                AppButton(
                    text = "Sign in with Google",
                    onClick = onSignIn,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (state is AuthState.Failed) {
                    Text(
                        state.message,
                        style = AppTypography.Meta,
                        color = AppColors.Danger,
                    )
                }

                Text(
                    "Releaf only sees files it creates in your Drive.",
                    style = AppTypography.Meta,
                    color = AppColors.TextTertiary,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
