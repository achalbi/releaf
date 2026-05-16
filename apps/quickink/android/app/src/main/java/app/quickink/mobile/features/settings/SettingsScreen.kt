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
import androidx.compose.foundation.layout.height
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
import app.quickink.mobile.data.sync.QuickInkRestoreWorker
import app.quickink.mobile.data.sync.QuickInkSyncScheduler
import app.quickink.mobile.data.sync.QuickInkSyncWorker
import app.quickink.mobile.features.auth.rememberQuickInkSignInAction
import app.quickink.mobile.features.nav.QuickInkBottomNavReservedHeight
import app.quickink.mobile.ui.theme.LocalQuickInkColors
import app.quickink.mobile.ui.theme.LocalQuickInkTypography
import app.quickink.mobile.ui.theme.QuickInkRadius
import app.quickink.mobile.ui.theme.QuickInkSpacing
import app.quickink.mobile.ui.theme.quickInkDotGridBackground
import app.releaf.mobile.auth.AuthState
import app.releaf.mobile.auth.AuthStore
import app.releaf.mobile.data.common.IsoClock
import app.releaf.mobile.data.sync.SyncErrorCodes
import app.releaf.mobile.data.sync.SyncStateEntity
import app.releaf.mobile.data.sync.SyncStateKeys
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    authStore: AuthStore,
    onManageCategories: (() -> Unit)? = null,
    /// Pushed up to MainShell so the Home greeting reflects the
    /// edit immediately (without a SharedPreferences observer).
    /// Optional so this screen still renders standalone in previews.
    onCustomDisplayNameChange: ((String) -> Unit)? = null,
    /// Appearance state, hoisted to MainActivity. The Settings UI
    /// renders the picker; mutations flow through the callbacks so
    /// QuickInkTheme at the activity level can recompose with the
    /// new accent / mode. Defaults preserve standalone-preview
    /// behaviour (no callback wiring needed at preview time).
    primaryColor: app.quickink.mobile.ui.theme.PrimaryColor =
        app.quickink.mobile.ui.theme.PrimaryColor.Coral,
    themeMode: app.quickink.mobile.ui.theme.ThemeMode =
        app.quickink.mobile.ui.theme.ThemeMode.System,
    onPrimaryColorChange: (app.quickink.mobile.ui.theme.PrimaryColor) -> Unit = {},
    onThemeModeChange: (app.quickink.mobile.ui.theme.ThemeMode) -> Unit = {},
) {
    val context = LocalContext.current
    val preferences = remember { SettingsPreferences(context) }
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current

    var driveBackupEnabled by remember { mutableStateOf(preferences.driveBackupEnabled) }
    var searchablePdfExportEnabled by remember { mutableStateOf(preferences.searchablePdfExportEnabled) }
    var locationForScansEnabled by remember { mutableStateOf(preferences.locationForScansEnabled) }
    var customDisplayName by remember { mutableStateOf(preferences.customDisplayName) }

    // "Syncing now…" feedback combines two signals:
    //
    //   1. `tapAckUntilMs` — set when the user taps Sync now /
    //      Restore. Bridges the brief queued-before-running window
    //      (~1–2s with `setExpedited`) so the user sees an ack
    //      immediately, even before WorkManager dispatches.
    //
    //   2. WorkInfo.State.RUNNING — only RUNNING shows the badge.
    //      ENQUEUED is intentionally ignored: a worker that
    //      `Result.retry()`s after a failure goes back to
    //      ENQUEUED during exponential backoff, and treating that
    //      as "syncing" left users staring at a spinner that
    //      never stopped (the regression that prompted this fix).
    //
    // When either signal is true, show the badge; when both go
    // quiet, the sync_state DAO Flow drives the "Last synced" line.
    val workManager = remember(context) { WorkManager.getInstance(context) }
    val syncWorkInfos by remember(workManager) {
        workManager.getWorkInfosForUniqueWorkFlow(QuickInkSyncWorker.ONESHOT_WORK_NAME)
    }.collectAsState(initial = emptyList())
    val restoreWorkInfos by remember(workManager) {
        workManager.getWorkInfosForUniqueWorkFlow(QuickInkRestoreWorker.ONESHOT_WORK_NAME)
    }.collectAsState(initial = emptyList())
    val isSyncWorkerRunning: Boolean = syncWorkInfos.any {
        it.state == WorkInfo.State.RUNNING
    }
    val runningSyncInfo = syncWorkInfos.firstOrNull {
        it.state == WorkInfo.State.RUNNING
    }
    val isRestoreWorkerRunning: Boolean = restoreWorkInfos.any {
        it.state == WorkInfo.State.RUNNING
    }
    val runningRestoreInfo = restoreWorkInfos.firstOrNull {
        it.state == WorkInfo.State.RUNNING
    }
    val isWorkerRunning: Boolean = isSyncWorkerRunning || isRestoreWorkerRunning
    // Tap-ack window: when the user taps Sync now we set this to
    // ~6s in the future; until that timestamp passes, the badge
    // stays on regardless of WorkInfo state. After the window
    // expires, we hand off to the WorkInfo-RUNNING signal. 6s is a
    // safe upper bound on `setExpedited` dispatch latency.
    var tapAckUntilMs by remember { mutableStateOf(0L) }
    var tapAckLabel by remember { mutableStateOf("Syncing now…") }
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    androidx.compose.runtime.LaunchedEffect(tapAckUntilMs) {
        // Only spin while the ack window is open — once it closes
        // we stop ticking and rely on Flow re-renders.
        while (System.currentTimeMillis() < tapAckUntilMs) {
            nowMs = System.currentTimeMillis()
            kotlinx.coroutines.delay(250L)
        }
        nowMs = System.currentTimeMillis()
    }
    val isTapAckActive = nowMs < tapAckUntilMs
    val isSyncingFlash: Boolean = isTapAckActive || isWorkerRunning
    val activeWorkLabel = when {
        isRestoreWorkerRunning -> "Restoring…"
        isSyncWorkerRunning    -> "Syncing now…"
        else                   -> tapAckLabel
    }
    val syncProgress: RestoreProgress? = runningSyncInfo?.let {
        RestoreProgress.fromSync(it)
    } ?: if (isTapAckActive && tapAckLabel == "Syncing now…") {
        RestoreProgress(
            title = "Backup queued",
            phase = QuickInkSyncWorker.SYNC_PROGRESS_PHASE_QUEUED,
            label = "Waiting to start backup…",
            percent = 0,
            logTitle = "Backup log",
            logs = listOf("Backup request queued."),
        )
    } else {
        null
    }
    val restoreProgress: RestoreProgress? = runningRestoreInfo?.let {
        RestoreProgress.fromRestore(it)
    } ?: if (isTapAckActive && tapAckLabel == "Restoring…") {
        RestoreProgress(
            title = "Restore queued",
            phase = QuickInkRestoreWorker.RESTORE_PROGRESS_PHASE_QUEUED,
            label = "Waiting to start restore…",
            percent = 0,
            logTitle = "Restore log",
            logs = listOf("Restore request queued."),
        )
    } else {
        null
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
    val errorCodeRow by syncStateDao
        .observe(SyncStateKeys.LAST_SYNC_ERROR_CODE)
        .collectAsState(initial = null)
    val lastSyncErrorCode = errorCodeRow?.value.orEmpty()
    val isSignedIn = authState is AuthState.SignedIn

    // Reconnect action for the AuthRejectedBanner. Reuses the same
    // sign-in plumbing the onboarding SignIn screen uses — when
    // invoked, runs CredentialManager identity + AuthorizationClient
    // authorize, surfacing the Drive consent sheet via the
    // ActivityResultLauncher held by `rememberQuickInkSignInAction`.
    // Hoisting it here keeps the launcher rooted on this screen so
    // a banner tap doesn't have to navigate elsewhere first.
    val reconnectAction = rememberQuickInkSignInAction(authStore)

    // Fire one silent token refresh when Settings opens, and another
    // every time the AUTH_REJECTED banner shows up. The on-resume
    // hook in QuickInkApp also runs; this is an extra nudge so a
    // user who has just opened Settings (often because they noticed
    // sync isn't working) gets a fresh attempt without having to
    // tap anything. `requestTokenRefresh` no-ops cleanly when the
    // cached token is still healthy, when there's no foreground
    // Activity, or when a refresh is already in flight.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        app.requestTokenRefresh()
    }
    androidx.compose.runtime.LaunchedEffect(lastSyncErrorCode) {
        if (lastSyncErrorCode == SyncErrorCodes.AUTH_REJECTED) {
            app.requestTokenRefresh()
        }
    }

    // Last restore outcome — surfaces a transient banner under the
    // Sync section's action buttons so the user sees what just
    // happened without staring at a logcat. Cleared on dismiss or
    // on the next restore tap. See `RestoreOutcome.parse` for the
    // wire format the worker writes.
    val restoreOutcomeRow by syncStateDao
        .observe(SyncStateKeys.LAST_RESTORE_OUTCOME)
        .collectAsState(initial = null)
    val restoreOutcome = remember(restoreOutcomeRow?.value) {
        RestoreOutcome.parse(restoreOutcomeRow?.value)
    }
    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()

    // System status-bar inset + visual breathing room above the
    // top bar — same pattern as HomeScreen / NotesListScreen so the
    // "Settings" title clears the notch on edge-to-edge devices
    // (target SDK 35+).
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    Box(modifier = Modifier.fillMaxSize().quickInkDotGridBackground()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
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
                .padding(
                    start  = QuickInkSpacing.s5,
                    end    = QuickInkSpacing.s5,
                    top    = QuickInkSpacing.s4,
                    // Reserve nav-bar height so the last setting row
                    // doesn't sit under the floating bar at scroll-end.
                    bottom = QuickInkBottomNavReservedHeight,
                ),
            verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s5),
        ) {
            Section(title = "Appearance") {
                ThemeModeRow(
                    selected = themeMode,
                    onChange = onThemeModeChange,
                )
                PrimaryColorRow(
                    selected = primaryColor,
                    onChange = onPrimaryColorChange,
                )
            }

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
                    activeLabel   = activeWorkLabel,
                )
                // Surface a "needs re-auth" banner when Drive has
                // been rejecting the token (401/403). The worker
                // can't refresh the token in the background on
                // Android (Credential Manager refresh requires an
                // Activity), so the user has to sign out + sign
                // back in — and at the consent sheet, make sure
                // the Drive checkbox is ticked. Banner clears
                // automatically once a sync succeeds.
                if (lastSyncErrorCode == SyncErrorCodes.AUTH_REJECTED && isSignedIn) {
                    AuthRejectedBanner(
                        onReconnect = reconnectAction,
                        onSignOut   = { authStore.signOut() },
                    )
                }
                Spacer(Modifier.size(QuickInkSpacing.s1))
                Row(horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s3)) {
                    SecondaryButton(
                        label    = "Sync now",
                        onClick  = {
                            if (isSignedIn) {
                                // requestUserSync (REPLACE) instead of
                                // requestImmediate (KEEP) so a tap
                                // cancels any pending retry from a
                                // previous failure and starts clean.
                                QuickInkSyncScheduler.requestUserSync(context)
                                // Open a 6s tap-ack window — covers
                                // the queued-before-running gap so
                                // the user sees immediate feedback
                                // even before WorkManager dispatches.
                                // After the window expires the badge
                                // is driven solely by WorkInfo.RUNNING,
                                // so a stuck-in-retry worker doesn't
                                // pin the spinner on forever.
                                tapAckLabel = "Syncing now…"
                                tapAckUntilMs = System.currentTimeMillis() + 6_000L
                            }
                        },
                        // Disable while a sync is in flight. Without
                        // this, a double-tap fires two REPLACE work
                        // requests in quick succession; the second
                        // cancels the warming worker started by the
                        // first, and we end up with two
                        // CancellationException-bearing failures and
                        // no progress. Stays disabled for the full
                        // tap-ack window AND while WorkInfo reports
                        // RUNNING — see [isSyncingFlash].
                        enabled  = isSignedIn && !isSyncingFlash,
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
                                coroutineScope.launch {
                                    runCatching {
                                        syncStateDao.upsert(
                                            SyncStateEntity(
                                                key       = SyncStateKeys.LAST_RESTORE_OUTCOME,
                                                value     = "",
                                                updatedAt = IsoClock.nowIso(),
                                            )
                                        )
                                    }
                                }
                                QuickInkSyncScheduler.requestRestore(context)
                                tapAckLabel = "Restoring…"
                                tapAckUntilMs = System.currentTimeMillis() + 6_000L
                            }
                        },
                        // Same debounce as Sync now. Restore uses a
                        // distinct unique-work-name so its own REPLACE
                        // calls don't cancel a running QuickInkSyncWorker,
                        // but the user can still nuke an in-flight
                        // restore with a double-tap on this button —
                        // gate that out for symmetry.
                        enabled  = isSignedIn && !isSyncingFlash,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (restoreProgress != null) {
                    DriveProgressBanner(progress = restoreProgress)
                } else if (syncProgress != null) {
                    DriveProgressBanner(progress = syncProgress)
                }
                // Restore-outcome banner — visible after a Restore
                // run completes. Surfaces "Restored 73 items, 11
                // orphan rows skipped, 0 failed" or similar. Auto-
                // dismiss is intentional only on the user's next
                // tap (Sync / Restore overwrites the sync_state
                // value via the worker), or on the explicit Dismiss
                // button.
                if (restoreOutcome != null) {
                    RestoreOutcomeBanner(
                        outcome   = restoreOutcome,
                        onDismiss = {
                            coroutineScope.launch {
                                runCatching {
                                    syncStateDao.upsert(
                                        SyncStateEntity(
                                            key       = SyncStateKeys.LAST_RESTORE_OUTCOME,
                                            value     = "",
                                            updatedAt = IsoClock.nowIso(),
                                        )
                                    )
                                }
                            }
                        },
                    )
                }
                DriveFolderRow(context = context)
            }

            Section(title = "Location") {
                // Master switch for the scan + import flows'
                // geolocation attach. When off, captures save with
                // NULL latitude / locality / sub_locality columns —
                // the Details card simply omits the Area + City
                // rows. Independent of the system permission grant
                // (revoking that in OS Settings shuts the feature
                // off regardless of the toggle here).
                ToggleRow(
                    label   = "Attach location to scans",
                    help    = "Each scan records the city and area it was taken in so you can find scans by place.",
                    checked = locationForScansEnabled,
                    onCheckedChange = { value ->
                        locationForScansEnabled = value
                        preferences.locationForScansEnabled = value
                    },
                )
            }

            if (onManageCategories != null) {
                Section(title = "Tags") {
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

/**
 * Snapshot of the most recent [QuickInkRestoreWorker] run. Parsed
 * from the pipe-separated string the worker writes to
 * `sync_state[LAST_RESTORE_OUTCOME]`. See the worker's
 * `writeRestoreOutcome` for the wire format.
 */
private data class RestoreOutcome(
    val downloaded: Int,
    val applyFailed: Int,
    val orphanFound: Int,
    val orphanCleaned: Int,
    val completedAt: String,
    val status: String,
) {
    companion object {
        /**
         * Parse the worker-written string. Returns null when the
         * value is null/blank (no restore has run yet, or the user
         * dismissed the banner — which writes back an empty value).
         * Defensive: unrecognised keys are ignored, missing keys
         * default to 0/empty.
         */
        fun parse(raw: String?): RestoreOutcome? {
            if (raw.isNullOrBlank()) return null
            val parts: Map<String, String> = raw.split('|').mapNotNull { segment ->
                val eq = segment.indexOf('=')
                if (eq <= 0) null
                else segment.substring(0, eq) to segment.substring(eq + 1)
            }.toMap()
            val status = parts["status"].orEmpty()
            if (status.isEmpty()) return null
            return RestoreOutcome(
                downloaded    = parts["downloaded"]?.toIntOrNull() ?: 0,
                applyFailed   = parts["applyFailed"]?.toIntOrNull() ?: 0,
                orphanFound   = parts["orphanFound"]?.toIntOrNull() ?: 0,
                orphanCleaned = parts["orphanCleaned"]?.toIntOrNull() ?: 0,
                completedAt   = parts["completedAt"].orEmpty(),
                status        = status,
            )
        }
    }
}

private data class RestoreProgress(
    val title: String,
    val phase: String,
    val label: String,
    val percent: Int,
    val logTitle: String,
    val logs: List<String> = emptyList(),
) {
    val fraction: Float get() = percent.coerceIn(0, 100) / 100f

    companion object {
        fun fromRestore(info: WorkInfo): RestoreProgress {
            val progress = info.progress
            val phase = progress.getString(QuickInkRestoreWorker.RESTORE_PROGRESS_PHASE_KEY)
                ?: QuickInkRestoreWorker.RESTORE_PROGRESS_PHASE_PREPARING
            val label = progress.getString(QuickInkRestoreWorker.RESTORE_PROGRESS_LABEL_KEY)
                ?: "Preparing Drive restore…"
            val percent = progress.getInt(
                QuickInkRestoreWorker.RESTORE_PROGRESS_PERCENT_KEY,
                3,
            )
            val logs = progress
                .getString(QuickInkRestoreWorker.RESTORE_PROGRESS_LOG_KEY)
                ?.lineSequence()
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.toList()
                .orEmpty()
            return RestoreProgress(
                title = restoreTitle(phase),
                phase = phase,
                label = label,
                percent = percent,
                logTitle = "Restore log",
                logs = logs,
            )
        }

        fun fromSync(info: WorkInfo): RestoreProgress {
            val progress = info.progress
            val phase = progress.getString(QuickInkSyncWorker.SYNC_PROGRESS_PHASE_KEY)
                ?: QuickInkSyncWorker.SYNC_PROGRESS_PHASE_PREPARING
            val label = progress.getString(QuickInkSyncWorker.SYNC_PROGRESS_LABEL_KEY)
                ?: "Preparing Drive backup…"
            val percent = progress.getInt(
                QuickInkSyncWorker.SYNC_PROGRESS_PERCENT_KEY,
                3,
            )
            val logs = progress
                .getString(QuickInkSyncWorker.SYNC_PROGRESS_LOG_KEY)
                ?.lineSequence()
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.toList()
                .orEmpty()
            return RestoreProgress(
                title = syncTitle(phase),
                phase = phase,
                label = label,
                percent = percent,
                logTitle = "Backup log",
                logs = logs,
            )
        }

        private fun restoreTitle(phase: String): String = when (phase) {
            QuickInkRestoreWorker.RESTORE_PROGRESS_PHASE_QUEUED    -> "Restore queued"
            QuickInkRestoreWorker.RESTORE_PROGRESS_PHASE_METADATA  -> "Restoring records"
            QuickInkRestoreWorker.RESTORE_PROGRESS_PHASE_BINARIES  -> "Downloading files"
            QuickInkRestoreWorker.RESTORE_PROGRESS_PHASE_CLEANUP   -> "Checking Drive"
            QuickInkRestoreWorker.RESTORE_PROGRESS_PHASE_FINISHING -> "Finishing restore"
            QuickInkRestoreWorker.RESTORE_PROGRESS_PHASE_DONE      -> "Restore complete"
            else                                                   -> "Preparing restore"
        }

        private fun syncTitle(phase: String): String = when (phase) {
            QuickInkSyncWorker.SYNC_PROGRESS_PHASE_QUEUED    -> "Backup queued"
            QuickInkSyncWorker.SYNC_PROGRESS_PHASE_BINARIES  -> "Uploading files"
            QuickInkSyncWorker.SYNC_PROGRESS_PHASE_METADATA  -> "Updating Drive"
            QuickInkSyncWorker.SYNC_PROGRESS_PHASE_DONE      -> "Backup complete"
            else                                             -> "Preparing backup"
        }
    }
}

@Composable
private fun DriveProgressBanner(progress: RestoreProgress) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    var expanded by remember { mutableStateOf(false) }
    val percent = progress.percent.coerceIn(0, 100)
    val barFraction = progress.fraction.coerceIn(0.02f, 1f)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(QuickInkRadius.md))
            .background(colors.accentSoft.copy(alpha = 0.35f))
            .border(1.dp, colors.accent.copy(alpha = 0.35f), RoundedCornerShape(QuickInkRadius.md))
            .padding(QuickInkSpacing.s4),
        verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text     = progress.title,
                style    = type.body,
                color    = colors.ink,
                modifier = Modifier.weight(1f),
            )
            Text(
                text  = "$percent%",
                style = type.meta,
                color = colors.accentDeep,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(QuickInkRadius.pill))
                .background(colors.borderSoft),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(barFraction)
                    .height(6.dp)
                    .clip(RoundedCornerShape(QuickInkRadius.pill))
                    .background(colors.accent),
            )
        }
        Text(text = progress.label, style = type.meta, color = colors.inkSoft)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(QuickInkRadius.md))
                .clickable { expanded = !expanded }
                .padding(vertical = QuickInkSpacing.s2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text     = if (expanded) "Hide log" else progress.logTitle,
                style    = type.label,
                color    = colors.accentDeep,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector        = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint               = colors.accentDeep,
                modifier           = Modifier.size(18.dp),
            )
        }
        if (expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(QuickInkRadius.md))
                    .background(colors.surface.copy(alpha = 0.72f))
                    .border(1.dp, colors.border, RoundedCornerShape(QuickInkRadius.md))
                    .padding(QuickInkSpacing.s3),
                verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s1),
            ) {
                val lines = progress.logs.ifEmpty { listOf(progress.label) }
                for (line in lines) {
                    Text(text = "- $line", style = type.meta, color = colors.inkSoft)
                }
            }
        }
    }
}

