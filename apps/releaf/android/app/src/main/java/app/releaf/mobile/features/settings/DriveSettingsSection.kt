/*
 * DriveSettingsSection.kt
 *
 * Settings card for Google Drive sync state + manual controls. Renders:
 *
 *   • Connection state (signed in? to which email?)
 *   • Last successful sync timestamp (from `sync_state` table via
 *     [SyncStateDao]).
 *   • Pending-count badge — rows that haven't landed on Drive yet
 *     (failed + still-dirty at last pass).
 *   • "Sync now" button — enqueues a one-shot [SyncWorker] via
 *     [SyncScheduler].
 *   • "Restore from Drive" — enqueues a sync pass whose download arm
 *     will reconcile any remote-only entities locally. The sync is
 *     bidirectional; a fresh install with no local rows will pull
 *     everything on its first pass.
 */

package app.releaf.mobile.features.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.releaf.mobile.ReleafApp
import app.releaf.mobile.auth.AuthState
import app.releaf.mobile.data.sync.SyncScheduler
import app.releaf.mobile.data.sync.SyncStateKeys
import app.releaf.mobile.ui.components.AppButton
import app.releaf.mobile.ui.components.AppButtonVariant
import app.releaf.mobile.ui.theme.AppAccent
import app.releaf.mobile.ui.theme.AppColors
import app.releaf.mobile.ui.theme.AppRadius
import app.releaf.mobile.ui.theme.AppSpacing
import app.releaf.mobile.ui.theme.AppTypography

@Composable
fun DriveSettingsSection(
    modifier: Modifier = Modifier,
) {
    val context   = LocalContext.current
    val app       = context.applicationContext as ReleafApp
    val authState by app.authStore.state.collectAsState()
    val syncDao   = remember(app) { app.database.syncStateDao() }
    val lastSync  by syncDao.observe(SyncStateKeys.LAST_FULL_SYNC_AT).collectAsState(initial = null)
    val pending   by syncDao.observe(SyncStateKeys.PENDING_COUNT).collectAsState(initial = null)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.md))
            .background(AppColors.CardSolid)
            .border(1.dp, AppColors.BorderDefault, RoundedCornerShape(AppRadius.md))
            .padding(AppSpacing.s4),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.s3),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.s1)) {
            Text(
                text  = "DRIVE",
                style = AppTypography.Eyebrow,
                color = AppColors.TextSecondary,
            )
            Text(
                text  = "Google Drive sync",
                style = AppTypography.SectionTitle,
                color = AppColors.TextPrimary,
            )
            Text(
                text  = "Your notebooks live in your Drive under a " +
                        "`Releaf/` folder. Releaf can only see files it creates.",
                style = AppTypography.Meta,
                color = AppColors.TextSecondary,
            )
        }

        // ---- Connection row ----
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text  = "Connection",
                    style = AppTypography.Meta,
                    color = AppColors.TextSecondary,
                )
                Text(
                    text  = when (val s = authState) {
                        is AuthState.SignedIn -> s.session.email
                        AuthState.SigningIn   -> "Signing in…"
                        is AuthState.Failed   -> "Sign-in failed"
                        AuthState.SignedOut   -> "Not connected"
                    },
                    style = AppTypography.Body,
                    color = AppColors.TextPrimary,
                )
            }
        }

        // ---- Last sync row ----
        Row {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text  = "Last sync",
                    style = AppTypography.Meta,
                    color = AppColors.TextSecondary,
                )
                Text(
                    text  = lastSync?.value ?: "—",
                    style = AppTypography.Body,
                    color = AppColors.TextPrimary,
                )
            }
            val pendingCount = pending?.value?.toIntOrNull() ?: 0
            if (pendingCount > 0) {
                Text(
                    text  = "$pendingCount pending",
                    style = AppTypography.Meta,
                    color = AppAccent.primary,
                )
            }
        }

        Spacer(Modifier.height(AppSpacing.s1))

        // AppButton has no `enabled` flag; taps on the signed-out
        // state no-op because the SyncWorker short-circuits without a
        // session. Keeping the buttons visually enabled simplifies the
        // UI and matches the "always-available action" pattern on the
        // rest of Settings.
        val isSignedIn = authState is AuthState.SignedIn
        Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.s3)) {
            AppButton(
                text    = "Sync now",
                onClick = {
                    if (isSignedIn) SyncScheduler.requestImmediate(context)
                },
                variant = AppButtonVariant.Secondary,
                modifier = Modifier.weight(1f),
            )
            AppButton(
                text    = "Restore from Drive",
                onClick = {
                    if (isSignedIn) SyncScheduler.requestImmediate(context)
                },
                variant = AppButtonVariant.Secondary,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
