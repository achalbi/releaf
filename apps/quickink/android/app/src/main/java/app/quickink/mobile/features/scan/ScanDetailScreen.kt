/*
 * ScanDetailScreen.kt
 *
 * Full-bleed viewer for a single capture row. The first-page
 * preview JPEG is the hero; recognised text loads lazily and
 * shows beneath only when the user expands "Show extracted text".
 * Multi-page PDF rendering is a follow-up — for the MVP we lean on
 * the preview JPEG since it's the page the user actually sees in
 * the home rail.
 *
 * Mirror of iOS `ScanDetailScreen.swift`.
 */

package app.quickink.mobile.features.scan

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.ContactsContract
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.LocalOffer
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.VideoLibrary
import app.quickink.mobile.features.scan.businesscard.AddContactReviewSheet
import app.quickink.mobile.features.scan.businesscard.launchAddContactIntent
import app.quickink.mobile.features.scan.businesscard.runBusinessCardExtraction
import app.releaf.shared.scan.businesscard.ExtractedContact
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.quickink.mobile.QuickInkApp
import app.quickink.mobile.data.capture.CaptureEntity
import app.quickink.mobile.data.capture.CaptureRepository
import app.quickink.mobile.data.sync.QuickInkBinarySync
import app.quickink.mobile.features.nav.QuickInkBottomNavReservedHeight
import app.quickink.mobile.features.nav.QuickInkTimeBar
import app.quickink.mobile.ui.theme.LocalQuickInkColors
import app.quickink.mobile.ui.theme.LocalQuickInkTypography
import app.quickink.mobile.ui.theme.QuickInkRadius
import app.quickink.mobile.ui.theme.QuickInkSpacing
import androidx.core.content.FileProvider
import app.releaf.mobile.auth.AuthState
import app.quickink.mobile.features.workspace.FolderPickerSheet
import app.quickink.mobile.features.workspace.LocationPickerSheet
import app.quickink.mobile.features.workspace.PeoplePickerSheet
import app.quickink.mobile.features.workspace.PersonEditorDialog
import app.quickink.mobile.features.workspace.TagPickerSheet
import app.releaf.mobile.data.common.IsoClock
import app.releaf.mobile.data.sync.DeviceIdentity
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun ScanDetailScreen(
    captureId: String,
    userId: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as QuickInkApp
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    val scope = rememberCoroutineScope()

    val captureDao = remember(app) { app.database.captureDao() }
    val ocrDao = remember(app) { app.database.ocrResultDao() }
    val tagDao = remember(app) { app.database.tagDao() }
    val captureTagDao = remember(app) { app.database.captureTagDao() }
    val folderDao = remember(app) { app.database.folderDao() }

    // Workspace v1 folder picker — opens when the Actions card's
    // "Move to folder" row is tapped. Sheet observes the folder list
    // and writes via [CaptureDao.setFolder] on pick.
    var showFolderPicker by remember(captureId) { mutableStateOf(false) }

    // Workspace v1 tag picker — Manage tags row opens it. Manual
    // entry only in Phase C.2; AI-suggested chips ship in Phase E.
    var showTagPicker by remember(captureId) { mutableStateOf(false) }
    // Locations picker — Manage locations row in the more-menu opens
    // it. Mirrors the tag-picker contract; commits diffs against
    // `capture_locations` on Save.
    var showLocationPicker by remember(captureId) { mutableStateOf(false) }
    // People picker — Manage people row in the more-menu opens it.
    // Same contract, commits diffs against `capture_people` on Save.
    var showPeoplePicker by remember(captureId) { mutableStateOf(false) }
    val folders by remember(userId, folderDao) {
        folderDao.observeActive(userId)
    }.collectAsState(initial = emptyList())

    var capture by remember(captureId) { mutableStateOf<CaptureEntity?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    // Drives the retag bottom sheet. Tapping the category pill (or
    // the "Tag scan" affordance for an untagged capture) sets this
    // to true; the sheet's options call into [retagCapture].
    var showRetagSheet by remember { mutableStateOf(false) }
    // Title editor modal — opens when the user taps the title row.
    // `titleDraft` is the in-flight string; persisted via setTitle on
    // Save and discarded on Cancel.
    var showTitleEditor by remember { mutableStateOf(false) }
    var titleDraft by remember { mutableStateOf("") }
    // Notes editor — full-screen sheet on tap of the Notes card.
    // Save persists via [CaptureRepository.setNotes]; Cancel drops
    // the draft. The same column is appended-to by the voice-note
    // transcript editor, so notes accumulate from both surfaces.
    var showNotesEditor by remember { mutableStateOf(false) }
    var notesDraft by remember { mutableStateOf("") }
    // Fullscreen viewer toggle. Set true by the fullscreen button on
    // the inline preview; cleared by the dialog's close affordance or
    // the back press.
    var showFullscreenViewer by remember(captureId) { mutableStateOf(false) }
    // Set to a non-null URI by the "Play" CTA on the video card.
    // Cleared by the dialog's dismiss affordance. Only ever non-null
    // for hold-to-record Photo-mode captures (video_uri set).
    var videoPlayerUri by remember(captureId) { mutableStateOf<String?>(null) }
    // More-actions dropdown anchored to the ellipsis chip beside the
    // fullscreen chip on the preview. Holds the actions that used to
    // live in the inline Actions card.
    var moreMenuExpanded by remember(captureId) { mutableStateOf(false) }
    // Selected page index for the thumbnails strip (0-based). Visual-
    // only highlight today; tap-to-jump is a follow-up that requires
    // surfacing a `currentPage` state through PageTurnPdfView.
    var selectedPageIndex by remember(captureId) { mutableStateOf(0) }
    // Rendered page bitmaps for the thumbnails strip. Loaded once,
    // off the main thread, so the chips show actual page content
    // instead of placeholder document icons. Empty until the
    // background render lands. Mirror of iOS `pageImages` state.
    var pageBitmaps by remember(captureId) { mutableStateOf<List<Bitmap>>(emptyList()) }
    // On-disk size of the capture's PDF in bytes, loaded lazily so
    // the Details row can render "2.4 MB" etc. Null until resolved.
    var pdfFileSize by remember(captureId) { mutableStateOf<Long?>(null) }
    // Extracted contact for the in-flight Business Card review
    // sheet. Set on tap of "Add to contact"; the sheet observes it
    // through the non-null check, and clearing it dismisses.
    var businessCardExtraction by remember(captureId) { mutableStateOf<ExtractedContact?>(null) }
    // True while the Share-as-Image action is rasterising pages to
    // JPEGs. Drives the row's label ("Preparing…") and the disabled
    // tap-state so a double-tap doesn't queue a second render.
    var isPreparingImageShare by remember(captureId) { mutableStateOf(false) }
    // In-memory rasterised pages handed to the WhatsApp-style image
    // editor before the share sheet opens. Non-null = editor is up.
    var pendingEditorPages by remember(captureId) { mutableStateOf<List<Bitmap>?>(null) }

    // Live category list — populated from the same DAO the home
    // grid + review screen read, scoped to the current user. The
    // sheet uses this for its picker rows.
    val categories by remember(userId, tagDao) {
        tagDao.observeActive(userId)
    }.collectAsState(initial = emptyList())

    // Live tag-id list for the in-view capture, so the legacy
    // single-label badge + the Business Card mode switch can pick a
    // primary tag name (the earliest-attached active tag). Replaces
    // the pre-A.3c `captures.category` read.
    val attachedTagIds by remember(captureId, captureTagDao) {
        captureTagDao.observeTagIdsForCapture(captureId)
    }.collectAsState(initial = emptyList())
    val primaryTagName: String? = remember(attachedTagIds, categories) {
        val byId = categories.associateBy { it.id }
        attachedTagIds.firstNotNullOfOrNull { byId[it]?.name }
    }

    // Live attached-locations names — joined from the user's active
    // location list. Drives the read-only chip strip on the Details
    // card; the picker writes the underlying join rows.
    val captureLocationDao = remember(app) { app.database.captureLocationDao() }
    val locationDao        = remember(app) { app.database.locationDao() }
    val allLocations by remember(userId, locationDao) {
        locationDao.observeActive(userId)
    }.collectAsState(initial = emptyList())
    val attachedLocationIds by remember(captureId, captureLocationDao) {
        captureLocationDao.observeLocationIdsForCapture(captureId)
    }.collectAsState(initial = emptyList())
    val attachedLocationNames: List<String> = remember(attachedLocationIds, allLocations) {
        val byId = allLocations.associateBy { it.id }
        attachedLocationIds.mapNotNull { byId[it]?.name }
    }

    // Same shape for people — observe the join + the active people
    // list, derive the attached entities for the inline chip strip.
    val capturePersonDao = remember(app) { app.database.capturePersonDao() }
    val personDao        = remember(app) { app.database.personDao() }
    val allPeople by remember(userId, personDao) {
        personDao.observeActive(userId)
    }.collectAsState(initial = emptyList())
    val attachedPersonIds by remember(captureId, capturePersonDao) {
        capturePersonDao.observePersonIdsForCapture(captureId)
    }.collectAsState(initial = emptyList())
    val attachedPeople: List<app.quickink.mobile.data.person.PersonEntity> =
        remember(attachedPersonIds, allPeople) {
            val byId = allPeople.associateBy { it.id }
            attachedPersonIds.mapNotNull { byId[it] }
        }

    // Per-person action sheet — opened by tapping a chip on the
    // Details card. Offers Share (system share sheet with pre-fill)
    // and Edit (opens the person editor).
    var personActionTarget by remember(captureId) {
        mutableStateOf<app.quickink.mobile.data.person.PersonEntity?>(null)
    }
    var personEditorExisting by remember(captureId) {
        mutableStateOf<app.quickink.mobile.data.person.PersonEntity?>(null)
    }
    var personEditorOpen by remember(captureId) { mutableStateOf(false) }
    val captureRepository = remember(app) {
        CaptureRepository(
            captureDao    = captureDao,
            ocrResultDao  = app.database.ocrResultDao(),
            tagDao        = tagDao,
            captureTagDao = captureTagDao,
        )
    }

    LaunchedEffect(captureId) {
        capture = captureDao.findById(captureId)
    }

    // Workspace v1 — Continue card signal. Writes `last_opened_*`
    // after the user lingers on a page for 500ms so a quick skim
    // through pages doesn't churn the row. Page is 1-indexed in the
    // DB; selectedPageIndex is 0-based. Device install id comes from
    // the shared DeviceIdentity so a future cross-device "continue
    // on iPhone" can attribute the row to the producing device.
    LaunchedEffect(captureId, selectedPageIndex) {
        kotlinx.coroutines.delay(500L)
        captureDao.setLastOpened(
            id       = captureId,
            openedAt = IsoClock.nowIso(),
            page     = selectedPageIndex + 1,
            deviceId = DeviceIdentity.get(context),
        )
    }

    // Backfill the reverse-geocoded place name on captures whose
    // coordinates landed without a locality / sub-locality at scan
    // time (rate-limited Geocoder, offline, or a remote area the
    // system couldn't resolve). Re-runs only when the in-screen
    // capture row changes (new open / after a sync refresh) so we
    // don't loop on a missing-data row.
    LaunchedEffect(capture?.id, capture?.locality, capture?.subLocality, capture?.address) {
        val cap = capture ?: return@LaunchedEffect
        android.util.Log.i(
            "QuickInkLocation",
            "retry: row state lat=${cap.latitude} lon=${cap.longitude} locality=${cap.locality} subLocality=${cap.subLocality} address=${cap.address}",
        )
        val lat = cap.latitude
        val lon = cap.longitude
        if (lat == null || lon == null) {
            android.util.Log.i("QuickInkLocation", "retry: no coordinates, nothing to backfill")
            return@LaunchedEffect
        }
        val hasLocality    = !cap.locality.isNullOrBlank()
        val hasSubLocality = !cap.subLocality.isNullOrBlank()
        val hasAddress     = !cap.address.isNullOrBlank()
        if (hasLocality && hasSubLocality && hasAddress) {
            android.util.Log.i("QuickInkLocation", "retry: already have locality + subLocality + address, skip")
            return@LaunchedEffect
        }

        val resolved = withContext(Dispatchers.IO) {
            runCatching {
                LocationService.reverseGeocodeFull(context, lat, lon)
            }.getOrNull()
        }
        if (resolved == null) {
            android.util.Log.i("QuickInkLocation", "retry: geocode failed")
            return@LaunchedEffect
        }
        android.util.Log.i(
            "QuickInkLocation",
            "retry: placemark raw locality=${resolved.locality} subLocality=${resolved.subLocality} address=${resolved.address}",
        )

        // Same dedupe as the write path in LocationService — drop
        // the sub-locality when it duplicates the locality so the
        // backfilled row doesn't recreate the "Area = City" UX
        // problem.
        val (newLocality, newSubLocality) = LocationService.dedupePlaceNames(
            locality    = resolved.locality,
            subLocality = resolved.subLocality,
        )
        val newAddress = resolved.address
        android.util.Log.i(
            "QuickInkLocation",
            "retry: dedupe -> locality=$newLocality subLocality=$newSubLocality address=$newAddress",
        )
        if (newLocality.isNullOrBlank() && newSubLocality.isNullOrBlank() && newAddress.isNullOrBlank()) {
            android.util.Log.i("QuickInkLocation", "retry: nothing useful to persist, skip")
            return@LaunchedEffect
        }

        runCatching {
            captureDao.setLocation(
                id          = captureId,
                locality    = newLocality ?: cap.locality,
                subLocality = newSubLocality ?: cap.subLocality,
                address     = newAddress ?: cap.address,
                timestamp   = IsoClock.nowIso(),
            )
            capture = captureDao.findById(captureId)
            android.util.Log.i("QuickInkLocation", "retry: persisted update for capture=$captureId")
        }.onFailure {
            android.util.Log.i("QuickInkLocation", "retry: persist failed ${it.message}")
        }
    }

    // Resolve the on-disk PDF size after the capture row lands.
    // Best-effort — leaves `pdfFileSize = null` if the file isn't
    // readable, in which case the Details row falls back to "—".
    LaunchedEffect(capture?.pdfUri) {
        pdfFileSize = withContext(Dispatchers.IO) {
            resolvePdfFileSize(capture?.pdfUri)
        }
    }

    // Rasterise PDF pages for the thumbnails strip once the capture
    // resolves. Off the main thread (PdfRenderer can be slow on
    // large files); skipped for single-page captures since the strip
    // doesn't render in that case. Empty list = "show placeholder
    // icons" until the bitmaps land.
    LaunchedEffect(capture?.pdfUri, capture?.pageCount) {
        val pdfUriString = capture?.pdfUri
        val pageCount    = capture?.pageCount ?: 0
        if (pdfUriString.isNullOrBlank() || pageCount <= 1 ||
            !localFileExists(pdfUriString)) {
            pageBitmaps = emptyList()
            return@LaunchedEffect
        }
        val rendered = withContext(Dispatchers.IO) {
            runCatching {
                renderPdfPages(context, Uri.parse(pdfUriString))
            }.getOrDefault(emptyList())
        }
        pageBitmaps = rendered
    }

    // Self-heal: if the capture row references a local file that
    // doesn't resolve here (typical after a fresh-device sync —
    // the row carries the source device's `pdf_uri`, which is a
    // path on that device's filesystem) AND we have a Drive file
    // id to fall back on, eagerly download the binary so the
    // preview renders. Without this the user sees
    // "open failed: ENOENT" and has no way to recover except
    // waiting for the next periodic sync's `restorePending` pass.
    //
    // Keyed on the capture's id + drive-id pair so the effect
    // re-runs only when those identities change (not on every
    // pdf_uri rewrite the heal itself triggers).
    LaunchedEffect(capture?.id, capture?.pdfDriveFileId, capture?.previewDriveFileId) {
        val row = capture ?: return@LaunchedEffect
        val authState = app.authStore.state.value
        val accessToken = (authState as? AuthState.SignedIn)?.session?.accessToken
            ?: return@LaunchedEffect

        val needPdf = row.pdfDriveFileId != null &&
            !localFileExists(row.pdfUri)
        val needPreview = row.previewDriveFileId != null &&
            (row.previewUri.isNullOrBlank() || !localFileExists(row.previewUri))

        if (!needPdf && !needPreview) return@LaunchedEffect

        // Run on IO so HTTP + disk write don't block recomposition.
        // Repository writes go through the existing DAO setters that
        // QuickInkBinarySync already uses, so the row is reactively
        // re-read here on the next captureDao.findById() refresh.
        withContext(Dispatchers.IO) {
            runCatching {
                val binarySync = QuickInkBinarySync(
                    context            = context,
                    captureDao         = captureDao,
                    profileSettingsDao = app.database.profileSettingsDao(),
                    voiceNoteDao       = app.database.voiceNoteDao(),
                    storyVoiceClipDao  = app.database.storyVoiceClipDao(),
                    driveClient        = app.driveClient,
                )
                binarySync.restorePending(row.userId, accessToken)
            }
        }
        // Refresh the local copy so the UI sees the rewritten URIs.
        capture = captureDao.findById(captureId)
    }

    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg),
    ) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = statusBarTop + 4.dp),
    ) {

        val scrollState = rememberScrollState()

        // Reuse the global `QuickInkTimeBar`, but auto-hide it as
        // soon as the user starts scrolling so the preview chrome
        // doesn't crowd the page; reappears when the scroll returns
        // to the very top. The global bar in `QuickInkRoot` is
        // suppressed on this route, so this is the only time-chip
        // surface on the scan-detail screen.
        androidx.compose.animation.AnimatedVisibility(
            visible = scrollState.value == 0,
            enter   = androidx.compose.animation.fadeIn() +
                androidx.compose.animation.expandVertically(),
            exit    = androidx.compose.animation.fadeOut() +
                androidx.compose.animation.shrinkVertically(),
        ) {
            QuickInkTimeBar()
        }

        if (showDeleteConfirm) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirm = false },
                title            = { Text("Delete this scan?", style = type.body, color = colors.ink) },
                text             = { Text(
                    text  = "The scan and its recognised text will be removed from this device and your other devices on the next sync.",
                    style = type.meta,
                    color = colors.inkSoft,
                ) },
                confirmButton    = {
                    TextButton(
                        onClick = {
                            showDeleteConfirm = false
                            scope.launch {
                                try {
                                    captureDao.softDelete(captureId, IsoClock.nowIso())
                                    onBack()
                                } catch (_: Exception) { /* best-effort */ }
                            }
                        },
                    ) {
                        Text("Delete", color = colors.danger)
                    }
                },
                dismissButton    = {
                    TextButton(onClick = { showDeleteConfirm = false }) {
                        Text("Cancel", color = colors.ink)
                    }
                },
                containerColor   = colors.surface,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(bottom = QuickInkBottomNavReservedHeight),
            verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s5),
        ) {
            val current = capture
            if (current == null) {
                LoadingSkeleton(
                    modifier = Modifier.padding(horizontal = QuickInkSpacing.s5),
                )
            } else {
                // Title header — large display title + breadcrumb
                TitleHeader(
                    capture = current,
                    onEdit  = {
                        titleDraft     = current.title.orEmpty()
                        showTitleEditor = true
                    },
                    modifier = Modifier.padding(horizontal = QuickInkSpacing.s5),
                )

                // Preview block — full-bleed within margins. The
                // selectedPageIndex two-way bind keeps the thumbnails
                // strip and the swipeable pager in sync (tap a chip
                // to jump; swipe the pager to advance the chip).
                val isBusinessCard = primaryTagName
                    ?.equals("business-card", ignoreCase = true) == true
                // Playable-video URI for the preview overlay. Non-null
                // only when the row carries a `video_uri` AND the .mp4
                // is actually on disk (i.e. either this device produced
                // it or the binary-restore pass has already pulled it).
                // The preview's fullscreen tap reroutes to the video
                // player when this is set; the play-button overlay
                // below is the visual cue.
                val playableVideoUri = current.videoUri
                    ?.takeIf { it.isNotBlank() && localFileExists(it) }
                Box(modifier = Modifier.fillMaxWidth()) {
                PreviewImage(
                    capture             = current,
                    onFullscreenClick   = {
                        if (playableVideoUri != null) {
                            videoPlayerUri = playableVideoUri
                        } else {
                            showFullscreenViewer = true
                        }
                    },
                    onMoreClick         = { moreMenuExpanded = true },
                    moreMenuContent     = {
                        // Video subtype = a hold-to-record photo capture
                        // that produced a `.mp4`. Same rule as HomeScreen
                        // / fileTypeLabel — source == "photo" + a video
                        // URI (local or Drive). The dropdown swaps the
                        // image/PDF share pair for a single "Share video"
                        // when this trips.
                        val isVideo = current.source == "photo" && (
                            !current.videoUri.isNullOrBlank() ||
                                !current.videoDriveFileId.isNullOrBlank()
                        )
                        ScanActionsDropdown(
                            expanded              = moreMenuExpanded,
                            onDismiss             = { moreMenuExpanded = false },
                            isBusinessCard        = isBusinessCard,
                            isVideo               = isVideo,
                            isPreparingImageShare = isPreparingImageShare,
                            onAddToContact        = {
                                moreMenuExpanded = false
                                scope.launch {
                                    businessCardExtraction = runCatching {
                                        runBusinessCardExtraction(captureId, ocrDao)
                                    }.getOrDefault(ExtractedContact.empty)
                                }
                            },
                            onShareVideo          = {
                                moreMenuExpanded = false
                                shareVideo(context, current.videoUri)
                            },
                            onShareAsImage        = {
                                moreMenuExpanded = false
                                if (!isPreparingImageShare) {
                                    scope.launch {
                                        isPreparingImageShare = true
                                        try {
                                            val bitmaps = withContext(Dispatchers.IO) {
                                                rasterisePagesForEditor(
                                                    context    = context,
                                                    pdfUri     = current.pdfUri,
                                                    previewUri = current.previewUri,
                                                )
                                            }
                                            if (bitmaps.isEmpty()) {
                                                android.widget.Toast.makeText(
                                                    context,
                                                    "Nothing to share for this scan",
                                                    android.widget.Toast.LENGTH_SHORT,
                                                ).show()
                                            } else {
                                                pendingEditorPages = bitmaps
                                            }
                                        } finally {
                                            isPreparingImageShare = false
                                        }
                                    }
                                }
                            },
                            onExportPdf           = {
                                moreMenuExpanded = false
                                exportAsPdf(context, current.pdfUri)
                            },
                            onMoveToFolder        = {
                                moreMenuExpanded = false
                                showFolderPicker = true
                            },
                            onManageTags          = {
                                moreMenuExpanded = false
                                showTagPicker = true
                            },
                            onManageLocations     = {
                                moreMenuExpanded = false
                                showLocationPicker = true
                            },
                            onManagePeople        = {
                                moreMenuExpanded = false
                                showPeoplePicker = true
                            },
                            onDelete              = {
                                moreMenuExpanded = false
                                showDeleteConfirm = true
                            },
                        )
                    },
                    currentPage         = selectedPageIndex,
                    onCurrentPageChange = { selectedPageIndex = it },
                    modifier            = Modifier.padding(horizontal = QuickInkSpacing.s5),
                )

                // Centred play button on top of the preview — only
                // when the capture has a locally-playable video. Tap
                // opens the same VideoPlayerDialog the (now-removed)
                // standalone "Play recorded clip" card used. The
                // surrounding preview also routes its tap to the
                // player when playableVideoUri is set, so either
                // surface fires the player.
                if (playableVideoUri != null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(horizontal = QuickInkSpacing.s5)
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.55f))
                            .clickable { videoPlayerUri = playableVideoUri },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector       = Icons.Filled.PlayArrow,
                            contentDescription = "Play recorded video",
                            tint              = Color.White,
                            modifier          = Modifier.size(36.dp),
                        )
                    }
                }
                } // closes the preview Box

                // Page thumbnails strip (multi-page only)
                if (current.pageCount > 1) {
                    PageThumbnailsStrip(
                        pageCount         = current.pageCount,
                        pageBitmaps       = pageBitmaps,
                        selectedPageIndex = selectedPageIndex,
                        onSelectPage      = { selectedPageIndex = it },
                    )
                }

                // Video pending placeholder — only shown on a
                // receiver device whose row has a
                // `video_drive_file_id` set but whose local .mp4
                // hasn't been downloaded yet. The playable case
                // is surfaced via the play-button overlay on the
                // preview above (see `playableVideoUri`); no
                // second card needed.
                val rawVideoUri = current.videoUri?.takeIf { it.isNotBlank() }
                val hasLocalVideo = rawVideoUri != null && localFileExists(rawVideoUri)
                val hasVideoDriveId = !current.videoDriveFileId.isNullOrBlank()
                if (!hasLocalVideo && hasVideoDriveId) {
                    VideoPendingCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = QuickInkSpacing.s5),
                    )
                }

                // Details card — full width now that the Actions
                // card has moved to the more-menu dropdown anchored
                // beside the fullscreen chip on the preview.
                DetailsCard(
                    capture                = current,
                    primaryTagName         = primaryTagName,
                    pdfFileSize            = pdfFileSize,
                    onAddTag               = { showRetagSheet = true },
                    attachedLocationNames  = attachedLocationNames,
                    onAddLocation          = { showLocationPicker = true },
                    attachedPeople         = attachedPeople,
                    onAddPerson            = { showPeoplePicker = true },
                    onPersonChipTap        = { person -> personActionTarget = person },
                    modifier               = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = QuickInkSpacing.s5),
                )

                // Document notes — free-form text the user can type
                // directly into the scan. Tapping the card opens a
                // full-screen editor; the voice-note transcript
                // editor also appends here, so notes accumulate from
                // both surfaces.
                NotesCard(
                    notes    = current.notes,
                    onTap    = {
                        notesDraft = current.notes.orEmpty()
                        showNotesEditor = true
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = QuickInkSpacing.s5),
                )

                // Voice notes — full-width section below the
                // Details + Actions row. Owns its own list +
                // recorder sheet; persists rows in `voice_notes`
                // with a foreign key to this capture so deletes
                // cascade with the scan. `onNotesChanged` fires
                // after Copy-to-notes or the transcript editor
                // append so the Notes card above refreshes without
                // waiting for a screen revisit.
                VoiceNoteSection(
                    captureId      = captureId,
                    userId         = userId,
                    onNotesChanged = {
                        scope.launch {
                            capture = captureDao.findById(captureId)
                        }
                    },
                    modifier       = Modifier.padding(horizontal = QuickInkSpacing.s5),
                )
            }
        }
    } // end inner Column
    } // end outer Box

    // WhatsApp-style image editor — overlays the detail screen
    // entirely when the user picks "Share as Image". Crop + pencil
    // per page; Done writes the edited images to cache JPEGs and
    // hands them to the system share intent that used to fire
    // directly from the menu.
    //
    // Rendered inside a [Dialog] with `usePlatformDefaultWidth =
    // false` and `decorFitsSystemWindows = false` so it covers the
    // whole activity — including the QuickInk bottom-nav bar that
    // lives at the MainShell root above this composable. Without
    // the Dialog the editor would only fill the NavHost slot and
    // the footer chips would still hover on top.
    pendingEditorPages?.let { bitmaps ->
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { pendingEditorPages = null },
            properties = androidx.compose.ui.window.DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows  = false,
                dismissOnBackPress      = true,
                dismissOnClickOutside   = false,
            ),
        ) {
            // Edge-to-edge + system bars hidden: tell the Dialog's
            // window to draw behind the status + nav bars, paint
            // them transparent, then actively HIDE them while the
            // editor is up. Restored on dispose so the rest of the
            // app gets its normal chrome back.
            val view = androidx.compose.ui.platform.LocalView.current
            val dialogWindow = (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
            androidx.compose.runtime.DisposableEffect(dialogWindow) {
                if (dialogWindow != null) {
                    androidx.core.view.WindowCompat.setDecorFitsSystemWindows(dialogWindow, false)
                    @Suppress("DEPRECATION")
                    dialogWindow.statusBarColor      = android.graphics.Color.TRANSPARENT
                    @Suppress("DEPRECATION")
                    dialogWindow.navigationBarColor  = android.graphics.Color.TRANSPARENT
                    val controller = androidx.core.view.WindowInsetsControllerCompat(
                        dialogWindow,
                        dialogWindow.decorView,
                    )
                    controller.systemBarsBehavior =
                        androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                }
                onDispose {
                    if (dialogWindow != null) {
                        val controller = androidx.core.view.WindowInsetsControllerCompat(
                            dialogWindow,
                            dialogWindow.decorView,
                        )
                        controller.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                    }
                }
            }
            ImageEditorScreen(
                pages    = bitmaps,
                onCancel = { pendingEditorPages = null },
                onDone   = { edited ->
                    pendingEditorPages = null
                    scope.launch {
                        val files = withContext(Dispatchers.IO) {
                            writeEditedJpegs(context, edited)
                        }
                        if (files.isEmpty()) {
                            android.widget.Toast.makeText(
                                context,
                                "Couldn't prepare scan for sharing",
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                            return@launch
                        }
                        val authority = "${context.packageName}.fileprovider"
                        val uris = files.mapNotNull {
                            runCatching { FileProvider.getUriForFile(context, authority, it) }
                                .getOrNull()
                        }
                        if (uris.isEmpty()) return@launch
                        val intent = buildImageShareIntent(uris)
                        try {
                            context.startActivity(Intent.createChooser(intent, "Share scan"))
                        } catch (_: Exception) {
                            android.widget.Toast.makeText(
                                context,
                                "Couldn't open the share sheet for this scan",
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                },
            )
        }
    }

    // Retag bottom sheet — tapping the primary-tag pill (or the
    // untagged "Tag scan" affordance) opens this. One row per
    // active tag plus a "Remove tag" row when the capture already
    // has one. Each row calls into [attachOrEnsurePrimaryTag] which
    // attaches the tag through `capture_tags`. Refreshes the
    // in-screen `capture` state so the pill flips immediately.
    if (showRetagSheet) {
        RetagSheet(
            categories = categories.map { it.name },
            current    = primaryTagName,
            onDismiss  = { showRetagSheet = false },
            onPick     = { name ->
                showRetagSheet = false
                scope.launch {
                    try {
                        captureRepository.attachOrEnsurePrimaryTag(
                            captureId = captureId,
                            userId    = userId,
                            name      = name,
                        )
                        capture = captureDao.findById(captureId)
                    } catch (_: Exception) { /* best-effort */ }
                }
            },
        )
    }

    // Notes editor — modal AlertDialog with a multi-line text
    // field. Save commits via [CaptureRepository.setNotes] (dirty-bit
    // picked up by the next sync); Cancel discards the draft. Blank
    // input clears the column (stored as null) so the card's empty-
    // state branch reads correctly afterward.
    if (showNotesEditor) {
        AlertDialog(
            onDismissRequest = { showNotesEditor = false },
            title            = { Text("Notes", style = type.body, color = colors.ink) },
            text             = {
                Column(verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2)) {
                    Text(
                        text = "Capture notes about this scan. Voice-note transcripts also get appended here.",
                        style = type.caption,
                        color = colors.muted,
                    )
                    OutlinedTextField(
                        value         = notesDraft,
                        onValueChange = { notesDraft = it },
                        placeholder   = { Text("Add notes…", color = colors.muted) },
                        minLines      = 6,
                        modifier      = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton    = {
                TextButton(
                    onClick = {
                        showNotesEditor = false
                        val draft = notesDraft
                        scope.launch {
                            try {
                                captureRepository.setNotes(captureId, draft)
                                capture = captureDao.findById(captureId)
                            } catch (_: Exception) { /* best-effort */ }
                        }
                    },
                ) {
                    Text("Save", color = colors.accent)
                }
            },
            dismissButton    = {
                TextButton(onClick = { showNotesEditor = false }) {
                    Text("Cancel", color = colors.ink)
                }
            },
            containerColor   = colors.surface,
        )
    }

    // Title editor — modal AlertDialog with a single text field.
    // Save commits via [CaptureDao.setTitle] (dirty-bit, picked up by
    // the next sync); Cancel discards the draft. Blank input clears
    // the title (stored as null), so the Library card falls back to
    // its OCR/category/"Untitled" cascade.
    if (showTitleEditor) {
        AlertDialog(
            onDismissRequest = { showTitleEditor = false },
            title            = { Text("Edit title", style = type.body, color = colors.ink) },
            text             = {
                OutlinedTextField(
                    value         = titleDraft,
                    onValueChange = { titleDraft = it },
                    placeholder   = { Text("Untitled scan", color = colors.muted) },
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth(),
                )
            },
            confirmButton    = {
                TextButton(
                    onClick = {
                        showTitleEditor = false
                        scope.launch {
                            try {
                                captureDao.setTitle(
                                    captureId,
                                    titleDraft.trim().takeIf { it.isNotEmpty() },
                                    IsoClock.nowIso(),
                                )
                                capture = captureDao.findById(captureId)
                            } catch (_: Exception) { /* best-effort */ }
                        }
                    },
                ) {
                    Text("Save", color = colors.accent)
                }
            },
            dismissButton    = {
                TextButton(onClick = { showTitleEditor = false }) {
                    Text("Cancel", color = colors.ink)
                }
            },
            containerColor   = colors.surface,
        )
    }

    // Workspace v1 tag picker — opened by the Actions card's
    // "Manage tags" row. The sheet writes diffs to capture_tags on
    // Save; no work happens on Cancel.
    if (showTagPicker) {
        TagPickerSheet(
            captureId = captureId,
            userId    = userId,
            onDismiss = { showTagPicker = false },
        )
    }

    // Locations picker — opened by the more-menu's "Manage
    // locations" row. Writes diffs to `capture_locations` on Save;
    // no work happens on Cancel.
    if (showLocationPicker) {
        LocationPickerSheet(
            captureId = captureId,
            userId    = userId,
            onDismiss = { showLocationPicker = false },
        )
    }

    // People picker — opened by the more-menu's "Manage people"
    // row. Writes diffs to `capture_people` on Save.
    if (showPeoplePicker) {
        PeoplePickerSheet(
            captureId = captureId,
            userId    = userId,
            onDismiss = { showPeoplePicker = false },
        )
    }

    // Per-person action sheet — opens when a chip on the Details
    // card is tapped. Routes to either the system share sheet
    // (pre-filled with the person's contact info) or the editor.
    personActionTarget?.let { target ->
        val pdfUri = capture?.pdfUri
        PersonChipActionsSheet(
            person    = target,
            canShare  = pdfUri != null && pdfUri.isNotBlank() && localFileExists(pdfUri),
            onShare   = {
                personActionTarget = null
                shareCapturePdfWithPerson(context, pdfUri, target)
            },
            onEdit    = {
                personActionTarget    = null
                personEditorExisting  = target
                personEditorOpen      = true
            },
            onDismiss = { personActionTarget = null },
        )
    }

    if (personEditorOpen) {
        PersonEditorDialog(
            userId    = userId,
            existing  = personEditorExisting,
            onDismiss = { personEditorOpen = false },
            onSaved   = { personEditorOpen = false },
        )
    }

    // Workspace v1 folder picker — opened by the "Move to folder"
    // Actions row. Writes via [CaptureDao.setFolder] which dirties
    // the row for the next sync push.
    if (showFolderPicker) {
        FolderPickerSheet(
            folders         = folders,
            currentFolderId = capture?.folderId,
            onDismiss       = { showFolderPicker = false },
            onPickFolder    = { folder ->
                scope.launch {
                    captureDao.setFolder(
                        id        = captureId,
                        folderId  = folder.id,
                        timestamp = IsoClock.nowIso(),
                    )
                    // Refresh the in-screen capture so the
                    // Details card reflects the new folder
                    // assignment without a back-and-forth.
                    capture = captureDao.findById(captureId)
                    showFolderPicker = false
                }
            },
        )
    }

    // Fullscreen viewer — opens when the user taps the overlay
    // fullscreen button on the inline preview. Only meaningful when
    // we have a real PDF on disk; the dialog itself handles the
    // load + flipbook flow.
    if (showFullscreenViewer) {
        val current = capture
        val pdfUriString = current?.pdfUri
        val pdfUri = pdfUriString
            ?.takeIf { it.isNotBlank() && localFileExists(it) }
            ?.let(Uri::parse)
        if (pdfUri != null) {
            FullscreenPdfDialog(
                pdfUri    = pdfUri,
                onDismiss = { showFullscreenViewer = false },
            )
        } else {
            // PDF missing on disk — close the dialog rather than
            // opening an empty viewer. The inline preview already
            // surfaces a "file isn't available" placeholder.
            LaunchedEffect(showFullscreenViewer) {
                showFullscreenViewer = false
            }
        }
    }

    // Hold-to-record video clip player. Renders the canonical
    // .mp4 via a stock Android `VideoView` + `MediaController`
    // inside a full-screen dialog. Dismiss tears down the
    // VideoView so the MediaPlayer releases its surface.
    val playingUri = videoPlayerUri
    if (playingUri != null) {
        VideoPlayerDialog(
            videoUri  = playingUri,
            onDismiss = { videoPlayerUri = null },
        )
    }

    // Business Card review sheet — opens once `runBusinessCard-
    // Extraction` lands (set businessCardExtraction != null). On
    // confirm we hand the (possibly-edited) form to
    // `launchAddContactIntent`. On dismiss we just clear the state.
    val pendingExtraction = businessCardExtraction
    if (pendingExtraction != null) {
        AddContactReviewSheet(
            extracted = pendingExtraction,
            onDismiss = { businessCardExtraction = null },
            onConfirm = { edited ->
                businessCardExtraction = null
                launchAddContactIntent(context, edited)
            },
        )
    }
}

/**
 * Best preview surface for the capture, in priority order:
 *   1. Multi-page PDF render via [PdfPagesView] (preferred —
 *      pinch-to-zoom + every page).
 *   2. The first-page JPEG `preview_uri` via Coil (when the PDF
 *      isn't on disk).
 *   3. A friendly placeholder — either "restoring from Drive" (when
 *      a Drive backup exists and the self-heal effect can recover
 *      it) or "file isn't available" (no Drive backup to recover
 *      from). Replaces the raw "open failed: ENOENT" the system
 *      message we used to surface when the URI pointed at a path
 *      that no longer exists.
 */
@Composable
private fun PreviewImage(
    capture: CaptureEntity,
    onFullscreenClick: (() -> Unit)? = null,
    /// Optional secondary chip drawn beside the fullscreen affordance
    /// in the TopEnd Row. `moreMenuContent` is rendered as a sibling
    /// of the chip so a DropdownMenu anchored there opens next to
    /// it. Used by `ScanDetailScreen` to host the per-capture
    /// actions menu (Share / Export / Move / Delete / …).
    onMoreClick: (() -> Unit)? = null,
    moreMenuContent: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
    currentPage: Int = 0,
    onCurrentPageChange: (Int) -> Unit = {},
) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    val context = LocalContext.current
    val pdfUri = capture.pdfUri.takeIf { it.isNotBlank() }?.let(Uri::parse)
    val previewUri = capture.previewUri

    // Verify the URIs actually resolve to files on disk before
    // handing them to the PDF renderer / image loader. The DB row
    // can outlive the file (cleared app data, attachment-folder
    // migration mid-run, manual delete, never-uploaded scan whose
    // file got pruned). Without this check the renderer crashes
    // out with the platform's `open failed: ENOENT (No such file
    // or directory)` and the user has no way to interpret it.
    val pdfPresent = pdfUri != null && localFileExists(capture.pdfUri)
    val previewPresent = !previewUri.isNullOrBlank() && localFileExists(previewUri)

    when {
        pdfPresent -> {
            // Tap-anywhere-to-fullscreen modifier — gives the user a
            // visible affordance to enter the interactive viewer
            // since the inline PDF surfaces are now non-interactive
            // (so vertical drags can reach the outer `verticalScroll`).
            // No indication ripple — a Material ripple over the page
            // surface reads as a glitch on a "thumbnail" preview.
            val previewTap = onFullscreenClick?.let { handler ->
                Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication        = null,
                    onClick           = handler,
                )
            } ?: Modifier

            // Multi-page captures get the swipe + page-turn viewer;
            // single-page captures keep the scrollable PdfPagesView
            // since it already handles pinch-to-zoom and there's
            // nothing to swipe to anyway. Both run with
            // `interactionsEnabled = false` here — the fullscreen
            // viewer carries the full pinch / pan / swipe UX.
            if (capture.pageCount > 1) {
                PageTurnPdfView(
                    pdfUri              = pdfUri!!,
                    onFullscreenClick   = onFullscreenClick,
                    onMoreClick         = onMoreClick,
                    moreMenuContent     = moreMenuContent,
                    currentPage         = currentPage,
                    onCurrentPageChange = onCurrentPageChange,
                    interactionsEnabled = false,
                    modifier = modifier
                        .fillMaxWidth()
                        .aspectRatio(0.707f) // A4-ish portrait until pages render
                        .clip(RoundedCornerShape(QuickInkRadius.md))
                        .background(colors.surface)
                        .border(1.dp, colors.border, RoundedCornerShape(QuickInkRadius.md))
                        .then(previewTap),
                )
            } else {
                PdfPagesView(
                    pdfUri              = pdfUri,
                    onFullscreenClick   = onFullscreenClick,
                    onMoreClick         = onMoreClick,
                    moreMenuContent     = moreMenuContent,
                    interactionsEnabled = false,
                    modifier = modifier
                        .fillMaxWidth()
                        .border(1.dp, colors.border, RoundedCornerShape(QuickInkRadius.md))
                        .then(previewTap),
                )
            }
        }
        previewPresent -> {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(Uri.parse(previewUri))
                    .crossfade(true)
                    .build(),
                contentDescription = "Scan preview",
                contentScale       = ContentScale.Fit,
                modifier = modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(QuickInkRadius.md))
                    .background(colors.surface)
                    .border(1.dp, colors.border, RoundedCornerShape(QuickInkRadius.md)),
            )
        }
        else -> {
            // Neither file is on this device. If we have any Drive
            // file id, the self-heal effect at the screen's top is
            // currently downloading the binary — show a loader so
            // the wait is intentional. Otherwise this scan was
            // never uploaded and the local file is gone (e.g., app
            // data cleared between create and first sync) — say so
            // plainly so the user isn't left guessing what ENOENT
            // meant.
            val isRestoringFromDrive =
                capture.pdfDriveFileId != null || capture.previewDriveFileId != null
            Box(
                modifier = modifier
                    .fillMaxWidth()
                    .height(320.dp)
                    .clip(RoundedCornerShape(QuickInkRadius.md))
                    .background(colors.borderSoft)
                    .padding(QuickInkSpacing.s4),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2),
                ) {
                    if (isRestoringFromDrive) {
                        CircularProgressIndicator(
                            color    = colors.accent,
                            modifier = Modifier.size(32.dp),
                        )
                        Text(
                            text  = "Restoring from Drive…",
                            style = type.meta,
                            color = colors.inkSoft,
                        )
                    } else {
                        Icon(
                            imageVector       = Icons.Outlined.Description,
                            contentDescription = null,
                            tint              = colors.muted,
                            modifier          = Modifier.size(64.dp),
                        )
                        Text(
                            text  = "This scan's file isn't on this device or Drive.",
                            style = type.meta,
                            color = colors.inkSoft,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Render the capture's pages to JPEGs and hand them to the system
 * share sheet as image content. Multi-page captures use
 * `ACTION_SEND_MULTIPLE`; single-page captures use `ACTION_SEND`.
 * Falls back to copying the preview JPEG when the PDF isn't on
 * disk; surfaces a Toast when neither is available.
 *
 * Mirror of iOS `prepareImageShare` + `ActivityView`. URI grants
 * follow the same FileProvider pattern as [exportAsPdf] (see
 * [shareableUri] below).
 */
private suspend fun shareAsImage(
    context: android.content.Context,
    pdfUri: String?,
    previewUri: String?,
) {
    val files = withContext(Dispatchers.IO) {
        prepareShareImageFiles(context, pdfUri, previewUri)
    }
    if (files.isEmpty()) {
        android.widget.Toast.makeText(
            context, "Nothing to share for this scan",
            android.widget.Toast.LENGTH_SHORT,
        ).show()
        return
    }
    val authority = "${context.packageName}.fileprovider"
    val uris = files.mapNotNull {
        runCatching { FileProvider.getUriForFile(context, authority, it) }.getOrNull()
    }
    if (uris.isEmpty()) {
        android.widget.Toast.makeText(
            context, "Couldn't prepare scan for sharing",
            android.widget.Toast.LENGTH_SHORT,
        ).show()
        return
    }
    val intent = buildImageShareIntent(uris)
    try {
        context.startActivity(Intent.createChooser(intent, "Share scan"))
    } catch (_: Exception) {
        android.widget.Toast.makeText(
            context, "Couldn't open the share sheet for this scan",
            android.widget.Toast.LENGTH_SHORT,
        ).show()
    }
}

/**
 * IO-bound helper for [shareAsImage]: writes one JPEG per page to a
 * fresh per-call subdirectory under the app's cache dir, falling
 * back to copying the preview JPEG when the PDF isn't on disk.
 * Returns an empty list when neither path resolves; the caller
 * surfaces a Toast in that case. Unique-per-call subdir keeps file
 * names (`page-1.jpg` etc.) human-readable in the share-sheet
 * preview without clobbering a previous share's files.
 */
/**
 * Rasterise the capture's pages into in-memory [Bitmap]s for the
 * image editor. Multi-page PDFs return one bitmap per page;
 * image-only (PDF-less) captures fall back to decoding the preview
 * JPEG. Empty list means there's nothing to edit — caller surfaces
 * a Toast.
 */
private fun rasterisePagesForEditor(
    context: android.content.Context,
    pdfUri: String?,
    previewUri: String?,
): List<Bitmap> {
    if (!pdfUri.isNullOrBlank() && localFileExists(pdfUri)) {
        val bitmaps = runCatching {
            renderPdfPages(context, Uri.parse(pdfUri))
        }.getOrDefault(emptyList())
        if (bitmaps.isNotEmpty()) return bitmaps
    }
    if (!previewUri.isNullOrBlank()) {
        val parsed = runCatching { Uri.parse(previewUri) }.getOrNull()
        val bm = runCatching {
            val stream = when (parsed?.scheme) {
                "file"    -> parsed.path?.let(::File)?.inputStream()
                null      -> File(previewUri).inputStream()
                "content" -> context.contentResolver.openInputStream(parsed)
                else      -> null
            }
            stream?.use { android.graphics.BitmapFactory.decodeStream(it) }
        }.getOrNull()
        if (bm != null) return listOf(bm)
    }
    return emptyList()
}

/**
 * Write edited bitmaps (post-crop / annotation) to JPEGs in the
 * standard share-images cache subdir. Mirror of the writer used by
 * [prepareShareImageFiles] but with the bitmaps already in hand.
 */
private fun writeEditedJpegs(
    context: android.content.Context,
    bitmaps: List<Bitmap>,
): List<File> {
    val callDir = "share-${java.util.UUID.randomUUID().toString().take(8)}"
    val outDir = File(File(context.cacheDir, "share-images"), callDir)
        .also { it.mkdirs() }
    return bitmaps.mapIndexedNotNull { index, bm ->
        val out = File(outDir, "page-${index + 1}.jpg")
        runCatching {
            java.io.FileOutputStream(out).use { os ->
                bm.compress(Bitmap.CompressFormat.JPEG, 92, os)
            }
            out
        }.getOrNull()
    }
}

private fun prepareShareImageFiles(
    context: android.content.Context,
    pdfUri: String?,
    previewUri: String?,
): List<File> {
    // Nest under `<cacheDir>/share-images/<per-call-subdir>/` so the
    // FileProvider's `share-images` cache-path entry covers every
    // file we hand out. The per-call subdir keeps a second share
    // from clobbering the first's files while the chooser is still
    // up.
    val callDir = "share-${java.util.UUID.randomUUID().toString().take(8)}"
    val outDir = File(File(context.cacheDir, "share-images"), callDir)
        .also { it.mkdirs() }

    if (!pdfUri.isNullOrBlank() && localFileExists(pdfUri)) {
        val bitmaps = runCatching {
            renderPdfPages(context, Uri.parse(pdfUri))
        }.getOrDefault(emptyList())
        val files = bitmaps.mapIndexedNotNull { index, bm ->
            val out = File(outDir, "page-${index + 1}.jpg")
            runCatching {
                java.io.FileOutputStream(out).use { os ->
                    bm.compress(Bitmap.CompressFormat.JPEG, 92, os)
                }
                out
            }.getOrNull()
        }
        if (files.isNotEmpty()) return files
    }

    if (!previewUri.isNullOrBlank()) {
        val parsed = runCatching { Uri.parse(previewUri) }.getOrNull()
        val out = File(outDir, "scan.jpg")
        val copied = runCatching {
            val input = when (parsed?.scheme) {
                "file"    -> parsed.path?.let(::File)?.inputStream()
                null      -> File(previewUri).inputStream()
                "content" -> context.contentResolver.openInputStream(parsed)
                else      -> null
            } ?: return@runCatching null
            input.use { src -> java.io.FileOutputStream(out).use { src.copyTo(it) } }
            out
        }.getOrNull()
        if (copied != null) return listOf(copied)
    }

    return emptyList()
}

/**
 * Build the share intent for one or more image URIs. Single-image
 * shares use `ACTION_SEND`; multi-image shares use
 * `ACTION_SEND_MULTIPLE`. `clipData` mirrors `EXTRA_STREAM` so the
 * URI grant survives the chooser hop (see [exportAsPdf]'s note).
 */
private fun buildImageShareIntent(uris: List<Uri>): Intent {
    if (uris.size == 1) {
        return Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uris[0])
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = android.content.ClipData.newRawUri(null, uris[0])
        }
    }
    return Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        type = "image/jpeg"
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val clip = android.content.ClipData.newRawUri(null, uris[0])
        for (i in 1 until uris.size) {
            clip.addItem(android.content.ClipData.Item(uris[i]))
        }
        clipData = clip
    }
}

/**
 * Hand the capture's PDF off to the system share sheet. PDF-only —
 * the legacy "Share" affordance fell back to the preview JPEG, but
 * that behaviour now lives in [shareAsImage] under its own row.
 *
 * URI handling matters here: after sync (or a Drive restore) the
 * `pdf_uri` row is a `file://` URI rooted at AttachmentStorage's
 * directory (`<filesDir>/quickink/attachments/` once
 * QuickInkApp.onCreate's `appFolderName` override has run, with
 * historic rows migrated in by `migrateLegacyAttachmentsFolder`).
 * Modern Android forbids forwarding `file://` URIs from app-private
 * storage to other apps, so we wrap them through our FileProvider
 * to get a `content://` URI with a usable read grant.
 *
 * Failures surface via Toast so the user knows the tap registered —
 * silent failure was reported as "share button not wired".
 *
 * Mirror of iOS `ShareLink(item: pdfURL)` on the Export-as-PDF row.
 */
/**
 * Share-sheet for the recorded `.mp4` of a hold-to-record Photo-
 * mode capture. Same FileProvider plumbing as [exportAsPdf] —
 * the receiver gets a `content://` URI it can read without
 * needing storage permission. Mime type is `video/mp4` so the
 * share sheet only surfaces apps that handle video.
 *
 * Toast on every failure path so the tap doesn't read as silent
 * (matches the PDF-share UX).
 */
private fun shareVideo(
    context: android.content.Context,
    videoUri: String?,
) {
    if (videoUri.isNullOrBlank() || !localFileExists(videoUri)) {
        android.widget.Toast.makeText(
            context, "Video isn't available for this capture",
            android.widget.Toast.LENGTH_SHORT,
        ).show()
        return
    }
    val shareUri = shareableUri(context, videoUri)
    if (shareUri == null) {
        android.widget.Toast.makeText(
            context, "Couldn't prepare video for sharing",
            android.widget.Toast.LENGTH_SHORT,
        ).show()
        return
    }
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "video/mp4"
        putExtra(Intent.EXTRA_STREAM, shareUri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // Mirror EXTRA_STREAM into clipData so the read-grant
        // survives the chooser-target trampoline on API 24+.
        clipData = android.content.ClipData.newRawUri(null, shareUri)
    }
    try {
        context.startActivity(Intent.createChooser(intent, "Share video"))
    } catch (_: Exception) {
        android.widget.Toast.makeText(
            context, "Couldn't open the share sheet for this video",
            android.widget.Toast.LENGTH_SHORT,
        ).show()
    }
}

private fun exportAsPdf(
    context: android.content.Context,
    pdfUri: String?,
) {
    if (pdfUri.isNullOrBlank() || !localFileExists(pdfUri)) {
        android.widget.Toast.makeText(
            context, "PDF isn't available for this scan",
            android.widget.Toast.LENGTH_SHORT,
        ).show()
        return
    }
    val shareUri = shareableUri(context, pdfUri)
    if (shareUri == null) {
        android.widget.Toast.makeText(
            context, "Couldn't prepare PDF for export",
            android.widget.Toast.LENGTH_SHORT,
        ).show()
        return
    }
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(Intent.EXTRA_STREAM, shareUri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // Without `clipData`, the grant flag isn't honored on some
        // receivers (notably anything routed through a chooser
        // target on API 24+). Mirroring EXTRA_STREAM into clipData
        // is the documented workaround.
        clipData = android.content.ClipData.newRawUri(null, shareUri)
    }
    try {
        context.startActivity(Intent.createChooser(intent, "Export scan as PDF"))
    } catch (_: Exception) {
        android.widget.Toast.makeText(
            context, "Couldn't open the export sheet for this scan",
            android.widget.Toast.LENGTH_SHORT,
        ).show()
    }
}

/**
 * Per-person action sheet — opens from tapping a chip on the
 * Details card. Lists the two contextual actions: share the
 * document (system share sheet, with the person's email
 * pre-filled when available) and edit the person row.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun PersonChipActionsSheet(
    person: app.quickink.mobile.data.person.PersonEntity,
    canShare: Boolean,
    onShare: () -> Unit,
    onEdit: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors     = LocalQuickInkColors.current
    val type       = LocalQuickInkTypography.current
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
    )

    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = sheetState,
        containerColor   = colors.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start  = QuickInkSpacing.s4,
                    end    = QuickInkSpacing.s4,
                    bottom = QuickInkSpacing.s4,
                ),
            verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2),
        ) {
            Text(
                text  = person.name,
                style = type.heading,
                color = colors.ink,
            )
            val subtitle = person.contactPhone?.takeIf { it.isNotBlank() }
                ?: person.contactEmail?.takeIf { it.isNotBlank() }
            if (subtitle != null) {
                Text(
                    text  = subtitle,
                    style = type.meta,
                    color = colors.muted,
                )
            }
            Spacer(modifier = Modifier.height(QuickInkSpacing.s1))
            PersonActionRow(
                icon    = androidx.compose.material.icons.Icons.Outlined.Share,
                label   = "Share document",
                enabled = canShare,
                onClick = onShare,
            )
            PersonActionRow(
                icon    = androidx.compose.material.icons.Icons.Outlined.Edit,
                label   = "Edit person",
                onClick = onEdit,
            )
        }
    }
}

@Composable
private fun PersonActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = QuickInkSpacing.s2),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s3),
    ) {
        Icon(
            imageVector        = icon,
            contentDescription = null,
            tint               = if (enabled) colors.accent else colors.muted,
            modifier           = Modifier.size(20.dp),
        )
        Text(
            text  = label,
            style = type.body,
            color = if (enabled) colors.ink else colors.muted,
        )
    }
}

/**
 * Share a capture's PDF via the system share sheet, pre-filled with
 * a person's contact info when available. Routes through email
 * (`mailto:` chooser) when only an email is on file, SMS
 * (`smsto:`) when only a phone is on file, and the generic
 * ACTION_SEND otherwise so the user can pick any app (WhatsApp,
 * etc.). Always falls back to the generic chooser when a more
 * specific intent has no handler installed.
 */
private fun shareCapturePdfWithPerson(
    context: android.content.Context,
    pdfUri: String?,
    person: app.quickink.mobile.data.person.PersonEntity,
) {
    if (pdfUri.isNullOrBlank() || !localFileExists(pdfUri)) {
        android.widget.Toast.makeText(
            context, "PDF isn't available for this scan",
            android.widget.Toast.LENGTH_SHORT,
        ).show()
        return
    }
    val shareUri = shareableUri(context, pdfUri)
    if (shareUri == null) {
        android.widget.Toast.makeText(
            context, "Couldn't prepare PDF for sharing",
            android.widget.Toast.LENGTH_SHORT,
        ).show()
        return
    }
    val email = person.contactEmail?.takeIf { it.isNotBlank() }
    val phone = person.contactPhone?.takeIf { it.isNotBlank() }

    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(Intent.EXTRA_STREAM, shareUri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        clipData = android.content.ClipData.newRawUri(null, shareUri)
        if (email != null) {
            putExtra(Intent.EXTRA_EMAIL, arrayOf(email))
        }
        // No standard EXTRA for phone numbers on ACTION_SEND, but
        // many apps (WhatsApp / Signal) pick up the recipient when
        // the user picks the contact in their own UI. Title hint
        // helps the user find the right contact in the chooser.
        putExtra(
            Intent.EXTRA_SUBJECT,
            "Document for ${person.name}",
        )
    }
    val title = "Share with ${person.name}" +
        if (phone != null && email == null) "  ($phone)" else ""
    try {
        context.startActivity(Intent.createChooser(intent, title))
    } catch (_: Exception) {
        android.widget.Toast.makeText(
            context, "Couldn't open the share sheet",
            android.widget.Toast.LENGTH_SHORT,
        ).show()
    }
}

/// Translate a stored capture URI string into something the share
/// sheet's receivers can actually read. `file://` URIs go through
/// FileProvider so the receiver gets a content:// URI with a usable
/// read grant; `content://` URIs (e.g. ML Kit's fresh scanner output)
/// are returned as-is. Returns null when the URI can't be parsed or
/// the file is outside the FileProvider's exposed paths.
private fun shareableUri(context: android.content.Context, raw: String): Uri? {
    val parsed = runCatching { Uri.parse(raw) }.getOrNull() ?: return null
    return when (parsed.scheme) {
        "content" -> parsed
        "file"    -> {
            val path = parsed.path ?: return null
            val file = File(path)
            if (!file.exists()) return null
            val authority = "${context.packageName}.fileprovider"
            runCatching { FileProvider.getUriForFile(context, authority, file) }
                .getOrNull()
        }
        else -> null
    }
}

/**
 * Best-effort check used by the self-heal LaunchedEffect: does the
 * `pdf_uri` / `preview_uri` on the current row actually point at a
 * file that exists on this device? `null`/`""`/unparseable counts
 * as missing so the heal kicks. content:// URIs (rare here — only
 * for fresh-from-scanner captures the user hasn't fully saved) are
 * conservatively treated as present so we don't spuriously
 * re-download.
 */
private fun localFileExists(uri: String?): Boolean {
    if (uri.isNullOrBlank()) return false
    val parsed = runCatching { Uri.parse(uri) }.getOrNull() ?: return false
    return runCatching {
        when (parsed.scheme) {
            "file" -> parsed.path?.let { File(it).exists() } ?: false
            null   -> File(uri).exists()
            else   -> true
        }
    }.getOrDefault(false)
}

/**
 * Editable title row, sitting between the preview and the metadata
 * pills. Shows the persisted title when one is set; otherwise renders
 * an "Untitled scan" placeholder in muted ink so the empty state is
 * clearly an affordance, not a label. Whole row is clickable — tap
 * opens the title editor modal owned by [ScanDetailScreen].
 */
/**
 * Large display title at the top of the detail screen, matching the
 * mockup: prominent display title with an inline edit pencil,
 * followed by the breadcrumb row (date • pages • category).
 */
@Composable
private fun TitleHeader(
    capture: CaptureEntity,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    val displayed = capture.title?.takeIf { it.isNotBlank() }
    Column(
        modifier            = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2),
    ) {
        Row(
            modifier              = Modifier.clickable(onClick = onEdit),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2),
        ) {
            Text(
                text     = displayed ?: "Add a title",
                style    = type.display,
                color    = if (displayed != null) colors.ink else colors.accent,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                imageVector        = Icons.Outlined.Edit,
                contentDescription = "Edit title",
                tint               = colors.muted,
                modifier           = Modifier.size(22.dp),
            )
        }
        BreadcrumbRow(capture = capture)
    }
}

/**
 * Compact breadcrumb under the title — date, page count, and
 * category (when present) separated by middle dots, each prefixed
 * with a small icon for visual scanning.
 */
@Composable
private fun BreadcrumbRow(capture: CaptureEntity) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    Row(
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2),
    ) {
        BreadcrumbItem(
            icon = Icons.Outlined.CalendarToday,
            text = friendlyDate(capture.createdAt),
        )
        BreadcrumbDot()
        BreadcrumbItem(
            icon = Icons.Outlined.Description,
            text = "${capture.pageCount} page${if (capture.pageCount == 1) "" else "s"}",
        )
        // Pre-A.3c this row carried the legacy `captures.category`
        // breadcrumb. Post-drop the canonical per-capture primary
        // label lives in `capture_tags`; the DetailsCard / pill row
        // already surface it, so the breadcrumb no longer
        // duplicates that signal.
    }
}