/**
 * Transient banner that surfaces the result of the most recent
 * [QuickInkRestoreWorker] run — number of items restored, orphan
 * rows skipped + tombstoned on Drive, and any apply failures.
 * Sits below the Sync now / Restore from Drive buttons in the Sync
 * section. Auto-dismissed when a fresh restore starts (the worker
 * overwrites the value); the user can also tap Dismiss to clear it.
 *
 * Status drives the visual treatment:
 *   - "ok"              → neutral surface (info tone), summary line.
 *   - "failed"          → warning surface, "Restore failed" headline.
 *   - "version_blocked" → warning surface, "App needs update" headline.
 */
@Composable
private fun RestoreOutcomeBanner(
    outcome: RestoreOutcome,
    onDismiss: () -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current

    val tint    = if (outcome.status == "ok") colors.success else colors.warning
    val border  = tint.copy(alpha = 0.55f)
    val bg      = tint.copy(alpha = 0.12f)

    val title: String = when (outcome.status) {
        "failed"          -> "Restore failed"
        "version_blocked" -> "Restore blocked — app update required"
        else              -> "Restore complete"
    }

    val body: String = when (outcome.status) {
        "ok" -> buildString {
            append("Restored ")
            append(outcome.downloaded)
            append(" scan/import item")
            if (outcome.downloaded != 1) append('s')
            append(" from Drive.")
            if (outcome.applyFailed > 0) {
                append(' ')
                append(outcome.applyFailed)
                append(" failed to apply (see logcat for detail).")
            }
            if (outcome.orphanCleaned > 0) {
                append(" Cleaned up ")
                append(outcome.orphanCleaned)
                append(" stale orphan record")
                if (outcome.orphanCleaned != 1) append('s')
                append(" on Drive.")
            } else if (outcome.orphanFound > 0) {
                append(' ')
                append(outcome.orphanFound)
                append(" orphan record")
                if (outcome.orphanFound != 1) append('s')
                append(" detected on Drive but couldn't be cleaned (see logcat).")
            }
        }
        "failed" ->
            "Drive rejected the restore request. Check your internet connection " +
                "or sign out and sign back in if this persists."
        "version_blocked" ->
            "Your Drive backup was written by a newer app version. Update " +
                "QuickInk to the latest release before restoring."
        else -> "" // Shouldn't happen — parse() returns null on unknown status.
    }

    Column(
        modifier = androidx.compose.ui.Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(QuickInkRadius.md))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(QuickInkRadius.md))
            .padding(QuickInkSpacing.s4),
        verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2),
    ) {
        Text(text = title, style = type.body, color = colors.ink)
        if (body.isNotBlank()) {
            Text(text = body, style = type.meta, color = colors.inkSoft)
        }
        SecondaryButton(
            label    = "Dismiss",
            onClick  = onDismiss,
            modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
        )
    }
}

