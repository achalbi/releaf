/*
 * MergeSection.kt
 *
 * Notepad-page merge panel. Rendered at the bottom of the notepad
 * editor once the current entry is persisted (merging a blank,
 * un-saved draft is nonsensical — there's nothing to fold into).
 *
 * Contract:
 *   - Tap "Choose another notepad page" → opens a modal picker of the
 *     user's other live entries; selecting one fills the row.
 *   - Radio picks which side stays primary. Primary keeps title +
 *     entry-date; the secondary's notes / photos / voice / todos /
 *     scans get appended.
 *   - Tap "Merge pages" → calls `onMerge(otherId, keepThisAsPrimary)`.
 *     Caller is responsible for navigating when the current page
 *     becomes the secondary (and therefore gets soft-deleted).
 */

package app.releaf.mobile.ui.components.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.releaf.mobile.data.notepad.NotepadEntry
import app.releaf.mobile.ui.theme.AppAccent
import app.releaf.mobile.ui.theme.AppColors
import app.releaf.mobile.ui.theme.AppRadius
import app.releaf.mobile.ui.theme.AppSpacing
import app.releaf.mobile.ui.theme.AppTypography

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MergeSection(
    otherEntries: List<NotepadEntry>,
    enabled: Boolean,
    onMerge: (otherId: String, keepThisAsPrimary: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedOther by remember { mutableStateOf<NotepadEntry?>(null) }
    var keepThisAsPrimary by remember { mutableStateOf(true) }
    var showPicker by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            "MERGE",
            style = AppTypography.Eyebrow,
            color = AppAccent.primary,
            modifier = Modifier.padding(bottom = AppSpacing.s2),
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(AppRadius.md))
                .background(AppColors.CardSolid)
                .border(1.dp, AppColors.BorderDefault, RoundedCornerShape(AppRadius.md)),
        ) {
            // Header strip: "Merge pages" + "Notepad merge" tag.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.s4, vertical = AppSpacing.s4),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Merge pages",
                    style = AppTypography.SectionTitle,
                    color = AppColors.TextPrimary,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(AppRadius.pill))
                        .background(AppColors.BorderDefault.copy(alpha = 0.5f))
                        .padding(horizontal = AppSpacing.s3, vertical = 6.dp),
                ) {
                    Text(
                        "Notepad merge",
                        style = AppTypography.Tag,
                        color = AppColors.TextSecondary,
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(AppColors.BorderDefault),
            )

            Column(
                modifier = Modifier.padding(AppSpacing.s4),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.s3),
            ) {
                Text(
                    "Merge this page with another notepad page",
                    style = AppTypography.Body.copy(fontWeight = FontWeight.SemiBold),
                    color = AppColors.TextPrimary,
                )
                Text(
                    "Choose the other page, then decide which one stays primary. The primary page keeps its title and date, while notes, photos, voice notes, to-dos, and scans from the secondary page are appended into it.",
                    style = AppTypography.Body,
                    color = AppColors.TextSecondary,
                )

                Spacer(Modifier.height(AppSpacing.s1))

                Text(
                    "Other page",
                    style = AppTypography.Body.copy(fontWeight = FontWeight.SemiBold),
                    color = AppColors.TextPrimary,
                )

                // Picker trigger row.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(AppRadius.md))
                        .background(AppColors.Canvas)
                        .border(1.dp, AppColors.BorderDefault, RoundedCornerShape(AppRadius.md))
                        .clickable(enabled = enabled && otherEntries.isNotEmpty()) { showPicker = true }
                        .padding(AppSpacing.s4),
                ) {
                    val label = selectedOther?.let { rowLabel(it) }
                        ?: if (otherEntries.isEmpty()) "No other notepad pages yet"
                           else "Choose another notepad page"
                    Text(
                        label,
                        style = AppTypography.Body,
                        color = if (selectedOther != null) AppColors.TextPrimary
                                else AppColors.TextSecondary,
                    )
                }

                Spacer(Modifier.height(AppSpacing.s1))

                Text(
                    "Primary page",
                    style = AppTypography.Body.copy(fontWeight = FontWeight.SemiBold),
                    color = AppColors.TextPrimary,
                )

                RadioRow(
                    label    = "Keep this page as primary",
                    selected = keepThisAsPrimary,
                    onClick  = { keepThisAsPrimary = true },
                )
                RadioRow(
                    label    = "Keep the selected page as primary",
                    selected = !keepThisAsPrimary,
                    onClick  = { keepThisAsPrimary = false },
                )

                Text(
                    "The secondary page is removed after its content is merged into the primary page.",
                    style = AppTypography.Meta,
                    color = AppColors.TextSecondary,
                )

                // CTA row — right-aligned.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    val canMerge = enabled && selectedOther != null
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(AppRadius.pill))
                            .background(if (canMerge) Color(0xFF1B1B1D)
                                        else AppColors.BorderDefault)
                            .clickable(enabled = canMerge) {
                                selectedOther?.let { onMerge(it.id, keepThisAsPrimary) }
                            }
                            .padding(horizontal = AppSpacing.s5, vertical = AppSpacing.s3),
                    ) {
                        Text(
                            "Merge pages",
                            style = AppTypography.Button,
                            color = if (canMerge) Color.White else AppColors.TextSecondary,
                        )
                    }
                }
            }
        }
    }

    if (showPicker) {
        ModalBottomSheet(
            onDismissRequest = { showPicker = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = AppColors.Canvas,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.s4, vertical = AppSpacing.s3),
            ) {
                Text(
                    "Choose another notepad page",
                    style = AppTypography.SectionTitle,
                    color = AppColors.TextPrimary,
                    modifier = Modifier.padding(bottom = AppSpacing.s3),
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.s2),
                ) {
                    items(otherEntries, key = { it.id }) { entry ->
                        PickerRow(
                            entry = entry,
                            isSelected = selectedOther?.id == entry.id,
                            onClick = {
                                selectedOther = entry
                                showPicker = false
                            },
                        )
                    }
                }
                Spacer(Modifier.height(AppSpacing.s4))
            }
        }
    }
}

@Composable
private fun RadioRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.sm))
            .clickable { onClick() }
            .padding(vertical = 6.dp),
    ) {
        // Classic radio: outer ring + inner disc.
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .border(
                    width = 2.dp,
                    color = if (selected) AppAccent.primary else AppColors.BorderStrong,
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(AppAccent.primary),
                )
            }
        }
        Spacer(Modifier.size(AppSpacing.s3))
        Text(
            label,
            style = AppTypography.Body,
            color = AppColors.TextPrimary,
        )
    }
}

@Composable
private fun PickerRow(
    entry: NotepadEntry,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.md))
            .background(AppColors.CardSolid)
            .border(
                1.dp,
                if (isSelected) AppAccent.primary else AppColors.BorderDefault,
                RoundedCornerShape(AppRadius.md),
            )
            .clickable { onClick() }
            .padding(AppSpacing.s4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                rowLabel(entry),
                style = AppTypography.Body.copy(fontWeight = FontWeight.SemiBold),
                color = AppColors.TextPrimary,
            )
            Text(
                entry.entryDate,
                style = AppTypography.Meta,
                color = AppColors.TextSecondary,
            )
        }
        if (isSelected) {
            Text(
                "✓",
                color = AppAccent.primary,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
            )
        }
    }
}

private fun rowLabel(entry: NotepadEntry): String =
    entry.title?.takeIf { it.isNotBlank() } ?: "Untitled (${entry.entryDate})"
