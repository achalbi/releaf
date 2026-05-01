/*
 * PhotoViewerDialog.kt
 *
 * Fullscreen photo viewer used by the Photos section's tap-to-view
 * affordance. Tapping any tile opens this dialog at that tile's index;
 * the user swipes left/right through the remaining photos and taps
 * the close icon (or back) to dismiss.
 *
 * Implemented as a `Dialog` with `usePlatformDefaultWidth = false` so
 * it paints edge-to-edge. We deliberately use a plain black backdrop
 * and `ContentScale.Fit` — this is a "view the photo, don't edit it"
 * surface, so the image is centered and preserved whole rather than
 * cropped or decorated.
 */

package app.releaf.mobile.ui.components.editor

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.releaf.mobile.data.notebook.Attachment
import app.releaf.mobile.ui.theme.AppAccent
import app.releaf.mobile.ui.theme.AppSpacing
import app.releaf.mobile.ui.theme.AppTypography
import coil.compose.AsyncImage

@Composable
fun PhotoViewerDialog(
    photos: List<Attachment>,
    initialIndex: Int,
    onDismiss: () -> Unit,
    /** When non-null, surfaces an "Import this photo to notes" pill
     *  anchored to the bottom of the viewer (mirrors the affordance
     *  on `PdfPageViewerDialog`). Tapping it calls back with the
     *  photo currently on screen (resolved from the pager's current
     *  page). Wrapper at the call site is responsible for any IO
     *  + dismissing the viewer — the dialog only forwards the tap. */
    onImportToNotes: ((Attachment) -> Unit)? = null,
) {
    if (photos.isEmpty()) return
    // Clamp the initial index — defensive in case the caller passed an
    // out-of-range value (e.g. a photo deletion racing the tap).
    val safeInitialIndex = initialIndex.coerceIn(0, photos.size - 1)
    val pagerState = rememberPagerState(
        initialPage = safeInitialIndex,
        pageCount   = { photos.size },
    )

    Dialog(
        onDismissRequest = onDismiss,
        // usePlatformDefaultWidth=false paints the dialog edge-to-edge
        // instead of the default inset card.
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress      = true,
            dismissOnClickOutside   = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            HorizontalPager(
                state       = pagerState,
                modifier    = Modifier.fillMaxSize(),
                pageSpacing = AppSpacing.s2,
            ) { index ->
                val photo = photos.getOrNull(index) ?: return@HorizontalPager
                // Prefer the preview URI if the attachment carries one
                // (e.g. scans) — but for pure photos preview is null and
                // we fall back to the primary URI.
                val model = photo.previewUri ?: photo.uri
                val parsed = runCatching { Uri.parse(model) }.getOrNull()
                if (parsed != null) {
                    AsyncImage(
                        model              = parsed,
                        contentDescription = null,
                        contentScale       = androidx.compose.ui.layout.ContentScale.Fit,
                        modifier           = Modifier.fillMaxSize(),
                    )
                }
            }

            // Top bar — Close on the left, page counter on the right.
            // `statusBars` inset keeps it clear of the notch / status bar.
            val statusPadding = WindowInsets.statusBars.asPaddingValues()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(statusPadding)
                    .padding(AppSpacing.s3),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.45f))
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector        = Icons.Filled.Close,
                        contentDescription = "Close",
                        tint               = Color.White,
                        modifier           = Modifier.size(20.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.45f))
                        .padding(horizontal = AppSpacing.s3, vertical = 6.dp),
                ) {
                    Text(
                        text  = "${pagerState.currentPage + 1} / ${photos.size}",
                        style = AppTypography.Meta,
                        color = Color.White,
                    )
                }
            }

            // Bottom action: import current photo into a new sub-page.
            // Mirrors `ImportPagePill` on PdfPageViewerDialog so both
            // viewers share a visual language for the same intent.
            if (onImportToNotes != null) {
                val bottomInsets = WindowInsets.navigationBars.asPaddingValues()
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(bottomInsets)
                        .padding(AppSpacing.s4),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    ImportPhotoPill(
                        onClick = {
                            photos.getOrNull(pagerState.currentPage)
                                ?.let(onImportToNotes)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ImportPhotoPill(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(AppSpacing.s3))
            .background(AppAccent.primary)
            .clickable(onClick = onClick)
            .padding(horizontal = AppSpacing.s4, vertical = AppSpacing.s2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector        = Icons.AutoMirrored.Filled.NoteAdd,
            contentDescription = null,
            tint               = Color.White,
            modifier           = Modifier.size(18.dp),
        )
        Spacer(Modifier.size(AppSpacing.s2))
        Text(
            text  = "Import this photo to notes",
            style = AppTypography.Button,
            color = Color.White,
        )
    }
}