@Composable
private fun BreadcrumbItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    Row(
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s1),
    ) {
        Icon(
            imageVector        = icon,
            contentDescription = null,
            tint               = colors.inkSoft,
            modifier           = Modifier.size(14.dp),
        )
        Text(text = text, style = type.meta, color = colors.inkSoft)
    }
}

@Composable
private fun BreadcrumbDot() {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    Text(text = "•", style = type.meta, color = colors.muted)
}

/**
 * Horizontal scrollable strip of page thumbnails — one chip per page,
 * with the currently selected page highlighted in the accent color.
 * Tap a chip to set [selectedPageIndex]. Only rendered for multi-page
 * captures.
 *
 * Renders the actual rasterised page bitmap when available
 * ([pageBitmaps] is populated by ScanDetailScreen's LaunchedEffect)
 * and falls through to a paper-toned placeholder + document icon
 * while the background render is in flight. Mirrors iOS
 * `pageThumbnail`.
 */
@Composable
private fun PageThumbnailsStrip(
    pageCount: Int,
    pageBitmaps: List<Bitmap>,
    selectedPageIndex: Int,
    onSelectPage: (Int) -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = QuickInkSpacing.s5),
        horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s3),
    ) {
        repeat(pageCount) { index ->
            val selected = (index == selectedPageIndex)
            val bitmap   = pageBitmaps.getOrNull(index)
            // Outer Box is NOT clipped — lets the page-number badge
            // sit fully visible in the bottom-right corner without
            // being eaten by the rounded-corner clip on the inner
            // image surface.
            Box(
                modifier = Modifier
                    .width(64.dp)
                    .height(80.dp)
                    .clickable { onSelectPage(index) },
            ) {
                // Inner clipped surface — image / fallback icon.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(QuickInkRadius.sm))
                        .background(colors.paper2)
                        .border(
                            width = if (selected) 2.dp else 1.dp,
                            color = if (selected) colors.accent else colors.border,
                            shape = RoundedCornerShape(QuickInkRadius.sm),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (bitmap != null) {
                        Image(
                            bitmap             = bitmap.asImageBitmap(),
                            contentDescription = "Page ${index + 1}",
                            contentScale       = ContentScale.Crop,
                            modifier           = Modifier.fillMaxSize(),
                        )
                    } else {
                        Icon(
                            imageVector        = Icons.Outlined.Description,
                            contentDescription = null,
                            tint               = colors.muted,
                            modifier           = Modifier.size(20.dp),
                        )
                    }
                }
                // Page-number badge bottom-right, sitting inside the
                // outer (un-clipped) Box so the rounded-corner clip
                // on the inner surface doesn't hide the corner where
                // the badge sits. Inset slightly with positive padding
                // so it reads as overlaid on the corner, not floating
                // off the chip.
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 4.dp, bottom = 4.dp)
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(if (selected) colors.accent else colors.surface)
                        .border(
                            width = 0.5.dp,
                            color = colors.border,
                            shape = CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text  = "${index + 1}",
                        style = type.caption,
                        color = if (selected) colors.textOnAccent else colors.ink,
                    )
                }
            }
        }
    }
}

