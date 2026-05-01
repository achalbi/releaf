/*
 * LedgerEditor.kt
 *
 * Two-column "label + amount" form used when a sub-page's background
 * is set to `SubPage.BG_RULED`. Replaces the rich-text editor for
 * that sub-page — freehand strokes and text boxes are hidden in this
 * mode too, since they would collide with the form rows.
 *
 * The editor renders every stored [LedgerEntry] plus one synthetic
 * blank row at the bottom, so the user can always type a new line.
 * Typing into the blank promotes it to a real entry and a fresh blank
 * id is generated. A live TOTAL row at the bottom sums every entry's
 * `amount` — `null` amounts are skipped so empty rows don't pull the
 * total back to zero. Amount parsing is deliberately forgiving: any
 * non-numeric character (other than a single decimal point) is
 * discarded at input time so the numeric column stays summable.
 */

package app.releaf.mobile.ui.components.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.releaf.mobile.data.common.Uuidv7
import app.releaf.mobile.data.notebook.LedgerEntry
import app.releaf.mobile.ui.theme.AppAccent
import app.releaf.mobile.ui.theme.AppColors
import app.releaf.mobile.ui.theme.AppRadius
import app.releaf.mobile.ui.theme.AppSpacing
import app.releaf.mobile.ui.theme.AppTypography
import java.util.Locale
import app.releaf.mobile.ui.theme.LocalFontWeight

/** How much of the row width the label column consumes. 2/3 matches
 *  `NotesBackground.RuledMarginFraction` exactly — so the vertical
 *  margin line drawn by the ruled background lines up with the
 *  boundary between label and amount in every row. */
private const val LabelFraction = 2f / 3f

/** Matches `NotesBackground.BaseSpacing`. Every ledger row is exactly
 *  one ruled-line tall so text baselines land on the drawn rule. */
private val RowHeight = 24.dp

/** Matches the rich-text editor's top padding on non-ledger sub-pages
 *  (see `SubPageEditorPager`'s `.padding(AppSpacing.s3)`). Keeping the
 *  same padding means the first ruled line ends up at the same y
 *  whether the sub-page is in ledger mode or plain note mode. */
private val TopPadding = 12.dp

/** Thousands-separator / fixed-fraction formatter for the total.
 *  Locale-sensitive so "1,234.50" / "1 234,50" both render correctly. */
private fun formatTotal(value: Double): String =
    String.format(Locale.getDefault(), "%,.2f", value)

/** Parse user input in the amount column. Strips anything that
 *  wouldn't survive `toDouble()` (alpha, spaces, duplicate decimals)
 *  so garbage typing doesn't break the total. Returns the cleaned
 *  string for the field + its parsed Double (null = "no number"). */
private fun normalizeAmountInput(raw: String): Pair<String, Double?> {
    // Keep the first dot, drop subsequent dots; keep minus sign only
    // at position 0.
    val filtered = buildString {
        var seenDot = false
        raw.forEachIndexed { index, c ->
            when {
                c.isDigit() -> append(c)
                c == '.' && !seenDot -> { append('.'); seenDot = true }
                c == '-' && index == 0 -> append(c)
                // drop everything else silently
            }
        }
    }
    if (filtered.isEmpty() || filtered == "-" || filtered == ".") {
        return filtered to null
    }
    return filtered to filtered.toDoubleOrNull()
}

