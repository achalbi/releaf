package app.releaf.mobile.features.notepad.recents.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.EventNote
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.releaf.mobile.ui.components.CaptureMode
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.releaf.mobile.features.notepad.recents.model.CaptureType
import app.releaf.mobile.features.notepad.recents.model.RecentsDay
import app.releaf.mobile.features.notepad.recents.model.RecentsPage
import app.releaf.mobile.features.notepad.recents.theme.BgCanvas
import app.releaf.mobile.features.notepad.recents.theme.Green100
import app.releaf.mobile.features.notepad.recents.theme.Green200
import app.releaf.mobile.features.notepad.recents.theme.Green400
import app.releaf.mobile.features.notepad.recents.theme.Green600
import app.releaf.mobile.features.notepad.recents.theme.Green800
import app.releaf.mobile.features.notepad.recents.theme.TextGreenMuted
import app.releaf.mobile.features.notepad.recents.theme.OnDark14
import app.releaf.mobile.features.notepad.recents.theme.OnDark16
import app.releaf.mobile.features.notepad.recents.theme.OnDark25
import app.releaf.mobile.features.notepad.recents.theme.TextOnDark
import app.releaf.mobile.features.notepad.recents.theme.TextOnDarkMuted
import app.releaf.mobile.features.notepad.recents.theme.TextOnDarkSubtle
import app.releaf.mobile.features.notepad.recents.theme.TextPrimary
import app.releaf.mobile.features.notepad.recents.theme.Type
import java.time.format.DateTimeFormatter

// Hero-local override: the amber/coral accent is no longer used inside
// the hero. The new-entry slot uses the same cream/canvas treatment as
// the rest of the hero's sub-elements; EarlierGrid still uses the
// amber AccentImport for imported-page pips since it sits outside the
// hero on the page.
private val NewEntryPillBg = OnDark16

/**
 * The centerpiece of the screen: a swipeable hero card showing today's pages.
 * Total slots = pages.size + 1 (always one trailing "new entry" slot).
 *
 * Provides:
 *  - header (date, time, "X pages" / "new")
 *  - title row (theme + page indicator pill)
 *  - inset card (media / text / new-entry variant)
 *  - capture pips
 *  - day timeline
 *  - footer CTA "Open page X →" or "Add a page →"
 */