/**
 * Structured details card matching the mockup — header + rows for
 * File type / Size / Created / Location / Tags. Each row is a
 * label-left / value-right pair; the Tags row swaps the value for an
 * inline category chip plus a "+" affordance.
 */

/**
 * Free-form notes card. Renders the current `captures.notes` value
 * preserving line breaks; falls back to an empty-state prompt when
 * the column is null/blank. The whole card is tappable — tap opens
 * the editor sheet. Voice-note transcripts also land in the same
 * column (the transcript editor appends), so this card surfaces
 * content from both surfaces.
 */
/**
 * Full-screen dialog wrapping a stock Android `VideoView` for
 * the hold-to-record Photo-mode clip. The VideoView's own
 * `MediaController` handles play / pause / scrub; we attach it
 * once on construction and seek to 0 + start so the clip auto-
 * plays on present.
 */
@Composable
private fun VideoPlayerDialog(videoUri: String, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties       = DialogProperties(
            // Full-bleed dialog with no inset chrome:
            //   - `usePlatformDefaultWidth = false` lets us go
            //     wider than the platform-default 280dp.
            //   - `decorFitsSystemWindows = false` (Android 11+)
            //     drops the system-bar inset that otherwise
            //     bracketed the video with a top + bottom gap.
            //     The close button overlay below uses
            //     `WindowInsets.systemBars` padding so it still
            //     clears the status bar / nav handle.
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows  = false,
            dismissOnBackPress      = true,
            dismissOnClickOutside   = false,
        ),
    ) {
        Box(
            modifier         = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory  = { ctx ->
                    VideoView(ctx).apply {
                        setVideoURI(Uri.parse(videoUri))
                        val controller = MediaController(ctx)
                        controller.setAnchorView(this)
                        setMediaController(controller)
                        setOnPreparedListener { mp -> mp.start() }
                    }
                },
            )
            // Top-right close affordance — the MediaController has
            // its own pause/play but no exit, so render a small
            // circular X over the player. `WindowInsets.systemBars`
            // padding clears the status bar / notch even though the
            // dialog itself draws edge-to-edge under it.
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .windowInsetsPadding(WindowInsets.systemBars)
                    .padding(QuickInkSpacing.s4)
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f))
                    .clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector       = Icons.Outlined.Close,
                    contentDescription = "Close video player",
                    tint              = Color.White,
                    modifier          = Modifier.size(18.dp),
                )
            }
        }
    }
}

