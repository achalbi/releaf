/*
 * SettingsScreen.kt
 *
 * QuickInk Settings — Account / Sync / Experimental sections,
 * styled with the QuickInk warm/coral palette via
 * `LocalQuickInkColors` + `LocalQuickInkTypography`. Functional
 * shape (Drive toggle, Last sync row, Sync now / Restore from
 * Drive controls, Searchable PDF toggle) matches the Releaf
 * `DriveSettingsSection` pattern; visual style is the editorial
 * QuickInk look.
 *
 * Mirror of iOS `SettingsScreen.swift`.
 */

package app.quickink.mobile.features.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import app.quickink.mobile.ui.theme.LocalQuickInkColors
import app.quickink.mobile.ui.theme.LocalQuickInkTypography
import app.quickink.mobile.ui.theme.QuickInkRadius
import app.quickink.mobile.ui.theme.QuickInkSpacing
import app.releaf.mobile.auth.AuthState
import app.releaf.mobile.auth.AuthStore
import app.releaf.mobile.data.sync.SyncStateKeys

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    authStore: AuthStore,
) {
    val context = LocalContext.current
    val preferences = remember { SettingsPreferences(context) }
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current

    var driveBackupEnabled by remember { mutableStateOf(preferences.driveBackupEnabled) }
    var searchablePdfExportEnabled by remember { mutableStateOf(preferences.searchablePdfExportEnabled) }
    val authState by authStore.state.collectAsState()

    val app = context.applicationContext as QuickInkApp
    val syncStateDao = remember(app) { app.database.syncStateDao() }
    val lastSyncRow by syncStateDao
        .observe(SyncStateKeys.LAST_FULL_SYNC_AT)
        .collectAsState(initial = null)
    val pendingRow by syncStateDao
        .observe(SyncStateKeys.PENDING_COUNT)
        .collectAsState(initial = null)
    val pendingCount = pendingRow?.value?.toIntOrNull() ?: 0
    val isSignedIn = authState is AuthState.SignedIn

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg),
    ) {
        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = QuickInkSpacing.s2, vertical = QuickInkSpacing.s2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector       = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint              = colors.ink,
                )
            }
            Text(text = "Settings", style = type.pageTitle, color = colors.ink)
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = QuickInkSpacing.s5, vertical = QuickInkSpacing.s4),
            verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s5),
        ) {
            Section(title = "Account") {
                AccountRow(authState = authState, onSignOut = { authStore.signOut() })
            }

            Section(title = "Sync") {
                ToggleRow(
                    label   = "Back up to Google Drive",
                    help    = "Scans and notes sync to Drive so they follow you across devices.",
                    checked = driveBackupEnabled,
                    onCheckedChange = { value ->
                        driveBackupEnabled = value
                        preferences.driveBackupEnabled = value
                        if (value && authState is AuthState.SignedIn) {
                            QuickInkSyncScheduler.requestImmediate(context)
                        }
                    },
                )
                LastSyncRow(
                    timestampIso = lastSyncRow?.value,
                    pendingCount = pendingCount,
                )
                Spacer(Modifier.size(QuickInkSpacing.s1))
                Row(horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s3)) {
                    SecondaryButton(
                        label    = "Sync now",
                        onClick  = {
                            if (isSignedIn) QuickInkSyncScheduler.requestImmediate(context)
                        },
                        modifier = Modifier.weight(1f),
                    )
                    SecondaryButton(
                        label    = "Restore from Drive",
                        onClick  = {
                            if (isSignedIn) QuickInkSyncScheduler.requestImmediate(context)
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Section(title = "Experimental") {
                ToggleRow(
                    label   = "Searchable PDF export",
                    help    = "Adds an invisible OCR text layer to exported PDFs so PDF readers can search and copy the text. Off by default while we tune the layout.",
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

@Composable
private fun AccountRow(authState: AuthState, onSignOut: () -> Unit) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    val session = (authState as? AuthState.SignedIn)?.session

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s1),
        ) {
            Text(
                text  = session?.displayName ?: "Signed in",
                style = type.body,
                color = colors.ink,
            )
            Text(
                text  = session?.email ?: "Not signed in",
                style = type.meta,
                color = colors.inkSoft,
            )
        }
        if (session != null) {
            Row(
                modifier = Modifier
                    .clickable(onClick = onSignOut)
                    .padding(QuickInkSpacing.s2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector       = Icons.AutoMirrored.Filled.Logout,
                    contentDescription = null,
                    tint              = colors.accentDeep,
                    modifier          = Modifier.size(20.dp),
                )
                Text(
                    text     = "Sign out",
                    style    = type.body,
                    color    = colors.accentDeep,
                    modifier = Modifier.padding(start = QuickInkSpacing.s1),
                )
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current

    Column(verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2)) {
        Text(
            text  = title.uppercase(),
            style = type.eyebrow,
            color = colors.muted,
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(QuickInkRadius.md))
                .background(colors.surface)
                .border(1.dp, colors.border, RoundedCornerShape(QuickInkRadius.md))
                .padding(QuickInkSpacing.s4),
            verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2),
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
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    Column(verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s1)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text     = label,
                style    = type.body,
                color    = colors.ink,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked         = checked,
                onCheckedChange = onCheckedChange,
                colors          = SwitchDefaults.colors(
                    checkedThumbColor   = colors.textOnAccent,
                    checkedTrackColor   = colors.accent,
                    uncheckedThumbColor = colors.muted,
                    uncheckedTrackColor = colors.borderSoft,
                ),
            )
        }
        Text(text = help, style = type.meta, color = colors.inkSoft)
    }
}

@Composable
private fun LastSyncRow(timestampIso: String?, pendingCount: Int) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text     = "Last synced",
            style    = type.body,
            color    = colors.ink,
            modifier = Modifier.weight(1f),
        )
        if (pendingCount > 0) {
            Text(
                text     = "$pendingCount pending",
                style    = type.meta,
                color    = colors.warning,
                modifier = Modifier.padding(end = QuickInkSpacing.s2),
            )
        }
        Text(
            text      = timestampIso ?: "Never",
            style     = type.meta,
            color     = colors.inkSoft,
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun SecondaryButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(QuickInkRadius.pill))
            .background(colors.borderSoft)
            .border(1.dp, colors.border, RoundedCornerShape(QuickInkRadius.pill))
            .clickable(onClick = onClick)
            .padding(vertical = QuickInkSpacing.s3),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, style = type.label, color = colors.ink)
    }
}
