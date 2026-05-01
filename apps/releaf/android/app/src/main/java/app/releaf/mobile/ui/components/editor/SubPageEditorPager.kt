/*
 * SubPageEditorPager.kt
 *
 * Horizontal pager of sub-pages inside a single notebook page (or
 * notepad entry). Each sub-page gets its own rich-text editor + drawing
 * overlay; swiping between them navigates siblings. An indicator row
 * above the pager shows "N / M", adds a fresh sub-page, or deletes the
 * current one.
 *
 * Shared between the inline Edit-mode body and the fullscreen
 * NotesEditorSheet. Callers hoist the `richTextStates` map so that
 * text edits sync across both surfaces within a screen session.
 */

package app.releaf.mobile.ui.components.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.toIntSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import android.widget.Toast
import app.releaf.mobile.data.common.ExportResult
import app.releaf.mobile.data.common.NotesExport
// (No rememberCoroutineScope — scrolling is driven by LaunchedEffect on size.)
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.releaf.mobile.data.notebook.LedgerEntry
import app.releaf.mobile.data.notebook.Stroke
import app.releaf.mobile.data.notebook.SubPage
import app.releaf.mobile.data.notebook.TextBox
import app.releaf.mobile.ui.theme.AppColors
import app.releaf.mobile.ui.theme.AppAccent
import app.releaf.mobile.ui.theme.AppSpacing
import app.releaf.mobile.ui.theme.AppTypography
import com.mohamedrejeb.richeditor.model.RichTextState
import com.mohamedrejeb.richeditor.ui.BasicRichTextEditor

private val DefaultPagerHeight = 420.dp

/**
 * Pager of sub-pages. When `pageHeight` is non-null each page is
 * rendered at that fixed height — the layout path used inside the
 * Edit-mode `verticalScroll`, where a growing child would collapse
 * under `Constraints.Infinity`. When `pageHeight` is null the pager
 * takes all the vertical room the parent gives it (`weight(1f)`) —
 * the layout path used from the fullscreen `NotesEditorSheet` so the
 * writing surface feels like a whole sheet of paper.
 */
