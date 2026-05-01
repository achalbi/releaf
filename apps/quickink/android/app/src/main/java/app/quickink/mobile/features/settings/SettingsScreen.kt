/*
 * SettingsScreen.kt
 *
 * Slice 5 — two persisted toggles. Account row, theme override,
 * version info, etc. land in later slices alongside the auth
 * wiring + brand pass.
 *
 * Mirror of iOS `SettingsScreen.swift`.
 */

package app.quickink.mobile.features.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.quickink.mobile.QuickInkApp
import app.quickink.mobile.data.sync.QuickInkSyncScheduler
import app.releaf.mobile.auth.AuthState
import app.releaf.mobile.auth.AuthStore
import app.releaf.mobile.data.sync.SyncStateKeys
import app.releaf.mobile.ui.components.AppButton
import app.releaf.mobile.ui.components.AppButtonVariant
import app.releaf.mobile.ui.theme.AppAccent
import app.releaf.mobile.ui.theme.AppColors
import app.releaf.mobile.ui.theme.AppSpacing
import app.releaf.mobile.ui.theme.AppTypography

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    authStore: AuthStore,
) {
    val context = LocalContext.current
    val preferences = remember { SettingsPreferences(context) }

    var driveBackupEnabled by remember { mutableStateOf(preferences.driveBackupEnabled) }
    var searchablePdfExportEnabled by remember { mutableStateOf(preferences.searchablePdfExportEnabled) }
    val authState by authStore.state.collectAsState()

    // Slice 4.2b — read the last successful Drive sync timestamp
    // from the local sync_state table for the "Last synced" row.
    // `observe(...)` returns a Flow that emits null when the row
    // doesn't exist yet (fresh install before the first pass).
    val app = context.applicationContext as QuickInkApp
    val syncStateDao = remember(app) { app.database.syncStateDao() }
    val lastSyncRow by syncStateDao
        .observe(SyncStateKeys.LAST_FULL_SYNC_AT)
        .collectAsState(initial = null)
    // Slice 4.2d — pending-row count from the most recent pass.
    // Worker writes this key after each sync (see SyncRepository).
    // Surfaces as a "N pending" chip on the Last sync row when > 0.
    val pendingRow by syncStateDao
        .observe(SyncStateKeys.PENDING_COUNT)
        .collectAsState(initial = null)
    val pendingCount = pendingRow?.value?.toIntOrNull() ?: 0
    val isSignedIn = authState is AuthState.SignedIn

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColors.Canvas),
    ) {
        TopBar(onBack = onBack)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = AppSpacing.s5, vertical = AppSpacing.s4),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.s5),
        ) {
            Section(title = "Account") {
                AccountRow(
                    authState = authState,
                    onSignOut = { authStore.signOut() },
                )
            }

            Section(title = "Sync") {
                ToggleRow(
                    label = "Back up to Google Drive",
                    help  = "Scans and notes sync to Drive so they follow you across devices.",
                    checked = driveBackupEnabled,
                    onCheckedChange = { value ->
                        driveBackupEnabled = value
                        preferences.driveBackupEnabled = value
                        // No need to schedule/cancel the worker on
                        // toggle — the worker reads this preference
                        // per pass and no-ops when off (see
                        // QuickInkSyncWorker's header). Flipping to
                        // on opportunistically kicks an immediate
                        // pass so the user doesn't wait 15 minutes
                        // to see their first upload.
                        if (value && authState is AuthState.SignedIn) {
                            QuickInkSyncScheduler.requestImmediate(context)
                        }
                    },
                )
                // Last sync row — reads from sync_state via
                // SyncStateDao. Renders the raw ISO-8601 timestamp
                // for now; a "moments ago / 5m ago / yesterday at
                // 3:14pm" formatter can land later once the rest of
                // the surface gets a relative-time util. (Releaf's
                // DriveSettingsSection ships the same way today.)
                LastSyncRow(
                    timestampIso = lastSyncRow?.value,
                    enabled      = driveBackupEnabled,
                    pendingCount = pendingCount,
                )

                // Slice 4.2d — Sync now / Restore from Drive
                // controls. Mirror of Releaf's DriveSettingsSection
                // CTAs. Both fire requestImmediate; sync is
                // bidirectional, so a manual kick of the same
                // worker covers both push (sync now) and pull
                // (restore) — distinct labels just frame the
                // intent for the user. Taps on the signed-out
                // state no-op gracefully because the worker
                // short-circuits without a session.
                Spacer(Modifier.size(AppSpacing.s1))
                Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.s3)) {
                    AppButton(
                        text     = "Sync now",
                        onClick  = {
                            if (isSignedIn) QuickInkSyncScheduler.requestImmediate(context)
                        },
                        variant  = AppButtonVariant.Secondary,
                        modifier = Modifier.weight(1f),
                    )
                    AppButton(
                        text     = "Restore from Drive",
                        onClick  = {
                            if (isSignedIn) QuickInkSyncScheduler.requestImmediate(context)
                        },
                        variant  = AppButtonVariant.Secondary,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Section(title = "Experimental") {
                ToggleRow(
                    label = "Searchable PDF export",
                    help  = "Adds an invisible OCR text layer to exported PDFs so PDF readers can search and copy the text. Off by default while we tune the layout.",
                    checked = searchablePdfExportEnabled,
                    onCheckedChange = { value ->
                        searchablePdfExportEnabled = value
                        preferences.searchablePdfExportEnabled = value
                    },
                )
            }
        }
    }
}

