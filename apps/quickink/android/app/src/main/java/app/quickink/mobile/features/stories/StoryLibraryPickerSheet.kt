/*
 * StoryLibraryPickerSheet.kt
 *
 * Stories Phase 2 follow-up — single-select capture picker over the
 * user's library. Mirror of iOS `StoryLibraryPickerSheet.swift`.
 */

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.quickink.mobile.features.stories

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.quickink.mobile.QuickInkApp
import app.quickink.mobile.data.capture.CaptureEntity
import app.quickink.mobile.ui.theme.LocalQuickInkColors
import app.quickink.mobile.ui.theme.LocalQuickInkTypography
import app.quickink.mobile.ui.theme.QuickInkRadius
import app.quickink.mobile.ui.theme.QuickInkSpacing
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class StoryPickerFilter { PHOTO, DOCUMENT, ANY }

@Composable
fun StoryLibraryPickerSheet(
    userId: String,
    filter: StoryPickerFilter,
    onPick: (captureId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors   = LocalQuickInkColors.current
    val type     = LocalQuickInkTypography.current
    val context  = LocalContext.current
    val app      = remember(context) { context.applicationContext as QuickInkApp }

    var rows by remember { mutableStateOf<List<CaptureEntity>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(filter, userId) {
        val active = withContext(Dispatchers.IO) {
            app.database.captureDao().activeRows(userId)
        }
        rows = when (filter) {
            StoryPickerFilter.PHOTO    -> active.filter { it.source == "import" }
            StoryPickerFilter.DOCUMENT -> active.filter { it.source == "scan" }
            StoryPickerFilter.ANY      -> active
        }.sortedByDescending { it.createdAt }.take(100)
        loading = false
    }

    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = sheetState,
        containerColor   = colors.surface,
    ) {
        Column(
            modifier = Modifier.padding(
                start  = QuickInkSpacing.s4,
                end    = QuickInkSpacing.s4,
                bottom = QuickInkSpacing.s6,
            ),
        ) {
            Text(text = headerTitle(filter), style = type.editorial, color = colors.ink)
            Text(
                text = headerSubtitle(filter),
                style = type.bodyItalic,
                color = colors.inkSoft,
                modifier = Modifier.padding(bottom = QuickInkSpacing.s3),
            )
            when {
                loading -> {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = QuickInkSpacing.s5),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                    }
                }
                rows.isEmpty() -> {
                    Text(
                        text  = "Nothing in your library yet. Scan or import first.",
                        style = type.bodyItalic,
                        color = colors.inkSoft,
                    )
                }
                else -> {
                    LazyColumn(
                        contentPadding = PaddingValues(bottom = QuickInkSpacing.s3),
                        verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2),
                        modifier = Modifier.height(400.dp),
                    ) {
                        items(rows.size) { idx ->
                            val row = rows[idx]
                            PickerRow(row = row, onClick = { onPick(row.id) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PickerRow(row: CaptureEntity, onClick: () -> Unit) {
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(QuickInkRadius.md))
            .background(colors.bg)
            .border(1.dp, colors.border, RoundedCornerShape(QuickInkRadius.md))
            .clickable(onClick = onClick)
            .padding(QuickInkSpacing.s2 + 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s3),
    ) {
        Thumbnail(uri = row.previewUri)
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text     = row.title?.takeIf { it.isNotEmpty() } ?: "Untitled capture",
                style    = type.editorial,
                color    = colors.ink,
                maxLines = 1,
            )
            Text(
                text     = "${storyPickerSourceLabel(row.source)} · ${formatMonthDay(row.createdAt) ?: "—"}",
                color    = colors.inkSoft,
                fontSize = 11.sp,
            )
        }
    }
}

private fun storyPickerSourceLabel(source: String): String = when (source) {
    "import" -> "photo"
    "photo"  -> "photo"
    "video"  -> "video"
    else     -> "scan"
}

@Composable
private fun Thumbnail(uri: String?) {
    val colors = LocalQuickInkColors.current
    Box(
        modifier = Modifier
            .size(48.dp, 56.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(colors.paper1),
    ) {
        if (!uri.isNullOrEmpty()) {
            AsyncImage(
                model              = uri,
                contentDescription = null,
                modifier           = Modifier.size(48.dp, 56.dp),
                contentScale       = androidx.compose.ui.layout.ContentScale.Crop,
            )
        }
    }
}

private fun headerTitle(filter: StoryPickerFilter): String = when (filter) {
    StoryPickerFilter.PHOTO    -> "Choose a photo"
    StoryPickerFilter.DOCUMENT -> "Choose a document"
    StoryPickerFilter.ANY      -> "Choose from your library"
}

private fun headerSubtitle(filter: StoryPickerFilter): String = when (filter) {
    StoryPickerFilter.PHOTO    -> "Photos you imported via the system picker."
    StoryPickerFilter.DOCUMENT -> "Pages you scanned with the document scanner."
    StoryPickerFilter.ANY      -> "Anything in your library so far."
}

private val MONTH_DAY = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH)

private fun formatMonthDay(iso: String): String? = runCatching {
    OffsetDateTime.parse(iso).format(MONTH_DAY)
}.getOrNull()
