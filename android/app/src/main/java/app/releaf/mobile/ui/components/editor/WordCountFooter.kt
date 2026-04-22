/*
 * WordCountFooter.kt
 *
 * Muted meta row that shows a live word + character count for the body
 * of a markdown editor. Sits between the editable content and the
 * formatting toolbar on both the notepad and page editors.
 *
 * Word count is whitespace-delimited (good enough for an at-a-glance
 * sense of size; not trying to match wc(1) exactly). Zero-state renders
 * as an em-dash so an empty editor doesn't scream "0 words" at the user.
 */

package app.releaf.mobile.ui.components.editor

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.releaf.mobile.ui.theme.AppColors
import app.releaf.mobile.ui.theme.AppSpacing
import app.releaf.mobile.ui.theme.AppTypography

@Composable
fun WordCountFooter(text: String, modifier: Modifier = Modifier) {
    val words = if (text.isBlank()) 0 else text.trim().split(Regex("\\s+")).size
    val chars = text.length
    val label = if (words == 0) {
        "—"
    } else {
        val w = if (words == 1) "word" else "words"
        "$words $w · $chars chars"
    }
    Text(
        text     = label,
        style    = AppTypography.Meta,
        color    = AppColors.TextTertiary,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.s4, vertical = AppSpacing.s2),
    )
}
