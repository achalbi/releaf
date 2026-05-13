/*
 * ProfileScreen.kt
 *
 * Profile editor reachable from the Home avatar's dropdown menu
 * (alongside "Sign out"). Editorial refresh — leads with the user's
 * punchline as headline, drops a 3-card stats row (Notes / Streak /
 * Tags), consolidates contact + identity fields into a single white
 * card, and pins live sync + last-scan timestamps to the footer so
 * the page does double-duty as a "where am I" status surface.
 *
 *   - Profile photo  — picked via the system photo picker
 *                      (`ActivityResultContracts.PickVisualMedia`),
 *                      copied into the app's filesDir as
 *                      `profile_photo.jpg`. The file:// URI is
 *                      persisted in `SettingsPreferences.profilePhotoUri`
 *                      so the home avatar can render it on next
 *                      launch.
 *   - Phone number   — free-form string (no E.164 normalization);
 *                      cosmetic field for the user's reference.
 *   - Punchline      — one-line "personality punchline" the user
 *                      writes for themselves. Surfaced twice: once
 *                      as the italic serif tagline under the name
 *                      (display only, falls back to email when
 *                      empty), and once as an editable row in the
 *                      consolidated list. Same single source of
 *                      truth (`SettingsPreferences.personalityPunchline`),
 *                      two postures.
 *
 * Stats and footer pull from live data:
 *
 *   - Notes  — count of active captures for the user.
 *   - Tags   — count of active categories for the user.
 *   - Streak — placeholder ("—"); a real streak needs a daily-active
 *              roll-up that doesn't yet exist (see TODO at call site).
 *   - Last synced — observed via `SyncStateKeys.LAST_FULL_SYNC_AT`.
 *   - Last scan   — `created_at` of the user's most recent capture.
 *
 * Mirror of iOS `ProfileScreen.swift`.
 */

package app.quickink.mobile.features.settings

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import app.quickink.mobile.QuickInkApp
import app.quickink.mobile.data.profile.ProfileSettingsEntity
import app.quickink.mobile.ui.theme.LocalQuickInkColors
import app.quickink.mobile.ui.theme.LocalQuickInkTypography
import app.quickink.mobile.ui.theme.QuickInkRadius
import app.quickink.mobile.ui.theme.QuickInkSpacing
import app.quickink.mobile.ui.theme.quickInkDotGridBackground
import app.releaf.mobile.auth.AuthState
import app.releaf.mobile.auth.AuthStore
import app.releaf.mobile.data.sync.SyncStateKeys
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

