/*
 * TreesSavedHero.kt
 * Celebratory "trees saved" hero card for the Home tab — deep-green
 * slab with a coral headline number, a three-tree cluster on the
 * right, a segmented composition bar, and a five-mode legend.
 *
 * Use on: Home tab (always visible). For the Notebooks tab
 * (HomeScreenVariant1) use the compact [TreesSavedStrip] instead.
 *
 * Formula: trees = (notes + photos + scans + voice + contacts) / 8000
 * Rationale: ~8000 pages per mature tree (Conservatree heuristic).
 */

package app.releaf.mobile.features.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.releaf.mobile.data.domain.CaptureCountsByMode
import app.releaf.mobile.ui.theme.AppColors
import app.releaf.mobile.ui.theme.AppRadius
import app.releaf.mobile.ui.theme.AppSpacing
import app.releaf.mobile.ui.theme.AppTypography
import app.releaf.mobile.ui.theme.LocalFontWeight

// ---------- Palette (fixed — does not theme) ----------

private val HeroBg     = Color(0xFF1E5943)
private val HeroNumber = Color(0xFFE77850)
private val HeroMuted  = Color(0xFFD9EDE2)
private val HeroTrack  = Color(0x1FFFFAF4)

private val SegNotes     = Color(0xFF7AA874)
private val SegPhotos    = Color(0xFFF4C430)
private val SegScans     = Color(0xFFE77850)
private val SegVoice     = Color(0xFFFCEAE0)
private val SegContacts  = Color(0xFFD9EDE2)
private val SegLocations = Color(0xFFB8956A)

// ---------- Hero ----------

@Composable
fun TreesSavedHero(
    counts: CaptureCountsByMode,
    modifier: Modifier = Modifier,
) {
    var animate by remember { mutableStateOf(false) }
    val target = (counts.total / 8333.0).toFloat()
    val displayed by animateFloatAsState(
        targetValue = if (animate) target else 0f,
        animationSpec = tween(durationMillis = 1400),
        label = "trees-hero-count",
    )
    val capturesDisplayed by animateFloatAsState(
        targetValue = if (animate) counts.total.toFloat() else 0f,
        animationSpec = tween(durationMillis = 1400),
        label = "captures-hero-count",
    )
    val barFraction by animateFloatAsState(
        targetValue = if (animate) 1f else 0f,
        animationSpec = tween(durationMillis = 900, delayMillis = 400),
        label = "trees-hero-bar",
    )
    LaunchedEffect(counts) { animate = true }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.lg))
            .background(HeroBg)
            .padding(AppSpacing.s5),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.s3),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                text = "TREES SAVED",
                style = AppTypography.Eyebrow,
                color = HeroMuted,
                modifier = Modifier.weight(1f),
            )
            Row(verticalAlignment = Alignment.Bottom) {
                TreeGlyph(height = 44.dp)
                Spacer(Modifier.width(2.dp))
                TreeGlyph(height = 54.dp)
                Spacer(Modifier.width(2.dp))
                TreeGlyph(height = 40.dp)
            }
        }

        Text(
            text = formatTrees(displayed),
            color = HeroNumber,
            fontSize = 72.sp,
            fontWeight = LocalFontWeight.current,
        )

        Text(
            text = "${formatCount(capturesDisplayed.toInt())} captures kept digital · Every ~8,333 sheets = 1 tree",
            style = AppTypography.Meta,
            color = HeroMuted,
        )

        CompositionBar(
            counts = counts,
            fraction = barFraction,
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .padding(top = 4.dp),
        )

        LegendGrid(counts = counts)
    }
}

// ---------- Legend ----------