@Composable
fun LedgerEditor(
    entries: List<LedgerEntry>,
    title: String,
    onEntriesChange: (List<LedgerEntry>) -> Unit,
    onTitleChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // A stable id for the synthetic "blank" trailing row. Promoted to
    // an entry id the moment the user types into it; a new blank id is
    // then generated so the next blank row is a fresh one.
    var blankRowId by remember { mutableStateOf(Uuidv7.generate()) }

    // The rows the user actually sees — stored entries plus one
    // always-editable blank at the end.
    val displayed = remember(entries, blankRowId) {
        entries + LedgerEntry(id = blankRowId)
    }

    val total = remember(entries) {
        entries.sumOf { entry ->
            val amt = entry.amount ?: 0.0
            if (entry.isExpense) -amt else amt
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            // Only top padding here — per-row horizontal padding is
            // applied inside each row so the full-width 2/3 column split
            // aligns with the ruled background's vertical margin line.
            .padding(top = TopPadding),
    ) {
        TitleRow(title = title, onTitleChange = onTitleChange)
        HeaderRow()

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(displayed, key = { it.id }) { entry ->
                    LedgerRow(
                        entry = entry,
                        onLabelChange = { newLabel ->
                            commitChange(
                                entryId         = entry.id,
                                blankId         = blankRowId,
                                entries         = entries,
                                newLabel        = newLabel,
                                newAmountText   = entry.amount?.let(::formatAmountForField),
                                newIsExpense    = entry.isExpense,
                                onEntriesChange = onEntriesChange,
                                onPromoteBlank  = { blankRowId = Uuidv7.generate() },
                            )
                        },
                        onAmountChange = { newAmountText ->
                            commitChange(
                                entryId         = entry.id,
                                blankId         = blankRowId,
                                entries         = entries,
                                newLabel        = entry.label,
                                newAmountText   = newAmountText,
                                newIsExpense    = entry.isExpense,
                                onEntriesChange = onEntriesChange,
                                onPromoteBlank  = { blankRowId = Uuidv7.generate() },
                            )
                        },
                        onToggleExpense = {
                            commitChange(
                                entryId         = entry.id,
                                blankId         = blankRowId,
                                entries         = entries,
                                newLabel        = entry.label,
                                newAmountText   = entry.amount?.let(::formatAmountForField),
                                newIsExpense    = !entry.isExpense,
                                onEntriesChange = onEntriesChange,
                                onPromoteBlank  = { blankRowId = Uuidv7.generate() },
                            )
                        },
                    )
                }
            }
        }

        TotalRow(total = total)
    }
}

/** Title row at the very top of the ledger. Spans both columns and
 *  occupies one ruled row so its baseline lands on the first ruled
 *  line. Renders a muted placeholder until the user types — keeps
 *  the page readable when the title hasn't been set yet. */
@Composable
private fun TitleRow(
    title: String,
    onTitleChange: (String) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(RowHeight)
            .padding(horizontal = AppSpacing.s3),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (title.isEmpty()) {
            Text(
                text  = "Title",
                style = AppTypography.SectionTitle.copy(
                    fontSize   = 18.sp,
                    lineHeight = 24.sp,
                    lineHeightStyle = LineHeightStyle(
                        alignment = LineHeightStyle.Alignment.Center,
                        trim      = LineHeightStyle.Trim.None,
                    ),
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                ),
                color = AppColors.TextTertiary,
            )
        }
        BasicTextField(
            value = title,
            onValueChange = onTitleChange,
            singleLine = true,
            textStyle = AppTypography.SectionTitle.copy(
                color      = AppColors.TextPrimary,
                fontSize   = 18.sp,
                lineHeight = 24.sp,
                lineHeightStyle = LineHeightStyle(
                    alignment = LineHeightStyle.Alignment.Center,
                    trim      = LineHeightStyle.Trim.None,
                ),
                platformStyle = PlatformTextStyle(includeFontPadding = false),
            ),
            cursorBrush = SolidColor(AppAccent.primary),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Header occupies the first ruled row. Uses the shared 24sp line-
 *  box metric so its eyebrow label's baseline lands on the first
 *  ruled line just like a typed data row would. */
@Composable
private fun HeaderRow() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(RowHeight),
    ) {
        Text(
            text  = "DESCRIPTION",
            style = AppTypography.Eyebrow.copy(
                lineHeight = 24.sp,
                lineHeightStyle = LineHeightStyle(
                    alignment = LineHeightStyle.Alignment.Center,
                    trim      = LineHeightStyle.Trim.None,
                ),
                platformStyle = PlatformTextStyle(includeFontPadding = false),
            ),
            color = AppColors.TextTertiary,
            modifier = Modifier
                .weight(LabelFraction)
                .padding(start = AppSpacing.s3),
        )
        Text(
            text  = "AMOUNT",
            style = AppTypography.Eyebrow.copy(
                lineHeight = 24.sp,
                lineHeightStyle = LineHeightStyle(
                    alignment = LineHeightStyle.Alignment.Center,
                    trim      = LineHeightStyle.Trim.None,
                ),
                platformStyle = PlatformTextStyle(includeFontPadding = false),
            ),
            color = AppColors.TextTertiary,
            textAlign = TextAlign.End,
            modifier = Modifier
                .weight(1f - LabelFraction)
                .padding(end = AppSpacing.s3),
        )
    }
}