@Composable
fun ProfileScreen(
    onBack: () -> Unit,
    authStore: AuthStore,
    /// Optional: pushed up to MainShell so the Home avatar reflects
    /// a freshly-picked photo without a SharedPreferences observer.
    onProfilePhotoChange: ((String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val preferences = remember { SettingsPreferences(context) }
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current

    val authState by authStore.state.collectAsState()
    val session = (authState as? AuthState.SignedIn)?.session

    // Profile fields are now backed by the `profile_settings` Room
    // entity (synced via QuickInkSyncDataSource). The screen still
    // keeps the in-memory mutableStateOf to drive instant UI
    // feedback while the user types — the DAO write happens on
    // commit (focus loss / save) and the entity flow re-syncs on
    // the next composition. Prefs writes are kept side-by-side as
    // a one-release legacy fallback so a rollback to the previous
    // pref-only build doesn't lose user data.
    val app = context.applicationContext as QuickInkApp
    val profileSettingsDao = remember(app) { app.database.profileSettingsDao() }
    val userId: String = session?.userId.orEmpty()

    val profileRow by remember(userId, profileSettingsDao) {
        if (userId.isEmpty()) kotlinx.coroutines.flow.flowOf(null)
        else profileSettingsDao.observe(userId)
    }.collectAsState(initial = null)

    var phoneNumber by remember { mutableStateOf(preferences.phoneNumber) }
    var personalityPunchline by remember { mutableStateOf(preferences.personalityPunchline) }
    var profilePhotoUri by remember { mutableStateOf(preferences.profilePhotoUri) }

    // Bootstrap pass — first render after the v4 schema upgrade has
    // an empty `profile_settings` table. We seed it from whatever
    // the user already had in SharedPreferences and mark dirty so
    // the next sync pass pushes it up to Drive.
    androidx.compose.runtime.LaunchedEffect(userId, profileRow) {
        if (userId.isEmpty()) return@LaunchedEffect
        if (profileRow == null) {
            val now = java.time.OffsetDateTime.now().toString()
            val customDisplay = preferences.customDisplayName.takeIf { it.isNotBlank() }
            val phone         = preferences.phoneNumber.takeIf { it.isNotBlank() }
            val punch         = preferences.personalityPunchline.takeIf { it.isNotBlank() }
            val photoUri      = preferences.profilePhotoUri.takeIf { it.isNotBlank() }
            profileSettingsDao.upsertLocal(
                ProfileSettingsEntity(
                    id                   = userId,
                    userId               = userId,
                    displayName          = customDisplay,
                    phoneNumber          = phone,
                    personalityPunchline = punch,
                    photoLocalUri        = photoUri,
                    photoDriveFileId     = null,
                    photoUpdatedAt       = if (photoUri != null) now else null,
                    driveFileId          = null,
                    createdAt            = now,
                    updatedAt            = now,
                    dirty                = true,
                    deletedAt            = null,
                )
            )
        } else {
            // Reconcile in-memory state with whatever the DAO holds
            // — handles the new-device-restore path where the row
            // was downloaded via the sync layer and the screen needs
            // to reflect it. Empty-string normalisation matches
            // SharedPreferences's prior contract.
            phoneNumber          = profileRow!!.phoneNumber.orEmpty()
            personalityPunchline = profileRow!!.personalityPunchline.orEmpty()
            profilePhotoUri      = profileRow!!.photoLocalUri.orEmpty()
        }
    }

    val resolvedDisplayName: String = run {
        // Prefer the entity's display_name (synced); fall back to
        // legacy prefs (pre-migration) and then the session name.
        val entityName = profileRow?.displayName?.trim().orEmpty()
        val custom = if (entityName.isNotEmpty()) entityName
                     else preferences.customDisplayName.trim()
        if (custom.isNotEmpty()) custom
        else session?.displayName?.trim()?.takeIf { it.isNotEmpty() } ?: "QuickInk"
    }

    val coroutineScope = rememberCoroutineScope()

    // Live data — Notes / Tags counts and the user's last-capture
    // timestamp pulled straight from the Room DAOs the rest of the
    // app reads. `userId` (declared above with the profile bootstrap)
    // is empty when signed out (no session); the observers then
    // return empty lists and the stats render their empty state,
    // which is the right behaviour.
    val captureDao = remember(app) { app.database.captureDao() }
    val tagDao = remember(app) { app.database.tagDao() }
    val syncStateDao = remember(app) { app.database.syncStateDao() }

    val notesCount by remember(userId, captureDao) {
        captureDao.observeActive(userId).map { it.size }
    }.collectAsState(initial = 0)

    val tagsCount by remember(userId, tagDao) {
        tagDao.observeActive(userId).map { it.size }
    }.collectAsState(initial = 0)

    /// Most recent capture's `created_at` — proxy for "last scan" in
    /// QuickInk's vocabulary. Captures are emitted newest-first by
    /// `observeRecent`, so `firstOrNull()` is the latest.
    val lastScanIso by remember(userId, captureDao) {
        captureDao.observeRecent(userId, limit = 1).map { it.firstOrNull()?.createdAt }
    }.collectAsState(initial = null)

    val lastSyncRow by syncStateDao
        .observe(SyncStateKeys.LAST_FULL_SYNC_AT)
        .collectAsState(initial = null)
    val lastSyncIso = lastSyncRow?.value

    // Drives the source picker that pops up when the avatar is
    // tapped. The dialog has two options — Take photo (camera) and
    // Choose from gallery — each of which triggers its own
    // ActivityResult launcher below.
    var showSourceDialog by remember { mutableStateOf(false) }

    // Pending temp file that the camera launcher will write to. We
    // remember it across recompositions so the post-capture handler
    // can copy it into filesDir, then clear the state to release the
    // FileProvider grant. `null` while no capture is in flight.
    var pendingCameraFile by remember { mutableStateOf<File?>(null) }

    /// Shared post-pick application — runs on IO, then bounces back
    /// to the main thread to update state + preferences. Used by both
    /// the gallery launcher (URI input) and the camera launcher
    /// (File input that we wrap in a `file://` URI).
    fun applyPickedSource(src: Uri) {
        coroutineScope.launch {
            val savedUri = withContext(Dispatchers.IO) {
                ProfilePhotoStore.save(context, src)
            }
            if (savedUri != null) {
                profilePhotoUri = savedUri
                preferences.profilePhotoUri = savedUri
                // Mirror the change into the synced entity so the
                // photo flows up to Drive on the next sync pass and
                // restores onto a new device after sign-in. The
                // `setPhoto` DAO method also nulls `photo_drive_file_id`
                // so QuickInkBinarySync re-uploads the new bytes
                // rather than leaving the row pointing at the old
                // Drive blob.
                if (userId.isNotEmpty()) {
                    val now = java.time.OffsetDateTime.now().toString()
                    profileSettingsDao.setPhoto(userId, savedUri, now)
                }
                onProfilePhotoChange?.invoke(savedUri)
            }
        }
    }

    // System photo picker. PickVisualMedia returns a content:// URI;
    // we copy it into the app's filesDir so the URI keeps resolving
    // across launches and survives the OS revoking the gallery's
    // grant. Errors are swallowed — silent failure leaves the
    // existing avatar in place.
    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { pickedUri: Uri? ->
        if (pickedUri != null) applyPickedSource(pickedUri)
    }

    // Camera launcher. TakePicture returns a boolean — the bitmap
    // itself is already written by the camera app to the URI we
    // supplied. On success we read that file and feed it through the
    // same save pipeline as the gallery branch; on failure / user
    // cancel we just discard the (zero-byte) temp file.
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success: Boolean ->
        val file = pendingCameraFile
        pendingCameraFile = null
        if (success && file != null) {
            applyPickedSource(Uri.fromFile(file))
        } else {
            file?.delete()
        }
    }

    /// Allocate a fresh temp file under cacheDir/camera/, vend a
    /// FileProvider content:// URI for it, and launch the camera. The
    /// result lands in `cameraLauncher` above.
    fun launchCamera() {
        val cameraDir = File(context.cacheDir, "camera").apply { mkdirs() }
        val target = File(cameraDir, "capture_${System.currentTimeMillis()}.jpg")
        pendingCameraFile = target
        val authority = "${context.packageName}.fileprovider"
        val uri = FileProvider.getUriForFile(context, authority, target)
        cameraLauncher.launch(uri)
    }

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
            Text(text = "Profile", style = type.pageTitle, color = colors.ink)
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = QuickInkSpacing.s5, vertical = QuickInkSpacing.s4),
            verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s4),
        ) {
            IdentityBlock(
                photoUri           = profilePhotoUri,
                displayName        = resolvedDisplayName,
                displayNameInitial = resolvedDisplayName.firstOrNull()?.uppercase(),
                punchline          = personalityPunchline,
                onPick             = { showSourceDialog = true },
            )

            StatsRow(
                notesCount = notesCount,
                streak     = null, // TODO(streak): derive consecutive-days-with-captures.
                tagsCount  = tagsCount,
            )

            FieldsCard(
                email            = session?.email ?: "Not signed in",
                phoneNumber      = phoneNumber,
                onPhoneChange    = { value ->
                    phoneNumber = value
                    preferences.phoneNumber = value
                    if (userId.isNotEmpty()) {
                        val now = java.time.OffsetDateTime.now().toString()
                        coroutineScope.launch {
                            profileSettingsDao.setPhoneNumber(
                                id        = userId,
                                phone     = value.takeIf { it.isNotBlank() },
                                timestamp = now,
                            )
                        }
                    }
                },
                punchline        = personalityPunchline,
                onPunchlineChange = { value ->
                    personalityPunchline = value
                    preferences.personalityPunchline = value
                    if (userId.isNotEmpty()) {
                        val now = java.time.OffsetDateTime.now().toString()
                        coroutineScope.launch {
                            profileSettingsDao.setPersonalityPunchline(
                                id        = userId,
                                line      = value.takeIf { it.isNotBlank() },
                                timestamp = now,
                            )
                        }
                    }
                },
            )

            SyncFooter(
                lastSyncIso = lastSyncIso,
                lastScanIso = lastScanIso,
            )
        }
    }

    // Source-picker bottom sheet — opens on avatar tap, lets the
    // user pick between a fresh camera capture and an existing
    // gallery photo. Each option dismisses the sheet and triggers
    // its launcher; cancel / back-press / scrim tap just closes.
    if (showSourceDialog) {
        PhotoSourceSheet(
            onDismiss = { showSourceDialog = false },
            onTakePhoto = {
                showSourceDialog = false
                launchCamera()
            },
            onChooseFromGallery = {
                showSourceDialog = false
                photoPicker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            },
        )
    }
}

