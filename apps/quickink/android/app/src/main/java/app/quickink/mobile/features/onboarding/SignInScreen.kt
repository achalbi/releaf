/*
 * SignInScreen.kt
 *
 * Onboarding step 3/3 — Google Sign-In + Drive backup toggle.
 * Per QUICKINK_PROPOSAL.md §1, screen 3 carries the Drive toggle
 * (a v8 lock decision). v1 default is on — Drive sync is the
 * value prop; opting out is for users who explicitly don't want
 * cloud backup.
 *
 * Phase 4 Slice 4.1 — sign-in is real. The button drives
 * `rememberQuickInkSignInAction` which runs the Credential
 * Manager + AuthorizationClient flow when
 * `R.string.google_web_client_id` is populated, or falls through
 * to the `AuthStore.signIn()` stub when it's still the placeholder.
 * Either way, success transitions `AuthStore.state` to
 * `SignedIn`, which triggers `onSignedIn`.
 *
 * The screen is also reused by `QuickInkRoot.ReSignInGate` for the
 * sign-out → re-sign-in flow (Option A). The Drive toggle stays
 * functional in that path — toggling it overwrites Settings, same
 * as the first-run flow. `onSignedIn` is the only post-success
 * hook; for the ReSignInGate path it's a no-op (QuickInkRoot
 * routes off `AuthStore.state` directly).
 *
 * Mirror of iOS `SignInScreen.swift`.
 */

package app.quickink.mobile.features.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.quickink.mobile.features.auth.rememberQuickInkSignInAction
import app.releaf.mobile.auth.AuthState
import app.releaf.mobile.auth.AuthStore
import app.releaf.mobile.ui.theme.AppColors
import app.releaf.mobile.ui.theme.AppSpacing
import app.releaf.mobile.ui.theme.AppTypography

@Composable
fun SignInScreen(
    state: OnboardingState,
    authStore: AuthStore,
    onSignedIn: () -> Unit,
) {
    val signInAction = rememberQuickInkSignInAction(authStore)
    val authState by authStore.state.collectAsState()

    // Whenever the AuthStore transitions to SignedIn while this
    // screen is visible, fire onSignedIn. Keyed on `authState` so
    // the effect re-runs when state flips. First-run callers
    // mark onboarding complete + persist Drive choice in the
    // handler; the ReSignInGate path passes a no-op handler and
    // relies on `QuickInkRoot`'s state observer to swap to
    // MainShell on its own.
    LaunchedEffect(authState) {
        if (authState is AuthState.SignedIn) {
            onSignedIn()
        }
    }

    val isSigningIn  = authState is AuthState.SigningIn
    val errorMessage = (authState as? AuthState.Failed)?.message

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColors.Canvas),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))

        Icon(
            imageVector  = Icons.Filled.CloudUpload,
            contentDescription = null,
            tint         = AppColors.ThemeGreenPrimary,
            modifier     = Modifier.size(64.dp),
        )

        Spacer(Modifier.size(AppSpacing.s5))

        Text(
            text  = "Sign in to back up",
            style = AppTypography.PageTitle,
            color = AppColors.TextPrimary,
        )

        Spacer(Modifier.size(AppSpacing.s2))

        Text(
            text     = "Sign in with Google so your scans sync to Drive and follow you across devices.",
            style    = AppTypography.Body,
            color    = AppColors.TextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = AppSpacing.s5),
        )

        Spacer(Modifier.size(AppSpacing.s5))

        // Drive toggle. Held in OnboardingState; OnboardingFlow
        // persists into Settings on success.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.s5),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically,
        ) {
            Text(
                text  = "Back up to Google Drive",
                style = AppTypography.Body,
                color = AppColors.TextPrimary,
            )
            Switch(
                checked         = state.driveBackupEnabled,
                onCheckedChange = { state.driveBackupEnabled = it },
                enabled         = !isSigningIn,
            )
        }

        Spacer(Modifier.weight(1f))

        if (isSigningIn) {
            CircularProgressIndicator(
                color    = AppColors.ThemeGreenPrimary,
                modifier = Modifier.size(36.dp),
            )
            Spacer(Modifier.size(AppSpacing.s5))
        } else {
            errorMessage?.let { msg ->
                Text(
                    text     = msg,
                    style    = AppTypography.Meta,
                    color    = AppColors.CoralDeep,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = AppSpacing.s5),
                )
                Spacer(Modifier.size(AppSpacing.s2))
            }

            OnboardingPrimaryButton(
                label   = "Sign in with Google",
                onClick = signInAction,
            )
            Spacer(Modifier.size(AppSpacing.s5))
        }
    }
}
