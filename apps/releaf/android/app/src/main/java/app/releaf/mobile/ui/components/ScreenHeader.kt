/*
 * ScreenHeader.kt
 *
 * Sticky screen header used by the notebook / chapter / page surfaces:
 * an eyebrow label and serif display title. The header is fixed at the top
 * of the screen — breadcrumbs render *below* it as a separate row so the
 * trail can grow without pushing the title off.
 */

package app.releaf.mobile.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.releaf.mobile.ui.theme.AppColors
import app.releaf.mobile.ui.theme.AppSpacing
import app.releaf.mobile.ui.theme.AppTypography

@Composable
fun ScreenHeader(
    eyebrow: String,
    title: String,
    modifier: Modifier = Modifier,
    /** Override the default top padding. Top-level tabs (Notepad,
     *  Notebook) use [AppSpacing.s3] so their headers share the same
     *  vertical rhythm; drill-in surfaces stay on the [AppSpacing.s4]
     *  default for breathing room above breadcrumbs. */
    topPadding: Dp = AppSpacing.s4,
    /** Style override for the title. Defaults to the heavy serif
     *  EditorialTitle; pass a lighter variant for screens that want
     *  the title to read as a label rather than a display heading. */
    titleStyle: TextStyle = AppTypography.EditorialTitle,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = AppSpacing.s4,
                end = AppSpacing.s4,
                top = topPadding,
                bottom = AppSpacing.s3,
            ),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.s3),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = eyebrow.uppercase(),
                style = AppTypography.Eyebrow,
                color = AppColors.TextTertiary,
            )
            Text(
                text = title,
                style = titleStyle,
                color = AppColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