/**
 * Bottom-drawer chooser for the profile-photo source. Two big
 * tappable rows ("Take photo" + "Choose from gallery") plus a
 * trailing "Cancel" affordance. Hosted in Material3's
 * [ModalBottomSheet] so the user gets the standard Android
 * bottom-sheet chrome (drag handle, scrim, swipe-to-dismiss),
 * styled with QuickInk surface tokens so the cream/coral palette
 * reads through.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhotoSourceSheet(
    onDismiss: () -> Unit,
    onTakePhoto: () -> Unit,
    onChooseFromGallery: () -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = sheetState,
        containerColor   = colors.surface,
        contentColor     = colors.ink,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start  = QuickInkSpacing.s5,
                    end    = QuickInkSpacing.s5,
                    bottom = QuickInkSpacing.s5,
                ),
            verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2),
        ) {
            Text(
                text  = "Update profile photo",
                style = type.heading,
                color = colors.ink,
            )
            Spacer(Modifier.height(QuickInkSpacing.s1))
            PhotoSourceRow(
                icon  = Icons.Filled.PhotoCamera,
                label = "Take photo",
                onClick = onTakePhoto,
            )
            PhotoSourceRow(
                icon  = Icons.Filled.Image,
                label = "Choose from gallery",
                onClick = onChooseFromGallery,
            )
            Spacer(Modifier.height(QuickInkSpacing.s1))
            Text(
                text      = "Cancel",
                style     = type.label,
                color     = colors.muted,
                textAlign = TextAlign.Center,
                modifier  = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(QuickInkRadius.md))
                    .clickable(onClick = onDismiss)
                    .padding(vertical = QuickInkSpacing.s2),
            )
        }
    }
}

@Composable
private fun PhotoSourceRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(QuickInkRadius.md))
            .background(colors.borderSoft)
            .clickable(onClick = onClick)
            .padding(horizontal = QuickInkSpacing.s4, vertical = QuickInkSpacing.s3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s3),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(colors.accentSoft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector       = icon,
                contentDescription = null,
                tint              = colors.accent,
                modifier          = Modifier.size(18.dp),
            )
        }
        Text(
            text  = label,
            style = type.body,
            color = colors.ink,
            modifier = Modifier.weight(1f),
        )
    }
}

// ────────────────────────────────────────────────────────────────────
// Identity — avatar, name, punchline-as-headline, privacy pill
// ────────────────────────────────────────────────────────────────────

/**
 * Top identity block: avatar with edit affordance, name in serif, and
 * a rotating-punchline tagline underneath. When the user has saved
 * their own punchline, that wins; otherwise the slot cycles through
 * [PRESET_PUNCHLINES] every few seconds with a crossfade so the page
 * always has personality on a brand-new profile. A single "Local
 * only" pill grounds the privacy story of the page.
 */