/**
 * "Drive needs re-authentication" banner shown when the worker
 * has hit a persistent Drive auth rejection (401 / 403). Most
 * common cause: the user's access token was revoked server-side,
 * or the Drive scope wasn't granted at sign-in. The worker can't
 * refresh in the background (Credential Manager refresh needs an
 * Activity), so the user has to sign out and sign back in —
 * making sure the Drive checkbox is ticked on the consent sheet.
 *
 * Banner clears automatically once a sync succeeds (the worker's
 * SUCCESS path writes an empty string to LAST_SYNC_ERROR_CODE).
 */
@Composable
private fun AuthRejectedBanner(
    onReconnect: () -> Unit,
    onSignOut: () -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    Column(
        modifier = androidx.compose.ui.Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(QuickInkRadius.md))
            .background(colors.warning.copy(alpha = 0.12f))
            .border(
                1.dp,
                colors.warning.copy(alpha = 0.55f),
                RoundedCornerShape(QuickInkRadius.md),
            )
            .padding(QuickInkSpacing.s4),
        verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2),
    ) {
        Text(
            text  = "Drive sync needs re-authentication",
            style = type.body,
            color = colors.ink,
        )
        Text(
            text  = "Google needs to renew your Drive permission. " +
                    "Tap Reconnect — when prompted, make sure to grant access " +
                    "to Google Drive on the consent screen. Use Sign out only " +
                    "if Reconnect keeps failing.",
            style = type.meta,
            color = colors.inkSoft,
        )
        // Reconnect is the primary CTA. It runs the same
        // CredentialManager + AuthorizationClient flow the
        // onboarding SignIn button uses — which, unlike
        // RealGoogleAuthClient.refresh(), gracefully surfaces a
        // consent sheet when Google says hasResolution=true instead
        // of throwing "Drive scope no longer granted". For the
        // common "token aged out / device-side grant lost" case the
        // consent sheet is enough to restore the session in-place.
        PrimaryBannerButton(
            label    = "Reconnect",
            onClick  = onReconnect,
            modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
        )
        SecondaryButton(
            label    = "Sign out",
            onClick  = onSignOut,
            modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Local accent-filled CTA for the AuthRejectedBanner's Reconnect
 * action. Kept inline rather than promoted to a shared button so
 * the banner stays self-contained — the rest of Settings only
 * needs [SecondaryButton]. Same pill shape and vertical padding as
 * SecondaryButton, just accent fill + on-accent label colour.
 */
@Composable
private fun PrimaryBannerButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(QuickInkRadius.pill))
            .background(colors.accent)
            .clickable(onClick = onClick)
            .padding(vertical = QuickInkSpacing.s3),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text  = label,
            style = type.label,
            color = colors.textOnAccent,
        )
    }
}

