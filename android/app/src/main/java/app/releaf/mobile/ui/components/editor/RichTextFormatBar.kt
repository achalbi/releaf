/*
 * RichTextFormatBar.kt
 *
 * WYSIWYG-aware toolbar. Operates directly on a `RichTextState` from the
 * `richeditor-compose` library — toggles appear "active" when the cursor
 * sits inside a styled span, and tapping them flips the style on/off
 * exactly the way a word-processor toolbar does.
 *
 * Replaces the old `MarkdownFormatBar` (which inserted raw `**` / `- `
 * syntax into a plain-text field). Users now see `bold` render as actual
 * bold text — the markdown string is still the persisted format, but
 * we serialize/deserialize through `state.toMarkdown()` so the editor
 * is WYSIWYG from the user's point of view.
 */

package app.releaf.mobile.ui.components.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import app.releaf.mobile.ui.theme.AppColors
import app.releaf.mobile.ui.theme.AppAccent
import app.releaf.mobile.ui.theme.AppSpacing
import com.mohamedrejeb.richeditor.model.RichTextState

@Composable
fun RichTextFormatBar(
    state: RichTextState,
    modifier: Modifier = Modifier,
    /**
     * When non-null a brush icon is rendered at the trailing edge; tapping
     * it hands control to the freehand-drawing toolbar. Optional so screens
     * that don't want the drawing affordance (e.g. read-only previews)
     * render the stock toolbar unchanged.
     */
    onEnterDrawing: (() -> Unit)? = null,
) {
    val currentSpan = state.currentSpanStyle
    val boldActive      = currentSpan.fontWeight == FontWeight.Bold
    val italicActive    = currentSpan.fontStyle == FontStyle.Italic
    val underlineActive = currentSpan.textDecoration?.contains(TextDecoration.Underline) == true

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(AppColors.CardSolid)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = AppSpacing.s3, vertical = AppSpacing.s2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToggleButton(Icons.Filled.FormatBold, "Bold", active = boldActive) {
            state.toggleSpanStyle(SpanStyle(fontWeight = FontWeight.Bold))
        }
        ToggleButton(Icons.Filled.FormatItalic, "Italic", active = italicActive) {
            state.toggleSpanStyle(SpanStyle(fontStyle = FontStyle.Italic))
        }
        ToggleButton(Icons.Filled.FormatUnderlined, "Underline", active = underlineActive) {
            state.toggleSpanStyle(SpanStyle(textDecoration = TextDecoration.Underline))
        }

        BarDivider()

        ToggleButton(
            Icons.AutoMirrored.Filled.FormatListBulleted,
            "Bulleted list",
            active = state.isUnorderedList,
        ) {
            state.toggleUnorderedList()
        }
        ToggleButton(
            Icons.Filled.FormatListNumbered,
            "Numbered list",
            active = state.isOrderedList,
        ) {
            state.toggleOrderedList()
        }

        BarDivider()

        // Links use a minimal scaffold: "link" text pointing at a placeholder
        // URL. The user can edit both inline. A richer add-link dialog is a
        // nice follow-up once we have a general-purpose text input dialog.
        ToggleButton(Icons.Filled.Link, "Link", active = false) {
            state.addLink(text = "link", url = "https://")
        }

        if (onEnterDrawing != null) {
            BarDivider()
            ToggleButton(Icons.Filled.Brush, "Draw", active = false) {
                onEnterDrawing()
            }
        }
    }
}

@Composable
private fun ToggleButton(
    icon: ImageVector,
    description: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(AppSpacing.s2))
            .background(if (active) AppAccent.primary.copy(alpha = 0.15f) else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector        = icon,
            contentDescription = description,
            tint               = AppAccent.primary,
            modifier           = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun BarDivider() {
    Box(
        Modifier
            .padding(horizontal = AppSpacing.s2)
            .width(1.dp)
            .height(20.dp)
            .background(AppColors.BorderDefault)
    )
}