@Composable
private fun IdentityBlock(
    photoUri: String,
    displayName: String,
    displayNameInitial: String?,
    punchline: String,
    onPick: () -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    val context = LocalContext.current

    // Rotating index into PRESET_PUNCHLINES. Seeded off the current
    // minute so two side-by-side launches don't show identical
    // taglines, then auto-advances every 15s while the screen is
    // composed AND the user hasn't saved their own punchline. The
    // effect is keyed on `hasCustom` so writing/clearing the custom
    // line restarts (or bails) the rotator immediately rather than
    // waiting for the next tick.
    val hasCustom = punchline.isNotBlank()
    var rotatingIndex by remember {
        mutableStateOf(
            ((System.currentTimeMillis() / 60_000L) % PRESET_PUNCHLINES.size).toInt()
        )
    }
    LaunchedEffect(hasCustom) {
        if (hasCustom) return@LaunchedEffect
        while (true) {
            delay(15_000L)
            rotatingIndex = (rotatingIndex + 1) % PRESET_PUNCHLINES.size
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2),
    ) {
        // Outer Box is intentionally NOT clipped so the camera badge
        // can sit beyond the avatar circle's silhouette. Only the
        // inner avatar surface is clipped to a circle — clipping the
        // outer container would cut the badge in half (the bug fixed
        // here).
        Box(
            modifier = Modifier.size(120.dp),
            contentAlignment = Alignment.Center,
        ) {
            // Inner 112dp avatar circle (clipped) — same surface as
            // before, just nested inside the un-clipped outer Box.
            Box(
                modifier = Modifier
                    .size(112.dp)
                    .clip(CircleShape)
                    .background(colors.accentSoft)
                    .border(1.dp, colors.border, CircleShape)
                    .clickable(onClick = onPick),
                contentAlignment = Alignment.Center,
            ) {
                if (photoUri.isNotEmpty()) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(Uri.parse(photoUri))
                            // Cache disabled because the user's photo
                            // is overwritten in-place at the same
                            // path — without this, a fresh pick would
                            // keep serving the stale bitmap. The
                            // avatar is small, so the perf cost is
                            // nil.
                            .memoryCachePolicy(CachePolicy.DISABLED)
                            .diskCachePolicy(CachePolicy.DISABLED)
                            .crossfade(true)
                            .build(),
                        contentDescription = "Profile photo",
                        contentScale       = ContentScale.Crop,
                        modifier           = Modifier.fillMaxSize().clip(CircleShape),
                    )
                } else if (displayNameInitial != null) {
                    Text(
                        text  = displayNameInitial,
                        style = type.display,
                        color = colors.accent,
                    )
                } else {
                    Icon(
                        imageVector       = Icons.Filled.AccountCircle,
                        contentDescription = null,
                        tint              = colors.accent,
                        modifier          = Modifier.size(72.dp),
                    )
                }
            }

            // Coral camera badge — sibling of the avatar circle in
            // the un-clipped outer Box. The 2dp canvas-coloured ring
            // punches it visually off the avatar so it reads as an
            // "edit" affordance. Tap target piggybacks on the
            // avatar's clickable; this badge is purely decorative.
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(colors.accent)
                    .border(2.dp, colors.bg, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector       = Icons.Filled.PhotoCamera,
                    contentDescription = null,
                    tint              = colors.textOnAccent,
                    modifier          = Modifier.size(16.dp),
                )
            }
        }

        Spacer(Modifier.height(QuickInkSpacing.s2))

        Text(
            text  = displayName,
            style = type.heading,
            color = colors.ink,
        )

        // Tagline slot — punchline (italic serif). User's own
        // punchline wins when set; otherwise cycle the preset list
        // with a soft crossfade so the page reads as a "mood" line
        // instead of a static empty state.
        val taglineText = if (punchline.isNotBlank()) {
            "“${punchline.trim()}”"
        } else {
            "“${PRESET_PUNCHLINES[rotatingIndex]}”"
        }
        Crossfade(
            targetState     = taglineText,
            animationSpec   = tween(durationMillis = 600),
            label           = "punchline",
        ) { line ->
            Text(
                text      = line,
                style     = type.bodyItalic,
                color     = colors.inkSoft,
                textAlign = TextAlign.Center,
                modifier  = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = QuickInkSpacing.s5),
            )
        }

        Spacer(Modifier.height(QuickInkSpacing.s1))

        // Local-only pill — the field helper text used to repeat
        // "saved on this device" twice; consolidating that promise
        // up here lets the field rows stay terse.
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(QuickInkRadius.pill))
                .background(colors.borderSoft)
                .padding(horizontal = QuickInkSpacing.s3, vertical = QuickInkSpacing.s1),
        ) {
            Text(
                text  = "Local only",
                style = type.caption,
                color = colors.inkSoft,
            )
        }
    }
}