/**
 * Account section content — shows the signed-in email + a Sign
 * out row when there's a session, otherwise a minimal "Not signed
 * in" indicator. Sign out flips `AuthStore` state to `SignedOut`,
 * which `QuickInkRoot`'s router observes and bounces to the
 * SignIn screen (Option A — see QuickInkRoot.ReSignInGate).
 */
@Composable
private fun AccountRow(
    authState: AuthState,
    onSignOut: () -> Unit,
) {
    val session = (authState as? AuthState.SignedIn)?.session

    Row(
        modifier = Modifier
            .fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.s1),
        ) {
            Text(
                text  = session?.displayName ?: "Signed in",
                style = AppTypography.Body,
                color = AppColors.TextPrimary,
            )
            Text(
                text  = session?.email ?: "Not signed in",
                style = AppTypography.Meta,
                color = AppColors.TextSecondary,
            )
        }
        if (session != null) {
            Row(
                modifier = Modifier
                    .clickable(onClick = onSignOut)
                    .padding(AppSpacing.s2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector  = Icons.AutoMirrored.Filled.Logout,
                    contentDescription = null,
                    tint         = AppColors.CoralDeep,
                    modifier     = Modifier.size(20.dp),
                )
                Text(
                    text     = "Sign out",
                    style    = AppTypography.Body,
                    color    = AppColors.CoralDeep,
                    modifier = Modifier.padding(start = AppSpacing.s1),
                )
            }
        }
    }
}

@Composable
private fun TopBar(onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.s2, vertical = AppSpacing.s2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector  = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint         = AppColors.TextPrimary,
            )
        }
        Text(
            text  = "Settings",
            style = AppTypography.PageTitle,
            color = AppColors.TextPrimary,
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.s2)) {
        Text(
            text  = title.uppercase(),
            style = AppTypography.Eyebrow,
            color = AppColors.TextSecondary,
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(AppRadiusMd))
                .background(AppColors.CardSolid)
                .padding(AppSpacing.s4),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.s2),
        ) {
            content()
        }
    }
}

@Composable
private fun ToggleRow(
    label: String,
    help: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.s1)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text  = label,
                style = AppTypography.Body,
                color = AppColors.TextPrimary,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
        Text(
            text  = help,
            style = AppTypography.Meta,
            color = AppColors.TextSecondary,
        )
    }
}

/**
 * "Last synced" row inside the Sync section. When [enabled] is
 * false (Drive backup toggle off), grays the value to communicate
 * that the timestamp is frozen — sync isn't running. When [timestampIso]
 * is null (fresh install, never synced), shows "Never". When
 * [pendingCount] > 0, surfaces a "N pending" chip — rows that
 * failed the most recent pass and will retry on the next tick.
 */
@Composable
private fun LastSyncRow(
    timestampIso: String?,
    enabled: Boolean,
    pendingCount: Int,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text  = "Last synced",
            style = AppTypography.Body,
            color = AppColors.TextPrimary,
            modifier = Modifier.weight(1f),
        )
        if (pendingCount > 0) {
            Text(
                text  = "$pendingCount pending",
                style = AppTypography.Meta,
                color = AppAccent.primary,
                modifier = Modifier.padding(end = AppSpacing.s2),
            )
        }
        Text(
            text  = timestampIso ?: "Never",
            style = AppTypography.Meta,
            color = AppColors.TextSecondary,
            textAlign = TextAlign.End,
        )
    }
}

/// Local 12dp constant — `AppRadius` is iOS-side; Android tokens
/// use direct dp values per the existing Releaf pattern. Promote
/// to a shared `AppRadius` Compose object if more screens need it.
private val AppRadiusMd = 12.dp
