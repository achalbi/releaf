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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
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
import app.quickink.mobile.data.ocr.OcrResultEntity
import app.quickink.mobile.ui.theme.LocalQuickInkColors
import app.quickink.mobile.ui.theme.LocalQuickInkTypography
import app.quickink.mobile.ui.theme.QuickInkRadius
import app.quickink.mobile.ui.theme.QuickInkSpacing
import app.quickink.mobile.ui.theme.quickInkDotGridBackground
import androidx.core.content.FileProvider
import app.releaf.mobile.data.common.IsoClock
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.launch
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

    // Live category list — populated from the same DAO the home
    // grid + review screen read, scoped to the current user. The
    // sheet uses this for its picker rows.
    val categories by remember(userId, categoryDao) {
        categoryDao.observeActive(userId)
    }.collectAsState(initial = emptyList())

    LaunchedEffect(captureId) {
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
                text  = capture?.category ?: "Scan",
                style = type.pageTitle,
                color = colors.ink,
                modifier = Modifier.weight(1f),
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
                MetaBlock(
                    capture  = current,
                    onTagTap = { showRetagSheet = true },
                )
                OcrSection(
                    showOcr   = showOcr,
                    isLoading = showOcr && !ocrLoaded,
                    ocrPages  = ocrPages,
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
}

/**
 * Best preview surface for the capture, in priority order:
 *   1. Multi-page PDF render via [PdfPagesView] (preferred —
 *      pinch-to-zoom + every page).
 *   2. The first-page JPEG `preview_uri` via Coil (when no PDF URI).
 *   3. A paper-toned placeholder (when the file is missing entirely).
 */
@Composable
private fun PreviewImage(capture: CaptureEntity) {
    val colors = LocalQuickInkColors.current
    val context = LocalContext.current
    val pdfUri = capture.pdfUri.takeIf { it.isNotBlank() }?.let(Uri::parse)
    val previewUri = capture.previewUri

    when {
        pdfUri != null -> {
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
        !previewUri.isNullOrBlank() -> {
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
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(320.dp)
                    .clip(RoundedCornerShape(QuickInkRadius.md))
                    .background(colors.borderSoft),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector       = Icons.Filled.Description,
                    contentDescription = null,
                    tint              = colors.muted,
                    modifier          = Modifier.size(64.dp),
                )
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
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(QuickInkRadius.md))
                            .background(colors.surface)
                            .border(1.dp, colors.border, RoundedCornerShape(QuickInkRadius.md))
                            .padding(QuickInkSpacing.s4),
                        verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2),
                    ) {
                        Text(
                            text  = "PAGE ${page.pageIndex + 1}",
                            style = type.eyebrow,
                            color = colors.muted,
                        )
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