@Composable
private fun LedgerRow(
    entry: LedgerEntry,
    onLabelChange: (String) -> Unit,
    onAmountChange: (String) -> Unit,
    onToggleExpense: () -> Unit,
) {
    // Local text state for the amount field. Formatting from
    // `entry.amount` on every keystroke would eat intermediate input
    // like "1." (which `formatAmountForField(1.0)` rewrites back to
    // "1"), so we keep the raw typed string here and resync only when
    // the stored amount changes to something our local text can't
    // already describe (e.g. a fresh entry is loaded from storage).
    var amountText by remember(entry.id) {
        mutableStateOf(entry.amount?.let(::formatAmountForField) ?: "")
    }
    val localAmount = amountText.toDoubleOrNull()
    if (localAmount != entry.amount &&
        !(amountText.isBlank() && entry.amount == null)
    ) {
        amountText = entry.amount?.let(::formatAmountForField) ?: ""
    }

    // Row height is locked to `RowHeight` (24dp) so the BasicTextField's
    // 24sp line box fills the full row height. That means the text
    // baseline lands on the same y as the ruled line drawn underneath.
    // No per-row border — the ruled paper background already provides
    // the horizontal separator at row-bottom.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(RowHeight),
    ) {
        BasicTextField(
            value = entry.label,
            onValueChange = onLabelChange,
            singleLine = true,
            textStyle = rowTextStyle().copy(color = AppColors.TextPrimary),
            cursorBrush = SolidColor(AppAccent.primary),
            modifier = Modifier
                .weight(LabelFraction)
                .padding(start = AppSpacing.s3),
        )
        // Amount column — sign toggle on the left + amount field on the
        // right. Sits entirely to the right of the ruled margin line at
        // 2/3, so the 2/3 column split stays visually intact.
        Row(
            modifier = Modifier.weight(1f - LabelFraction),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SignToggle(isExpense = entry.isExpense, onToggle = onToggleExpense)
            BasicTextField(
                value = amountText,
                onValueChange = { raw ->
                    val (cleaned, _) = normalizeAmountInput(raw)
                    amountText = cleaned
                    onAmountChange(cleaned)
                },
                singleLine = true,
                textStyle = rowTextStyle().copy(
                    color = AppColors.TextPrimary,
                    textAlign = TextAlign.End,
                ),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                cursorBrush = SolidColor(AppAccent.primary),
                modifier = Modifier
                    .weight(1f)
                    .padding(end = AppSpacing.s3),
            )
        }
    }
}

/** Tiny tappable "+" / "−" that flips the row between earning and
 *  expense. Kept to a single glyph in the primary row rhythm so it
 *  reads as a minimal affordance rather than a chip/button — just
 *  "this row is `+` (earning) or `−` (expense)". */
@Composable
private fun SignToggle(isExpense: Boolean, onToggle: () -> Unit) {
    Text(
        text = if (isExpense) "−" else "+",
        style = rowTextStyle().copy(
            fontWeight = LocalFontWeight.current,
            textAlign  = TextAlign.Center,
        ),
        color = if (isExpense) AppColors.Danger else AppColors.Success,
        modifier = Modifier
            .width(20.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onToggle,
            ),
    )
}

