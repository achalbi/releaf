/*
 * StoryReaderScreen.kt
 *
 * Stories Phase 3 — the reader (§7.4 of the v3 mockup). Mirror of
 * iOS `StoryReaderScreen.swift`; see that file's header for the
 * ASCII layout.
 */

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.quickink.mobile.features.stories

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.palette.graphics.Palette
import android.graphics.BitmapFactory
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.quickink.mobile.QuickInkApp
import app.quickink.mobile.data.story.StoryEntity
import app.quickink.mobile.data.storyitem.StoryItemEntity
import app.quickink.mobile.ui.theme.LocalQuickInkColors
import app.quickink.mobile.ui.theme.LocalQuickInkTypography
import app.quickink.mobile.ui.theme.QuickInkRadius
import app.quickink.mobile.ui.theme.QuickInkSpacing
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun StoryReaderScreen(
    storyId: String,
    userId: String,
    onBack: () -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current

    // Reuse the editor's VM for the live observation. Reader is
    // read-only — no writes happen here.
    val vm: StoryEditorViewModel = viewModel(factory = StoryEditorViewModel.factory(storyId, userId))
    val story by vm.story.collectAsState()
    val items by vm.items.collectAsState()

    var toastMessage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(toastMessage) {
        if (toastMessage != null) {
            delay(2_000)
            toastMessage = null
        }
    }

    val markers = remember(items) { StoryDayMarkers.derive(items) }
    val markerByItemId = remember(markers) { markers.associateBy { it.precedingItemId } }

    // Palette-extracted dominant colour for the cover gradient.
    // Cached after first compute keyed by capture id; missing →
    // null → falls back to the static palette in `StoryCoverColor`.
    val context = androidx.compose.ui.platform.LocalContext.current
    val app     = remember(context) { context.applicationContext as QuickInkApp }
    var dominantCoverColor by remember { mutableStateOf<Int?>(null) }
    var lastSampledCaptureId by remember { mutableStateOf<String?>(null) }
    val coverItemId = story?.coverItemId
    val firstItemId = items.firstOrNull()?.id
    LaunchedEffect(coverItemId, firstItemId) {
        val targetItemId = coverItemId ?: firstItemId ?: return@LaunchedEffect
        val item = items.firstOrNull { it.id == targetItemId } ?: return@LaunchedEffect
        val captureId = item.refId ?: return@LaunchedEffect
        if (captureId == lastSampledCaptureId) return@LaunchedEffect
        lastSampledCaptureId = captureId
        dominantCoverColor = withContext(Dispatchers.IO) {
            val previewUri = app.database.captureDao().findById(captureId)?.previewUri
            previewUri?.let { extractDominantColor(it) }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(colors.bg)) {

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start  = QuickInkSpacing.s4,
                end    = QuickInkSpacing.s4,
                top    = QuickInkSpacing.s7,
                bottom = QuickInkSpacing.s6,
            ),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            item("cover") {
                CoverCard(
                    story          = story,
                    firstItemIso   = items.firstOrNull()?.let { it.occurredAt ?: it.createdAt },
                    dominantColor  = dominantCoverColor,
                )
            }
            item("attribution") {
                Text(
                    text     = "${items.size} items",
                    style    = type.bodyItalic,
                    color    = colors.inkSoft,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = QuickInkSpacing.s3),
                    textAlign = TextAlign.Center,
                )
            }

            items.forEach { storyItem ->
                markerByItemId[storyItem.id]?.let { marker ->
                    item("marker-${storyItem.id}") { DayMarker(label = marker.label) }
                }
                item(storyItem.id) {
                    ReaderRow(storyItem)
                    Spacer(modifier = Modifier.height(QuickInkSpacing.s2))
                }
            }

            item("end") {
                EndCard(
                    onReply     = { toastMessage = "Reply target lands in v1.1 — stay tuned." },
                    onMakeOwn   = { toastMessage = "Make your own — coming in Phase 4 share sheet." },
                )
            }
            item("footer") {
                Text(
                    text       = "— Made with QuickInk · scan, jot, find again. —",
                    style      = type.bodyItalic,
                    color      = colors.muted,
                    textAlign  = TextAlign.Center,
                    modifier   = Modifier
                        .fillMaxWidth()
                        .padding(top = QuickInkSpacing.s3),
                )
            }
        }

        // Back chevron, top-left.
        val interactionSource = remember { MutableInteractionSource() }
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(QuickInkSpacing.s3)
                .size(36.dp)
                .shadow(elevation = 4.dp, shape = CircleShape)
                .clip(CircleShape)
                .background(colors.surface.copy(alpha = 0.92f))
                .clickable(
                    interactionSource = interactionSource,
                    indication        = null,
                    onClick           = onBack,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector        = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = "Back",
                tint               = colors.ink,
                modifier           = Modifier.size(20.dp),
            )
        }

        // Toast (top-center).
        AnimatedVisibility(
            visible  = toastMessage != null,
            enter    = fadeIn(),
            exit     = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 60.dp),
        ) {
            val msg = toastMessage
            if (msg != null) {
                Box(
                    modifier = Modifier
                        .shadow(elevation = 4.dp, shape = RoundedCornerShape(QuickInkRadius.pill))
                        .clip(RoundedCornerShape(QuickInkRadius.pill))
                        .background(colors.surface)
                        .border(0.5.dp, colors.border, RoundedCornerShape(QuickInkRadius.pill))
                        .padding(horizontal = 12.dp, vertical = 5.dp),
                ) {
                    Text(text = msg, style = type.bodyItalic, color = colors.inkSoft, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun CoverCard(
    story: StoryEntity?,
    firstItemIso: String?,
    /** Palette-extracted dominant colour. When present, overrides
     *  the static `coverStyle` palette and pairs with a lightened
     *  / darkened sibling for the two-stop gradient. */
    dominantColor: Int? = null,
) {
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current

    val (startColor, endColor) = if (dominantColor != null) {
        val base = Color(dominantColor)
        lightenColor(base, 0.10f) to darkenColor(base, 0.10f)
    } else {
        StoryCoverColor.colorsFor(story?.coverStyle)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
            .padding(bottom = QuickInkSpacing.s3)
            .clip(RoundedCornerShape(12.dp))
            .background(Brush.linearGradient(listOf(startColor, endColor))),
    ) {
        // Bottom-fade for legibility.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, colors.ink.copy(alpha = 0.25f)),
                    )
                ),
        )

        // Top-left stamp + bottom-left title + handwritten subtitle.
        val stampSource = story?.timeRangeStart ?: firstItemIso ?: story?.createdAt
        val stamp = stampSource?.let { StoryCoverColor.monthYearStamp(it) }
        Column(modifier = Modifier.align(Alignment.BottomStart).padding(QuickInkSpacing.s3 + 2.dp)) {
            if (!stamp.isNullOrEmpty()) {
                Text(
                    text          = stamp,
                    style         = type.bodyItalic,
                    color         = colors.inkSoft,
                    fontSize      = 9.sp,
                    letterSpacing = 2.sp,
                    modifier      = Modifier.padding(bottom = 4.dp),
                )
            }
            Text(
                text       = story?.title ?: "Untitled story",
                fontSize   = 28.sp,
                fontWeight = FontWeight.Medium,
                style      = type.display.copy(lineHeight = 30.sp),
                color      = colors.ink,
            )
            val sub = story?.subtitle.orEmpty()
            if (sub.isNotEmpty()) {
                Text(
                    text     = sub,
                    style    = type.handwritten,
                    fontSize = 15.sp,
                    color    = colors.ink.copy(alpha = 0.85f),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun DayMarker(label: String) {
    val colors = LocalQuickInkColors.current
    Text(
        text          = label,
        color         = colors.accentDeep,
        fontSize      = 12.sp,
        fontStyle     = FontStyle.Italic,
        letterSpacing = 1.sp,
        modifier      = Modifier
            .fillMaxWidth()
            .background(colors.bg)
            .padding(top = QuickInkSpacing.s2, bottom = QuickInkSpacing.s1 + 2.dp),
    )
}

@Composable
private fun ReaderRow(item: StoryItemEntity) {
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current
    when (item.kind) {
        StoryItemEntity.Kind.TEXT_BLOCK.raw -> {
            Text(
                text       = item.text.orEmpty(),
                style      = type.editorial.copy(lineHeight = 22.sp),
                fontSize   = 14.sp,
                color      = colors.ink,
                modifier   = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = (QuickInkSpacing.s1.value + 2).dp),
            )
        }
        StoryItemEntity.Kind.HANDWRITTEN_NOTE.raw -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = (QuickInkSpacing.s1.value + 2).dp),
            ) {
                Box(modifier = Modifier.width(2.dp).height(36.dp).background(colors.accent))
                Spacer(modifier = Modifier.width(QuickInkSpacing.s2))
                Text(
                    text     = item.text.orEmpty(),
                    style    = type.handwritten,
                    fontSize = 16.sp,
                    color    = colors.inkSoft,
                )
            }
        }
        StoryItemEntity.Kind.DATE_DIVIDER.raw -> {
            Row(
                modifier = Modifier.padding(vertical = QuickInkSpacing.s2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.weight(1f).height(0.5.dp).background(colors.border))
                Text(
                    text          = item.text ?: "Date divider",
                    style         = type.bodyItalic,
                    fontSize      = 12.sp,
                    fontWeight    = FontWeight.Medium,
                    color         = colors.accentDeep,
                    letterSpacing = 1.sp,
                    modifier      = Modifier.padding(horizontal = QuickInkSpacing.s3),
                )
                Box(modifier = Modifier.weight(1f).height(0.5.dp).background(colors.border))
            }
        }
        StoryItemEntity.Kind.PLACE_PIN.raw -> {
            Row(
                modifier = Modifier.padding(vertical = QuickInkSpacing.s1),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2),
            ) {
                Icon(
                    imageVector        = Icons.Filled.LocationOn,
                    contentDescription = null,
                    tint               = colors.accent,
                    modifier           = Modifier.size(16.dp),
                )
                Text(
                    text       = item.text.orEmpty(),
                    style      = type.editorial,
                    fontSize   = 13.sp,
                    fontStyle  = FontStyle.Italic,
                    fontWeight = FontWeight.Medium,
                    color      = colors.inkSoft,
                )
            }
        }
        StoryItemEntity.Kind.VOICE_CLIP.raw -> {
            ReaderCard {
                Row(
                    modifier = Modifier.padding(QuickInkSpacing.s2 + 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(colors.accentSoft),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector        = Icons.Filled.Mic,
                            contentDescription = null,
                            tint               = colors.accent,
                            modifier           = Modifier.size(18.dp),
                        )
                    }
                    Spacer(modifier = Modifier.width(QuickInkSpacing.s2))
                    Text(
                        text     = item.caption ?: "Voice clip",
                        style    = type.bodyItalic,
                        fontSize = 12.sp,
                        color    = colors.inkSoft,
                    )
                }
            }
        }
        else -> {
            ReaderCard {
                Column(modifier = Modifier.padding(QuickInkSpacing.s2)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(photoHeight(item.layout))
                            .clip(RoundedCornerShape(8.dp))
                            .background(colors.paper1),
                    )
                    val caption = item.caption.orEmpty()
                    if (caption.isNotEmpty()) {
                        Text(
                            text      = caption,
                            style     = type.bodyItalic,
                            fontSize  = 12.sp,
                            color     = colors.inkSoft,
                            modifier  = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReaderCard(content: @Composable () -> Unit) {
    val colors = LocalQuickInkColors.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(12.dp)),
    ) {
        content()
    }
}

@Composable
private fun EndCard(onReply: () -> Unit, onMakeOwn: () -> Unit) {
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = QuickInkSpacing.s3)
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(14.dp))
            .padding(QuickInkSpacing.s3 + 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text          = "— THE END —",
            style         = type.bodyItalic,
            fontSize      = 13.sp,
            letterSpacing = 1.5.sp,
            color         = colors.inkSoft,
            modifier      = Modifier.padding(bottom = QuickInkSpacing.s2 + 2.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.bg)
                    .border(1.dp, colors.border, RoundedCornerShape(10.dp))
                    .clickable(onClick = onReply)
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text       = "Reply with a note",
                    fontSize   = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color      = colors.inkSoft,
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.accent)
                    .clickable(onClick = onMakeOwn)
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text       = "Make your own",
                    fontSize   = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color      = colors.textOnAccent,
                )
            }
        }
    }
}

