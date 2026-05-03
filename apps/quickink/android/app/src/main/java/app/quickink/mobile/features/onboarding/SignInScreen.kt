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
 *
 * The screen is also reused by `QuickInkRoot.ReSignInGate` for the
 * sign-out → re-sign-in flow (Option A).
 *
 * Mirror of iOS `SignInScreen.swift`.
 */

package app.quickink.mobile.features.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.quickink.mobile.features.auth.rememberQuickInkSignInAction
import app.quickink.mobile.ui.theme.LocalQuickInkColors
import app.quickink.mobile.ui.theme.LocalQuickInkTypography
import app.quickink.mobile.ui.theme.QuickInkRadius
import app.quickink.mobile.ui.theme.QuickInkSpacing
import app.quickink.mobile.ui.theme.quickInkDotGridBackground
import app.releaf.mobile.auth.AuthState
import app.releaf.mobile.auth.AuthStore

@Composable
fun SignInScreen(
    state: OnboardingState,
    authStore: AuthStore,
    onSignedIn: () -> Unit,
) {
    val signInAction = rememberQuickInkSignInAction(authStore)
    val authState by authStore.state.collectAsState()
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current

    // Whenever AuthStore flips to SignedIn while this screen is
    // visible, fire onSignedIn. Keyed on `authState` so the effect
    // re-runs on state flip.
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
            .quickInkDotGridBackground(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(320.dp),
            contentAlignment = Alignment.Center,
        ) {
            DriveIllustration()
        }

        Spacer(Modifier.size(QuickInkSpacing.s4))

        Text(
            text     = "Synced privately\nto your Drive",
            style    = type.onboardingTitle,
            color    = colors.ink,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = QuickInkSpacing.s5),
        )

        Spacer(Modifier.size(QuickInkSpacing.s3))

        Text(
            text     = "Your notebook follows you across devices. We never see your pages.",
            style    = type.body,
            color    = colors.inkSoft,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = QuickInkSpacing.s7),
        )

        Spacer(Modifier.size(QuickInkSpacing.s5))

        // Drive toggle — soft surface card row.
        Row(
            modifier = Modifier
                .padding(horizontal = QuickInkSpacing.s5)
                .fillMaxWidth()
                .clip(RoundedCornerShape(QuickInkRadius.md))
                .background(colors.surface)
                .padding(QuickInkSpacing.s4),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.padding(end = QuickInkSpacing.s3)) {
                Text(
                    text  = "Back up to Google Drive",
                    style = type.label,
                    color = colors.ink,
                )
                Text(
                    text  = "Recommended",
                    style = type.caption,
                    color = colors.muted,
                )
            }
            Switch(
                checked         = state.driveBackupEnabled,
                onCheckedChange = { state.driveBackupEnabled = it },
                enabled         = !isSigningIn,
                colors = SwitchDefaults.colors(
                    checkedThumbColor   = colors.textOnAccent,
                    checkedTrackColor   = colors.accent,
                    uncheckedThumbColor = colors.muted,
                    uncheckedTrackColor = colors.borderSoft,
                ),
            )
        }

        Spacer(Modifier.weight(1f))

        // Page-indicator dots — third one active.
        Row(
            horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2),
            verticalAlignment     = Alignment.CenterVertically,
            modifier              = Modifier.padding(bottom = QuickInkSpacing.s5),
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(colors.border)
            )
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(colors.border)
            )
            Box(
                modifier = Modifier
                    .width(24.dp)
                    .height(8.dp)
                    .clip(RoundedCornerShape(QuickInkRadius.pill))
                    .background(colors.accent)
            )
        }

        if (isSigningIn) {
            CircularProgressIndicator(
                color    = colors.accent,
                modifier = Modifier.size(36.dp),
            )
            Spacer(Modifier.size(QuickInkSpacing.s7))
        } else {
            errorMessage?.let { msg ->
                Text(
                    text     = msg,
                    style    = type.meta,
                    color    = colors.danger,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = QuickInkSpacing.s5),
                )
                Spacer(Modifier.size(QuickInkSpacing.s2))
            }

            // Coral rounded-rectangle CTA (mockup's `rounded-2xl
            // shadow-md`) — text-only per the latest design pass;
            // the leading account-circle glyph was dropped so the
            // button feels editorial rather than chrome-y. Label
            // uses the same serif family as the hero.
            Box(
                modifier = Modifier
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
                    .clickable(onClick = signInAction)
                    .padding(vertical = QuickInkSpacing.s4),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text  = "Continue with Google",
                    style = type.ctaSerif,
                    color = colors.textOnAccent,
                )
            }
            Spacer(Modifier.size(QuickInkSpacing.s7))
        }
    }
}
