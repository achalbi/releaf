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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import app.releaf.mobile.data.common.IsoClock
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun ScanDetailScreen(
    captureId: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as QuickInkApp
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    val scope = rememberCoroutineScope()

    val captureDao = remember(app) { app.database.captureDao() }
    val ocrDao = remember(app) { app.database.ocrResultDao() }

    var capture by remember(captureId) { mutableStateOf<CaptureEntity?>(null) }
    var showOcr by remember(captureId) { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

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
                MetaBlock(capture = current)
                OcrSection(
                    showOcr   = showOcr,
                    isLoading = showOcr && !ocrLoaded,
                    ocrPages  = ocrPages,
                    onToggle  = { showOcr = !showOcr },
                )
            }
        }
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
 * the PDF URI is missing or rejected by the chooser. Surfaces
 * failures via Toast so the user knows the tap registered — silent
 * failure was reported as "share button not wired".
 *
 * `FLAG_GRANT_READ_URI_PERMISSION` only works for URIs we own or
 * received with grantable permission; ML-Kit's scanner grants the
 * latter, so forwarding is allowed.
 */
private fun sharePdf(
    context: android.content.Context,
    pdfUri: String?,
    previewUri: String?,
) {
    val candidates = listOfNotNull(
        pdfUri?.takeIf { it.isNotBlank() }    to "application/pdf",
        previewUri?.takeIf { it.isNotBlank() } to "image/jpeg",
    ).mapNotNull { (uri, type) -> uri?.let { it to type } }

    if (candidates.isEmpty()) {
        android.widget.Toast.makeText(
            context, "Nothing to share for this scan", android.widget.Toast.LENGTH_SHORT
        ).show()
        return
    }

    for ((rawUri, mime) in candidates) {
        val uri = try { Uri.parse(rawUri) } catch (_: Exception) { continue }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
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

@Composable
private fun MetaBlock(capture: CaptureEntity) {
    Row(
        modifier              = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2),
        verticalAlignment     = Alignment.CenterVertically,
    ) {
        MetaPill(text = friendlyDate(capture.createdAt))
        if (capture.pageCount > 1) {
            MetaPill(text = "${capture.pageCount} pages")
        }
        capture.category?.takeIf { it.isNotEmpty() }?.let {
            MetaPill(text = it, accent = true)
        }
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