private fun photoHeight(layoutRaw: String) = when (layoutRaw) {
    StoryItemEntity.Layout.HALF.raw -> 80.dp
    StoryItemEntity.Layout.GRID.raw -> 64.dp
    else                            -> 140.dp
}

/**
 * Cover-gradient palette keyed by `StoryEntity.CoverStyle`. Phase 3
 * fallback; Palette-API-driven dominant-colour from the hero photo
 * lands as a follow-up (loads cover_item_id's preview bitmap,
 * `Palette.from(bitmap).generate()`, caches the result).
 */
object StoryCoverColor {

    fun colorsFor(coverStyleRaw: String?): Pair<Color, Color> {
        // Reading directly from QuickInkColors via a Composable Local
        // requires a Composable context; this is called only from
        // composables that already have access, but we read the
        // current colors by capturing them via a public read on the
        // static defaults table. To keep the implementation simple,
        // return semantic Color pairs and let the caller map them.
        return when (coverStyleRaw) {
            StoryEntity.CoverStyle.GRADIENT.raw    -> CORAL_PAIR
            StoryEntity.CoverStyle.TYPOGRAPHIC.raw -> TAN_PAIR
            else                                   -> WARM_PAIR
        }
    }

    private val WARM_PAIR  = Color(0xFFE8DCC4) to Color(0xFFF0E4D7)
    private val TAN_PAIR   = Color(0xFFEADFCF) to Color(0xFFE8DCC4)
    private val CORAL_PAIR = Color(0xFFE07856) to Color(0xFFC65A3E)