@Composable
private fun LastSyncRow(
    timestampIso: String?,
    pendingCount: Int,
    isSyncingFlash: Boolean = false,
    activeLabel: String = "Syncing now…",
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
                text     = activeLabel,
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
private fun SecondaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * When false, the button stops accepting taps and dims its
     * label to muted to signal the inactive state. Used by the
     * Sync now / Restore from Drive call sites in the Sync section
     * to debounce while a worker run is in flight — without this,
     * a double-tap fires two `requestUserSync(REPLACE)` calls,
     * which cancels the in-flight worker mid-execution.
     */
    enabled: Boolean = true,
) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(QuickInkRadius.pill))
            .background(colors.borderSoft)
            .border(1.dp, colors.border, RoundedCornerShape(QuickInkRadius.pill))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = QuickInkSpacing.s3),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text  = label,
            style = type.label,
            color = if (enabled) colors.ink else colors.muted,
        )
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


// ──────────────────────────────────────────────────────────────────────
// Appearance — theme mode + primary color pickers
// ──────────────────────────────────────────────────────────────────────

/**
 * Three-segment toggle for the user's theme override (System / Light /
 * Dark). The active segment paints `accent` over `accentSoft`; inactive
 * segments stay transparent on the section's white card.
 */
@Composable
private fun ThemeModeRow(
    selected: app.quickink.mobile.ui.theme.ThemeMode,
    onChange: (app.quickink.mobile.ui.theme.ThemeMode) -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    Column(verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2)) {
        Text(text = "Theme", style = type.label, color = colors.ink)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(QuickInkRadius.pill))
                .background(colors.borderSoft)
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            app.quickink.mobile.ui.theme.ThemeMode.values().forEach { mode ->
                val active = mode == selected
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(QuickInkRadius.pill))
                        .background(if (active) colors.accent else androidx.compose.ui.graphics.Color.Transparent)
                        .clickable { onChange(mode) }
                        .padding(vertical = QuickInkSpacing.s2),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text  = mode.displayName,
                        style = type.label,
                        color = if (active) colors.textOnAccent else colors.ink,
                    )
                }
            }
        }
    }
}