@Composable
fun TodayHero(
    day: RecentsDay,
    onOpenPage: (RecentsPage) -> Unit,
    /// Fires for the new-entry slot's picker cells (Photo / Scan /
    /// Voice / Todo / Contact) and for the footer "Add a page" CTA
    /// when on the trailing slot. The host wires this to whatever
    /// "compose new" routing it has — typically opening the editor;
    /// once the editor learns to focus a specific tab, the [CaptureMode]
    /// picked here is the right thing to forward.
    onPickMode: (CaptureMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val totalSlots = day.pages.size + 1
    val initial = if (day.pages.isEmpty()) 0 else (day.pages.size - 1)

    val pagerState = rememberPagerState(initialPage = initial) { totalSlots }

    // Pager entrance peek — on arrival the inner pager nudges
    // ~36dp to the right and springs back, hinting that the card's
    // contents swipe between pages while the outer card frame
    // (background + border + section labels) stays anchored. The
    // offset is render-only (Modifier.offset { } at placement
    // phase), so the visual nudge doesn't reflow neighbours and
    // the pager's own scroll state is untouched — a user swipe
    // mid-animation still works correctly.
    val density = LocalDensity.current
    val peekPx = remember(density) { with(density) { 36.dp.toPx() } }
    val peek = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        peek.animateTo(
            targetValue = peekPx,
            animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
        )
        peek.animateTo(
            targetValue = 0f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness    = Spring.StiffnessLow,
            ),
        )
    }

    // Outer card: dark green by default, but flips to an outline-style
    // (cream fill + green border) when the carousel settles on the
    // trailing new-entry slot. The flip tracks `currentPage` (the
    // settled slot) rather than each rendered page so the chrome
    // doesn't flicker mid-swipe.
    val isOnNewEntry = pagerState.currentPage >= day.pages.size
    val heroBg = if (isOnNewEntry) BgCanvas else Green800

    // Tap-anywhere-to-open. Mirrors the footer CTA's behaviour so
    // the whole card is the affordance: real page → open it; new-
    // entry slot → open the editor at its default tab. The pager's
    // horizontal-drag gesture and child clickables (picker cells,
    // footer button) all win over this — they consume their own
    // gestures before this lambda fires.
    val onCardTap: () -> Unit = {
        val current = pagerState.currentPage
        if (current >= day.pages.size) {
            onPickMode(CaptureMode.Overview)
        } else {
            day.pages.getOrNull(current)?.let(onOpenPage)
        }
    }
    // Suppress the default ripple — a card-sized ripple flash on
    // every tap reads as visual noise, and the navigation
    // transition itself is the user's feedback.
    val tapInteraction = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(heroBg)
            .then(
                if (isOnNewEntry) {
                    Modifier.border(1.5.dp, Green800, RoundedCornerShape(20.dp))
                } else {
                    Modifier
                }
            )
            .clickable(
                interactionSource = tapInteraction,
                indication = null,
                onClick = onCardTap,
            )
            .padding(14.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .offset { IntOffset(peek.value.toInt(), 0) },
            ) { pageIndex ->
                val isNewEntry = pageIndex >= day.pages.size
                val page = day.pages.getOrNull(pageIndex)

                HeroPage(
                    day = day,
                    page = page,
                    pageIndex = pageIndex,
                    totalSlots = totalSlots,
                    isNewEntry = isNewEntry,
                    inverted = isNewEntry,
                    onOpenPage = { p -> onOpenPage(p) },
                    onPickMode = onPickMode,
                )
            }
        }
    }
}

@Composable
private fun HeroPage(
    day: RecentsDay,
    page: RecentsPage?,
    pageIndex: Int,
    totalSlots: Int,
    isNewEntry: Boolean,
    /// Inverted = outline-style hero. When true, every chrome element
    /// in this page renders with dark green on cream instead of cream
    /// on dark green. Today this matches `isNewEntry` (only the
    /// new-entry slot inverts), but it's a separate parameter so the
    /// inversion can be driven by the settled carousel page later.
    inverted: Boolean,
    onOpenPage: (RecentsPage) -> Unit,
    onPickMode: (CaptureMode) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        HeroHeader(day = day, page = page, isNewEntry = isNewEntry, inverted = inverted)
        Spacer(Modifier.height(8.dp))
        // Title row tracks the active page — when you swipe through the
        // carousel the big serif heading changes from page to page so
        // it always names what you're looking at. On the new-entry
        // slot the title is intentionally blank (the picker cells in
        // the inset are the focus there) — pass `null` and the row
        // renders just the page-indicator pill on the right.
        HeroTitleRow(
            title = if (isNewEntry) {
                null
            } else {
                page?.title?.takeIf { it.isNotBlank() }
                    ?: day.theme.ifBlank { "today" }
            },
            pageIndex = pageIndex,
            pageCount = day.pages.size,
            isNewEntry = isNewEntry,
            inverted = inverted,
        )
        Spacer(Modifier.height(12.dp))
        // The inset is description-only for every capture type — the
        // 16:9 media tile was removed; the day-level capture pip row
        // below already signals the type mix without an image preview.
        when {
            isNewEntry -> NewEntryInset(onPickMode = onPickMode, inverted = inverted)
            page == null -> Unit // unreachable
            else -> TextOnlyInset(page = page)
        }
        Spacer(Modifier.height(12.dp))
        // Pip row reflects the *active page's* capture mix, not the
        // day total. Swiping to a different page updates the pips. On
        // the new-entry slot (no live page) we render zero counts so
        // the row collapses to nothing.
        CapturePips(
            counts = page?.captureCounts
                ?: app.releaf.mobile.features.notepad.recents.model.CaptureCounts(),
            inverted = inverted,
        )
        Spacer(Modifier.height(10.dp))
        DayTimeline(
            day = day,
            currentIndex = pageIndex,
            isNewEntry = isNewEntry,
            inverted = inverted,
        )
        Spacer(Modifier.height(10.dp))
        HeroFooter(
            isNewEntry = isNewEntry,
            currentPageIndex = pageIndex,
            inverted = inverted,
            onOpenPage = { page?.let(onOpenPage) },
            // The footer's "Add a page" CTA on the new-entry slot has
            // no specific mode — fall back to the editor's default tab.
            onAddPagePrimary = { onPickMode(CaptureMode.Overview) },
        )
    }
}