/**
 * Placeholder card — shown on a receiver device whose row has
 * a `video_drive_file_id` set but whose local .mp4 hasn't been
 * downloaded yet. The `QuickInkBinarySync` restore pass fills
 * `video_uri` in on its next run; the card flips to the real
 * player automatically on the next re-render. Disabled tap to
 * make clear there's nothing to play yet.
 */
@Composable
private fun VideoPendingCard(modifier: Modifier = Modifier) {
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(QuickInkRadius.md))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(QuickInkRadius.md)),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier          = Modifier
                .fillMaxWidth()
                .background(colors.borderSoft)
                .padding(horizontal = QuickInkSpacing.s3, vertical = QuickInkSpacing.s2),
        ) {
            Icon(
                imageVector       = Icons.Filled.PlayCircle,
                contentDescription = null,
                tint              = colors.inkSoft,
                modifier          = Modifier.size(16.dp),
            )
            Spacer(Modifier.size(QuickInkSpacing.s2))
            Text(
                text       = "Video",
                fontSize   = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color      = colors.ink,
            )
            Spacer(Modifier.weight(1f))
        }
        Row(
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s3),
            modifier              = Modifier.padding(QuickInkSpacing.s3),
        ) {
            androidx.compose.material3.CircularProgressIndicator(
                color       = colors.accent,
                strokeWidth = 2.dp,
                modifier    = Modifier.size(24.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text       = "Downloading video…",
                    fontSize   = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color      = colors.ink,
                )
                Text(
                    text  = "Restoring from Drive — try again in a moment.",
                    style = type.caption,
                    color = colors.muted,
                )
            }
        }
    }
}


