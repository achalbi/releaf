/*
 * NotesEditorSheet.kt
 *
 * Full-height modal bottom sheet that owns the multi-sub-page rich-text
 * editor + format bar. Invoked from the Overview tab on the notepad and
 * notebook-page editors: tap the notes preview to open the sheet;
 * dismiss via the handle, a back-swipe, or the "Done" button.
 *
 * The sheet binds to the caller's shared `richTextStates` map — one
 * RichTextState per sub-page — so any typing or formatting done here
 * shows up unchanged when the user flips to Edit mode, and vice versa.
 */

package app.releaf.mobile.ui.components.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import app.releaf.mobile.data.notebook.Stroke
import app.releaf.mobile.data.notebook.SubPage
import app.releaf.mobile.ui.theme.AppColors
import app.releaf.mobile.ui.theme.AppAccent
import app.releaf.mobile.ui.theme.AppSpacing
import app.releaf.mobile.ui.theme.AppTypography
import com.mohamedrejeb.richeditor.model.RichTextState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesEditorSheet(
    subPages: List<SubPage>,
    richTextStates: Map<String, RichTextState>,
    onSubPageStrokesChange: (id: String, strokes: List<Stroke>) -> Unit,
    onSubPageTextBoxesChange: (id: String, textBoxes: List<app.releaf.mobile.data.notebook.TextBox>) -> Unit,
    onSubPageLedgerChange: (id: String, entries: List<app.releaf.mobile.data.notebook.LedgerEntry>) -> Unit = { _, _ -> },
    onSubPageLedgerTitleChange: (id: String, title: String) -> Unit = { _, _ -> },
    onAddSubPage: () -> String,
    onRemoveSubPage: (id: String) -> Unit,
    onSubPageBackgroundChange: (id: String, background: String) -> Unit,
    onSubPageBgScaleChange: (id: String, scale: Float) -> Unit,
    onPhotoExported: (uri: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(
        // Bypass the half-height "peek" — notes editing wants all the
        // vertical room it can get. `confirmValueChange` lets us
        // reject the `Hidden → Expanded` demotion the sheet would
        // otherwise try on keyboard show/hide.
        skipPartiallyExpanded = true,
        confirmValueChange    = { it != SheetValue.PartiallyExpanded },
    )
    val focusManager = LocalFocusManager.current

    // Drawing toolbar state. Screen-local so a single sheet session has
    // a stable pen configuration across add / delete / swipe.
    var drawingMode by rememberSaveable(stateSaver = DrawingModeSaver) {
        mutableStateOf(DrawingMode.Off)
    }
    var drawColor by rememberSaveable(stateSaver = DrawingColorSaver) {
        mutableStateOf(DrawingPalette[0])
    }
    var drawOpacity by rememberSaveable { mutableStateOf(1f) }
    var drawWidth by rememberSaveable { mutableStateOf(DrawingThicknesses[1].widthDp) }
    var drawNib by rememberSaveable { mutableStateOf(Stroke.NIB_BALLPOINT) }

    val pagerState = rememberPagerState(
        initialPage = 0,
        pageCount   = { subPages.size },
    )
    val currentSubPage = subPages.getOrNull(pagerState.currentPage)
    val currentRts = currentSubPage?.let { richTextStates[it.id] }
    val penConfig = PenConfig(
        color    = drawColor,
        opacity  = drawOpacity,
        widthDp  = drawWidth,
        nib      = drawNib,
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = sheetState,
        containerColor   = AppColors.Canvas,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
        ) {
            SheetHeader(onDone = onDismiss)

            // Pager owns the editor body — one rich-text editor +
            // drawing overlay per sub-page, page indicator + add /
            // delete controls at the top.
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                // pageHeight = null makes the pager grow to fill the
                // Box — the sheet is already fullscreen, so the editor
                // surface feels like a whole page of paper.
                SubPageEditorPager(
                    subPages           = subPages,
                    pagerState         = pagerState,
                    richTextStates     = richTextStates,
                    drawingMode        = drawingMode,
                    penConfig          = penConfig,
                    onStrokesChange    = onSubPageStrokesChange,
                    onTextBoxesChange  = onSubPageTextBoxesChange,
                    onLedgerChange     = onSubPageLedgerChange,
                    onLedgerTitleChange = onSubPageLedgerTitleChange,
                    onAddSubPage       = onAddSubPage,
                    onRemoveSubPage    = onRemoveSubPage,
                    onBackgroundChange = onSubPageBackgroundChange,
                    onBgScaleChange    = onSubPageBgScaleChange,
                    onPhotoExported    = onPhotoExported,
                    pageHeight         = null,
                )
            }

            if (drawingMode == DrawingMode.Off) {
                // Only render the format bar when the current sub-page
                // resolves to a live RichTextState. During a re-hydrate
                // or right after a delete the pager can briefly sit on
                // a half-torn state.
                if (currentRts != null) {
                    RichTextFormatBar(
                        state          = currentRts,
                        onEnterDrawing = {
                            focusManager.clearFocus()
                            drawingMode = DrawingMode.Pen
                        },
                    )
                }
            } else {
                DrawingToolbar(
                    mode            = drawingMode,
                    onModeChange    = { drawingMode = it },
                    color           = drawColor,
                    onColorChange   = { drawColor = it },
                    opacity         = drawOpacity,
                    onOpacityChange = { drawOpacity = it },
                    widthDp         = drawWidth,
                    onWidthChange   = { drawWidth = it },
                    nib             = drawNib,
                    onNibChange     = { drawNib = it },
                    onClose         = { drawingMode = DrawingMode.Off },
                )
            }
        }
    }
}

@Composable
private fun SheetHeader(onDone: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start  = AppSpacing.s4,
                end    = AppSpacing.s4,
                top    = AppSpacing.s3,
                bottom = AppSpacing.s3,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Notes",
            style = AppTypography.SectionTitle,
            color = AppColors.TextPrimary,
        )
        Spacer(Modifier.weight(1f))
        Text(
            "Done",
            style    = AppTypography.Button,
            color    = AppAccent.primary,
            modifier = Modifier.clickable(onClick = onDone),
        )
    }
}
