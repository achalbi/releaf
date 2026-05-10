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
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.quickink.mobile.QuickInkApp
import app.quickink.mobile.data.capture.CaptureEntity
import app.quickink.mobile.data.ocr.OcrResultDao
import app.quickink.mobile.data.ocr.OcrResultEntity
import app.quickink.mobile.data.sync.QuickInkBinarySync
import app.quickink.mobile.ui.theme.LocalQuickInkColors
import app.quickink.mobile.ui.theme.LocalQuickInkTypography
import app.quickink.mobile.ui.theme.QuickInkRadius
import app.quickink.mobile.ui.theme.QuickInkSpacing
import app.quickink.mobile.ui.theme.quickInkDotGridBackground
import androidx.core.content.FileProvider
import app.releaf.mobile.auth.AuthState
import app.releaf.mobile.data.common.IsoClock
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
    val categoryDao = remember(app) { app.database.categoryDao() }

    var capture by remember(captureId) { mutableStateOf<CaptureEntity?>(null) }
    var showOcr by remember(captureId) { mutableStateOf(false) }
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
    // More-menu dropdown anchor state. Driven by the ellipsis button
    // in the top bar; opens a small menu with secondary actions
    // (move to folder, delete) so the bar itself stays uncluttered.
    var showMoreMenu by remember { mutableStateOf(false) }
    // Selected page index for the thumbnails strip (0-based). Visual-
    // only highlight today; tap-to-jump is a follow-up that requires
    // surfacing a `currentPage` state through PageTurnPdfView.
    var selectedPageIndex by remember(captureId) { mutableStateOf(0) }
    // On-disk size of the capture's PDF in bytes, loaded lazily so
    // the Details row can render "2.4 MB" etc. Null until resolved.
    var pdfFileSize by remember(captureId) { mutableStateOf<Long?>(null) }

    // Live category list — populated from the same DAO the home
    // grid + review screen read, scoped to the current user. The
    // sheet uses this for its picker rows.
    val categories by remember(userId, categoryDao) {
        categoryDao.observeActive(userId)
    }.collectAsState(initial = emptyList())

    LaunchedEffect(captureId) {
        capture = captureDao.findById(captureId)
    }

    // Resolve the on-disk PDF size after the capture row lands.
    // Best-effort — leaves `pdfFileSize = null` if the file isn't
    // readable, in which case the Details row falls back to "—".
    LaunchedEffect(capture?.pdfUri) {
        pdfFileSize = withContext(Dispatchers.IO) {
            resolvePdfFileSize(capture?.pdfUri)
        }
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

    // OCR is loaded only when the user first expands the section.
    // The Flow re-collection cost is tiny (one query against a
    // capture-id-scoped index) so we just keep it live for the
    // remainder of the screen's lifetime instead of trying to
    // detach it after the first emission.
    val ocrPages by remember(captureId, showOcr) {
        if (showOcr) ocrDao.observeForCapture(captureId)
        else kotlinx.coroutines.flow.flowOf(emptyList())
    }.collectAsState(initial = emptyList())
    val ocrLoaded = showOcr && ocrPages.isNotEmpty()

    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .quickInkDotGridBackground()
            .padding(top = statusBarTop + QuickInkSpacing.s4),
    ) {
        // Top bar — circular floating buttons (back, share, more)
        // matching the mockup's pill style. Title moves into the
        // body's title header, freeing the top bar for action chips.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = QuickInkSpacing.s5, vertical = QuickInkSpacing.s2),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2),
        ) {
            CircularTopBarButton(
                icon            = Icons.AutoMirrored.Filled.ArrowBack,
                contentDesc     = "Back to library",
                onClick         = onBack,
            )

            Spacer(modifier = Modifier.weight(1f))

            if (capture != null) {
                CircularTopBarButton(
                    icon        = Icons.Filled.Share,
                    contentDesc = "Share scan",
                    onClick     = {
                        sharePdf(context, capture?.pdfUri, capture?.previewUri)
                    },
                )
            }

            // More menu — anchored to the ellipsis button. Opens a
            // small dropdown with secondary actions (move to folder,
            // delete) so the bar stays uncluttered.
            Box {
                CircularTopBarButton(
                    icon        = Icons.Filled.MoreHoriz,
                    contentDesc = "More options",
                    onClick     = { showMoreMenu = true },
                )
                DropdownMenu(
                    expanded         = showMoreMenu,
                    onDismissRequest = { showMoreMenu = false },
                ) {
                    DropdownMenuItem(
                        text    = { Text("Move to folder", style = type.body, color = colors.ink) },
                        onClick = {
                            showMoreMenu = false
                            showRetagSheet = true
                        },
                        leadingIcon = {
                            Icon(
                                imageVector        = Icons.Filled.Folder,
                                contentDescription = null,
                                tint               = colors.inkSoft,
                            )
                        },
                    )
                    DropdownMenuItem(
                        text    = { Text("Delete scan", style = type.body, color = colors.danger) },
                        onClick = {
                            showMoreMenu = false
                            showDeleteConfirm = true
                        },
                        leadingIcon = {
                            Icon(
                                imageVector        = Icons.Filled.Delete,
                                contentDescription = null,
                                tint               = colors.danger,
                            )
                        },
                    )
                }
            }
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
                .verticalScroll(rememberScrollState())
                .padding(top = QuickInkSpacing.s4, bottom = QuickInkSpacing.s8),
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

                // Preview block — full-bleed within margins
                PreviewImage(
                    capture           = current,
                    onFullscreenClick = { showFullscreenViewer = true },
                    modifier          = Modifier.padding(horizontal = QuickInkSpacing.s5),
                )

                // Page thumbnails strip (multi-page only)
                if (current.pageCount > 1) {
                    PageThumbnailsStrip(
                        pageCount         = current.pageCount,
                        selectedPageIndex = selectedPageIndex,
                        onSelectPage      = { selectedPageIndex = it },
                    )
                }

                // Details card — File type, Size, Created, Location, Tags
                DetailsCard(
                    capture     = current,
                    pdfFileSize = pdfFileSize,
                    onAddTag    = { showRetagSheet = true },
                    modifier    = Modifier.padding(horizontal = QuickInkSpacing.s5),
                )

                // Actions card — Export PDF, Share, Move to folder, Delete
                ActionsCard(
                    capture        = current,
                    onShare        = {
                        sharePdf(context, current.pdfUri, current.previewUri)
                    },
                    onMoveToFolder = { showRetagSheet = true },
                    onDelete       = { showDeleteConfirm = true },
                    modifier       = Modifier.padding(horizontal = QuickInkSpacing.s5),
                )

                // Existing collapsible OCR section
                OcrSection(
                    showOcr   = showOcr,
                    isLoading = showOcr && !ocrLoaded,
                    ocrPages  = ocrPages,
                    ocrDao    = ocrDao,
                    onToggle  = { showOcr = !showOcr },
                    modifier  = Modifier.padding(horizontal = QuickInkSpacing.s5),
                )
            }
        }
    }

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
            // Multi-page captures get the swipe + page-turn viewer;
            // single-page captures keep the scrollable PdfPagesView
            // since it already handles pinch-to-zoom and there's
            // nothing to swipe to anyway.
            if (capture.pageCount > 1) {
                PageTurnPdfView(
                    pdfUri            = pdfUri!!,
                    onFullscreenClick = onFullscreenClick,
                    modifier = modifier
                        .fillMaxWidth()
                        .aspectRatio(0.707f) // A4-ish portrait until pages render
                        .clip(RoundedCornerShape(QuickInkRadius.md))
                        .background(colors.surface)
                        .border(1.dp, colors.border, RoundedCornerShape(QuickInkRadius.md)),
                )
            } else {
                PdfPagesView(
                    pdfUri            = pdfUri,
                    onFullscreenClick = onFullscreenClick,
                    modifier = modifier
                        .fillMaxWidth()
                        .border(1.dp, colors.border, RoundedCornerShape(QuickInkRadius.md)),
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
                            imageVector       = Icons.Filled.Description,
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
 * Hand the capture's content off to the system share sheet. Tries
 * the PDF first (richest result); falls back to the preview JPEG if
 * the PDF URI is missing or unshareable.
 *
 * URI handling matters here: after sync (or a Drive restore) the
 * `pdf_uri` / `preview_uri` rows are `file://` URIs rooted at
 * AttachmentStorage's directory (`<filesDir>/quickink/attachments/`
 * once QuickInkApp.onCreate's `appFolderName` override has run, with
 * historic rows migrated in by `migrateLegacyAttachmentsFolder`).
 * Modern Android forbids forwarding `file://` URIs from app-private
 * storage to other apps — the chooser opens but the receiving app
 * can't read the bytes (FLAG_GRANT_READ only takes effect on
 * `content://` URIs). So we wrap any `file://` URI through our
 * FileProvider to get a content:// URI that does carry a usable
 * grant. Fresh captures from ML Kit's scanner already arrive as
 * content:// URIs; those are forwarded as-is.
 *
 * Failures surface via Toast so the user knows the tap registered —
 * silent failure was reported as "share button not wired".
 */