@Composable
private fun NotesCard(
    notes: String?,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    val trimmed = notes?.trim().orEmpty()
    val hasNotes = trimmed.isNotEmpty()

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(QuickInkRadius.md))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(QuickInkRadius.md))
            .clickable(onClick = onTap),
    ) {
        // Heading on a soft grey strip — matches Details / Voice notes.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier          = Modifier
                .fillMaxWidth()
                .background(colors.borderSoft)
                .padding(horizontal = QuickInkSpacing.s3, vertical = QuickInkSpacing.s2),
        ) {
            Icon(
                imageVector = Icons.Outlined.Edit,
                contentDescription = null,
                tint = colors.inkSoft,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.size(QuickInkSpacing.s2))
            Text(
                text = "Notes",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.ink,
            )
            Spacer(Modifier.weight(1f))
        }

        Column(
            modifier            = Modifier.padding(QuickInkSpacing.s3),
            verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2),
        ) {
        if (hasNotes) {
            Text(
                text = trimmed,
                fontSize = 11.sp,
                color = colors.ink,
                maxLines = 8,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Text(
                text = "Tap to add notes for this scan. Voice-note transcripts also land here.",
                style = type.caption,
                color = colors.muted,
            )
        }
        }
    }
}

@Composable
private fun DetailsCard(
    capture: CaptureEntity,
    primaryTagName: String?,
    pdfFileSize: Long?,
    onAddTag: () -> Unit,
    attachedLocationNames: List<String>,
    onAddLocation: () -> Unit,
    attachedPeople: List<app.quickink.mobile.data.person.PersonEntity>,
    onAddPerson: () -> Unit,
    onPersonChipTap: (app.quickink.mobile.data.person.PersonEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(QuickInkRadius.md))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(QuickInkRadius.md)),
    ) {
        // Heading sits on a soft grey strip spanning the card's
        // full inner width. Padding is local to the strip so the
        // detail rows below keep their existing inset.
        Row(
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2),
            modifier              = Modifier
                .fillMaxWidth()
                .background(colors.borderSoft)
                .padding(horizontal = QuickInkSpacing.s3, vertical = QuickInkSpacing.s2),
        ) {
            Icon(
                imageVector        = Icons.Outlined.Description,
                contentDescription = null,
                tint               = colors.inkSoft,
                modifier           = Modifier.size(16.dp),
            )
            Text(
                text  = "Details",
                style = type.cardTitle.copy(fontSize = 13.sp),
                color = colors.ink,
            )
        }

        Column(
            modifier            = Modifier.padding(QuickInkSpacing.s3),
            verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2),
        ) {
            DetailRow(label = "File type", value = fileTypeLabel(capture))
            DetailRow(
                label = "Size",
                value = pdfFileSize?.let { android.text.format.Formatter.formatFileSize(context, it) } ?: "—",
            )
            DetailRow(
                label      = "Folder",
                value      = primaryTagName ?: "Unsorted",
                valueColor = if (primaryTagName != null) colors.accent else colors.inkSoft,
            )
            // Address / Area / City rows render only when the reverse-
            // geocoded place name landed on the capture row. Captures
            // taken before Phase 7, with the location toggle off, with
            // the permission denied, or with a failed geocode lookup
            // all omit these rows. Raw coordinates without a place
            // name aren't surfaced — they'd read as opaque decimals.
            // Dedupe at render time so existing rows where the geocoder
            // fell back to the city for both fields don't show
            // identical Area + City rows.
            capture.address
                ?.takeIf { it.isNotBlank() }
                ?.let { DetailRow(label = "Address", value = it) }
            val (locOut, subOut) = LocationService.dedupePlaceNames(
                locality    = capture.locality,
                subLocality = capture.subLocality,
            )
            subOut
                ?.takeIf { it.isNotBlank() }
                ?.let { DetailRow(label = "Area", value = it) }
            locOut
                ?.takeIf { it.isNotBlank() }
                ?.let { DetailRow(label = "City", value = it) }
            TagsRow(primaryTagName = primaryTagName, onAddTag = onAddTag)
            LocationsRow(
                names         = attachedLocationNames,
                onAddLocation = onAddLocation,
            )
            PeopleRow(
                people          = attachedPeople,
                onAddPerson     = onAddPerson,
                onPersonChipTap = onPersonChipTap,
            )
        }
    }
}