// ---------------------------------------------------------------------------
// Header
// ---------------------------------------------------------------------------

// Reference design renders the eyebrow as "SUN · APR 26 · 8:15 PM"
// — a single bullet between the weekday, the date, and the time, with
// uppercase AM/PM. Embed the bullet inside the date pattern so the
// sequence matches the reference even when the locale would otherwise
// reorder the parts.
private val DAY_FMT = DateTimeFormatter.ofPattern("EEE ' · ' MMM d")
private val TIME_FMT = DateTimeFormatter.ofPattern("h:mm a")

@Composable
private fun HeroHeader(
    day: RecentsDay,
    page: RecentsPage?,
    isNewEntry: Boolean,
    inverted: Boolean,
) {
    val subtle = if (inverted) TextGreenMuted else TextOnDarkSubtle
    val muted  = if (inverted) Green600 else TextOnDarkMuted
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val left = buildString {
            append(day.date.format(DAY_FMT).uppercase())
            if (page != null) {
                append(" · ")
                append(page.createdAt.format(TIME_FMT).uppercase())
            } else if (isNewEntry) {
                append(" · NOW")
            }
        }
        BasicText(
            text = left,
            style = Type.MicroLabel.copy(color = subtle),
        )
        Spacer(Modifier.weight(1f))
        // Right rail: the active page's category (Home / Work /
        // Recipes / Personal). New-entry slot stays as "new". Pages
        // with no tag drop the label entirely so the right side
        // doesn't dangle a blank field.
        val rightLabel = when {
            isNewEntry -> "new"
            else       -> page?.tags?.firstOrNull()?.display.orEmpty()
        }
        if (rightLabel.isNotEmpty()) {
            BasicText(
                text = rightLabel,
                style = Type.MicroLabel.copy(color = muted),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Title row + indicator pill
// ---------------------------------------------------------------------------

@Composable
private fun HeroTitleRow(
    title: String?,
    pageIndex: Int,
    pageCount: Int,
    isNewEntry: Boolean,
    inverted: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (title != null) {
            BasicText(
                text = title,
                style = Type.ThemeName.copy(
                    color = if (inverted) Green800 else TextOnDark,
                ),
                maxLines = 2,
            )
        }
        Spacer(Modifier.weight(1f))
        PageIndicatorPill(
            index = pageIndex,
            pageCount = pageCount,
            isNewEntry = isNewEntry,
            inverted = inverted,
        )
    }
}

@Composable
private fun PageIndicatorPill(
    index: Int,
    pageCount: Int,
    isNewEntry: Boolean,
    inverted: Boolean,
) {
    // Two palettes: solid (cream-on-dark-green) and outline-style
    // (dark-green-on-cream). Both keep the same shape so the swap is
    // a pure color change.
    val bg         = if (inverted) Green100 else OnDark16
    val labelColor = if (inverted) Green800 else TextOnDarkMuted
    val filledDot  = if (inverted) Green800 else TextOnDark
    val emptyDot   = if (inverted) Green200 else TextOnDarkSubtle.copy(alpha = 0.35f)
    val newDot     = if (inverted) Green800 else BgCanvas

    // Cap the rendered dot count. A long day (e.g. 16 pages) would
    // otherwise stretch the pill beyond the available width and force
    // the label to wrap one character per line. Above the cap we drop
    // the dots and let the "X of N" text carry the indicator alone.
    val maxRenderedDots = 6
    val showDots = pageCount in 1..maxRenderedDots

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (showDots) {
            repeat(pageCount) { i ->
                val filled = i <= (index.coerceAtMost(pageCount - 1)) && !isNewEntry
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(if (filled) filledDot else emptyDot),
                )
            }
        }
        if (isNewEntry) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(newDot),
            )
        }
        if (showDots || isNewEntry) {
            Spacer(Modifier.width(6.dp))
        }
        val label = when {
            isNewEntry        -> "new"
            pageCount == 0    -> "—"
            else              -> "${index + 1} of $pageCount"
        }
        BasicText(
            text = label,
            style = Type.BodySmall.copy(color = labelColor, fontSize = 10.sp),
            maxLines = 1,
        )
    }
}