private fun sharePdf(
    context: android.content.Context,
    pdfUri: String?,
    previewUri: String?,
) {
    val candidates = listOfNotNull(
        pdfUri?.takeIf { it.isNotBlank() }     to "application/pdf",
        previewUri?.takeIf { it.isNotBlank() } to "image/jpeg",
    ).mapNotNull { (uri, type) -> uri?.let { it to type } }

    if (candidates.isEmpty()) {
        android.widget.Toast.makeText(
            context, "Nothing to share for this scan", android.widget.Toast.LENGTH_SHORT
        ).show()
        return
    }

    for ((rawUri, mime) in candidates) {
        val shareUri = shareableUri(context, rawUri) ?: continue
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, shareUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // Without `clipData`, the grant flag isn't honored on
            // some receivers (notably anything routed through a
            // chooser target on API 24+). Mirroring EXTRA_STREAM into
            // clipData is the documented workaround.
            clipData = android.content.ClipData.newRawUri(null, shareUri)
        }
        try {
            context.startActivity(Intent.createChooser(intent, "Share scan"))
            return // success
        } catch (_: Exception) {
            // Try the next candidate (e.g. PDF rejected → fall to JPEG).
        }
    }

    android.widget.Toast.makeText(
        context, "Couldn't open the share sheet for this scan",
        android.widget.Toast.LENGTH_SHORT,
    ).show()
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
 * Circular top-bar action button. Matches iOS's floating-pill style —
 * white surface with a soft border, 40dp hit target. Used for back,
 * share, and the more-menu trigger so the bar reads as a floating
 * action layer rather than a flat row.
 */
