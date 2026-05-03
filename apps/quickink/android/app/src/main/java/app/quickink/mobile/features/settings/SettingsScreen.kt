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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.SolidColor
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
import app.quickink.mobile.QUICKINK_APP_VERSION
import app.quickink.mobile.QuickInkApp
import app.quickink.mobile.data.sync.QuickInkSyncScheduler
import app.quickink.mobile.data.sync.QuickInkSyncWorker
import app.quickink.mobile.ui.theme.LocalQuickInkColors
import app.quickink.mobile.ui.theme.LocalQuickInkTypography
import app.quickink.mobile.ui.theme.QuickInkRadius
import app.quickink.mobile.ui.theme.QuickInkSpacing
import app.quickink.mobile.ui.theme.quickInkDotGridBackground
import app.releaf.mobile.auth.AuthState
import app.releaf.mobile.auth.AuthStore
import app.releaf.mobile.data.sync.SyncStateKeys
import androidx.work.WorkInfo
import androidx.work.WorkManager

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    authStore: AuthStore,
    onManageCategories: (() -> Unit)? = null,
    /// Pushed up to MainShell so the Home greeting reflects the
    /// edit immediately (without a SharedPreferences observer).
    /// Optional so this screen still renders standalone in previews.
    onCustomDisplayNameChange: ((String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val preferences = remember { SettingsPreferences(context) }
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current

    var driveBackupEnabled by remember { mutableStateOf(preferences.driveBackupEnabled) }
    var searchablePdfExportEnabled by remember { mutableStateOf(preferences.searchablePdfExportEnabled) }
    var customDisplayName by remember { mutableStateOf(preferences.customDisplayName) }

    // "Syncing now…" feedback driven by the actual worker state,
    // not a fixed timer. Observes the unique sync work via
    // WorkManager's per-name LiveData/Flow — when the worker is
    // ENQUEUED or RUNNING the badge shows; when it finishes
    // (SUCCEEDED / FAILED / CANCELLED) the badge clears and the
    // sync_state DAO Flow drives the "Last synced" line. The old
    // 2.5s `flashSyncing()` timer was a UX bug: WorkManager
    // typically takes 5–10s to actually start a constrained
    // OneTimeWork (mitigated now by `setExpedited` in
    // QuickInkSyncScheduler.requestUserSync), so the timer ended
    // before the worker even ran, and users saw "Last synced:
    // Never" reappear right after tapping Sync now.
    val workManager = remember(context) { WorkManager.getInstance(context) }
    val syncWorkInfos by remember(workManager) {
        workManager.getWorkInfosForUniqueWorkFlow(QuickInkSyncWorker.ONESHOT_WORK_NAME)
    }.collectAsState(initial = emptyList())
    val restoreWorkInfos by remember(workManager) {
        workManager.getWorkInfosForUniqueWorkFlow(
            app.quickink.mobile.data.sync.QuickInkRestoreWorker.ONESHOT_WORK_NAME
        )
    }.collectAsState(initial = emptyList())
    val isSyncingFlash: Boolean = (syncWorkInfos + restoreWorkInfos).any {
        it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING
    }

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

    // System status-bar inset + visual breathing room above the
    // top bar — same pattern as HomeScreen / NotesListScreen so the
    // "Settings" title clears the notch on edge-to-edge devices
    // (target SDK 35+).
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .quickInkDotGridBackground()
            .padding(top = statusBarTop + QuickInkSpacing.s4),
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
                // Display-name override row — what the Home greeting
                // shows. Empty value falls back to the Google
                // session's name (resolver lives in MainShell).
                // Bound directly to SharedPreferences via
                // `customDisplayName` setter, plus pushed up via the
                // optional callback so the parent's reactive copy
                // stays in sync.
                DisplayNameRow(
                    value = customDisplayName,
                    onValueChange = { value ->
                        customDisplayName = value
                        preferences.customDisplayName = value
                        onCustomDisplayNameChange?.invoke(value)
                    },
                )
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
                    timestampIso  = lastSyncRow?.value,
                    pendingCount  = pendingCount,
                    isSyncingFlash = isSyncingFlash,
                )
                Spacer(Modifier.size(QuickInkSpacing.s1))
                Row(horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s3)) {
                    SecondaryButton(
                        label    = "Sync now",
                        onClick  = {
                            if (isSignedIn) {
                                // requestUserSync (REPLACE) instead of
                                // requestImmediate (KEEP) so a tap
                                // cancels any pending retry from a
                                // previous failure and starts clean —
                                // KEEP was silently dropping the tap
                                // whenever a backoff retry was queued,
                                // leaving "Last synced" stuck on
                                // "Never". See QuickInkSyncScheduler
                                // for the rationale.
                                QuickInkSyncScheduler.requestUserSync(context)
                                // No fake timer needed — `isSyncingFlash`
                                // is driven by WorkInfo above and
                                // tracks the worker's real state.
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                    SecondaryButton(
                        label    = "Restore from Drive",
                        onClick  = {
                            // Distinct path from "Sync now" — kicks
                            // QuickInkRestoreWorker (pull-only) via
                            // its own unique work name + REPLACE
                            // policy so a fresh tap always wins.
                            if (isSignedIn) {
                                QuickInkSyncScheduler.requestRestore(context)
                                // No fake timer needed — `isSyncingFlash`
                                // is driven by WorkInfo above and
                                // tracks the worker's real state.
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
                DriveFolderRow(context = context)
            }

            if (onManageCategories != null) {
                Section(title = "Categories") {
                    ManageCategoriesRow(onClick = onManageCategories)
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

            Section(title = "About") {
                AboutRow()
            }
        }
    }
}

/**
 * Inline TextField for the user's preferred display name. Edits flow
 * up via [onValueChange] which writes to SharedPreferences AND pushes
 * the new value to MainShell so the Home greeting updates without a
 * preferences observer. Placeholder cues the fallback behaviour —
 * empty here means the Google session's name wins.
 */
@Composable
private fun DisplayNameRow(
    value: String,
    onValueChange: (String) -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current

    Column(verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s1)) {
        Text(text = "Display name", style = type.body, color = colors.ink)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(QuickInkRadius.md))
                .background(colors.borderSoft)
                .padding(horizontal = QuickInkSpacing.s3, vertical = QuickInkSpacing.s2),
        ) {
            BasicTextField(
                value         = value,
                onValueChange = onValueChange,
                textStyle     = type.body.copy(color = colors.ink),
                cursorBrush   = SolidColor(colors.accent),
                singleLine    = true,
                modifier      = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    if (value.isEmpty()) {
                        Text(
                            text  = "Use Google account name",
                            style = type.body,
                            color = colors.muted,
                        )
                    }
                    inner()
                },
            )
        }
        Text(
            text  = "Shown on the home screen. Leave blank to use your Google account name.",
            style = type.meta,
            color = colors.inkSoft,
        )
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
private fun LastSyncRow(
    timestampIso: String?,
    pendingCount: Int,
    isSyncingFlash: Boolean = false,
) {
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
        if (isSyncingFlash) {
            // Inline progress + label so the user sees something
            // happen the instant they tap Sync now / Restore.
            CircularProgressIndicator(
                color       = colors.accent,
                strokeWidth = 2.dp,
                modifier    = Modifier
                    .size(12.dp)
                    .padding(end = QuickInkSpacing.s2),
            )
            Text(
                text     = "Syncing now…",
                style    = type.meta,
                color    = colors.accent,
            )
        } else {
            if (pendingCount > 0) {
                Text(
                    text     = "$pendingCount pending",
                    style    = type.meta,
                    color    = colors.warning,
                    modifier = Modifier.padding(end = QuickInkSpacing.s2),
                )
            }
            Text(
                text      = relativeSyncTimestamp(timestampIso) ?: "Never",
                style     = type.meta,
                color     = colors.inkSoft,
                textAlign = TextAlign.End,
            )
        }
    }
}

/**
 * Drive folder link — opens the user's Drive in the system browser
 * at a search query for "QuickInk", which lands them on the per-app
 * folder created by `SyncRepository.ensureRootFolder`. Direct
 * deep-link to a specific folder ID would need the ID from the
 * manifest; until that round-trips through the UI, the search-based
 * link is a low-friction stand-in.
 */
@Composable
private fun DriveFolderRow(context: android.content.Context) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                val intent = android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(
                        "https://drive.google.com/drive/u/0/search?q=QuickInk"
                    )
                )
                context.startActivity(intent)
            }
            .padding(vertical = QuickInkSpacing.s2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text  = "Open Drive folder",
                style = type.body,
                color = colors.ink,
            )
            Text(
                text  = "Browse your scans + notes on Google Drive.",
                style = type.meta,
                color = colors.inkSoft,
            )
        }
        Icon(
            imageVector       = Icons.AutoMirrored.Filled.OpenInNew,
            contentDescription = null,
            tint              = colors.accent,
            modifier          = Modifier.size(16.dp),
        )
    }
}

