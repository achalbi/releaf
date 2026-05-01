package app.releaf.mobile.features.notepad.recents.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.EventNote
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.releaf.mobile.features.notepad.recents.model.CaptureCounts
import app.releaf.mobile.features.notepad.recents.model.RecentsPage
import app.releaf.mobile.features.notepad.recents.model.isImported
import app.releaf.mobile.features.notepad.recents.theme.AccentImport
import app.releaf.mobile.features.notepad.recents.theme.BgFeatured
import app.releaf.mobile.features.notepad.recents.theme.BgSurface
import app.releaf.mobile.features.notepad.recents.theme.BorderFaint
import app.releaf.mobile.features.notepad.recents.theme.Green200
import app.releaf.mobile.features.notepad.recents.theme.Green600
import app.releaf.mobile.features.notepad.recents.theme.TextGreen
import app.releaf.mobile.features.notepad.recents.theme.TextGreenMuted
import app.releaf.mobile.features.notepad.recents.theme.TextMuted
import app.releaf.mobile.features.notepad.recents.theme.TextPrimary
import app.releaf.mobile.features.notepad.recents.theme.Type
import app.releaf.mobile.ui.components.CaptureMode
import java.time.format.DateTimeFormatter

/**
 * 2-column grid of past pages — one card per [RecentsPage], sorted
 * by `createdAt` desc by the caller. The most-recently-created page
 * is auto-promoted to a tall card spanning two rows on the left;
 * remaining cards flow as regular tiles in column-2 / paired rows
 * below.
 *
 * Empty days don't surface here anymore — the grid is a *page* feed,
 * so days with no captures simply contribute zero cards.
 */
@Composable
fun EarlierGrid(
    pages: List<RecentsPage>,
    onOpenPage: (RecentsPage) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (pages.isEmpty()) return

    val featured = pages.first()
    val rest = pages.drop(1)

    // Right column hosts the next two pages, then any remaining pages
    // flow in 2-up rows below the featured tall card.
    val rightTop = rest.getOrNull(0)
    val rightBottom = rest.getOrNull(1)
    val below = if (rest.size > 2) rest.subList(2, rest.size) else emptyList()

    val rowHeight = 132.dp
    val tallHeight = rowHeight * 2 + 12.dp

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Tall featured (most-recently-created page).
            Box(modifier = Modifier.weight(1f).height(tallHeight)) {
                TallPageCard(
                    page = featured,
                    onClick = { onOpenPage(featured) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // Right column with two regular cards stacked.
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                rightTop?.let {
                    RegularPageCard(
                        page = it,
                        onClick = { onOpenPage(it) },
                        modifier = Modifier.fillMaxWidth().height(rowHeight),
                    )
                }
                rightBottom?.let {
                    RegularPageCard(
                        page = it,
                        onClick = { onOpenPage(it) },
                        modifier = Modifier.fillMaxWidth().height(rowHeight),
                    )
                }
            }
        }
        // Remaining cards flow in 2-up pairs below.
        below.chunked(2).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                row.forEach { p ->
                    Box(modifier = Modifier.weight(1f).height(rowHeight)) {
                        RegularPageCard(
                            page = p,
                            onClick = { onOpenPage(p) },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Card variants
// ---------------------------------------------------------------------------

private val DATE_FMT = DateTimeFormatter.ofPattern("MMM d")
private val TIME_FMT = DateTimeFormatter.ofPattern("h:mm a")

@Composable
private fun TallPageCard(
    page: RecentsPage,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(BgFeatured)
            .clickable(onClick = onClick)
            .padding(14.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            BasicText(
                text = headerLabel(page),
                style = Type.MicroLabel.copy(color = TextGreenMuted),
            )
            Spacer(Modifier.height(6.dp))
            BasicText(
                text = page.title.ifBlank { "untitled" },
                style = Type.CardTitle.copy(color = TextGreen, fontSize = 18.sp),
                maxLines = 2,
            )
            val description = page.description.takeIf { it.isNotBlank() }
            if (description != null) {
                Spacer(Modifier.height(8.dp))
                BasicText(
                    text = description,
                    style = Type.BodySmall.copy(color = TextGreen, fontSize = 12.sp),
                    maxLines = 5,
                )
            }
            Spacer(Modifier.weight(1f))
            // Same surface order as the hero pip row, but coloured
            // for the leaf-on-leaf tall card. Each pill carries the
            // canonical [CaptureMode] icon plus the count, so the
            // user can see *what* the page contains and *how much*
            // at a glance — independent of the footer's category +
            // total tally below.
            EarlierCapturePips(page.captureCounts)
            CardFooter(page = page, primaryColor = TextGreen, mutedColor = TextGreenMuted)
        }
    }
}

/**
 * Capture-pip row used by the EarlierGrid tall card. Mirrors the hero
 * pip row's shape (icon + count digit, capsule bg) but with the
 * leaf-on-leaf palette tuned for the tall card's `BgFeatured` (#DDEACD)
 * surface. Pills with a zero count are skipped; if every count is zero
 * the row collapses entirely so the spacing above the footer doesn't
 * gain dead height.
 */
@Composable
private fun EarlierCapturePips(counts: CaptureCounts) {
    if (counts.total <= 0) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(bottom = 8.dp),
    ) {
        if (counts.photos    > 0) EarlierPip(CaptureMode.Photos.icon,    counts.photos)
        if (counts.scans     > 0) EarlierPip(CaptureMode.Scans.icon,     counts.scans)
        if (counts.voice     > 0) EarlierPip(CaptureMode.Voice.icon,     counts.voice)
        if (counts.todos     > 0) EarlierPip(CaptureMode.Todo.icon,      counts.todos)
        if (counts.contacts  > 0) EarlierPip(CaptureMode.Contacts.icon,  counts.contacts)
        if (counts.locations > 0) EarlierPip(CaptureMode.Location.icon,  counts.locations)
        if (counts.notes     > 0) EarlierPip(Icons.AutoMirrored.Outlined.EventNote, counts.notes)
    }
}

@Composable
private fun EarlierPip(icon: ImageVector, count: Int) {
    // Slightly darker leaf tint than the card's `BgFeatured` (#DDEACD)
    // so the pill is legible without going loud. Deep-green icon
    // matches the card's title typography; the count digit drops to
    // [Green600] so the icon stays the primary read.
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Green200)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = TextGreen,
            modifier = Modifier.size(13.dp),
        )
        BasicText(
            text = count.toString(),
            style = Type.BodySmall.copy(
                color = Green600,
                fontSize = 10.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
            ),
        )
    }
}