@Composable
private fun DetailRow(
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color = LocalQuickInkColors.current.ink,
) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    // Bump up from the 10sp `caption` token to 13sp so the rows
    // read at body comfort. The card's a primary surface; the
    // caption size belongs on confidence badges, not on labelled
    // metadata the user actually reads.
    val rowStyle = type.caption.copy(fontSize = 11.sp)
    Row(
        modifier          = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text  = label,
            style = rowStyle,
            color = colors.inkSoft,
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text     = value,
            style    = rowStyle,
            color    = valueColor,
            maxLines = 2,
            textAlign = TextAlign.End,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun TagsRow(primaryTagName: String?, onAddTag: () -> Unit) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    val rowStyle = type.caption.copy(fontSize = 11.sp)
    Row(
        modifier          = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = "Tags", style = rowStyle, color = colors.inkSoft)
        Spacer(modifier = Modifier.weight(1f))
        Row(
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s1),
        ) {
            if (!primaryTagName.isNullOrEmpty()) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(QuickInkRadius.pill))
                        .background(colors.accentSoft)
                        .clickable(onClick = onAddTag)
                        .padding(horizontal = QuickInkSpacing.s2, vertical = 4.dp),
                ) {
                    Text(text = primaryTagName, style = rowStyle, color = colors.accent)
                }
            }
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(colors.borderSoft)
                    .clickable(onClick = onAddTag),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector        = Icons.Outlined.Add,
                    contentDescription = "Add tag",
                    tint               = colors.inkSoft,
                    modifier           = Modifier.size(14.dp),
                )
            }
        }
    }
}

