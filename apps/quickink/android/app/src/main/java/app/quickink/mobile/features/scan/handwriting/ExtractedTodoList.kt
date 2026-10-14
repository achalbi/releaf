/*
 * ExtractedTodoList.kt
 *
 * Shared UI for to-dos read off checkbox lines. Two surfaces use it:
 *
 *   - `ScanReviewScreen` (editable) — the confirm step before the
 *     items are saved: edit text, tick / untick, remove.
 *   - `NoteEditorScreen` (read + toggle) — the day's saved to-dos in
 *     place of the old "Coming soon" smart-suggestions card.
 *
 * Mirror of iOS `ExtractedTodoList.swift`.
 */

package app.quickink.mobile.features.scan.handwriting

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import app.quickink.mobile.ui.theme.LocalQuickInkColors
import app.quickink.mobile.ui.theme.LocalQuickInkTypography
import app.quickink.mobile.ui.theme.QuickInkSpacing

/**
 * Review-screen section: "TO-DOS FROM THIS SCAN" with one editable row
 * per item. Renders nothing for an empty list.
 */
@Composable
fun ExtractedTodosReviewSection(
    items: List<HandwritingTodo>,
    onToggle: (HandwritingTodo) -> Unit,
    onEdit: (HandwritingTodo, String) -> Unit,
    onRemove: (HandwritingTodo) -> Unit,
) {
    if (items.isEmpty()) return
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    Column(verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2)) {
        Text(text = "TO-DOS FROM THIS SCAN", style = type.eyebrow, color = colors.muted)
        Text(
            text = "Saved to the day's note when you tap Done. Edit, untick or remove anything that was misread.",
            style = type.caption,
            color = colors.inkSoft,
        )
        items.forEach { todo ->
            ExtractedTodoRow(
                todo = todo,
                onToggle = { onToggle(todo) },
                onTextChange = { onEdit(todo, it) },
                onRemove = { onRemove(todo) },
            )
        }
    }
}

/**
 * One to-do row. [onTextChange] / [onRemove] null → read-only text
 * (the note editor); the checkbox always toggles.
 */
@Composable
fun ExtractedTodoRow(
    todo: HandwritingTodo,
    onToggle: () -> Unit,
    onTextChange: ((String) -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    val textStyle = type.body.copy(
        color = if (todo.done) colors.muted else colors.ink,
        textDecoration = if (todo.done) TextDecoration.LineThrough else null,
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2),
    ) {
        Icon(
            imageVector = if (todo.done) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank,
            contentDescription = if (todo.done) "Mark not done" else "Mark done",
            tint = if (todo.done) colors.accent else colors.inkSoft,
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .clickable(onClick = onToggle),
        )
        if (onTextChange != null) {
            BasicTextField(
                value = todo.text,
                onValueChange = onTextChange,
                textStyle = textStyle,
                cursorBrush = SolidColor(colors.accent),
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        } else {
            Text(text = todo.text, style = textStyle, modifier = Modifier.weight(1f))
        }
        if (onRemove != null) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "Remove to-do",
                tint = colors.muted,
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onRemove)
                    .padding(2.dp),
            )
        }
    }
}