@Composable
private fun RegularPageCard(
    page: RecentsPage,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(BgSurface)
            .border(1.dp, BorderFaint, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
    ) {
        BasicText(
            text = headerLabel(page),
            style = Type.MicroLabel.copy(color = TextGreenMuted),
        )
        Spacer(Modifier.height(6.dp))
        BasicText(
            text = page.title.ifBlank { "untitled" },
            style = Type.CardTitle.copy(color = TextPrimary, fontSize = 15.sp),
            maxLines = 2,
        )
        val description = page.description.takeIf { it.isNotBlank() }
        if (description != null) {
            Spacer(Modifier.height(6.dp))
            BasicText(
                text = description,
                style = Type.BodySmall.copy(color = TextMuted, fontSize = 12.sp),
                maxLines = 3,
            )
        }
        Spacer(Modifier.weight(1f))
        CardFooter(page = page, primaryColor = TextGreen, mutedColor = TextMuted)
    }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/** "APR 25 · 8:05 AM" header that mirrors the hero's eyebrow shape. */
private fun headerLabel(page: RecentsPage): String =
    "${page.createdAt.format(DATE_FMT)} · ${page.createdAt.format(TIME_FMT)}"
        .uppercase()

/** Bottom row: page category on the left, total capture count on
 *  the right. The amber dot for imported pages stays as a marker on
 *  the left of the category. Both sides drop out gracefully when
 *  empty so the row never looks lopsided. */
@Composable
private fun CardFooter(
    page: RecentsPage,
    primaryColor: androidx.compose.ui.graphics.Color,
    mutedColor: androidx.compose.ui.graphics.Color,
) {
    val category = page.tags.firstOrNull()?.display
    val total = page.captureCounts.total
    val showAny = category != null || total > 0
    if (!showAny) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (page.isImported()) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(AccentImport),
            )
        }
        if (category != null) {
            BasicText(
                text = category.uppercase(),
                style = Type.MicroLabel.copy(color = primaryColor, fontSize = 9.sp),
            )
        }
        Spacer(Modifier.weight(1f))
        if (total > 0) {
            BasicText(
                text = "$total ${if (total == 1) "capture" else "captures"}".uppercase(),
                style = Type.MicroLabel.copy(color = mutedColor, fontSize = 9.sp),
            )
        }
    }
}
