/*
 * NotesEditorSheet.kt
 *
 * Full-height modal bottom sheet that owns the rich-text editor +
 * format bar. Invoked from the Overview tab on the notepad and
 * notebook-page editors: tap the notes preview to open the sheet;
 * dismiss via the handle, a back-swipe, or the "Done" button.
 *
 * The sheet binds to the caller's shared `RichTextState`, so any
 * formatting or typing that happened in the inline Edit-mode editor
 * appears here unchanged, and edits here appear in Edit mode on
 * dismiss.
 */

package app.releaf.mobile.ui.components.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import app.releaf.mobile.ui.theme.AppColors
import app.releaf.mobile.ui.theme.AppSpacing
import app.releaf.mobile.ui.theme.AppTypography
import androidx.compose.foundation.clickable
import com.mohamedrejeb.richeditor.model.RichTextState
import com.mohamedrejeb.richeditor.ui.BasicRichTextEditor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesEditorSheet(
    richTextState: RichTextState,
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

            // Editor body owns the full middle band. No outer
            // `verticalScroll` here — the sheet's only scrollable
            // surface needs to be the editor itself, otherwise the
            // surrounding scroll container will contest text gestures
            // (including double-tap-to-select-a-word) with
            // `BasicRichTextEditor`. Letting the editor fill the box
            // and scroll internally keeps gesture ownership clean.
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.s4, vertical = AppSpacing.s2),
            ) {
                if (richTextState.annotatedString.text.isEmpty()) {
                    Text(
                        "Start typing…",
                        style = AppTypography.Body,
                        color = AppColors.TextTertiary,
                    )
                }
                BasicRichTextEditor(
                    state       = richTextState,
                    textStyle   = AppTypography.Body.copy(color = AppColors.TextPrimary),
                    cursorBrush = SolidColor(AppColors.Coral),
                    modifier    = Modifier.fillMaxSize(),
                )
            }

            RichTextFormatBar(state = richTextState)
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
        androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
        Text(
            "Done",
            style    = AppTypography.Button,
            color    = AppColors.Coral,
            modifier = Modifier.clickable(onClick = onDone),
        )
    }
}