/**
 * 30 stand-in punchlines that rotate in the tagline slot when the
 * user hasn't written their own. Editorial / writerly tone — leans
 * into QuickInk's ink + paper identity. Order matters: the first
 * entry is the same example shown in the empty-state placeholder so
 * a new user sees a familiar line on first paint.
 */
private val PRESET_PUNCHLINES: List<String> = listOf(
    "Curious by default, dangerous with a marker",
    "Half the ideas, twice the speed",
    "Margins-of-the-page energy",
    "Notes today, novels someday",
    "Future me, you owe present me",
    "Caffeinated and slightly italicized",
    "Reads dictionaries for fun, no apologies",
    "Built this thought in 3 cups of coffee",
    "Permanent draft, occasional masterpiece",
    "Underlined twice, still not sure",
    "Made of footnotes and good intentions",
    "More ink than free time",
    "Currently overthinking a sticky note",
    "Procrastinator with a five-year plan",
    "Lost the pen but found the point",
    "One bullet point away from genius",
    "Lives in the margins, dreams in serif",
    "Allergic to blank pages",
    "Notes it down, then forgets where",
    "Filed under: future-me's problem",
    "Reading between the lines I drew",
    "Half-finished thoughts, fully committed",
    "Stationery aficionado, deadline survivor",
    "Will scan it. Eventually. Probably.",
    "More post-its than personality",
    "Born to underline, forced to highlight",
    "Indexed but not organized",
    "Wrote it down so I could forget it safely",
    "Pen-first thinker, rules-second",
    "Drafting my way through the day",
)

