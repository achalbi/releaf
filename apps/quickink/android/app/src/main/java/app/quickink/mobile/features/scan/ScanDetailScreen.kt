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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.LocalOffer
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.PictureAsPdf
import app.quickink.mobile.features.scan.businesscard.AddContactReviewSheet
import app.quickink.mobile.features.scan.businesscard.launchAddContactIntent
import app.quickink.mobile.features.scan.businesscard.runBusinessCardExtraction
import app.releaf.shared.scan.businesscard.ExtractedContact
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.quickink.mobile.QuickInkApp
import app.quickink.mobile.data.capture.CaptureEntity
import app.quickink.mobile.data.sync.QuickInkBinarySync
import app.quickink.mobile.features.nav.NavTab
import app.quickink.mobile.features.nav.QuickInkBottomNavBar
import app.quickink.mobile.features.nav.QuickInkBottomNavReservedHeight
import app.quickink.mobile.ui.theme.LocalQuickInkColors
import app.quickink.mobile.ui.theme.LocalQuickInkTypography
import app.quickink.mobile.ui.theme.QuickInkRadius
import app.quickink.mobile.ui.theme.QuickInkSpacing
import app.quickink.mobile.ui.theme.quickInkDotGridBackground
import androidx.core.content.FileProvider
import app.releaf.mobile.auth.AuthState
import app.quickink.mobile.features.workspace.FolderPickerSheet
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
    // Bottom-nav callbacks. Optional so we keep the legacy "navigate
    // to detail and only allow back" path working from places that
    // don't host a tab bar. When all five are supplied, the floating
    // QuickInkBottomNavBar renders below the content.
    onHome: (() -> Unit)? = null,
    onWorkspace: (() -> Unit)? = null,
    onScan: (() -> Unit)? = null,
    onSearch: (() -> Unit)? = null,
    onSettings: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val app = context.applicationContext as QuickInkApp
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    val scope = rememberCoroutineScope()

    val captureDao = remember(app) { app.database.captureDao() }
    val ocrDao = remember(app) { app.database.ocrResultDao() }
    val tagDao = remember(app) { app.database.tagDao() }
    val folderDao = remember(app) { app.database.folderDao() }

    // Workspace v1 folder picker — opens when the Actions card's
    // "Move to folder" row is tapped. Sheet observes the folder list
    // and writes via [CaptureDao.setFolder] on pick.
    var showFolderPicker by remember(captureId) { mutableStateOf(false) }

    // Workspace v1 tag picker — Manage tags row opens it. Manual
    // entry only in Phase C.2; AI-suggested chips ship in Phase E.
    var showTagPicker by remember(captureId) { mutableStateOf(false) }
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
    // Fullscreen viewer toggle. Set true by the fullscreen button on
    // the inline preview; cleared by the dialog's close affordance or
    // the back press.
    var showFullscreenViewer by remember(captureId) { mutableStateOf(false) }
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

    // Live category list — populated from the same DAO the home
    // grid + review screen read, scoped to the current user. The
    // sheet uses this for its picker rows.
    val categories by remember(userId, tagDao) {
        tagDao.observeActive(userId)
    }.collectAsState(initial = emptyList())

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
                    driveClient        = app.driveClient,
                )
                binarySync.restorePending(row.userId, accessToken)
            }
        }
        // Refresh the local copy so the UI sees the rewritten URIs.
        capture = captureDao.findById(captureId)
    }

    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val hasBottomNav = onHome != null && onWorkspace != null && onScan != null &&
        onSearch != null && onSettings != null

    Box(
        modifier = Modifier
            .fillMaxSize()
            .quickInkDotGridBackground(),
    ) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = statusBarTop + QuickInkSpacing.s4),
    ) {

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
                .verticalScroll(rememberScrollState())
                .padding(
                    top    = QuickInkSpacing.s4,
                    bottom = if (hasBottomNav) QuickInkBottomNavReservedHeight else QuickInkSpacing.s8,
                ),
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
                // strip and the swipeable pager in sync (tap a chip to
                // jump; swipe the pager to advance the chip).
                PreviewImage(
                    capture             = current,
                    onFullscreenClick   = { showFullscreenViewer = true },
                    currentPage         = selectedPageIndex,
                    onCurrentPageChange = { selectedPageIndex = it },
                    modifier            = Modifier.padding(horizontal = QuickInkSpacing.s5),
                )

                // Page thumbnails strip (multi-page only)
                if (current.pageCount > 1) {
                    PageThumbnailsStrip(
                        pageCount         = current.pageCount,
                        pageBitmaps       = pageBitmaps,
                        selectedPageIndex = selectedPageIndex,
                        onSelectPage      = { selectedPageIndex = it },
                    )
                }

                // Details + Actions cards — side by side, matching the
                // Drive-style mockup. Both cards stretch to equal width
                // via Modifier.weight(1f); on narrow screens the rows
                // inside each card wrap rather than overflow.
                Row(
                    modifier              = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = QuickInkSpacing.s5),
                    horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s3),
                ) {
                    DetailsCard(
                        capture     = current,
                        pdfFileSize = pdfFileSize,
                        onAddTag    = { showRetagSheet = true },
                        modifier    = Modifier.weight(1f),
                    )
                    val isBusinessCard = current.category
                        ?.equals("Business Card", ignoreCase = true) == true
                    ActionsCard(
                        capture               = current,
                        onShareAsImage        = {
                            if (!isPreparingImageShare) {
                                scope.launch {
                                    isPreparingImageShare = true
                                    try {
                                        shareAsImage(
                                            context    = context,
                                            pdfUri     = current.pdfUri,
                                            previewUri = current.previewUri,
                                        )
                                    } finally {
                                        isPreparingImageShare = false
                                    }
                                }
                            }
                        },
                        isPreparingImageShare = isPreparingImageShare,
                        onExportPdf           = {
                            exportAsPdf(context, current.pdfUri)
                        },
                        onMoveToFolder        = { showFolderPicker = true },
                        onManageTags          = { showTagPicker = true },
                        onDelete              = { showDeleteConfirm = true },
                        // Business-card-only Add-to-contact row.
                        // Runs the full bbox-aware extraction
                        // pipeline over the capture's stored OCR
                        // blocks, then opens an editable review
                        // sheet so the user can fix any
                        // mis-classifications before the final
                        // contact intent fires.
                        onAddToContact        = if (isBusinessCard) {
                            {
                                scope.launch {
                                    businessCardExtraction = runCatching {
                                        runBusinessCardExtraction(captureId, ocrDao)
                                    }.getOrDefault(ExtractedContact.empty)
                                }
                            }
                        } else null,
                        modifier              = Modifier.weight(1f),
                    )
                }
            }
        }
    } // end inner Column

    // Floating bottom nav, anchored to the bottom of the surrounding
    // Box. Mirrors the iOS `safeAreaInset(.bottom)` layer — the
    // ScrollView above reserves QuickInkBottomNavReservedHeight at
    // the bottom of its content padding so the last card isn't hidden
    // behind the bar.
    if (onHome != null && onWorkspace != null && onScan != null &&
        onSearch != null && onSettings != null) {
        QuickInkBottomNavBar(
            activeTab  = NavTab.None,
            onHome     = onHome,
            onWorkspace  = onWorkspace,
            onScan     = onScan,
            onSearch   = onSearch,
            onSettings = onSettings,
            modifier   = Modifier.align(Alignment.BottomCenter),
        )
    }
    } // end outer Box

    // Retag bottom sheet — tapping the category pill (or the
    // untagged "Tag scan" affordance) opens this. One row per
    // active category plus a "Remove tag" row when the capture
    // already has one. Each row calls into [retagCapture] which
    // persists via `CaptureDao.setCategory(...)` and refreshes
    // the in-screen `capture` state so the pill flips
    // immediately.
    if (showRetagSheet) {
        RetagSheet(
            categories = categories.map { it.name },
            current    = capture?.category,
            onDismiss  = { showRetagSheet = false },
            onPick     = { name ->
                showRetagSheet = false
                scope.launch {
                    try {
                        captureDao.setCategory(captureId, name, IsoClock.nowIso())
                        capture = captureDao.findById(captureId)
                    } catch (_: Exception) { /* best-effort */ }
                }
            },
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
                contentDescription = capture.category ?: "Scan preview",
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
        if (!capture.category.isNullOrEmpty()) {
            BreadcrumbDot()
            BreadcrumbItem(
                icon = Icons.Outlined.Folder,
                text = capture.category!!,
            )
        }
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
@Composable
private fun DetailsCard(
    capture: CaptureEntity,
    pdfFileSize: Long?,
    onAddTag: () -> Unit,
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
            .border(1.dp, colors.border, RoundedCornerShape(QuickInkRadius.md))
            .padding(QuickInkSpacing.s3),
        verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2),
    ) {
        Row(
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2),
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

        DetailRow(label = "File type", value = fileTypeLabel(capture))
        DetailRow(
            label = "Size",
            value = pdfFileSize?.let { android.text.format.Formatter.formatFileSize(context, it) } ?: "—",
        )
        DetailRow(
            label      = "Folder",
            value      = capture.category ?: "Unsorted",
            valueColor = if (capture.category != null) colors.accent else colors.inkSoft,
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
        TagsRow(category = capture.category, onAddTag = onAddTag)
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
    Row(
        modifier          = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text  = label,
            style = type.caption,
            color = colors.inkSoft,
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text     = value,
            style    = type.caption,
            color    = valueColor,
            maxLines = 2,
            textAlign = TextAlign.End,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun TagsRow(category: String?, onAddTag: () -> Unit) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    Row(
        modifier          = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = "Tags", style = type.caption, color = colors.inkSoft)
        Spacer(modifier = Modifier.weight(1f))
        Row(
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s1),
        ) {
            if (!category.isNullOrEmpty()) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(QuickInkRadius.pill))
                        .background(colors.accentSoft)
                        .clickable(onClick = onAddTag)
                        .padding(horizontal = QuickInkSpacing.s2, vertical = 4.dp),
                ) {
                    Text(text = category, style = type.caption, color = colors.accent)
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
 * File-type label for the [DetailsCard] row. PDF on disk reads as
 * "PDF document"; preview-only captures read as "Image"; the missing-
 * file fallback reads as "Document".
 */
private fun fileTypeLabel(capture: CaptureEntity): String =
    when {
        capture.pdfUri.isNotBlank() && localFileExists(capture.pdfUri)            -> "PDF document"
        !capture.previewUri.isNullOrBlank() && localFileExists(capture.previewUri) -> "Image"
        else                                                                       -> "Document"
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


private fun friendlyDate(iso: String): String =
    try {
        val instant = Instant.parse(iso)
        val zoned   = instant.atZone(ZoneId.systemDefault())
        zoned.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT))
    } catch (_: Exception) {
        iso
    }