// ---------------------------------------------------------------------------
// Inset variants
// ---------------------------------------------------------------------------

/**
 * The single inset variant — description-only, used for every capture
 * type. The page title rides up into the hero's title row (so the big
 * serif heading tracks the active page); the day-level capture pip
 * row below this inset signals the type mix; the 16:9 media tile was
 * removed so a photo / scan page renders the same chrome as a
 * journal / voice / mood page. If [page.description] is blank, fall
 * back to [page.title] so the inset never reads as empty.
 */
@Composable
private fun TextOnlyInset(page: RecentsPage) {
    val text = page.description.ifBlank { page.title }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(13.dp))
            // Brief: "Cards on the dark green hero use translucent
            // overlays for sub-elements, never new colors." OnDark14
            // gives the inset enough contrast against the green hero
            // for cream-on-dark body text to stay readable.
            .background(OnDark14)
            .padding(12.dp),
    ) {
        BasicText(
            text = text,
            style = Type.BodySmall.copy(color = TextOnDarkMuted),
            maxLines = 4,
        )
    }
}

// ---------------------------------------------------------------------------
// New-entry inset: dashed cream border + 5-cell type picker row
// ---------------------------------------------------------------------------

@Composable
private fun NewEntryInset(
    onPickMode: (CaptureMode) -> Unit,
    inverted: Boolean,
) {
    val insetBg     = if (inverted) Green100.copy(alpha = 0.5f) else NewEntryPillBg
    val dashedColor = if (inverted) Green800 else BgCanvas
    val eyebrow     = if (inverted) Green800 else BgCanvas
    val addPillBg   = if (inverted) Green800 else OnDark16
    val addPillFg   = if (inverted) BgCanvas else BgCanvas
    val microcopy   = if (inverted) Green600 else TextOnDarkMuted
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(13.dp))
            .background(insetBg),
    ) {
        // matchParentSize() lets the Canvas size to whatever the Column below
        // ends up at, without participating in the Box's intrinsic measurement.
        Canvas(modifier = Modifier.matchParentSize()) {
            drawRoundRect(
                brush = SolidColor(dashedColor.copy(alpha = if (inverted) 0.65f else 0.55f)),
                topLeft = Offset(0f, 0f),
                size = Size(size.width, size.height),
                cornerRadius = CornerRadius(13.dp.toPx(), 13.dp.toPx()),
                style = Stroke(
                    width = 1.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f),
                ),
            )
        }
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText(
                    text = "NEW PAGE",
                    style = Type.MicroLabel.copy(color = eyebrow),
                )
                Spacer(Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(addPillBg)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    BasicText(
                        text = "+ ADD",
                        style = Type.MicroLabel.copy(color = addPillFg, fontSize = 9.sp),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            // Five PageDetails-tab shortcuts. Each cell uses the
            // canonical icon baked into [CaptureMode] so the picker
            // reads as the same family as the page editor's tab bar.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ModePickerCell(
                    mode     = CaptureMode.Photos,
                    label    = "Photo",
                    inverted = inverted,
                    modifier = Modifier.weight(1f),
                    onClick  = { onPickMode(CaptureMode.Photos) },
                )
                ModePickerCell(
                    mode     = CaptureMode.Scans,
                    label    = "Scan",
                    inverted = inverted,
                    modifier = Modifier.weight(1f),
                    onClick  = { onPickMode(CaptureMode.Scans) },
                )
                ModePickerCell(
                    mode     = CaptureMode.Voice,
                    label    = "Voice",
                    inverted = inverted,
                    modifier = Modifier.weight(1f),
                    onClick  = { onPickMode(CaptureMode.Voice) },
                )
                ModePickerCell(
                    mode     = CaptureMode.Todo,
                    label    = "Todo",
                    inverted = inverted,
                    modifier = Modifier.weight(1f),
                    onClick  = { onPickMode(CaptureMode.Todo) },
                )
                ModePickerCell(
                    mode     = CaptureMode.Contacts,
                    label    = "Contact",
                    inverted = inverted,
                    modifier = Modifier.weight(1f),
                    onClick  = { onPickMode(CaptureMode.Contacts) },
                )
            }
            Spacer(Modifier.height(10.dp))
            BasicText(
                text = "Tap any type to plant a new page in today's garden.",
                style = Type.BodySmall.copy(
                    color = microcopy,
                    fontSize = 11.sp,
                ),
            )
        }
    }
}