/**
 * Row of swatch circles, one per [PrimaryColor]. The picked swatch
 * gets a coloured ring + checkmark; the others are flat discs.
 *
 * Each swatch displays the family's DEEP variant — that's the
 * variant that lights up in light mode (where most users are), so
 * the picker preview matches what you'll see on Home / FAB / CTAs.
 */
@Composable
private fun PrimaryColorRow(
    selected: app.quickink.mobile.ui.theme.PrimaryColor,
    onChange: (app.quickink.mobile.ui.theme.PrimaryColor) -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    Column(verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = "Primary color", style = type.label, color = colors.ink, modifier = Modifier.weight(1f))
            Text(text = selected.displayName, style = type.meta, color = colors.inkSoft)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s3)) {
            app.quickink.mobile.ui.theme.PrimaryColor.values().forEach { hue ->
                val active = hue == selected
                // Empty content lambda — the swatch has no inner glyph,
                // but Compose's `Box(modifier, contentAlignment)`
                // overload requires a content slot. The empty `{}`
                // lets the compiler resolve to that overload.
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(hue.deep)
                        .border(
                            width = if (active) 3.dp else 1.dp,
                            color = if (active) hue.deep else colors.border,
                            shape = androidx.compose.foundation.shape.CircleShape,
                        )
                        .clickable { onChange(hue) },
                    contentAlignment = Alignment.Center,
                ) {}
            }
        }
    }
}