// ────────────────────────────────────────────────────────────────────
// Stats — Notes / Streak / Tags
// ────────────────────────────────────────────────────────────────────

@Composable
private fun StatsRow(
    notesCount: Int,
    streak: Int?,
    tagsCount: Int,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2),
    ) {
        StatCard(label = "NOTES",  value = notesCount.toString(), modifier = Modifier.weight(1f))
        StatCard(label = "STREAK", value = streak?.toString(),    modifier = Modifier.weight(1f))
        StatCard(label = "TAGS",   value = tagsCount.toString(),  modifier = Modifier.weight(1f))
    }
}

/**
 * One stat tile in the 3-up row. `value = null` renders the muted
 * em-dash empty state — used for Streak until the daily-active roll-
 * up exists. A literal `"0"` would imply "we measured this and you
 * have none of it"; the dash says "we haven't computed it yet".
 */
@Composable
private fun StatCard(
    label: String,
    value: String?,
    modifier: Modifier = Modifier,
) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(QuickInkRadius.md))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(QuickInkRadius.md))
            .padding(vertical = QuickInkSpacing.s3, horizontal = QuickInkSpacing.s2),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s1),
    ) {
        Text(
            text  = value ?: "—",
            style = type.heading,
            color = if (value != null) colors.ink else colors.muted,
        )
        Text(
            text  = label,
            style = type.caption,
            color = colors.muted,
        )
    }
}

// ────────────────────────────────────────────────────────────────────
// Field list — Email (read-only), Phone, Punchline
// ────────────────────────────────────────────────────────────────────

/**
 * Single white card hosting the editable + read-only profile fields.
 * Each row carries its own divider; the last row drops it. Inputs
 * stay inline (no modal sheets) so the page is never more than one
 * tap from edit mode.
 */
@Composable
private fun FieldsCard(
    email: String,
    phoneNumber: String,
    onPhoneChange: (String) -> Unit,
    punchline: String,
    onPunchlineChange: (String) -> Unit,
) {
    val colors = LocalQuickInkColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(QuickInkRadius.md))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(QuickInkRadius.md))
            .padding(horizontal = QuickInkSpacing.s4),
    ) {
        ReadonlyRow(
            label = "Email",
            value = email,
            trailing = "Read-only",
            showDivider = true,
        )
        EditableRow(
            label       = "Phone",
            value       = phoneNumber,
            placeholder = "Add a phone number",
            keyboardType = KeyboardType.Phone,
            singleLine  = true,
            showDivider = true,
            onChange    = onPhoneChange,
        )
        EditableRow(
            label       = "Punchline",
            value       = punchline,
            placeholder = "e.g. \"Curious by default, dangerous with a marker\"",
            keyboardType = KeyboardType.Text,
            singleLine  = false,
            showDivider = false,
            onChange    = onPunchlineChange,
        )
    }
}

