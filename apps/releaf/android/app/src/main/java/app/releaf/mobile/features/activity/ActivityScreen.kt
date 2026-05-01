/*
 * ActivityScreen.kt
 *
 * Full-screen activity log — same data source as the home timeline
 * card, just a bigger window (200 rows vs 5) and grouped by day so
 * the user can scan back through what they've touched.
 *
 * Drill-in screen reached from the home tab's "See all" link. Header
 * mirrors the home tab's vibe: tappable LeafEyebrow back-link
 * ("🌱 HOME · ACTIVITY") + a serif editorial title in 32sp serif.
 */

package app.releaf.mobile.features.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.releaf.mobile.data.activity.ActivityItem
import app.releaf.mobile.features.home.accentFor
import app.releaf.mobile.features.home.labelFor
import app.releaf.mobile.features.home.paletteFor
import app.releaf.mobile.ui.components.ActivityEntry
import app.releaf.mobile.ui.components.ActivityProminence
import app.releaf.mobile.ui.components.ActivityTimeline
import app.releaf.mobile.ui.components.LeafEyebrow
import app.releaf.mobile.ui.components.relativeTimeAgo
import app.releaf.mobile.ui.theme.AppAccent
import app.releaf.mobile.ui.theme.AppColors
import app.releaf.mobile.ui.theme.AppRadius
import app.releaf.mobile.ui.theme.AppSpacing
import app.releaf.mobile.ui.theme.AppTypography
import app.releaf.mobile.ui.theme.TimelineStyle
import app.releaf.mobile.ui.theme.UiPreferences
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun ActivityScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RecentActivityViewModel = viewModel(
        factory = RecentActivityViewModel.factory(RecentActivityViewModel.FULL_LIMIT),
    ),
) {
    val items by viewModel.items.collectAsState()
    // Timeline style follows the same Settings → Timeline preference
    // the home tab's HomeTimelineCard reads. Classic = the simple
    // rounded-row list with a colored dot + relative time. Bramble =
    // the serpentine-vine + flower marker timeline.
    val context = LocalContext.current
    val prefs = remember(context) { UiPreferences.get(context) }
    val style by prefs.state.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AppColors.Canvas),
    ) {
        TopBar(onBack = onBack, count = items.size)

        if (items.isEmpty()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(AppSpacing.s8),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Nothing here yet — your edits will show up as you make them.",
                    style = AppTypography.Body,
                    color = AppColors.TextSecondary,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            // Group rows by local date so the user gets a "Today /
            // Yesterday / Mon Apr 21" heading separating them.
            val grouped = items.groupBy { localDateOf(it.timestamp) }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start  = AppSpacing.s4,
                    end    = AppSpacing.s4,
                    top    = AppSpacing.s2,
                    bottom = AppSpacing.s10,
                ),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.s4),
            ) {
                when (style.timelineStyle) {
                    TimelineStyle.Classic -> {
                        // Classic rendering: each group → eyebrow header
                        // + a stack of dot-led rows on individual cards.
                        grouped.forEach { (date, rows) ->
                            item(key = "header:${date}") {
                                SectionHeader(label = headingFor(date))
                            }
                            items(rows, key = { "classic:${it.id}" }) { row ->
                                ActivityRow(row)
                            }
                        }
                    }
                    TimelineStyle.Bramble -> {
                        // Bramble rendering: each group → its own
                        // serpentine-vine ActivityTimeline card with the
                        // date as the eyebrow header.
                        grouped.forEach { (date, rows) ->
                            item(key = "section:$date") {
                                ActivityTimeline(
                                    header  = headingFor(date).uppercase(),
                                    entries = rows.mapIndexed { index, item ->
                                        ActivityEntry(
                                            id         = item.id,
                                            date       = relativeTimeAgo(item.timestamp),
                                            title      = labelFor(item),
                                            preview    = item.context,
                                            theme      = paletteFor(item.kind),
                                            prominence = if (index == 0) ActivityProminence.Featured
                                                         else ActivityProminence.Routine,
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(label: String) {
    Text(
        text     = label.uppercase(),
        style    = AppTypography.Eyebrow,
        color    = AppAccent.primary,
        modifier = Modifier.padding(top = AppSpacing.s2, bottom = AppSpacing.s1),
    )
}

@Composable
private fun ActivityRow(item: ActivityItem) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.md))
            .background(AppColors.CardSolid)
            .border(1.dp, AppColors.BorderDefault, RoundedCornerShape(AppRadius.md))
            .padding(AppSpacing.s4),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.s3),
        verticalAlignment     = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .padding(top = 4.dp)
                .size(10.dp)
                .clip(CircleShape)
                .background(accentFor(item.kind)),
        )
        Column(
            modifier            = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text     = labelFor(item),
                style    = AppTypography.Body,
                color    = AppColors.TextPrimary,
                maxLines = 2,
            )
            Text(
                text  = relativeTimeAgo(item.timestamp),
                style = AppTypography.Meta,
                color = AppColors.TextSecondary,
            )
        }
    }
}

@Composable
private fun TopBar(onBack: () -> Unit, count: Int) {
    // Eyebrow + serif title rhythm — same shape as the notebook detail
    // and chapter detail headers we recently aligned, and the home
    // tab's "RELEAF / greeting" header. Eyebrow links back; the count
    // chip sits on the trailing edge of the title row.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start  = AppSpacing.s4,
                end    = AppSpacing.s4,
                top    = AppSpacing.s4,
                bottom = AppSpacing.s3,
            ),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.s3),
    ) {
        LeafEyebrow(
            label    = "home · activity",
            modifier = Modifier.clickable(onClick = onBack),
        )
        Row(
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.s2),
            modifier              = Modifier.fillMaxWidth(),
        ) {
            Text(
                text     = "Recent edits",
                style    = TextStyle(
                    fontFamily = FontFamily.Serif,
                    fontSize   = 32.sp,
                ),
                color    = AppColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (count > 0) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(AppRadius.pill))
                        .background(AppAccent.soft)
                        .padding(horizontal = AppSpacing.s3, vertical = AppSpacing.s1),
                ) {
                    Text(
                        text  = "$count",
                        style = AppTypography.Tag,
                        color = AppAccent.deep,
                    )
                }
            }
        }
    }
}

/* ---------- date helpers ---------- */

private fun localDateOf(iso: String): LocalDate =
    runCatching {
        Instant.parse(iso).atZone(ZoneId.systemDefault()).toLocalDate()
    }.getOrElse { LocalDate.now() }

private fun headingFor(date: LocalDate): String {
    val today = LocalDate.now()
    return when (date) {
        today                  -> "Today"
        today.minusDays(1)     -> "Yesterday"
        else                   -> DateTimeFormatter.ofPattern("EEE MMM d").format(date)
    }
}
