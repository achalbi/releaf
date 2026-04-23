/*
 * PageDetailScreen.kt
 * One page, seven capture modes. Uses the design-system `CaptureTabBar`
 * (icon-pill row) and `StatGrid` (3-up dashboard glance) rather than
 * hand-rolled variants.
 */

package app.releaf.mobile.features.page

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.releaf.mobile.data.domain.Contact
import app.releaf.mobile.data.domain.LocationPin
import app.releaf.mobile.data.domain.Note
import app.releaf.mobile.data.domain.Page
import app.releaf.mobile.data.domain.Photo
import app.releaf.mobile.data.domain.ScannedDocument
import app.releaf.mobile.data.domain.TodoItem
import app.releaf.mobile.data.domain.VoiceNote
import app.releaf.mobile.ui.components.AppButton
import app.releaf.mobile.ui.components.AppButtonVariant
import app.releaf.mobile.ui.components.CaptureMode
import app.releaf.mobile.ui.components.CaptureTabBar
import app.releaf.mobile.ui.components.StatGrid
import app.releaf.mobile.ui.components.StatItem
import app.releaf.mobile.ui.components.StatTone
import app.releaf.mobile.ui.theme.AppColors
import app.releaf.mobile.ui.theme.AppAccent
import app.releaf.mobile.ui.theme.AppRadius
import app.releaf.mobile.ui.theme.AppSpacing
import app.releaf.mobile.ui.theme.AppTypography
import app.releaf.mobile.ui.theme.Card

@Composable
fun PageDetailScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PageDetailViewModel = viewModel(factory = PageDetailViewModel.Factory),
) {
    val state by viewModel.state.collectAsState()

    Box(modifier.fillMaxSize()) {
        when (val s = state) {
            PageDetailUiState.Loading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = AppAccent.primary)
                }
            }
            is PageDetailUiState.Failed -> {
                Column(
                    Modifier.fillMaxSize().padding(AppSpacing.s4),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(s.message, style = AppTypography.Body, color = AppColors.TextSecondary)
                    Spacer(Modifier.height(AppSpacing.s3))
                    AppButton(
                        "Try again",
                        onClick = viewModel::load,
                        variant = AppButtonVariant.Secondary,
                        fillWidth = false,
                    )
                    Spacer(Modifier.height(AppSpacing.s3))
                    AppButton("Back", onClick = onBack, variant = AppButtonVariant.Text, fillWidth = false)
                }
            }
            is PageDetailUiState.Loaded -> Loaded(page = s.page, onBack = onBack)
        }
    }
}

@Composable
private fun Loaded(page: Page, onBack: () -> Unit) {
    var selected by remember { mutableStateOf(CaptureMode.Overview) }
    val scroll = rememberScrollState()

    Column(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.padding(
                start = AppSpacing.s4, end = AppSpacing.s4,
                top = AppSpacing.s4, bottom = AppSpacing.s3,
            ),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.s2),
        ) {
            Text(
                "← Back",
                style = AppTypography.Button,
                color = AppAccent.primary,
                modifier = Modifier.clickable { onBack() },
            )
            Text("PAGE", style = AppTypography.Eyebrow, color = AppAccent.primary)
            Text(page.title, style = AppTypography.PageTitle, color = AppColors.TextPrimary)
            page.capturedOn?.let {
                Text(it, style = AppTypography.Meta, color = AppColors.TextTertiary)
            }
        }

        CaptureTabBar(
            selected = selected,
            onSelect = { selected = it },
        )

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(AppSpacing.s4),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.s4),
        ) {
            when (selected) {
                CaptureMode.Overview -> OverviewSection(page)
                CaptureMode.Photos   -> PhotosSection(page.photos)
                CaptureMode.Voice    -> VoiceSection(page.voiceNotes)
                CaptureMode.Todo     -> TodoSection(page.todoItems)
                CaptureMode.Scans    -> ScansSection(page.scannedDocuments)
                CaptureMode.Contacts -> ContactsSection(page.contacts)
                CaptureMode.Location -> LocationsSection(page.locations)
            }
            Spacer(Modifier.height(AppSpacing.s10))
        }
    }
}

/* ---------- mode sections ---------- */

@Composable
private fun OverviewSection(page: Page) {
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.s4)) {
        val c = page.counts
        Text(
            "AT A GLANCE",
            style = AppTypography.Eyebrow,
            color = AppColors.TextSecondary,
        )
        StatGrid(
            items = listOf(
                StatItem("Photos", "${c.photos}",           StatTone.Coral),
                StatItem("Voice",  "${c.voiceNotes}",       StatTone.Neutral),
                StatItem("To-do",  "${c.todoItems}",        StatTone.Green),
            ),
        )
        StatGrid(
            items = listOf(
                StatItem("Scans",    "${c.scannedDocuments}", StatTone.Neutral),
                StatItem("Contacts", "${c.contacts}",         StatTone.Info),
                StatItem("Places",   "${c.locations}",        StatTone.Neutral),
            ),
        )
        if (page.notes.isEmpty()) {
            EmptyState("Nothing written on this page yet.")
        } else {
            page.notes.forEach { NoteCard(it) }
        }
    }
}