@Composable
private fun TotalRow(total: Double) {
    // Green for net-earning, red for net-expense, muted for break-even.
    // Mirrors the per-row sign-toggle palette so the total's colour
    // "belongs" to the same visual language as the rows above it.
    val amountColor = when {
        total > 0.0 -> AppColors.Success
        total < 0.0 -> AppColors.Danger
        else        -> AppAccent.deep
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = AppSpacing.s3)
            .clip(RoundedCornerShape(AppRadius.md))
            .background(AppAccent.soft)
            .border(
                width = 1.dp,
                color = AppAccent.border,
                shape = RoundedCornerShape(AppRadius.md),
            )
            .padding(horizontal = AppSpacing.s3, vertical = AppSpacing.s3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text  = "TOTAL",
            style = AppTypography.SectionTitle.copy(fontSize = 16.sp),
            color = AppAccent.deep,
            modifier = Modifier.weight(LabelFraction),
        )
        Text(
            text  = formatTotal(total),
            style = AppTypography.SectionTitle.copy(fontSize = 18.sp),
            color = amountColor,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f - LabelFraction),
        )
    }
}

/** Shared text metric. Mirrors the non-ledger `BasicRichTextEditor`
 *  config in `SubPageEditorPager` — 24sp line box, centered, no
 *  font padding — so a ledger row's baseline lands on the ruled line
 *  at the same y a typed text line would on a non-ledger sub-page. */
@Composable
private fun rowTextStyle(): TextStyle =
    AppTypography.Body.copy(
        fontWeight = LocalFontWeight.current,
        lineHeight = 24.sp,
        lineHeightStyle = LineHeightStyle(
            alignment = LineHeightStyle.Alignment.Center,
            trim      = LineHeightStyle.Trim.None,
        ),
        platformStyle = PlatformTextStyle(includeFontPadding = false),
    )

/**
 * Format a stored amount back into a field-friendly string. No
 * thousands separators — those would round-trip incorrectly on
 * re-edit. Strips the ".0" suffix on whole numbers so "50" round-
 * trips as "50" instead of "50.0".
 */
private fun formatAmountForField(value: Double): String {
    val asLong = value.toLong()
    return if (asLong.toDouble() == value) asLong.toString()
    else value.toString()
}

/**
 * Apply one field edit to the entries list. If the edited row is the
 * synthetic blank, promote it to a stored entry (once the label or
 * amount gains content, or the sign is flipped) and signal the caller
 * to mint a new blank id. If the edited row is already stored, update
 * it in place.
 */
private fun commitChange(
    entryId: String,
    blankId: String,
    entries: List<LedgerEntry>,
    newLabel: String,
    newAmountText: String?,
    newIsExpense: Boolean,
    onEntriesChange: (List<LedgerEntry>) -> Unit,
    onPromoteBlank: () -> Unit,
) {
    val (cleanedAmountText, parsedAmount) = newAmountText
        ?.let(::normalizeAmountInput)
        ?: ("" to null)

    val isBlankRow = entryId == blankId
    // Flipping the sign on an otherwise-empty blank row also promotes
    // it — we store `isExpense = true` so the user's "I want this to
    // be an expense" intent isn't lost when they start typing the
    // amount.
    val hasAnyContent =
        newLabel.isNotEmpty() || cleanedAmountText.isNotEmpty() || newIsExpense

    if (isBlankRow) {
        if (!hasAnyContent) return // still empty — nothing to store
        onEntriesChange(
            entries + LedgerEntry(
                id        = entryId,
                label     = newLabel,
                amount    = parsedAmount,
                isExpense = newIsExpense,
            )
        )
        onPromoteBlank()
    } else {
        val updated = entries.map { e ->
            if (e.id == entryId) {
                e.copy(
                    label     = newLabel,
                    amount    = parsedAmount,
                    isExpense = newIsExpense,
                )
            } else {
                e
            }
        }
        onEntriesChange(updated)
    }
}