@Composable
private fun LegendGrid(counts: CaptureCountsByMode) {
    val rows = listOf(
        listOf(
            Triple("Notes",  counts.notes,  SegNotes),
            Triple("Photos", counts.photos, SegPhotos),
            Triple("Scans",  counts.scans,  SegScans),
        ),
        listOf(
            Triple("Voice",     counts.voice,     SegVoice),
            Triple("Contacts",  counts.contacts,  SegContacts),
            Triple("Locations", counts.locations, SegLocations),
        ),
    )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.s3)) {
                row.forEach { (label, count, color) ->
                    LegendDot(label = label, count = count, color = color,
                              modifier = Modifier.weight(1f))
                }
                // Pad trailing cells so 3-column grid stays aligned
                repeat(3 - row.size) {
                    Box(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun LegendDot(
    label: String,
    count: Int,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(color),
        )
        Text(label, style = AppTypography.Tag, color = HeroMuted)
        Text(
            text = formatCount(count),
            style = AppTypography.Tag,
            color = AppColors.OnAccent,
            fontWeight = LocalFontWeight.current,
        )
    }
}

// ---------- Composition bar ----------

@Composable
private fun CompositionBar(
    counts: CaptureCountsByMode,
    fraction: Float,
    modifier: Modifier = Modifier,
) {
    val total = counts.total.coerceAtLeast(1).toFloat()
    val parts = listOf(
        SegNotes     to counts.notes,
        SegPhotos    to counts.photos,
        SegScans     to counts.scans,
        SegVoice     to counts.voice,
        SegContacts  to counts.contacts,
        SegLocations to counts.locations,
    )
    // Canvas-based drawing — each segment's width is computed
    // against the Canvas's full width, so fractions never compound
    // (the Row/fillMaxWidth version compressed later segments by
    // applying their fraction to the *remaining* row width).
    Canvas(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(HeroTrack),
    ) {
        var x = 0f
        parts.forEach { (color, value) ->
            val w = (value.toFloat() / total) * size.width * fraction
            if (w > 0f) {
                drawRect(
                    color = color,
                    topLeft = Offset(x, 0f),
                    size = androidx.compose.ui.geometry.Size(w, size.height),
                )
                x += w
            }
        }
    }
}

// ---------- Tree glyph ----------

private val CanopyTop    = Color(0xFF7AA874)
private val CanopyMid    = Color(0xFF5B8C52)
private val CanopyBottom = Color(0xFF3E6B3B)
private val TrunkColor   = Color(0xFF3E2A18)

@Composable
private fun TreeGlyph(height: androidx.compose.ui.unit.Dp) {
    Canvas(
        modifier = Modifier
            .width(height * 0.8f)
            .height(height),
    ) {
        val w = size.width
        val h = size.height

        val top = Path().apply {
            moveTo(w * 0.50f, h * 0.04f)
            lineTo(w * 0.25f, h * 0.44f)
            lineTo(w * 0.75f, h * 0.44f)
            close()
        }
        drawPath(top, CanopyTop)

        val mid = Path().apply {
            moveTo(w * 0.50f, h * 0.28f)
            lineTo(w * 0.15f, h * 0.68f)
            lineTo(w * 0.85f, h * 0.68f)
            close()
        }
        drawPath(mid, CanopyMid)

        val bot = Path().apply {
            moveTo(w * 0.50f, h * 0.44f)
            lineTo(w * 0.06f, h * 0.88f)
            lineTo(w * 0.94f, h * 0.88f)
            close()
        }
        drawPath(bot, CanopyBottom)

        drawRect(
            color = TrunkColor,
            topLeft = Offset(w * 0.44f, h * 0.88f),
            size = androidx.compose.ui.geometry.Size(w * 0.12f, h * 0.12f),
        )
    }
}

// ---------- helpers ----------

private fun formatCount(value: Int): String {
    if (value < 1000) return value.toString()
    val s = value.toString()
    val out = StringBuilder()
    val len = s.length
    for (i in 0 until len) {
        if (i > 0 && (len - i) % 3 == 0) out.append(',')
        out.append(s[i])
    }
    return out.toString()
}

/** Trees number formatter — up to 3 decimal places, trailing zeros
 *  dropped (so "3.2", "3.25", "3.251" but never "3.200"). */
private fun formatTrees(value: Float): String =
    "%.3f".format(value).trimEnd('0').trimEnd('.')