@Composable
private fun CircularTopBarButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDesc: String,
    onClick: () -> Unit,
) {
    val colors = LocalQuickInkColors.current
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(colors.surface)
            .border(1.dp, colors.border, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector        = icon,
            contentDescription = contentDesc,
            tint               = colors.ink,
            modifier           = Modifier.size(18.dp),
        )
    }
}

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
                imageVector        = Icons.Filled.Edit,
                contentDescription = "Edit title",
                tint               = colors.muted,
                modifier           = Modifier.size(18.dp),
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
            icon = Icons.Filled.CalendarToday,
            text = friendlyDate(capture.createdAt),
        )
        BreadcrumbDot()
        BreadcrumbItem(
            icon = Icons.Filled.Description,
            text = "${capture.pageCount} page${if (capture.pageCount == 1) "" else "s"}",
        )
        if (!capture.category.isNullOrEmpty()) {
            BreadcrumbDot()
            BreadcrumbItem(
                icon = Icons.Filled.Folder,
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
            modifier           = Modifier.size(11.dp),
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
 * Horizontal scrollable strip of page thumbnails — one numbered chip
 * per page, with the currently selected page highlighted in the
 * accent color. Tap a chip to set [selectedPageIndex]. Only rendered
 * for multi-page captures.
 *
 * Today the chips are paper-toned placeholders with page numbers.
 * Rendering actual page bitmaps is a follow-up that needs the PDF
 * rasteriser surfaced from PdfPagesView.
 */
@Composable
private fun PageThumbnailsStrip(
    pageCount: Int,
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
            Box(
                modifier = Modifier
                    .width(64.dp)
                    .height(80.dp)
                    .clip(RoundedCornerShape(QuickInkRadius.sm))
                    .background(colors.paper2)
                    .border(
                        width = if (selected) 2.dp else 1.dp,
                        color = if (selected) colors.accent else colors.border,
                        shape = RoundedCornerShape(QuickInkRadius.sm),
                    )
                    .clickable { onSelectPage(index) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector        = Icons.Filled.Description,
                    contentDescription = null,
                    tint               = colors.muted,
                    modifier           = Modifier.size(20.dp),
                )
                // Page-number badge bottom-right
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = (-6).dp, bottom = (-6).dp)
                        .size(22.dp)
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
            .padding(QuickInkSpacing.s4),
        verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s3),
    ) {
        Row(
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2),
        ) {
            Icon(
                imageVector        = Icons.Filled.Description,
                contentDescription = null,
                tint               = colors.inkSoft,
                modifier           = Modifier.size(14.dp),
            )
            Text(text = "Details", style = type.heading, color = colors.ink)
        }

        DetailRow(label = "File type", value = fileTypeLabel(capture))
        DetailRow(
            label = "Size",
            value = pdfFileSize?.let { android.text.format.Formatter.formatFileSize(context, it) } ?: "—",
        )
        DetailRow(label = "Created", value = friendlyDate(capture.createdAt))
        DetailRow(
            label      = "Location",
            value      = capture.category ?: "Unsorted",
            valueColor = if (capture.category != null) colors.accent else colors.inkSoft,
        )
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
            style = type.meta,
            color = colors.inkSoft,
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text     = value,
            style    = type.body,
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
        Text(text = "Tags", style = type.meta, color = colors.inkSoft)
        Spacer(modifier = Modifier.weight(1f))
        Row(
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2),
        ) {
            if (!category.isNullOrEmpty()) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(QuickInkRadius.pill))
                        .background(colors.accentSoft)
                        .clickable(onClick = onAddTag)
                        .padding(horizontal = QuickInkSpacing.s3, vertical = QuickInkSpacing.s2),
                ) {
                    Text(text = category, style = type.caption, color = colors.accent)
                }
            }
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(colors.borderSoft)
                    .clickable(onClick = onAddTag),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector        = Icons.Filled.Add,
                    contentDescription = "Add tag",
                    tint               = colors.inkSoft,
                    modifier           = Modifier.size(14.dp),
                )
            }
        }
    }
}

