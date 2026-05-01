/*
 * TreesSavedStrip.kt
 * Ambient "trees saved" strip — always-visible single-line stat that
 * sits above the shelf filter row on the variant-1 shelves screen.
 * A three-tier evergreen glyph sways gently on idle; the number
 * animates up to its target on first composition.
 *
 * Formula: trees = (notes + photos + scans + voice + contacts) / 8000
 * Rationale: ~8000 pages per mature tree (Conservatree heuristic).
 */

package app.releaf.mobile.features.home

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.releaf.mobile.ui.theme.AppColors
import app.releaf.mobile.ui.theme.AppRadius
import app.releaf.mobile.ui.theme.AppSpacing
import app.releaf.mobile.ui.theme.AppTypography
import app.releaf.mobile.ui.theme.LocalFontWeight
import kotlin.math.max
import kotlin.math.roundToInt

// ---------- Metrics ----------

/**
 * Aggregated capture counts that drive the "trees saved" figure.
 * Populate from `CaptureRepository` inside the view model. Pages
 * count as "notes" until the domain layer exposes mode-typed capture
 * totals.
 */
data class TreesSavedMetrics(
    val notes: Int,
    val photos: Int = 0,
    val scans: Int = 0,
    val voice: Int = 0,
    val contacts: Int = 0,
    val locations: Int = 0,
) {
    val total: Int get() = notes + photos + scans + voice + contacts + locations
    val trees: Double get() = total.toDouble() / CAPTURES_PER_TREE
    val progressToNextTree: Double
        get() = (total % CAPTURES_PER_TREE.toInt()).toDouble() / CAPTURES_PER_TREE

    companion object {
        /** ~8,333 sheets ≈ 1 tree (Conservatree heuristic). */
        const val CAPTURES_PER_TREE: Double = 8333.0
    }
}

// ---------- Strip ----------

@Composable
fun TreesSavedStrip(
    metrics: TreesSavedMetrics,
    modifier: Modifier = Modifier,
) {
    var animate by remember { mutableStateOf(false) }
    val target = metrics.trees.toFloat()
    val displayed by animateFloatAsState(
        targetValue = if (animate) target else 0f,
        animationSpec = tween(durationMillis = 900),
        label = "trees-count-up",
    )
    LaunchedEffect(metrics) { animate = true }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.md))
            .background(AppColors.CardSolid)
            .border(
                width = 0.5.dp,
                color = AppColors.ThemeGreenDeep.copy(alpha = 0.25f),
                shape = RoundedCornerShape(AppRadius.md),
            )
            .padding(horizontal = AppSpacing.s4, vertical = AppSpacing.s3),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.s3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TreeGlyph(modifier = Modifier.size(width = 36.dp, height = 44.dp))

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                androidx.compose.material3.Text(
                    text = formatTrees(displayed),
                    color = AppColors.ThemeGreenDeep,
                    fontSize = 30.sp,
                    fontWeight = LocalFontWeight.current,
                )
                androidx.compose.material3.Text(
                    text = "trees saved",
                    style = AppTypography.Meta,
                    color = AppColors.TextSecondary,
                )
            }
            // The total spans both notebook pages AND notepad entries
            // (plus their attachments + contacts + locations) — the
            // "kept digital" copy framing captures the user impact: each
            // count is a paper page that didn't get printed.
            androidx.compose.material3.Text(
                text = "${formatCount(metrics.total)} captures kept digital",
                style = AppTypography.Tag,
                color = AppColors.TextTertiary,
                maxLines = 1,
            )
        }

        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            androidx.compose.material3.Text(
                text = "NEXT TREE",
                style = AppTypography.Eyebrow,
                color = AppColors.TextSecondary,
            )
            Box(
                modifier = Modifier
                    .width(52.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(AppRadius.pill))
                    .background(AppColors.Subtle),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(metrics.progressToNextTree.toFloat())
                        .height(4.dp)
                        .clip(RoundedCornerShape(AppRadius.pill))
                        .background(AppColors.ThemeGreenPrimary),
                )
            }
            androidx.compose.material3.Text(
                text = "%.1f to go".format(max(0.0, 1.0 - metrics.progressToNextTree)),
                style = AppTypography.Tag,
                color = AppColors.TextSecondary,
            )
        }
    }
}

// ---------- Tree glyph ----------

private val CanopyTop    = Color(0xFF7AA874)
private val CanopyMid    = Color(0xFF5B8C52)
private val CanopyBottom = Color(0xFF3E6B3B)
private val TrunkColor   = Color(0xFF3E2A18)

@Composable
private fun TreeGlyph(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "tree-sway")
    val sway by transition.animateFloat(
        initialValue = -1f,
        targetValue  = 1.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "sway-angle",
    )
    Canvas(modifier = modifier.rotate(sway)) {
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
    // Insert thousands separators. Avoid pulling in NumberFormat to
    // keep the strip allocation-free on recomposition.
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
