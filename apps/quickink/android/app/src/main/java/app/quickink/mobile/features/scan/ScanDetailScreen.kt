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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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

    // Live category list — populated from the same DAO the home
    // grid + review screen read, scoped to the current user. The
    // sheet uses this for its picker rows.
    val categories by remember(userId, categoryDao) {
        categoryDao.observeActive(userId)
    }.collectAsState(initial = emptyList())

    LaunchedEffect(captureId) {
        capture = captureDao.findById(captureId)
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
            Text(
                text  = capture?.title?.takeIf { it.isNotBlank() }
                    ?: capture?.category
                    ?: "Scan",
                style = type.pageTitle,
                color = colors.ink,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )

            // Share button — always visible when there's a capture
            // loaded. `sharePdf` falls back to the preview JPEG if
            // the PDF URI is missing/invalid and surfaces a Toast on
            // failure, so the tap is never a silent no-op.
            if (capture != null) {
                IconButton(onClick = {
                    sharePdf(context, capture?.pdfUri, capture?.previewUri)
                }) {
                    Icon(
                        imageVector       = Icons.Filled.Share,
                        contentDescription = "Share scan",
                        tint              = colors.ink,
                    )
                }
            }

            IconButton(onClick = { showDeleteConfirm = true }) {
                Icon(
                    imageVector       = Icons.Filled.Delete,
                    contentDescription = "Delete scan",
                    tint              = colors.danger,
                )
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
                .padding(horizontal = QuickInkSpacing.s5, vertical = QuickInkSpacing.s4),
            verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s5),
        ) {
            val current = capture
            if (current == null) {
                CircularProgressIndicator(
                    color    = colors.accent,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            } else {
                PreviewImage(capture = current)
                TitleSection(
                    title    = current.title,
                    onEdit   = {
                        titleDraft     = current.title.orEmpty()
                        showTitleEditor = true
                    },
                )
                MetaBlock(
                    capture  = current,
                    onTagTap = { showRetagSheet = true },
                )
                OcrSection(
                    showOcr   = showOcr,
                    isLoading = showOcr && !ocrLoaded,
                    ocrPages  = ocrPages,
                    ocrDao    = ocrDao,
                    onToggle  = { showOcr = !showOcr },
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
private fun PreviewImage(capture: CaptureEntity) {
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
        pdfPresent && pdfUri != null -> {
            // Multi-page captures get the swipe + page-turn viewer;
            // single-page captures keep the scrollable PdfPagesView
            // since it already handles pinch-to-zoom and there's
            // nothing to swipe to anyway.
            if (capture.pageCount > 1) {
                PageTurnPdfView(
                    pdfUri   = pdfUri,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(0.707f) // A4-ish portrait until pages render
                        .clip(RoundedCornerShape(QuickInkRadius.md))
                        .background(colors.surface)
                        .border(1.dp, colors.border, RoundedCornerShape(QuickInkRadius.md)),
                )
            } else {
                PdfPagesView(
                    pdfUri   = pdfUri,
                    modifier = Modifier
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
                modifier = Modifier
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
                modifier = Modifier
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
@Composable
private fun TitleSection(
    title: String?,
    onEdit: () -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    val displayed = title?.takeIf { it.isNotBlank() }
    Row(
        modifier              = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(QuickInkRadius.md))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(QuickInkRadius.md))
            .clickable(onClick = onEdit)
            .padding(QuickInkSpacing.s4),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s3),
    ) {
        Text(
            text     = displayed ?: "Untitled scan",
            style    = type.heading,
            color    = if (displayed != null) colors.ink else colors.muted,
            modifier = Modifier.weight(1f),
            maxLines = 2,
        )
        Icon(
            imageVector        = Icons.Filled.Edit,
            contentDescription = "Edit title",
            tint               = colors.muted,
            modifier           = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun MetaBlock(
    capture: CaptureEntity,
    onTagTap: () -> Unit,
) {
    Row(
        modifier              = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2),
        verticalAlignment     = Alignment.CenterVertically,
    ) {
        MetaPill(text = friendlyDate(capture.createdAt))
        if (capture.pageCount > 1) {
            MetaPill(text = "${capture.pageCount} pages")
        }
        // Category affordance — a tappable pill that opens the
        // retag sheet. When the capture already has a tag, the pill
        // renders the tag with the accent treatment; when it
        // doesn't, we fall back to a muted "Tag scan" affordance so
        // retagging is still discoverable.
        TagPill(category = capture.category, onClick = onTagTap)
    }
}

@Composable
private fun TagPill(category: String?, onClick: () -> Unit) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    val hasTag = !category.isNullOrEmpty()
    val bg = if (hasTag) colors.accentSoft else colors.borderSoft
    val fg = if (hasTag) colors.accent     else colors.inkSoft
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(QuickInkRadius.pill))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = QuickInkSpacing.s3, vertical = QuickInkSpacing.s2),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s1),
    ) {
        Icon(
            imageVector       = if (hasTag) Icons.Filled.LocalOffer else Icons.Filled.Add,
            contentDescription = null,
            tint              = fg,
            modifier          = Modifier.size(12.dp),
        )
        Text(
            text  = if (hasTag) category!! else "Tag scan",
            style = type.caption,
            color = fg,
        )
    }
}

@Composable
private fun MetaPill(text: String, accent: Boolean = false) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    val bg = if (accent) colors.accentSoft else colors.borderSoft
    val fg = if (accent) colors.accent     else colors.inkSoft
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(QuickInkRadius.pill))
            .background(bg)
            .padding(horizontal = QuickInkSpacing.s3, vertical = QuickInkSpacing.s2),
    ) {
        Text(text = text, style = type.caption, color = fg)
    }
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
) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current

    Column(verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s3)) {
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