@Composable
private fun ReadonlyRow(
    label: String,
    value: String,
    trailing: String,
    showDivider: Boolean,
) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = QuickInkSpacing.s3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = type.caption, color = colors.muted)
            Text(text = value, style = type.body,    color = colors.ink)
        }
        Text(text = trailing, style = type.caption, color = colors.muted)
    }
    if (showDivider) RowDivider()
}

@Composable
private fun EditableRow(
    label: String,
    value: String,
    placeholder: String,
    keyboardType: KeyboardType,
    singleLine: Boolean,
    showDivider: Boolean,
    onChange: (String) -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = QuickInkSpacing.s3),
        verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s1),
    ) {
        Text(text = label, style = type.caption, color = colors.muted)
        BasicTextField(
            value           = value,
            onValueChange   = onChange,
            textStyle       = type.body.copy(color = colors.ink),
            cursorBrush     = SolidColor(colors.accent),
            singleLine      = singleLine,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            modifier        = Modifier.fillMaxWidth().let {
                if (singleLine) it else it.heightIn(min = 24.dp)
            },
            decorationBox = { inner ->
                if (value.isEmpty()) {
                    Text(text = placeholder, style = type.body, color = colors.muted)
                }
                inner()
            },
        )
    }
    if (showDivider) RowDivider()
}

@Composable
private fun RowDivider() {
    val colors = LocalQuickInkColors.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(colors.border),
    )
}

// ────────────────────────────────────────────────────────────────────
// Sync footer — last synced + last scan, with status dots
// ────────────────────────────────────────────────────────────────────

/**
 * Two-row status footer. Each row: small dot + label + relative
 * timestamp. The sync dot lights green when there's *any* sync
 * record (data has reached the cloud at least once); the scan dot
 * stays neutral — it's informational, not a freshness signal.
 */
@Composable
private fun SyncFooter(
    lastSyncIso: String?,
    lastScanIso: String?,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = QuickInkSpacing.s2),
        verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s1),
    ) {
        StatusRow(
            label       = "Last synced",
            value       = relativeTimestamp(lastSyncIso) ?: "Never",
            dotColor    = if (lastSyncIso != null) LocalQuickInkColors.current.success
                          else LocalQuickInkColors.current.muted,
        )
        StatusRow(
            label    = "Last scan",
            value    = relativeTimestamp(lastScanIso) ?: "No scans yet",
            dotColor = LocalQuickInkColors.current.muted,
        )
    }
}

@Composable
private fun StatusRow(
    label: String,
    value: String,
    dotColor: Color,
) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2),
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(dotColor),
        )
        Text(
            text     = label,
            style    = type.meta,
            color    = colors.muted,
            modifier = Modifier.weight(1f),
        )
        Text(
            text  = value,
            style = type.meta,
            color = colors.inkSoft,
        )
    }
}

/**
 * Mirror of `relativeSyncTimestamp` from HomeScreen / SettingsScreen
 * — duplicated locally rather than promoted to a util because the
 * three sites might want to diverge (different "Never" / threshold
 * choices) and a one-liner that copy-pastes is cheaper than the
 * indirection. If a fourth caller appears, lift this to
 * `mobile/util/RelativeTime.kt`.
 */
private fun relativeTimestamp(iso: String?): String? {
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

// ────────────────────────────────────────────────────────────────────
// Profile photo storage
// ────────────────────────────────────────────────────────────────────

/**
 * Tiny disk helper for the user's chosen profile photo. The picked
 * `content://` URI is opened, copied byte-for-byte into the app's
 * filesDir as `profile_photo.jpg`, and the resulting `file://` URI
 * is returned for persistence. Subsequent picks overwrite.
 */
internal object ProfilePhotoStore {

    private const val FILENAME = "profile_photo.jpg"

    /**
     * Copy [src] into the app's filesDir and return the local
     * `file://` URI string. Returns `null` on I/O failure.
     */
    fun save(context: Context, src: Uri): String? {
        return try {
            val target = File(context.filesDir, FILENAME)
            context.contentResolver.openInputStream(src)?.use { input ->
                FileOutputStream(target).use { output ->
                    input.copyTo(output)
                }
            } ?: return null
            Uri.fromFile(target).toString()
        } catch (_: Exception) {
            null
        }
    }
}