/**
 * "About" section content — app version + a brief blurb. Pulls the
 * version from the same constant the sync worker stamps into the
 * Drive manifest, so the two stay aligned.
 */
@Composable
private fun AboutRow() {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    Column(verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s1)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text     = "App version",
                style    = type.body,
                color    = colors.ink,
                modifier = Modifier.weight(1f),
            )
            Text(
                text  = QUICKINK_APP_VERSION,
                style = type.meta,
                color = colors.inkSoft,
            )
        }
        Text(
            text  = "QuickInk by Releaf — scans go to your own Google Drive folder. Nothing leaves the device until you sign in and turn Drive backup on.",
            style = type.meta,
            color = colors.inkSoft,
        )
    }
}

/**
 * Mirror of HomeScreen's relative-time formatter: "moments ago" /
 * "5m ago" / "2h ago" / "yesterday" / "3d ago" / "Apr 28". Null /
 * unparsable returns null so the row falls back to "Never".
 */
private fun relativeSyncTimestamp(iso: String?): String? {
    if (iso.isNullOrBlank()) return null
    val instant = try {
        java.time.Instant.parse(iso)
    } catch (_: Exception) {
        return null
    }
    val seconds = java.time.Duration.between(instant, java.time.Instant.now())
        .seconds
        .coerceAtLeast(0L)
    return when {
        seconds < 60        -> "moments ago"
        seconds < 3600      -> "${seconds / 60}m ago"
        seconds < 86_400    -> "${seconds / 3600}h ago"
        seconds < 172_800   -> "yesterday"
        seconds < 604_800   -> "${seconds / 86_400}d ago"
        else                -> instant.atZone(java.time.ZoneId.systemDefault())
            .toLocalDate()
            .format(java.time.format.DateTimeFormatter.ofPattern("MMM d"))
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

/**
 * Row that pushes the Manage Categories screen. Mirrors iOS's
 * "Manage categories" disclosure row in [SettingsScreen.swift].
 */
@Composable
private fun ManageCategoriesRow(onClick: () -> Unit) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    Row(
        modifier = androidx.compose.ui.Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = QuickInkSpacing.s2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = androidx.compose.ui.Modifier.weight(1f)) {
            Text(text = "Manage categories", style = type.body, color = colors.ink)
            Spacer(androidx.compose.ui.Modifier.size(2.dp))
            Text(
                text  = "Add, rename, or remove the tags shown when you scan.",
                style = type.meta,
                color = colors.inkSoft,
            )
        }
        Icon(
            imageVector       = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint              = colors.muted,
        )
    }
}