/** One of the new-entry slot's 5 PageDetails-tab shortcuts. Renders
 *  the canonical icon from [CaptureMode] so the picker matches the
 *  page editor's tab bar. */
@Composable
private fun ModePickerCell(
    mode: CaptureMode,
    label: String,
    inverted: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    // Two palettes:
    //   - solid hero (inverted=false): cream cell with dark-green icon
    //     so the cell pops against the dark green hero card.
    //   - outlined hero (inverted=true): dark-green cell with cream
    //     icon so the cells pop against the cream hero card.
    val bg = if (inverted) Green800 else Color(0xFFFBF5E2)
    val fg = if (inverted) BgCanvas else Green800
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = mode.icon,
            contentDescription = null,
            tint = fg,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.height(4.dp))
        BasicText(
            text = label,
            style = Type.BodySmall.copy(
                color = fg,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
            ),
        )
    }
}

// ---------------------------------------------------------------------------
// Capture pips row
// ---------------------------------------------------------------------------

/**
 * Per-page capture pip row. Reads from the active page's
 * [CaptureCounts] — one pip per non-zero surface. The six attachment-
 * style surfaces use their matching [CaptureMode] icon so the row
 * reads as one family with the page editor's tab bar and the
 * new-entry slot's picker cells. The trailing `notes` pip uses the
 * notepad eyebrow glyph (the same one BottomNav uses for the Notepad
 * tab) since notes is the page's body, not a picker-cell surface.
 * Pips with a zero count are skipped so the row never looks padded.
 */
@Composable
private fun CapturePips(
    counts: app.releaf.mobile.features.notepad.recents.model.CaptureCounts,
    inverted: Boolean,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (counts.photos    > 0) Pip(icon = CaptureMode.Photos.icon,                    count = counts.photos,    inverted = inverted)
        if (counts.scans     > 0) Pip(icon = CaptureMode.Scans.icon,                     count = counts.scans,     inverted = inverted)
        if (counts.voice     > 0) Pip(icon = CaptureMode.Voice.icon,                     count = counts.voice,     inverted = inverted)
        if (counts.todos     > 0) Pip(icon = CaptureMode.Todo.icon,                      count = counts.todos,     inverted = inverted)
        if (counts.contacts  > 0) Pip(icon = CaptureMode.Contacts.icon,                  count = counts.contacts,  inverted = inverted)
        if (counts.locations > 0) Pip(icon = CaptureMode.Location.icon,                  count = counts.locations, inverted = inverted)
        if (counts.notes     > 0) Pip(icon = Icons.AutoMirrored.Outlined.EventNote,      count = counts.notes,     inverted = inverted)
    }
}