    private val MONTH_YEAR_FMT = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH)

    fun monthYearStamp(iso: String): String? = runCatching {
        OffsetDateTime.parse(iso).format(MONTH_YEAR_FMT).uppercase()
    }.getOrNull()
}

/** Run Palette on the preview JPEG. Returns the dominant or vibrant
 *  swatch's RGB int, or null when the file can't be decoded. */
private fun extractDominantColor(previewUri: String): Int? = runCatching {
    val path = if (previewUri.startsWith("file://")) {
        android.net.Uri.parse(previewUri).path
    } else {
        previewUri
    } ?: return null
    val bitmap = BitmapFactory.decodeFile(path) ?: return null
    val palette = Palette.from(bitmap).generate()
    bitmap.recycle()
    palette.dominantSwatch?.rgb
        ?: palette.vibrantSwatch?.rgb
        ?: palette.mutedSwatch?.rgb
}.getOrNull()

private fun lightenColor(c: Color, fraction: Float): Color {
    return Color(
        red   = minOf(1f, c.red   + (1f - c.red)   * fraction),
        green = minOf(1f, c.green + (1f - c.green) * fraction),
        blue  = minOf(1f, c.blue  + (1f - c.blue)  * fraction),
        alpha = c.alpha,
    )
}

private fun darkenColor(c: Color, fraction: Float): Color {
    return Color(
        red   = maxOf(0f, c.red   * (1f - fraction)),
        green = maxOf(0f, c.green * (1f - fraction)),
        blue  = maxOf(0f, c.blue  * (1f - fraction)),
        alpha = c.alpha,
    )
}