/**
 * Read-only chip strip showing every person currently attached to
 * this capture, with a trailing "+" affordance that opens the
 * [PeoplePickerSheet]. Mirror of [LocationsRow].
 */
@Composable
private fun PeopleRow(
    people: List<app.quickink.mobile.data.person.PersonEntity>,
    onAddPerson: () -> Unit,
    onPersonChipTap: (app.quickink.mobile.data.person.PersonEntity) -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    val rowStyle = type.caption.copy(fontSize = 11.sp)
    Row(
        modifier          = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = "People", style = rowStyle, color = colors.inkSoft)
        Spacer(modifier = Modifier.weight(1f))
        Row(
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s1),
        ) {
            people.forEach { person ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(QuickInkRadius.pill))
                        .background(colors.accentSoft)
                        .clickable { onPersonChipTap(person) }
                        .padding(horizontal = QuickInkSpacing.s2, vertical = 4.dp),
                ) {
                    Text(text = person.name, style = rowStyle, color = colors.accent)
                }
            }
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(colors.borderSoft)
                    .clickable(onClick = onAddPerson),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector        = Icons.Outlined.Add,
                    contentDescription = "Add person",
                    tint               = colors.inkSoft,
                    modifier           = Modifier.size(14.dp),
                )
            }
        }
    }
}

/**
 * Read-only chip strip showing every location currently attached to
 * this capture, with a trailing "+" affordance that opens the
 * [LocationPickerSheet]. Mirror of [TagsRow]'s layout — label on the
 * left, chips + add button right-aligned. Falls back to just the "+"
 * button when no locations are attached yet.
 */
@Composable
private fun LocationsRow(
    names: List<String>,
    onAddLocation: () -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    val rowStyle = type.caption.copy(fontSize = 11.sp)
    Row(
        modifier          = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = "Places", style = rowStyle, color = colors.inkSoft)
        Spacer(modifier = Modifier.weight(1f))
        Row(
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s1),
        ) {
            names.forEach { name ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(QuickInkRadius.pill))
                        .background(colors.accentSoft)
                        .clickable(onClick = onAddLocation)
                        .padding(horizontal = QuickInkSpacing.s2, vertical = 4.dp),
                ) {
                    Text(text = name, style = rowStyle, color = colors.accent)
                }
            }
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(colors.borderSoft)
                    .clickable(onClick = onAddLocation),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector        = Icons.Outlined.Add,
                    contentDescription = "Add location",
                    tint               = colors.inkSoft,
                    modifier           = Modifier.size(14.dp),
                )
            }
        }
    }
}

/**
 * Quick-actions card matching the mockup: header + rows for Share
 * as Image, Export as PDF, Move to folder, Delete (plus a business-
 * card-only "Add to contact" row at the top). Each row is a full-
 * width tappable surface with an icon on the left.
 */
@Composable
private fun ActionsCard(
    capture: CaptureEntity,
    onShareAsImage: () -> Unit,
    onExportPdf: () -> Unit,
    onMoveToFolder: () -> Unit,
    onManageTags: () -> Unit,
    onDelete: () -> Unit,
    /**
     * True while the parent is rasterising the capture's pages for
     * the Share-as-Image flow. Swaps the row's label to "Preparing…"
     * and disables further taps so a double-tap doesn't queue a
     * second render.
     */
    isPreparingImageShare: Boolean = false,
    /**
     * Optional Add-to-contact action. Non-null only for Business
     * Card captures — the parent gates this so other categories
     * don't see the row. When set, renders as the first action row
     * (most-likely action for a card capture).
     */
    onAddToContact: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current

    // Gate the rows on what's actually available on disk: hide
    // "Share as Image" when there's nothing to rasterise, and hide
    // "Export as PDF" when the PDF file is missing. Matches the iOS
    // `canShareAsImage` / `shareablePdfURL` checks.
    val pdfPresent = capture.pdfUri.isNotBlank() && localFileExists(capture.pdfUri)
    val previewPresent = !capture.previewUri.isNullOrBlank() &&
        localFileExists(capture.previewUri)
    val canShareAsImage = pdfPresent || previewPresent
    val canExportPdf = pdfPresent

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(QuickInkRadius.md))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(QuickInkRadius.md))
            .padding(QuickInkSpacing.s3),
        verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2),
    ) {
        Row(
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2),
        ) {
            Icon(
                imageVector        = Icons.Outlined.GridView,
                contentDescription = null,
                tint               = colors.inkSoft,
                modifier           = Modifier.size(16.dp),
            )
            Text(
                text  = "Actions",
                style = type.cardTitle.copy(fontSize = 13.sp),
                color = colors.ink,
            )
        }

        Column(modifier = Modifier.fillMaxWidth()) {
            if (onAddToContact != null) {
                ActionRow(
                    icon    = Icons.Outlined.PersonAdd,
                    label   = "Add to contact",
                    onClick = onAddToContact,
                )
                ActionDivider()
            }
            if (canShareAsImage) {
                ActionRow(
                    icon    = Icons.Outlined.Image,
                    label   = if (isPreparingImageShare) "Preparing…" else "Share as Image",
                    onClick = onShareAsImage,
                    enabled = !isPreparingImageShare,
                )
                ActionDivider()
            }
            if (canExportPdf) {
                ActionRow(
                    icon    = Icons.Outlined.PictureAsPdf,
                    label   = "Export as PDF",
                    onClick = onExportPdf,
                )
                ActionDivider()
            }
            ActionRow(
                icon    = Icons.Outlined.Folder,
                label   = "Move to folder",
                onClick = onMoveToFolder,
            )
            ActionDivider()
            ActionRow(
                icon    = Icons.Outlined.LocalOffer,
                label   = "Manage tags",
                onClick = onManageTags,
            )
            ActionDivider()
            ActionRow(
                icon          = Icons.Outlined.Delete,
                label         = "Delete",
                onClick       = onDelete,
                isDestructive = true,
            )
        }
    }
}