@Composable
private fun Pip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    count: Int,
    inverted: Boolean,
) {
    val pipBg  = if (inverted) Green100 else OnDark16
    val pipFg  = if (inverted) Green800 else TextOnDark
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(pipBg)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = pipFg,
            modifier = Modifier.size(13.dp),
        )
        BasicText(
            text = count.toString(),
            style = Type.BodySmall.copy(
                color = pipFg,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
            ),
        )
    }
}

// ---------------------------------------------------------------------------
// Day timeline (12am -> next 12am, full 24-hour span)
// ---------------------------------------------------------------------------

@Composable
private fun DayTimeline(
    day: RecentsDay,
    currentIndex: Int,
    isNewEntry: Boolean,
    inverted: Boolean,
) {
    val anchorColor = if (inverted) TextGreenMuted else TextOnDarkSubtle
    val trackColor  = if (inverted) Green200 else OnDark14
    val activeDot   = if (inverted) Green800 else TextOnDark
    val inactiveDot = if (inverted) Green400.copy(alpha = 0.55f) else OnDark25
    val newDot      = if (inverted) Green800 else BgCanvas
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // 12a anchor on the left = midnight at the start of the day
        BasicText(
            text = "12a",
            style = Type.MicroLabel.copy(color = anchorColor, fontSize = 9.sp),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(22.dp),
        ) {
            // Track — thicker line per the latest design notes (was 2dp).
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(trackColor),
            )
            // dots positioned by time across the full 24-hour span
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val cy = size.height / 2f

                day.pages.forEachIndexed { idx, p ->
                    val pct = pctOfDay(p.createdAt.hour, p.createdAt.minute)
                    val cx = w * pct
                    val isActive = !isNewEntry && idx == currentIndex
                    val r = if (isActive) 5.dp.toPx() else 3.dp.toPx()
                    val color = if (isActive) activeDot else inactiveDot
                    drawCircle(color = color, radius = r, center = Offset(cx, cy))
                }
                if (isNewEntry) {
                    drawCircle(
                        color = newDot,
                        radius = 5.dp.toPx(),
                        center = Offset(w - 4.dp.toPx(), cy),
                    )
                }
            }
        }
        // 12a anchor on the right = midnight at the end of the day
        BasicText(
            text = "12a",
            style = Type.MicroLabel.copy(color = anchorColor, fontSize = 9.sp),
        )
    }
}

private fun pctOfDay(hour: Int, minute: Int): Float {
    // 12am = 0, next 12am = 1 (full 24-hour span)
    val mins = hour * 60 + minute
    val total = 24 * 60
    return (mins.toFloat() / total.toFloat()).coerceIn(0f, 1f)
}

// ---------------------------------------------------------------------------
// Footer CTA
// ---------------------------------------------------------------------------

@Composable
private fun HeroFooter(
    isNewEntry: Boolean,
    currentPageIndex: Int,
    inverted: Boolean,
    onOpenPage: () -> Unit,
    onAddPagePrimary: () -> Unit,
) {
    val divider = if (inverted) Green200 else OnDark25
    val labelColor = if (inverted) Green800 else TextOnDark
    val buttonBg  = if (inverted) Green800 else OnDark16
    val buttonFg  = if (inverted) BgCanvas else TextOnDark
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(divider),
        )
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = if (isNewEntry) onAddPagePrimary else onOpenPage),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val label = if (isNewEntry) "Add a page" else "Open page ${currentPageIndex + 1}"
            // Brief: only Regular and Medium weights anywhere — no
            // SemiBold or Bold.
            BasicText(
                text = label,
                style = Type.Cta.copy(
                    color = labelColor,
                    fontWeight = FontWeight.Medium,
                ),
            )
            Spacer(Modifier.weight(1f))
            // Circular arrow / plus button. Both colour roles flip
            // together so the button always stays legible against the
            // surrounding hero card.
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(buttonBg),
                contentAlignment = Alignment.Center,
            ) {
                if (isNewEntry) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = null,
                        tint = buttonFg,
                        modifier = Modifier.size(18.dp),
                    )
                } else {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        tint = buttonFg,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