@Composable
private fun NoteCard(note: Note) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Text(note.body, style = AppTypography.Body, color = AppColors.TextPrimary)
    }
}

@Composable
private fun PhotosSection(photos: List<Photo>) {
    if (photos.isEmpty()) { EmptyState("No photos on this page."); return }
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.s3)) {
        photos.forEach { PhotoTile(it) }
    }
}

@Composable
private fun PhotoTile(photo: Photo) {
    Card {
        Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.s2)) {
            // Placeholder tile — real thumb comes with Drive.downloadBytes later.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(AppRadius.md))
                    .border(
                        width = 1.dp,
                        color = AppColors.BorderDefault,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(AppRadius.md)
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    photo.caption ?: "Photo",
                    style = AppTypography.Meta,
                    color = AppColors.TextTertiary,
                )
            }
            photo.caption?.let {
                Text(it, style = AppTypography.Body, color = AppColors.TextPrimary)
            }
        }
    }
}

@Composable
private fun VoiceSection(notes: List<VoiceNote>) {
    if (notes.isEmpty()) { EmptyState("No voice notes on this page."); return }
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.s3)) {
        notes.forEach { VoiceCard(it) }
    }
}

@Composable
private fun VoiceCard(note: VoiceNote) {
    Card {
        Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.s2)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Voice note · ${formatDuration(note.durationMs)}",
                    style = AppTypography.SectionTitle,
                    color = AppColors.TextPrimary,
                    modifier = Modifier.weight(1f),
                )
                Text("▶︎ Play", style = AppTypography.Button, color = AppAccent.primary)
            }
            note.transcription?.let {
                Text(
                    "\u201C$it\u201D",
                    style = AppTypography.Body.copy(fontStyle = FontStyle.Italic),
                    color = AppColors.TextSecondary,
                )
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

@Composable
private fun TodoSection(items: List<TodoItem>) {
    if (items.isEmpty()) { EmptyState("Nothing on the to-do list."); return }
    Card {
        Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.s3)) {
            items.sortedBy { it.position }.forEach { TodoRow(it) }
        }
    }
}

@Composable
private fun TodoRow(item: TodoItem) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = if (item.done) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
            contentDescription = if (item.done) "Done" else "Not done",
            tint = if (item.done) AppAccent.primary else AppColors.TextTertiary,
            modifier = Modifier.padding(end = AppSpacing.s2),
        )
        Text(
            item.body,
            style = AppTypography.Body.copy(
                textDecoration = if (item.done) TextDecoration.LineThrough else TextDecoration.None,
            ),
            color = if (item.done) AppColors.TextTertiary else AppColors.TextPrimary,
        )
    }
}

@Composable
private fun ScansSection(scans: List<ScannedDocument>) {
    if (scans.isEmpty()) { EmptyState("No scanned documents."); return }
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.s3)) {
        scans.forEach { ScanRow(it) }
    }
}

@Composable
private fun ScanRow(scan: ScannedDocument) {
    Card {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .padding(AppSpacing.s0)
            ) {
                Text(
                    "📄",
                    style = AppTypography.StatNumber,
                    color = AppAccent.primary,
                    modifier = Modifier.padding(end = AppSpacing.s3),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(scan.title, style = AppTypography.SectionTitle, color = AppColors.TextPrimary)
                Text(
                    "${scan.pageCount} page${if (scan.pageCount == 1) "" else "s"}",
                    style = AppTypography.Meta,
                    color = AppColors.TextSecondary,
                )
            }
        }
    }
}

@Composable
private fun ContactsSection(contacts: List<Contact>) {
    if (contacts.isEmpty()) { EmptyState("No contacts pinned to this page."); return }
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.s3)) {
        contacts.forEach { ContactCard(it) }
    }
}

@Composable
private fun ContactCard(contact: Contact) {
    Card {
        Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.s1)) {
            Text(contact.name, style = AppTypography.SectionTitle, color = AppColors.TextPrimary)
            contact.phone?.let {
                Text(it, style = AppTypography.Body, color = AppColors.TextSecondary)
            }
            contact.email?.let {
                Text(it, style = AppTypography.Body, color = AppColors.TextSecondary)
            }
            contact.notes?.let {
                Text(it, style = AppTypography.Meta, color = AppColors.TextTertiary)
            }
        }
    }
}

@Composable
private fun LocationsSection(pins: List<LocationPin>) {
    if (pins.isEmpty()) { EmptyState("No places on this page."); return }
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.s3)) {
        pins.forEach { LocationCard(it) }
    }
}

@Composable
private fun LocationCard(pin: LocationPin) {
    Card {
        Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.s1)) {
            Text(pin.name, style = AppTypography.SectionTitle, color = AppColors.TextPrimary)
            Text(
                "%.4f, %.4f".format(pin.latitude, pin.longitude),
                style = AppTypography.Meta,
                color = AppColors.TextSecondary,
            )
            pin.notes?.let {
                Text(it, style = AppTypography.Body, color = AppColors.TextPrimary)
            }
        }
    }
}

@Composable
private fun EmptyState(message: String) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = AppSpacing.s6),
        contentAlignment = Alignment.Center,
    ) {
        Text(message, style = AppTypography.Body, color = AppColors.TextTertiary)
    }
}
