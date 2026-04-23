/*
 * ScreenHeader.kt
 *
 * Sticky screen header used by the notebook / chapter / page surfaces:
 * an eyebrow label, a serif display title, and a trailing avatar chip. The
 * header is fixed at the top of the screen — breadcrumbs render *below* it
 * as a separate row so the trail can grow without pushing the title off.
 */

package app.releaf.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.releaf.mobile.ui.theme.AppAccent
import app.releaf.mobile.ui.theme.AppColors
import app.releaf.mobile.ui.theme.AppSpacing
import app.releaf.mobile.ui.theme.AppTypography

@Composable
fun ScreenHeader(
    eyebrow: String,
    title: String,
    modifier: Modifier = Modifier,
    avatarInitial: String? = null,
    onAvatarTap: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = AppSpacing.s4,
                end = AppSpacing.s4,
                top = AppSpacing.s4,
                bottom = AppSpacing.s3,
            ),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.s3),
        verticalAlignment = Alignment.CenterVertically,
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
                style = AppTypography.EditorialTitle,
                color = AppColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (avatarInitial != null) {
            AvatarChip(initial = avatarInitial, onTap = onAvatarTap)
        }
    }
}

@Composable
private fun AvatarChip(initial: String, onTap: (() -> Unit)?) {
    val base = Modifier
        .size(44.dp)
        .clip(CircleShape)
        .background(AppAccent.soft)
        .border(1.dp, AppAccent.border, CircleShape)
    val clickable = if (onTap != null) base.clickable(onClick = onTap) else base
    Box(
        modifier = clickable,
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initial.take(1).uppercase(),
            style = AppTypography.Button,
            color = AppAccent.deep,
        )
    }
}