@Composable
fun SubPageEditorPager(
    subPages: List<SubPage>,
    pagerState: PagerState,
    richTextStates: Map<String, RichTextState>,
    drawingMode: DrawingMode,
    penConfig: PenConfig,
    onStrokesChange: (id: String, strokes: List<Stroke>) -> Unit,
    onTextBoxesChange: (id: String, textBoxes: List<TextBox>) -> Unit = { _, _ -> },
    /** Fired when the user edits a row of the ledger on a
     *  `BG_RULED` sub-page. No-op default so existing callers that
     *  never use ledger mode don't have to plumb it. */
    onLedgerChange: (id: String, entries: List<LedgerEntry>) -> Unit = { _, _ -> },
    /** Fired when the user edits the ledger's title field on a
     *  `BG_RULED` sub-page. */
    onLedgerTitleChange: (id: String, title: String) -> Unit = { _, _ -> },
    onAddSubPage: () -> String,
    onRemoveSubPage: (id: String) -> Unit,
    onBackgroundChange: (id: String, background: String) -> Unit = { _, _ -> },
    onBgScaleChange: (id: String, scale: Float) -> Unit = { _, _ -> },
    /** Called once a sub-page snapshot has been written to app storage.
     *  Callers wire this to the VM's `addAttachment(TYPE_PHOTO, uri)`
     *  so the exported JPG appears in the page's Photos section. */
    onPhotoExported: (uri: String) -> Unit = {},
    pageHeight: Dp? = DefaultPagerHeight,
    modifier: Modifier = Modifier,
) {
    // Confirmation gate for the destructive action so users don't wipe
    // a page of notes + strokes on an accidental tap. Persisted across
    // config changes so a rotation mid-decision doesn't dismiss it.
    var pendingDeleteId by rememberSaveable { mutableStateOf<String?>(null) }

    // Which sub-page currently has its background picker open. Scoped
    // by id so navigating the pager and re-tapping lands the popover on
    // the right page. `null` = closed.
    var pickerOpenForId by remember { mutableStateOf<String?>(null) }

    // Sub-page export request. Set when the user taps "Save to Photos"
    // in the picker; the matching page's composable sees the change
    // via LaunchedEffect, captures its graphics layer, writes the JPG,
    // and clears the flag. Screen-local — no point persisting.
    var saveRequestedFor by remember { mutableStateOf<String?>(null) }

    // Which image-backed sub-page (if any) is currently expanded into
    // the fullscreen draw-on-image canvas. Null = closed. Tracked by
    // id rather than index so a swipe + delete race can't land the
    // dialog on a different page than the user tapped.
    var fullscreenForId by remember { mutableStateOf<String?>(null) }

    // Track the count we last scrolled to. When `subPages.size` grows
    // past this, a new sub-page was appended — scroll to it so the
    // user lands on the blank page they just asked for.
    var lastKnownSize by remember { mutableStateOf(subPages.size) }

    LaunchedEffect(subPages.size) {
        if (subPages.size > lastKnownSize) {
            // New page was added at the end — jump there. scrollToPage
            // is a no-op if pageCount hasn't caught up yet, but in
            // practice the new SubPage is already visible in the
            // pager's pageCount lambda by the time this fires.
            pagerState.animateScrollToPage(subPages.size - 1)
        } else if (pagerState.currentPage >= subPages.size && subPages.isNotEmpty()) {
            // Deletion pulled the current page out of range; clamp.
            pagerState.scrollToPage(subPages.size - 1)
        }
        lastKnownSize = subPages.size
    }

    // Outer column. Fill-height mode (pageHeight == null) gives the pager
    // a `fillMaxHeight()` so the HorizontalPager can claim the remaining
    // space via `weight(1f)`. Fixed-height mode leaves the column wrap-
    // content-tall, since the pager sets its own explicit height.
    val columnModifier = if (pageHeight == null) {
        modifier.fillMaxSize()
    } else {
        modifier.fillMaxWidth()
    }
    val currentSp = subPages.getOrNull(pagerState.currentPage)

    Column(modifier = columnModifier) {
        SubPageIndicator(
            currentIndex = pagerState.currentPage,
            total        = subPages.size,
            currentSubPage = currentSp,
            pickerOpen   = pickerOpenForId != null && pickerOpenForId == currentSp?.id,
            onTogglePicker = {
                val id = currentSp?.id ?: return@SubPageIndicator
                pickerOpenForId = if (pickerOpenForId == id) null else id
            },
            onDismissPicker   = { pickerOpenForId = null },
            onBackgroundChange = { bg ->
                val id = currentSp?.id ?: return@SubPageIndicator
                onBackgroundChange(id, bg)
            },
            onScaleChange = { scale ->
                val id = currentSp?.id ?: return@SubPageIndicator
                onBgScaleChange(id, scale)
            },
            onSaveToPhotos = {
                val id = currentSp?.id ?: return@SubPageIndicator
                pickerOpenForId = null
                saveRequestedFor = id
            },
            onAdd        = { onAddSubPage() },
            onDelete     = {
                pendingDeleteId = subPages.getOrNull(pagerState.currentPage)?.id
            },
            canDelete    = subPages.size > 1,
            canAdd       = true,
            // Fullscreen affordance is only shown when the current
            // sub-page is image-backed (the dialog has nothing useful
            // to render otherwise — the inline pattern editor already
            // gives plain / grid / ruled pages a full-width surface).
            onOpenFullscreen = currentSp
                ?.takeIf { it.backgroundImageUri != null }
                ?.let { sp -> { fullscreenForId = sp.id } },
        )

        // Image-backed pages get sized to their natural aspect so the
        // full imported page fits at editor width — the parent
        // `verticalScroll` (EditorBody) handles overflow on tall
        // scans. We resolve the painter for the *current* page only
        // and read its intrinsic size; Coil de-dupes via its in-memory
        // cache, so the per-page painter created later inside the
        // pager lambda is essentially free. Until the image loads
        // intrinsicSize is `Size.Unspecified` and we fall back to the
        // caller's `pageHeight`.
        val currentImageAspect: Float? = run {
            val uri = currentSp?.backgroundImageUri ?: return@run null
            val parsed = remember(uri) {
                runCatching { android.net.Uri.parse(uri) }.getOrNull()
            } ?: return@run null
            val sizingPainter = coil.compose.rememberAsyncImagePainter(model = parsed)
            val sz = sizingPainter.intrinsicSize
            if (sz != androidx.compose.ui.geometry.Size.Unspecified
                && sz.width > 0f && sz.height > 0f
            ) sz.width / sz.height else null
        }

        // Pager size. Fill-height mode uses `weight(1f)` so the editor
        // takes all the vertical room under the indicator row; fixed
        // mode sets an explicit height so the pager plays nicely inside
        // a verticalScroll (where Constraints.Infinity would collapse
        // a weighted child). Image-backed pages override the fixed
        // height with `aspectRatio` so the imported page is shown in
        // full and the parent scroll reveals the rest.
        val pagerSize: @Composable ColumnScope.(Modifier) -> Modifier = { base ->
            when {
                pageHeight == null         -> base.weight(1f).fillMaxWidth()
                currentImageAspect != null -> base.fillMaxWidth().aspectRatio(currentImageAspect)
                else                       -> base.height(pageHeight).fillMaxWidth()
            }
        }
        val pageBoxSize: Modifier = when {
            pageHeight == null         -> Modifier.fillMaxSize()
            currentImageAspect != null -> Modifier.fillMaxSize()
            else                       -> Modifier.fillMaxWidth().height(pageHeight)
        }

        HorizontalPager(
            state    = pagerState,
            // 4dp bottom gap keeps the card's rounded corner visible
            // above the format bar — without it the bottom edge sits
            // flush against the toolbar and reads as cropped.
            modifier = pagerSize(Modifier).padding(
                start  = AppSpacing.s4,
                end    = AppSpacing.s4,
                bottom = 4.dp,
            ),
            pageSpacing = AppSpacing.s3,
        ) { pageIndex ->
            val sp = subPages.getOrNull(pageIndex) ?: return@HorizontalPager
            val rts = richTextStates[sp.id] ?: return@HorizontalPager

            // One GraphicsLayer per page. `drawWithContent` records the
            // full subtree (background + text + strokes) into the
            // layer, then draws the layer — so the layer is always
            // in sync with what the user sees, which means
            // `toImageBitmap()` returns the current snapshot at any
            // time.
            val layer = rememberGraphicsLayer()
            val context = LocalContext.current

            // Capture + save when the user taps "Save to Photos" on
            // this page. Keyed on both the flag and the id so it fires
            // exactly once, and only on the targeted page.
            LaunchedEffect(saveRequestedFor) {
                if (saveRequestedFor != sp.id) return@LaunchedEffect
                val bitmap = layer.toImageBitmap()
                val result = NotesExport.saveSubPageToPageAttachments(
                    context = context,
                    image   = bitmap,
                )
                val message = when (result) {
                    is ExportResult.Saved  -> {
                        onPhotoExported(result.uri.toString())
                        "Added to Photos"
                    }
                    is ExportResult.Failed -> "Save failed: ${result.cause.message ?: "unknown error"}"
                }
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                saveRequestedFor = null
            }

            Box(
                modifier = pageBoxSize
                    .clip(RoundedCornerShape(AppSpacing.s2))
                    .background(AppColors.CardSolid)
                    .drawWithContent {
                        // Pass the DrawScope size through explicitly.
                        // The no-arg overload defaults to the same value,
                        // but being explicit guards against the layer
                        // buffer going stale if Box re-measures on a
                        // frame where the scope's size lags behind — on
                        // PDF-background pages that mismatch manifested
                        // as a subtle vertical offset between what the
                        // user saw and what the saved JPG contained.
                        layer.record(size = size.toIntSize()) {
                            this@drawWithContent.drawContent()
                        }
                        drawLayer(layer)
                    },
            ) {
                // Background layer. A sub-page imported from a PDF /
                // scan carries a `backgroundImageUri` — in that case
                // the scanned page is the paper, and the pattern
                // options are ignored. Everything else falls back to
                // `NotesBackground`'s pattern drawing.
                //
                // We go through `rememberAsyncImagePainter` + `Image`
                // instead of plain `AsyncImage`. The two are otherwise
                // equivalent, but `AsyncImage` wraps the painter in a
                // subcompose layout (`SubcomposeAsyncImage`-style) that
                // can leave a stale intrinsic size in the layout pass
                // while the image is still resolving. When we then
                // record the Box into a `GraphicsLayer` for "Save to
                // Photos", the painter's recorded bounds end up a few
                // pixels tighter than the displayed bounds, and the
                // content above ink strokes reads as shifted upward in
                // the captured bitmap. Going through the Painter API
                // directly gives us a single, deterministic draw path
                // shared by display and capture.
                val bgImage = sp.backgroundImageUri
                // Ledger mode — BG_RULED with no backing PDF image.
                // Swaps the rich-text body for the two-column
                // label/amount form. Strokes + text boxes are skipped,
                // but the ruled-paper pattern keeps rendering *under*
                // the form so the page still reads as ledger paper.
                val isLedger = bgImage == null && sp.background == SubPage.BG_RULED
                if (isLedger) {
                    NotesBackground(
                        background = sp.background,
                        scale      = sp.bgScale,
                        modifier   = Modifier.matchParentSize(),
                    )
                    LedgerEditor(
                        entries         = sp.ledgerEntries,
                        title           = sp.ledgerTitle,
                        onEntriesChange = { next -> onLedgerChange(sp.id, next) },
                        onTitleChange   = { next -> onLedgerTitleChange(sp.id, next) },
                        modifier        = Modifier.matchParentSize(),
                    )
                } else if (bgImage != null) {
                    val parsed = runCatching { android.net.Uri.parse(bgImage) }.getOrNull()
                    if (parsed != null) {
                        val painter = coil.compose.rememberAsyncImagePainter(model = parsed)
                        // ContentScale.FillWidth pins the painter to
                        // the editor width so the imported page never
                        // shifts off-center. The card's height is
                        // sized from the image's intrinsic aspect
                        // ratio (see `imageAspectAt` + `pagerSize`
                        // above) so the full page fits without
                        // cropping — the parent `verticalScroll`
                        // handles overflow on tall scans.
                        androidx.compose.foundation.Image(
                            painter            = painter,
                            contentDescription = null,
                            contentScale       = androidx.compose.ui.layout.ContentScale.FillWidth,
                            alignment          = Alignment.TopStart,
                            modifier           = Modifier.matchParentSize(),
                        )
                    }
                } else {
                    NotesBackground(
                        background = sp.background,
                        scale      = sp.bgScale,
                        modifier   = Modifier.matchParentSize(),
                    )
                }

                // Text body + overlays are only rendered outside
                // ledger mode. The LedgerEditor above already owns the
                // full Box when `isLedger` is true; painting a
                // transparent rich-text editor on top would steal
                // focus from the ledger's form fields.
                if (!isLedger) {
                    // Stabilize the TextStyle across recompositions — a
                    // fresh `.copy(...)` each frame caused the rich-text
                    // editor's `onTextLayout → adjustRichParagraphLayout →
                    // updateTextFieldValue` path to re-enter and the screen
                    // to flicker. Hoisting the color out (since
                    // `AppColors.TextPrimary` is a @Composable getter) lets
                    // `remember` key on the resolved Color value.
                    //
                    // Two extra knobs are needed to make `lineHeight`
                    // actually change visible spacing:
                    //   1. `lineHeightStyle` with `Trim.None` — without it,
                    //      Compose trims the extra leading from the first
                    //      and last line, so a lineHeight bump only affects
                    //      interior wrapped lines and a single-paragraph
                    //      note looks unchanged.
                    //   2. `platformStyle = PlatformTextStyle(
                    //      includeFontPadding = false)` — Android's legacy
                    //      font-padding adds invisible top/bottom padding
                    //      that masks line-height changes. Turning it off
                    //      lets the 36sp value reach the rendered glyphs.
                    val textPrimary = AppColors.TextPrimary
                    val bodyStyle   = AppTypography.Body
                    val editorTextStyle = remember(textPrimary, bodyStyle) {
                        bodyStyle.copy(
                            color = textPrimary,
                            // 24sp locks the text rhythm to the 24dp grid /
                            // ruled / dotted background spacing defined in
                            // `NotesBackground.BaseSpacing`. If this drifts
                            // from that value, each wrapped line rides a
                            // little higher than the previous one and the
                            // baseline stops sitting on the grid line.
                            // Keep them in lockstep.
                            lineHeight = 24.sp,
                            lineHeightStyle = LineHeightStyle(
                                alignment = LineHeightStyle.Alignment.Center,
                                trim      = LineHeightStyle.Trim.None,
                            ),
                            platformStyle = PlatformTextStyle(
                                includeFontPadding = false,
                            ),
                        )
                    }
                    BasicRichTextEditor(
                        state       = rts,
                        textStyle   = editorTextStyle,
                        cursorBrush = SolidColor(AppAccent.primary),
                        modifier    = if (pageHeight == null) {
                            Modifier
                                .fillMaxSize()
                                .padding(AppSpacing.s3)
                        } else {
                            Modifier
                                .fillMaxWidth()
                                .padding(AppSpacing.s3)
                        },
                    )
                    DrawingOverlay(
                        strokes         = sp.strokes,
                        mode            = drawingMode,
                        penConfig       = penConfig,
                        onStrokesChange = { newStrokes -> onStrokesChange(sp.id, newStrokes) },
                        modifier        = Modifier.matchParentSize(),
                    )

                    // Free-form text layer. Sits on top of strokes so a
                    // new text box can be placed over inked annotations;
                    // in non-Text modes each item is a plain Text (no
                    // pointerInput), so DrawingOverlay beneath still
                    // captures pen/eraser touches.
                    TextBoxLayer(
                        textBoxes     = sp.textBoxes,
                        mode          = drawingMode,
                        penConfig     = penConfig,
                        fontSpForWidth = { widthDp ->
                            // Thickness preset → font size:
                            //   2dp (S) → 14sp, 4dp (M) → 18sp, 8dp (L) → 24sp.
                            // Any other width snaps to the nearest preset
                            // rather than scaling linearly, to keep the
                            // three-size mental model consistent.
                            when {
                                widthDp <= 2.5f -> 14f
                                widthDp <= 6f   -> 18f
                                else            -> 24f
                            }
                        },
                        onChange      = { newList -> onTextBoxesChange(sp.id, newList) },
                        modifier      = Modifier.matchParentSize(),
                    )
                }
            }
        }
    }

    // Fullscreen draw-on-image surface for the imported page. Opens
    // when the user taps the expand icon in the indicator above an
    // image-backed sub-page. Strokes saved here go through the same
    // `onStrokesChange` callback the inline overlay uses, so closing
    // the dialog leaves the user's annotations intact on the page.
    fullscreenForId?.let { id ->
        val sp = subPages.firstOrNull { it.id == id }
        if (sp == null || sp.backgroundImageUri == null) {
            // Page was deleted (or its image cleared) while the
            // dialog was open — drop the request silently rather
            // than opening an empty canvas.
            fullscreenForId = null
        } else {
            ImageNoteCanvasDialog(
                subPage         = sp,
                onStrokesChange = { newStrokes -> onStrokesChange(sp.id, newStrokes) },
                onDismiss       = { fullscreenForId = null },
            )
        }
    }

    // Destructive-action guard. Mirrors the pattern used on the page /
    // entry delete dialog so the UX is consistent — one tap shows the
    // dialog, the actual remove only fires on "Delete".
    val pendingId = pendingDeleteId
    if (pendingId != null) {
        AlertDialog(
            onDismissRequest = { pendingDeleteId = null },
            title            = { Text("Delete this page?") },
            text             = {
                Text(
                    "The notes and drawing on this page will be removed. " +
                        "Other pages stay put.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingDeleteId = null
                    onRemoveSubPage(pendingId)
                }) {
                    Text("Delete", color = AppColors.Danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteId = null }) {
                    Text("Cancel", color = AppColors.TextSecondary)
                }
            },
        )
    }
}

