/*
 * LocationDetailScreen.kt
 *
 * Workspace drill-in for a single location row — lists every active
 * capture attached to it via `capture_locations`. Mirror of the
 * FolderDetailScreen shape (back bar + count + LazyColumn of doc
 * rows). No tag-filter strip here yet; locations are simpler than
 * folders (no axis-of-its-own).
 */

package app.quickink.mobile.features.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.net.Uri
import app.quickink.mobile.QuickInkApp
import app.quickink.mobile.data.capture.CaptureEntity
import app.quickink.mobile.data.location.LocationEntity
import app.quickink.mobile.features.nav.QuickInkBottomNavReservedHeight
import app.quickink.mobile.ui.theme.LocalQuickInkColors
import app.quickink.mobile.ui.theme.LocalQuickInkTypography
import app.quickink.mobile.ui.theme.QuickInkRadius
import app.quickink.mobile.ui.theme.QuickInkSpacing
import coil.compose.AsyncImage
import coil.request.ImageRequest

@Composable
fun LocationDetailScreen(
    locationId: String,
    userId: String,
    onBack: () -> Unit,
    onOpenCapture: (CaptureEntity) -> Unit,
) {
    val colors  = LocalQuickInkColors.current
    val type    = LocalQuickInkTypography.current
    val context = LocalContext.current
    val app     = remember(context) { context.applicationContext as QuickInkApp }

    val location by produceState<LocationEntity?>(initialValue = null, key1 = locationId) {
        value = app.database.locationDao().findById(locationId)
    }

    // capture_id list for this location → filtered against the user's
    // active captures so deleted / tombstoned rows don't leak through.
    val captureIds by remember(locationId) {
        app.database.captureLocationDao().observeCaptureIdsForLocation(locationId)
    }.collectAsState(initial = emptyList())

    val allActive by remember(userId) {
        app.database.captureDao().observeActive(userId)
    }.collectAsState(initial = emptyList())

    val captures: List<CaptureEntity> = remember(captureIds, allActive) {
        if (captureIds.isEmpty()) emptyList()
        else {
            val byId = allActive.associateBy { it.id }
            captureIds.mapNotNull { byId[it] }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top    = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
                              + QuickInkSpacing.s2,
                    bottom = QuickInkBottomNavReservedHeight,
                ),
        ) {
            LocationBar(
                location     = location,
                captureCount = captures.size,
                onBack       = onBack,
            )

            Spacer(Modifier.height(QuickInkSpacing.s2))

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = QuickInkSpacing.s4),
            ) {
                items(captures, key = { it.id }) { capture ->
                    DocListRow(
                        capture = capture,
                        onClick = { onOpenCapture(capture) },
                    )
                }
                if (captures.isEmpty()) {
                    item {
                        Text(
                            text     = "No documents attached to this location yet.",
                            style    = type.meta,
                            color    = colors.muted,
                            modifier = Modifier.padding(vertical = QuickInkSpacing.s4),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LocationBar(
    location: LocationEntity?,
    captureCount: Int,
    onBack: () -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = QuickInkSpacing.s2, vertical = QuickInkSpacing.s1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector        = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = "Back",
                tint               = colors.ink,
                modifier           = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(QuickInkSpacing.s2))
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(colors.accentSoft.copy(alpha = 0.7f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector        = Icons.Filled.LocationOn,
                contentDescription = null,
                tint               = colors.accent,
                modifier           = Modifier.size(15.dp),
            )
        }
        Spacer(Modifier.width(QuickInkSpacing.s2))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text     = location?.name ?: "…",
                style    = type.editorial.copy(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
                color    = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val sub = location?.address?.takeIf { it.isNotBlank() }
            if (sub != null) {
                Text(
                    text     = sub,
                    style    = type.meta.copy(fontSize = 11.sp),
                    color    = colors.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text  = "$captureCount ${if (captureCount == 1) "document" else "documents"}",
                style = type.meta.copy(fontSize = 11.sp),
                color = colors.muted,
            )
        }
    }
}

@Composable
internal fun DocListRow(
    capture: CaptureEntity,
    onClick: () -> Unit,
) {
    val colors  = LocalQuickInkColors.current
    val type    = LocalQuickInkTypography.current
    val context = LocalContext.current

    val title = capture.title?.takeIf { it.isNotBlank() } ?: "Untitled scan"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 13.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .size(width = 56.dp, height = 72.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(colors.borderSoft),
            contentAlignment = Alignment.Center,
        ) {
            val previewUri = capture.previewUri
            if (previewUri.isNullOrBlank()) {
                Icon(
                    imageVector        = Icons.Filled.Description,
                    contentDescription = null,
                    tint               = colors.muted,
                    modifier           = Modifier.size(20.dp),
                )
            } else {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(Uri.parse(previewUri))
                        .crossfade(true)
                        .build(),
                    contentDescription = title,
                    contentScale       = ContentScale.Crop,
                    modifier           = Modifier.fillMaxSize(),
                )
            }
        }

        Spacer(Modifier.width(QuickInkSpacing.s3))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text     = title,
                style    = type.body.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                color    = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(5.dp))
            Text(
                text  = buildString {
                    append(capture.pageCount)
                    append(if (capture.pageCount == 1) " page" else " pages")
                    append(" · ")
                    append(capture.createdAt.take(10))
                },
                style = type.meta.copy(fontSize = 11.5.sp),
                color = colors.muted,
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(colors.borderSoft),
    )
}