/**
 * Quick-actions card matching the mockup: header + rows for Export
 * as PDF, Share, Move to folder, Delete. Each row is a full-width
 * tappable surface with an icon on the left.
 */
@Composable
private fun ActionsCard(
    capture: CaptureEntity,
    onShare: () -> Unit,
    onMoveToFolder: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(QuickInkRadius.md))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(QuickInkRadius.md))
            .padding(QuickInkSpacing.s4),
        verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s3),
    ) {
        Row(
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2),
        ) {
            Icon(
                imageVector        = Icons.Filled.Edit,
                contentDescription = null,
                tint               = colors.inkSoft,
                modifier           = Modifier.size(14.dp),
            )
            Text(text = "Actions", style = type.heading, color = colors.ink)
        }

        Column(modifier = Modifier.fillMaxWidth()) {
            // Export as PDF (uses the same share intent — Android
            // doesn't have a separate "export" affordance the way
            // iOS's ShareLink does; sharing a PDF *is* the export
            // path on this platform).
            ActionRow(
                icon         = Icons.Filled.Description,
                label        = "Export as PDF",
                onClick      = onShare,
            )
            ActionDivider()
            ActionRow(
                icon         = Icons.Filled.Share,
                label        = "Share",
                onClick      = onShare,
            )
            ActionDivider()
            ActionRow(
                icon         = Icons.Filled.Folder,
                label        = "Move to folder",
                onClick      = onMoveToFolder,
            )
            ActionDivider()
            ActionRow(
                icon          = Icons.Filled.Delete,
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
) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    val tint = if (isDestructive) colors.danger else colors.inkSoft
    val labelColor = if (isDestructive) colors.danger else colors.ink
    Row(
        modifier              = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = QuickInkSpacing.s3),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s3),
    ) {
        Box(modifier = Modifier.width(24.dp), contentAlignment = Alignment.Center) {
            Icon(
                imageVector        = icon,
                contentDescription = null,
                tint               = tint,
                modifier           = Modifier.size(18.dp),
            )
        }
        Text(text = label, style = type.body, color = labelColor)
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
            imageVector       = if (selected) Icons.Filled.Check else Icons.Filled.LocalOffer,
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

@Composable
private fun OcrSection(
    showOcr: Boolean,
    isLoading: Boolean,
    ocrPages: List<OcrResultEntity>,
    ocrDao: OcrResultDao,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s3),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(QuickInkRadius.md))
                .background(colors.surface)
                .border(1.dp, colors.border, RoundedCornerShape(QuickInkRadius.md))
                .clickable(onClick = onToggle)
                .padding(QuickInkSpacing.s4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector       = if (showOcr) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint              = colors.muted,
                modifier          = Modifier.size(20.dp),
            )
            Spacer(Modifier.size(QuickInkSpacing.s2))
            Text(
                text     = if (showOcr) "Hide extracted text" else "Show extracted text",
                style    = type.body,
                color    = colors.ink,
                modifier = Modifier.weight(1f),
            )
            if (isLoading) {
                CircularProgressIndicator(
                    color    = colors.muted,
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                )
            }
        }

        if (showOcr) {
            if (ocrPages.isEmpty() && !isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(QuickInkRadius.md))
                        .background(colors.surface)
                        .border(1.dp, colors.border, RoundedCornerShape(QuickInkRadius.md))
                        .padding(QuickInkSpacing.s4),
                ) {
                    Text(
                        text  = "No text recognised on this scan.",
                        style = type.meta,
                        color = colors.inkSoft,
                    )
                }
            } else {
                ocrPages.sortedBy { it.pageIndex }.forEach { page ->
                    OcrPageCard(page = page, ocrDao = ocrDao)
                }
            }
        }
    }
}

