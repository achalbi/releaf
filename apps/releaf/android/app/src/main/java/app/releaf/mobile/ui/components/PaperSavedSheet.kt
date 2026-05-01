/*
 * PaperSavedSheet.kt
 *
 * ModalBottomSheet that opens when the RE-LEAF eyebrow on the strip is
 * tapped. Shows the math behind the two numbers in the strip:
 *
 *   - per-kind table  (count × sheets/each = sheets)
 *   - total           (sum)
 *   - tree readout    (sheets ÷ sheetsPerTree)
 *   - honest closing  copy on what the number means
 *
 * Mirrors `PaperSavedSheet.swift` (iOS).
 */

package app.releaf.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.releaf.mobile.ui.theme.AppColors
import app.releaf.mobile.ui.theme.AppMetrics
import app.releaf.mobile.ui.theme.AppRadius
import app.releaf.mobile.ui.theme.AppSpacing
import app.releaf.mobile.ui.theme.AppTypography
import app.releaf.mobile.ui.theme.ReleafImpact
import java.text.NumberFormat
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaperSavedSheet(
    photos: Int,
    voiceNotes: Int,
    todoItems: Int,
    scans: Int,
    contacts: Int,
    places: Int,
    notes: Int,
    onDismiss: () -> Unit,
    /** Optional accent override for the eyebrow + summary tile
     *  tones. Nil → defaults to the green theme. PageDetail
     *  passes the parent-notebook color so the explainer matches
     *  the surface that opened it. Per-capture-mode glyphs in
     *  the breakdown rows stay fixed (semantic color codes). */
    accentOverride: androidx.compose.ui.graphics.Color? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = sheetState,
        containerColor   = AppColors.CardSolid,
        contentColor     = AppColors.TextPrimary,
        shape            = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        SheetBody(
            photos = photos, voiceNotes = voiceNotes, todoItems = todoItems,
            scans = scans, contacts = contacts, places = places, notes = notes,
            accentOverride = accentOverride,
            onClose = onDismiss,
        )
    }
}

@Composable
private fun SheetBody(
    photos: Int,
    voiceNotes: Int,
    todoItems: Int,
    scans: Int,
    contacts: Int,
    places: Int,
    notes: Int,
    accentOverride: androidx.compose.ui.graphics.Color?,
    onClose: () -> Unit,
) {
    val impact = ReleafImpact.from(
        photos = photos, voiceNotes = voiceNotes, todoItems = todoItems,
        scans = scans, contacts = contacts, places = places, notes = notes,
    )
    val totalCaptures = photos + voiceNotes + todoItems + scans + contacts + places + notes
    val scroll = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(scroll)
            .padding(horizontal = AppSpacing.s5)
            .padding(top = AppSpacing.s4, bottom = AppSpacing.s6),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.s3),
    ) {
        val eyebrowTint = accentOverride ?: AppColors.ThemeGreenDeep
        Text("RE-LEAF",                style = AppTypography.Eyebrow,        color = eyebrowTint)
        Text("how paper saved is counted", style = AppTypography.EditorialTitle, color = AppColors.TextPrimary)

        Spacer(Modifier.height(AppSpacing.s1))

        SummaryTiles(impact = impact, accentTint = eyebrowTint)

        Spacer(Modifier.height(AppSpacing.s2))

        Text(
            "PER CAPTURE · ON THIS PAGE",
            style = AppTypography.Eyebrow,
            color = AppColors.TextSecondary,
        )

        BreakdownRows(
            scans      = scans,
            notes      = notes,
            voiceNotes = voiceNotes,
            contacts   = contacts,
            places     = places,
            photos     = photos,
            todoItems  = todoItems,
        )

        TotalRow(impact = impact, totalCaptures = totalCaptures)

        Spacer(Modifier.height(AppSpacing.s2))

        Text(
            text  = rationale(),
            style = AppTypography.Meta,
            color = AppColors.TextTertiary,
        )

        AppButton(
            "Close",
            onClick   = onClose,
            variant   = AppButtonVariant.Primary,
            modifier  = Modifier.padding(top = AppSpacing.s3),
        )
    }
}

