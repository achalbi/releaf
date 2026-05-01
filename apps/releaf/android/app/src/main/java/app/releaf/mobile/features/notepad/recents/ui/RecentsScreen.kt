package app.releaf.mobile.features.notepad.recents.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.releaf.mobile.features.notepad.recents.data.MockData
import app.releaf.mobile.features.notepad.recents.model.RecentsDayStats
import app.releaf.mobile.features.notepad.recents.model.RecentsPage
import app.releaf.mobile.features.notepad.recents.model.isImported
import app.releaf.mobile.features.notepad.recents.theme.AccentImport
import app.releaf.mobile.features.notepad.recents.theme.BgCanvas
import app.releaf.mobile.features.notepad.recents.theme.TextGreenMuted
import app.releaf.mobile.features.notepad.recents.theme.TextMuted
import app.releaf.mobile.features.notepad.recents.theme.Type
import app.releaf.mobile.ui.components.CaptureMode
import java.time.format.DateTimeFormatter

/**
 * The Recents tab. The host (NotepadScreen) renders the brand
 * eyebrow, H1, Day/Recents toggle, and category filter row above this
 * branch, so this screen starts straight at the stats strip and never
 * repeats them. The local tag-chip row was removed for the same reason
 * — the host's category filter sits in the same visual position. The
 * inline 5-cell bottom nav was also removed; the host app's main
 * floating footer continues to handle navigation. It composes, in
 * order:
 *
 *   1. StatsStrip
 *   2. "TODAY" + TodayHero
 *   3. "THIS WEEK" + WeekPulse
 *   4. "EARLIER IN <MONTH>" + EarlierGrid
 *
 * Stateless externally — pass in callbacks for content actions.
 */
@Composable
fun RecentsScreen(
    stats: RecentsDayStats = MockData.dayStats,
    onOpenPage: (RecentsPage) -> Unit = {},
    /// Fires when the user picks a type from the new-entry slot's
    /// 5-cell grid (Photo / Scan / Voice / Todo / Contact) or taps
    /// the new-entry slot's footer CTA. The host is expected to open
    /// the page editor; once the editor learns to focus a specific
    /// tab, the [CaptureMode] forwarded here is the right value.
    onPickMode: (CaptureMode) -> Unit = {},
) {
    // Host (NotepadScreen) already wraps this branch in a Column with
    // verticalScroll, so RecentsScreen is a plain inline column — no
    // self-scroll, no fillMaxSize. Wrapping its own scroll inside the
    // host's would crash with "Vertically scrollable component was
    // measured with an infinity maximum height constraints".
    //
    // The host also already renders the brand eyebrow, H1, Day/Recents
    // toggle, and category filter row above this branch, so this screen
    // starts straight at the stats strip and never repeats them.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(BgCanvas),
    ) {
        Spacer(Modifier.height(4.dp))

        // 1. Stats strip
        val monthLabel = stats.today?.date
            ?.format(DateTimeFormatter.ofPattern("MMM"))
            ?: stats.weekPulse.lastOrNull()?.date?.format(DateTimeFormatter.ofPattern("MMM"))
            ?: "—"
        // No inner horizontal inset — the strip spans the full width
        // of the recents column (only the host screen's outer padding
        // shows on the sides), wider than the today hero below it.
        StatsStrip(
            totals = stats.totals,
            monthLabel = monthLabel,
        )
        Spacer(Modifier.height(18.dp))

        // 2. TODAY
        SectionLabel(
            text = "TODAY",
            modifier = Modifier.padding(horizontal = 14.dp),
        )
        Spacer(Modifier.height(8.dp))
        stats.today?.let { day ->
            TodayHero(
                day = day,
                onOpenPage = onOpenPage,
                onPickMode = onPickMode,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
        }
        Spacer(Modifier.height(20.dp))

        // 3. THIS WEEK
        SectionLabel(
            text = "THIS WEEK",
            modifier = Modifier.padding(horizontal = 14.dp),
        )
        Spacer(Modifier.height(10.dp))
        WeekPulse(days = stats.weekPulse)
        Spacer(Modifier.height(22.dp))

        // 4. EARLIER IN <MONTH> — page-wise feed sorted by last
        // modified, freshest first. The grid no longer surfaces
        // empty days; days with no captures simply contribute zero
        // cards.
        val earlierMonth = stats.earlier.firstOrNull()
            ?.date?.format(DateTimeFormatter.ofPattern("MMMM"))?.uppercase()
            ?: monthLabel.uppercase()
        SectionLabel(
            text = "EARLIER IN $earlierMonth",
            modifier = Modifier.padding(horizontal = 14.dp),
        )
        Spacer(Modifier.height(10.dp))
        val earlierPages = stats.earlier
            .flatMap { it.pages }
            .sortedByDescending { it.updatedAt }
            .take(14)
        EarlierGrid(
            pages = earlierPages,
            onOpenPage = onOpenPage,
        )

        // Footnote legend explaining the amber dot — only renders
        // when at least one visible page (today or earlier) is
        // actually imported. Hidden when there's nothing to explain
        // so the screen doesn't accumulate a permanently-visible
        // remark for the typical user with no imported pages.
        val anyImported = (
            (stats.today?.pages.orEmpty()) + earlierPages
        ).any { it.isImported() }
        if (anyImported) {
            Spacer(Modifier.height(16.dp))
            ImportedLegend(modifier = Modifier.padding(horizontal = 14.dp))
        }
        Spacer(Modifier.height(20.dp))
    }
}

/**
 * Small footnote — amber dot + caption — explaining that pages
 * marked with the same dot in the EarlierGrid card footers were
 * imported from the photo library or a document scan.
 */
@Composable
private fun ImportedLegend(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(AccentImport),
        )
        BasicText(
            text = "Imported from library or a scan",
            style = Type.Caption.copy(color = TextMuted, fontSize = 11.sp),
        )
    }
}


// ---------------------------------------------------------------------------
// Section label — used for "TODAY" / "THIS WEEK" / "EARLIER IN APRIL"
// ---------------------------------------------------------------------------

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    // Reference uses a small, muted-green eyebrow (microWide style).
    BasicText(
        text = text.uppercase(),
        style = Type.MicroWide.copy(color = TextGreenMuted),
        modifier = modifier,
    )
}