/**
 * One page card inside [OcrSection]. Read mode renders the OCR text
 * inside a [SelectionContainer] so the user can copy it; tapping the
 * pencil flips into edit mode where the text becomes an
 * [OutlinedTextField]. Save persists via [OcrResultDao.setText]
 * (dirty + ts bump → next sync mirrors); Cancel discards the draft
 * and returns to read mode.
 *
 * Local edit state is keyed on [page.id] so navigating between pages
 * doesn't bleed drafts. While editing, an external `page.text` change
 * (e.g., sync arrived) is ignored — last-write-wins on Save.
 */
@Composable
private fun OcrPageCard(
    page: OcrResultEntity,
    ocrDao: OcrResultDao,
) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    val scope = rememberCoroutineScope()

    var editing by remember(page.id) { mutableStateOf(false) }
    var draft by remember(page.id) { mutableStateOf(page.text) }

    // Keep the draft synced with the persisted text whenever we're not
    // actively editing. Without this, a sync that lands a fresh OCR
    // value while the card sits in read mode would leave the draft
    // pointing at stale content the next time the user taps Edit.
    LaunchedEffect(page.text, editing) {
        if (!editing) draft = page.text
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(QuickInkRadius.md))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(QuickInkRadius.md))
            .padding(QuickInkSpacing.s4),
        verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text     = "PAGE ${page.pageIndex + 1}",
                style    = type.eyebrow,
                color    = colors.muted,
                modifier = Modifier.weight(1f),
            )
            if (editing) {
                IconButton(onClick = {
                    draft = page.text
                    editing = false
                }) {
                    Icon(
                        imageVector        = Icons.Filled.Close,
                        contentDescription = "Cancel edit",
                        tint               = colors.muted,
                        modifier           = Modifier.size(18.dp),
                    )
                }
                IconButton(onClick = {
                    val snapshot = draft
                    scope.launch {
                        try {
                            ocrDao.setText(page.id, snapshot, IsoClock.nowIso())
                            editing = false
                        } catch (_: Exception) { /* best-effort */ }
                    }
                }) {
                    Icon(
                        imageVector        = Icons.Filled.Check,
                        contentDescription = "Save text",
                        tint               = colors.accent,
                        modifier           = Modifier.size(18.dp),
                    )
                }
            } else {
                IconButton(onClick = { editing = true }) {
                    Icon(
                        imageVector        = Icons.Filled.Edit,
                        contentDescription = "Edit text",
                        tint               = colors.muted,
                        modifier           = Modifier.size(18.dp),
                    )
                }
            }
        }

        if (editing) {
            OutlinedTextField(
                value         = draft,
                onValueChange = { draft = it },
                modifier      = Modifier.fillMaxWidth(),
                minLines      = 3,
                textStyle     = type.body,
            )
        } else {
            SelectionContainer {
                Text(
                    text  = page.text,
                    style = type.body,
                    color = colors.ink,
                )
            }
        }
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