@Composable
private fun ActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    isDestructive: Boolean = false,
    enabled: Boolean = true,
) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    val tint = if (isDestructive) colors.danger else colors.inkSoft
    val labelColor = if (isDestructive) colors.danger else colors.ink
    Row(
        modifier              = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = QuickInkSpacing.s2),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2),
    ) {
        Box(modifier = Modifier.width(22.dp), contentAlignment = Alignment.Center) {
            Icon(
                imageVector        = icon,
                contentDescription = null,
                tint               = tint,
                modifier           = Modifier.size(16.dp),
            )
        }
        Text(
            text     = label,
            style    = type.caption,
            color    = labelColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ActionDivider() {
    val colors = LocalQuickInkColors.current
    HorizontalDivider(
        thickness = 1.dp,
        color     = colors.borderSoft,
    )
}

/**
 * Placeholder column shown while the capture row is loading. Mirrors
 * the resolved layout (preview slab + title row + breadcrumb) so the
 * screen doesn't visually jump when data lands.
 */
@Composable
private fun LoadingSkeleton(modifier: Modifier = Modifier) {
    val colors = LocalQuickInkColors.current
    Column(
        modifier            = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s4),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(320.dp)
                .clip(RoundedCornerShape(QuickInkRadius.md))
                .background(colors.borderSoft),
        )
        Box(
            modifier = Modifier
                .width(240.dp)
                .height(32.dp)
                .clip(RoundedCornerShape(QuickInkRadius.sm))
                .background(colors.borderSoft),
        )
        Box(
            modifier = Modifier
                .width(180.dp)
                .height(16.dp)
                .clip(RoundedCornerShape(QuickInkRadius.sm))
                .background(colors.borderSoft),
        )
    }
}

/**
 * File-type label for the [DetailsCard] row. Every capture
 * produces a PDF via `buildImportArtifacts` regardless of how
 * the bytes got in, so just gating on `pdfUri` would always
 * read "PDF document" — even for in-app photos / videos /
 * gallery imports. Mirror HomeScreen's `sourceChipInfo` logic
 * by branching on `source` (+ the presence of a video URI) so
 * the label reads "Photo", "Video", "Image" or "PDF document"
 * to match how the row is presented elsewhere in the app.
 */
private fun fileTypeLabel(capture: CaptureEntity): String {
    val isPhotoSource  = capture.source == "photo"
    val isImportSource = capture.source == "import"
    val isVideo        = isPhotoSource && (
        !capture.videoUri.isNullOrBlank() ||
            !capture.videoDriveFileId.isNullOrBlank()
    )
    return when {
        isVideo                                                                    -> "Video"
        isPhotoSource                                                              -> "Photo"
        isImportSource                                                             -> "Image"
        capture.pdfUri.isNotBlank() && localFileExists(capture.pdfUri)             -> "PDF document"
        !capture.previewUri.isNullOrBlank() && localFileExists(capture.previewUri) -> "Image"
        else                                                                       -> "Document"
    }
}

/**
 * Resolve the PDF's on-disk size for the [DetailsCard] Size row.
 * Best-effort — returns null when the URI is missing/unparseable or
 * the file isn't readable. Run on Dispatchers.IO; safe to call from a
 * suspend block.
 */
private fun resolvePdfFileSize(rawUri: String?): Long? {
    if (rawUri.isNullOrBlank()) return null
    val parsed = runCatching { Uri.parse(rawUri) }.getOrNull() ?: return null
    val file = when (parsed.scheme) {
        "file" -> parsed.path?.let(::File)
        null   -> File(rawUri)
        else   -> null
    } ?: return null
    return runCatching { file.length().takeIf { it > 0 } }.getOrNull()
}

/**
 * Bottom-sheet retag picker. Shows one row per active category
 * (with the current selection check-marked + accent-tinted) plus a
 * "Remove tag" row when the capture already has one. Selecting any
 * row calls back through [onPick] with the chosen category name
 * (or `null` for "Remove tag"). Cancelled by scrim tap, drag-down,
 * or back-press.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RetagSheet(
    categories: List<String>,
    current: String?,
    onDismiss: () -> Unit,
    onPick: (String?) -> Unit,
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
                text  = "Tag scan as",
                style = type.heading,
                color = colors.ink,
            )
            Spacer(Modifier.size(QuickInkSpacing.s1))
            categories.forEach { name ->
                val selected = name == current
                RetagRow(
                    label    = name,
                    selected = selected,
                    onClick  = { onPick(name) },
                )
            }
            if (!current.isNullOrEmpty()) {
                Spacer(Modifier.size(QuickInkSpacing.s1))
                Text(
                    text      = "Remove tag",
                    style     = type.label,
                    color     = colors.danger,
                    textAlign = TextAlign.Center,
                    modifier  = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(QuickInkRadius.md))
                        .clickable { onPick(null) }
                        .padding(vertical = QuickInkSpacing.s3),
                )
            }
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
private fun RetagRow(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(QuickInkRadius.md))
            .background(if (selected) colors.accentSoft else colors.borderSoft)
            .clickable(onClick = onClick)
            .padding(horizontal = QuickInkSpacing.s4, vertical = QuickInkSpacing.s3),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s3),
    ) {
        Icon(
            imageVector       = if (selected) Icons.Outlined.Check else Icons.Outlined.LocalOffer,
            contentDescription = null,
            tint              = if (selected) colors.accent else colors.inkSoft,
            modifier          = Modifier.size(18.dp),
        )
        Text(
            text     = label,
            style    = type.body,
            color    = if (selected) colors.accent else colors.ink,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Polished dropdown menu for the per-capture actions (Add to
 * contact / Share as Image / Export as PDF / Move to folder /
 * Manage tags / Delete). Each row has a leading icon for fast
 * recognition and Delete is rendered in the destructive role so
 * it reads as the highest-risk action. Sectioned with a divider
 * between the non-destructive group and Delete.
 *
 * Anchored to the more-actions chip in the preview's TopEnd Row.
 */
@Composable
private fun ScanActionsDropdown(
    expanded: Boolean,
    onDismiss: () -> Unit,
    isBusinessCard: Boolean,
    /**
     * Video captures (hold-to-record from Photo mode) get a
     * single "Share video" action in place of "Share as Image"
     * + "Export as PDF" — the PDF/image pair makes no sense for
     * a clip whose only useful artifact is the `.mp4` itself.
     */
    isVideo: Boolean,
    isPreparingImageShare: Boolean,
    onAddToContact: () -> Unit,
    onShareAsImage: () -> Unit,
    onShareVideo: () -> Unit,
    onExportPdf: () -> Unit,
    onMoveToFolder: () -> Unit,
    onManageTags: () -> Unit,
    onManageLocations: () -> Unit,
    onManagePeople: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = LocalQuickInkColors.current
    androidx.compose.material3.DropdownMenu(
        expanded         = expanded,
        onDismissRequest = onDismiss,
        modifier         = Modifier.background(colors.surface),
    ) {
        if (isBusinessCard) {
            ScanActionRow(
                label   = "Add to contact",
                icon    = androidx.compose.material.icons.Icons.Outlined.PersonAdd,
                onClick = onAddToContact,
            )
            ScanActionDivider()
        }
        if (isVideo) {
            ScanActionRow(
                label   = "Share video",
                icon    = androidx.compose.material.icons.Icons.Outlined.VideoLibrary,
                onClick = onShareVideo,
            )
            ScanActionDivider()
        } else {
            ScanActionRow(
                label   = if (isPreparingImageShare) "Preparing…" else "Share as Image",
                icon    = androidx.compose.material.icons.Icons.Outlined.Image,
                enabled = !isPreparingImageShare,
                onClick = onShareAsImage,
            )
            ScanActionDivider()
            ScanActionRow(
                label   = "Export as PDF",
                icon    = androidx.compose.material.icons.Icons.Outlined.PictureAsPdf,
                onClick = onExportPdf,
            )
            ScanActionDivider()
        }
        ScanActionRow(
            label   = "Move to folder",
            icon    = androidx.compose.material.icons.Icons.Outlined.Folder,
            onClick = onMoveToFolder,
        )
        ScanActionDivider()
        ScanActionRow(
            label   = "Manage tags",
            icon    = androidx.compose.material.icons.Icons.Outlined.LocalOffer,
            onClick = onManageTags,
        )
        ScanActionDivider()
        ScanActionRow(
            label   = "Manage locations",
            icon    = androidx.compose.material.icons.Icons.Outlined.LocationOn,
            onClick = onManageLocations,
        )
        ScanActionDivider()
        ScanActionRow(
            label   = "Manage people",
            icon    = androidx.compose.material.icons.Icons.Outlined.Person,
            onClick = onManagePeople,
        )
        ScanActionDivider()
        ScanActionRow(
            label       = "Delete",
            icon        = androidx.compose.material.icons.Icons.Outlined.Delete,
            destructive = true,
            onClick     = onDelete,
        )
    }
}

/**
 * Compact dropdown row — leading icon tinted with the accent (or
 * danger for destructive), Releaf-style. Bypasses
 * [DropdownMenuItem] because its 48dp `minHeight` is hard-coded;
 * we go a notch denser so the menu trims to ~75% of the default
 * Material3 row footprint (icon 18dp, label ~11sp, 6dp vertical
 * padding ≈ 32dp row height).
 */
@Composable
private fun ScanActionRow(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    enabled: Boolean = true,
    destructive: Boolean = false,
) {
    val colors = LocalQuickInkColors.current
    val iconTint = if (destructive) colors.danger
        else if (enabled) colors.accent
        else colors.muted
    val textColor = if (destructive) colors.danger
        else if (enabled) colors.ink
        else colors.muted
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        androidx.compose.material3.Icon(
            imageVector       = icon,
            contentDescription = null,
            tint              = iconTint,
            modifier          = Modifier.size(18.dp),
        )
        androidx.compose.material3.Text(
            text     = label,
            color    = textColor,
            fontSize = 11.sp,
        )
    }
}

/**
 * 1dp inline divider between adjacent dropdown rows. Padded
 * horizontally so it doesn't run edge-to-edge with the menu
 * border — mirrors Releaf's `LeafDropdownDivider`.
 */
@Composable
private fun ScanActionDivider() {
    val colors = LocalQuickInkColors.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = QuickInkSpacing.s3)
            .height(1.dp)
            .background(colors.borderSoft),
    )
}

private fun friendlyDate(iso: String): String =
    try {
        val instant = Instant.parse(iso)
        val zoned   = instant.atZone(ZoneId.systemDefault())
        zoned.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT))
    } catch (_: Exception) {
        iso
    }