@Composable
private fun SummaryTiles(impact: ReleafImpact, accentTint: Color) {
    val readout = impact.treeReadout
    Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.s2)) {
        SummaryTile(
            eyebrow     = "SHEETS",
            value       = impact.formattedSheets,
            caption     = "across this page",
            eyebrowTint = accentTint,
            modifier    = Modifier.weight(1f),
        )
        SummaryTile(
            eyebrow     = "TREES",
            value       = readout.number,
            caption     = readout.unit,
            eyebrowTint = AppColors.TextSecondary,
            modifier    = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SummaryTile(
    eyebrow: String,
    value: String,
    caption: String,
    eyebrowTint: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(AppRadius.md))
            .background(AppColors.Canvas)
            .padding(AppSpacing.s3),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.s1),
    ) {
        Text(eyebrow, style = AppTypography.Eyebrow,        color = eyebrowTint)
        Text(value,   style = AppTypography.EditorialTitle, color = AppColors.TextPrimary, maxLines = 1)
        Text(caption, style = AppTypography.Tag,            color = AppColors.TextTertiary)
    }
}

@Composable
private fun BreakdownRows(
    scans: Int,
    notes: Int,
    voiceNotes: Int,
    contacts: Int,
    places: Int,
    photos: Int,
    todoItems: Int,
) {
    val m = AppMetrics.PaperPerCapture
    Column {
        BreakdownRow("scans",       scans,      m.Scan,    AppColors.Green)
        BreakdownRow("notes",       notes,      m.Note,    AppColors.ThemeGreenPrimary)
        BreakdownRow("voice notes", voiceNotes, m.Voice,   AppColors.Warning)
        BreakdownRow("contacts",    contacts,   m.Contact, AppColors.Info)
        BreakdownRow("places",      places,     m.Place,   AppColors.CoralDeep)
        BreakdownRow("photos",      photos,     m.Photo,   AppColors.ThemeGreenPrimary.copy(alpha = 0.7f))
        BreakdownRow("to-do",       todoItems,  m.Todo,    AppColors.ThemeGreenPrimary.copy(alpha = 0.55f), isLast = true)
    }
}

@Composable
private fun BreakdownRow(
    label: String,
    count: Int,
    multiplier: Double,
    glyph: Color,
    isLast: Boolean = false,
) {
    val subtotal = count * multiplier
    Column {
        Row(
            modifier              = Modifier.padding(vertical = AppSpacing.s2),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.s1),
        ) {
            LeafDropletGlyph(tint = glyph)
            Text(label, style = AppTypography.Body, color = AppColors.TextPrimary, modifier = Modifier.weight(1f))
            Text(
                text     = "$count × ${formatMultiplier(multiplier)}",
                style    = AppTypography.Tag,
                color    = AppColors.TextTertiary,
                modifier = Modifier.padding(end = AppSpacing.s3),
            )
            Text(
                text     = String.format(Locale.US, "%.2f", subtotal),
                style    = AppTypography.Body,
                color    = AppColors.TextPrimary,
                textAlign = TextAlign.End,
                modifier = Modifier.widthIn(min = 44.dp),
            )
        }
        if (!isLast) {
            Box(modifier = Modifier.fillMaxWidth().height(0.5.dp).background(AppColors.BorderDefault))
        }
    }
}

@Composable
private fun TotalRow(impact: ReleafImpact, totalCaptures: Int) {
    Column {
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(AppColors.BorderStrong))
        Row(
            modifier              = Modifier.padding(vertical = AppSpacing.s3).fillMaxWidth(),
            verticalAlignment     = Alignment.CenterVertically,
        ) {
            Text(
                text     = "total · $totalCaptures capture${if (totalCaptures == 1) "" else "s"}",
                style    = AppTypography.Body,
                color    = AppColors.TextPrimary,
                modifier = Modifier.weight(1f),
            )
            Text(
                text  = impact.formattedSheets,
                style = AppTypography.EditorialTitle,
                color = AppColors.Green,
            )
        }
    }
}

private fun rationale(): String {
    val sheets = NumberFormat.getInstance(Locale.US).format(AppMetrics.sheetsPerTree.toLong())
    return "a mature pine yields about $sheets letter-size sheets. " +
        "each capture is rated against what it would have replaced on " +
        "paper — a scan against one printed page, a note against a " +
        "quarter, a voice note against a tenth. small on its own. " +
        "across years of notebooks, less so."
}

private fun formatMultiplier(value: Double): String {
    val formatted = String.format(Locale.US, "%.2f", value)
    return if (formatted.endsWith("0")) formatted.dropLast(1) else formatted
}