@Composable
private fun SubPageIndicator(
    currentIndex: Int,
    total: Int,
    currentSubPage: SubPage?,
    pickerOpen: Boolean,
    onTogglePicker: () -> Unit,
    onDismissPicker: () -> Unit,
    onBackgroundChange: (String) -> Unit,
    onScaleChange: (Float) -> Unit,
    onSaveToPhotos: () -> Unit,
    onAdd: () -> Unit,
    onDelete: () -> Unit,
    canDelete: Boolean,
    canAdd: Boolean,
    /** When non-null, shows a fullscreen-expand icon next to the
     *  background picker. Wired by `SubPageEditorPager` only when
     *  the current sub-page is image-backed — for pattern pages the
     *  inline view is already as expansive as it can usefully be. */
    onOpenFullscreen: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.s4, vertical = AppSpacing.s2),
    ) {
        // Top row: background / zoom picker. Right-aligned so the
        // affordance sits over where the page-level actions used to
        // live, but on its own line so the indicator row below isn't
        // crowded by the popover anchor.
        if (currentSubPage != null) {
            Row(
                modifier              = Modifier.fillMaxWidth(),
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
            ) {
                if (onOpenFullscreen != null) {
                    // Image-backed pages: expand into the fullscreen
                    // canvas where the imported page sits flush with
                    // the device's top edge. Sits to the LEFT of the
                    // background picker so the icon order reads as a
                    // natural "view → tweak" pairing.
                    CircleIconBtn(
                        icon    = Icons.Filled.OpenInFull,
                        label   = "Open fullscreen",
                        tint    = AppColors.TextSecondary,
                        onClick = onOpenFullscreen,
                    )
                    Spacer(Modifier.size(AppSpacing.s2))
                }
                Box {
                    // Icon-only anchor — the popover content labels
                    // itself, so the pager indicator doesn't need to
                    // carry the "Background" text too.
                    CircleIconBtn(
                        icon    = Icons.Filled.GridView,
                        label   = "Background",
                        tint    = AppColors.TextSecondary,
                        onClick = onTogglePicker,
                    )
                    BackgroundPickerPopover(
                        expanded           = pickerOpen,
                        background         = currentSubPage.background,
                        scale              = currentSubPage.bgScale,
                        onDismiss          = onDismissPicker,
                        onBackgroundChange = onBackgroundChange,
                        onScaleChange      = onScaleChange,
                        onSaveToPhotos     = onSaveToPhotos,
                    )
                }
            }
            Spacer(Modifier.size(AppSpacing.s1))
        }

        // Bottom row: page index + dots + icon-only actions. Keeping
        // delete + add as compact round icons (no labels) matches the
        // tight indicator style the user wanted.
        Row(
            modifier          = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text  = "Page ${currentIndex + 1} / $total",
                style = AppTypography.Button,
                color = AppColors.TextPrimary,
            )

            Spacer(Modifier.size(AppSpacing.s3))

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment     = Alignment.CenterVertically,
            ) {
                repeat(total) { i ->
                    val active = i == currentIndex
                    Box(
                        Modifier
                            .size(if (active) 8.dp else 6.dp)
                            .clip(CircleShape)
                            .background(
                                if (active) AppAccent.primary else AppColors.BorderDefault,
                            ),
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            if (canDelete) {
                CircleIconBtn(
                    icon    = Icons.Filled.DeleteOutline,
                    label   = "Delete page",
                    tint    = AppColors.Danger,
                    onClick = onDelete,
                )
                Spacer(Modifier.size(AppSpacing.s2))
            }
            if (canAdd) {
                CircleIconBtn(
                    icon    = Icons.Filled.Add,
                    label   = "Add page",
                    tint    = AppAccent.primary,
                    filled  = true,
                    onClick = onAdd,
                )
            }
        }
    }
}

@Composable
private fun CircleIconBtn(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color,
    filled: Boolean = false,
    onClick: () -> Unit,
) {
    val bgColor = if (filled) tint.copy(alpha = 0.15f) else Color.Transparent
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(bgColor)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector        = icon,
            contentDescription = label,
            tint               = tint,
            modifier           = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun PillBtn(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color,
    filled: Boolean = false,
    onClick: () -> Unit,
) {
    val bgColor = if (filled) tint.copy(alpha = 0.15f) else Color.Transparent
    val borderColor = if (filled) tint else AppColors.BorderDefault
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(AppSpacing.s3))
            .background(bgColor)
            .clickable(onClick = onClick)
            .padding(horizontal = AppSpacing.s3, vertical = AppSpacing.s2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector        = icon,
            contentDescription = label,
            tint               = tint,
            modifier           = Modifier.size(16.dp),
        )
        Spacer(Modifier.size(AppSpacing.s1))
        Text(
            text  = label,
            style = AppTypography.Button,
            color = tint,
        )
    }
}
