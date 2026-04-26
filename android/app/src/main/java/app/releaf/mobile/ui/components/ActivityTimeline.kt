/*
 * ActivityTimeline.kt
 *
 * Bramble-garland activity timeline. A serpentine vine runs down the
 * left of a cream card. Each entry is a 5-petal flower in one of the
 * four leaf themes; between entries the vine carries a stout leaf and
 * a berry trio sitting on the vine line.
 *
 * Source spec: design-system/timeline-vine-bramble-garland.html.
 *
 * Layout is fixed-height: each entry occupies `EntryStrideDp` (90dp)
 * so the vine bezier can be plotted from known y positions. If a
 * dynamic-height variant is needed later, swap the Canvas overlay for
 * a custom Layout that measures children and feeds y positions back.
 */

package app.releaf.mobile.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.releaf.mobile.ui.theme.AccentPalettes
import app.releaf.mobile.ui.theme.AccentPaletteId
import app.releaf.mobile.ui.theme.AppAccent
import app.releaf.mobile.ui.theme.AppColors
import app.releaf.mobile.ui.theme.AppSpacing
import app.releaf.mobile.ui.theme.AppTypography
import java.util.UUID

// region Public model

enum class ActivityProminence {
    /** Featured entry — bigger flower (today, captured highlights). */
    Featured,

    /** Routine entry — smaller flower. */
    Routine,
}

data class ActivityEntry(
    val date: String,
    val title: String,
    val preview: String? = null,
    val theme: AccentPaletteId,
    val prominence: ActivityProminence = ActivityProminence.Routine,
    val id: String = UUID.randomUUID().toString(),
)

// endregion

// region Layout constants — match the SwiftUI mirror so the two stay diff-able.

/** Width of the leading column reserved for the vine + flower marker. */
private val LeadColumnDp: Dp = 64.dp

/** Vertical distance between entry centers. */
private val EntryStrideDp: Dp = 64.dp

/** Where the vine sits inside the leading column (x within the card). */
private val VineXDp: Dp = 46.dp

/** Half-width of each vine bulge (control-x offset from VineXDp). */
private val VineBulgeDp: Dp = 20.dp

private val VineStrokeDp: Dp = 1.8.dp

/** Visible flower marker size — used to offset it onto the vine line. */
private val MarkerSizeDp: Dp = 34.dp

/** Length of the trailing vine stub past the last entry. */
private val TrailingTailDp: Dp = 40.dp

// endregion

@Composable
fun ActivityTimeline(
    entries: List<ActivityEntry>,
    modifier: Modifier = Modifier,
    header: String = "Activity",
    showsArrow: Boolean = false,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(AppColors.CardSolid)
            .border(0.5.dp, AppColors.BorderDefault, RoundedCornerShape(14.dp)),
    ) {
        // Eyebrow header — matches the YOUR LIBRARY / CONTACTS pattern
        // from the other Home cards.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = AppSpacing.s4,
                    end = AppSpacing.s4,
                    top = AppSpacing.s4,
                    bottom = AppSpacing.s2,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = header,
                style = AppTypography.Eyebrow,
                color = AppColors.ThemeGreenDeep,
                modifier = Modifier.weight(1f),
            )
            if (showsArrow) {
                Text(
                    text = "\u2192",
                    style = AppTypography.Button,
                    color = AppAccent.primary,
                )
            }
        }

        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.s4)
                .height(0.5.dp)
                .background(AppColors.BorderDefault),
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(EntryStrideDp * entries.size + TrailingTailDp),
        ) {
            VineDecoration(
                entryCount = entries.size,
                modifier = Modifier.fillMaxWidth(),
            )

            Column {
                entries.forEach { entry ->
                    EntryRow(entry)
                }
            }
        }
    }
}

@Composable
private fun EntryRow(entry: ActivityEntry) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(EntryStrideDp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Lead column: position the marker at (VineXDp, mid-row) so it
        // lands on the vine line drawn by the Canvas underneath.
        val markerHalf = MarkerSizeDp / 2
        Box(
            modifier = Modifier
                .width(LeadColumnDp)
                .height(EntryStrideDp),
        ) {
            FlowerMarker(
                theme = entry.theme,
                prominence = entry.prominence,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(
                        x = VineXDp - markerHalf,
                        y = EntryStrideDp / 2 - markerHalf,
                    ),
            )
        }

        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp, end = AppSpacing.s4),
        ) {
            Text(
                text = entry.date,
                fontSize = 11.sp,
                color = AppColors.TextSecondary,
                style = AppTypography.Tag,
            )
            Text(
                text = entry.title,
                fontSize = 14.sp,
                color = AppColors.TextPrimary,
                style = AppTypography.Body,
            )
            entry.preview?.let { preview ->
                Text(
                    text = preview,
                    fontSize = 12.sp,
                    color = AppColors.TextSecondary,
                    style = AppTypography.Meta,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// region Vine + garland drawing

@Composable
private fun VineDecoration(entryCount: Int, modifier: Modifier = Modifier) {
    val vineGreenDeep = AppColors.ThemeGreenDeep
    val vineGreen = AppColors.ThemeGreenPrimary
    val midribColor = Color(0x66463C31)
    val coralBerry = AppColors.Coral700
    val brownBerry = AppColors.ThemeDryDeep

    Canvas(modifier = modifier.height(EntryStrideDp * entryCount + TrailingTailDp)) {
        if (entryCount == 0) return@Canvas

        val vineX = VineXDp.toPx()
        val bulge = VineBulgeDp.toPx()
        val stride = EntryStrideDp.toPx()
        val totalHeight = stride * entryCount + TrailingTailDp.toPx()

        fun entryY(index: Int): Float = stride / 2f + index * stride

        // --- vine path ---
        val vinePath = Path().apply {
            moveTo(vineX, 0f)
            lineTo(vineX, entryY(0))
            for (index in 1 until entryCount) {
                val prevY = entryY(index - 1)
                val nextY = entryY(index)
                val bulgeRight = (index - 1) % 2 == 0
                val controlX = if (bulgeRight) vineX + bulge else vineX - bulge
                val controlY = (prevY + nextY) / 2f
                quadraticTo(controlX, controlY, vineX, nextY)
            }
            lineTo(vineX, totalHeight)
        }
        drawPath(
            path = vinePath,
            color = vineGreenDeep,
            style = Stroke(width = VineStrokeDp.toPx(), cap = StrokeCap.Round),
        )

        // --- garland: leaf + berry trio in each gap between entries ---
        for (gap in 0 until (entryCount - 1)) {
            val bulgeRight = gap % 2 == 0
            val apexY = (entryY(gap) + entryY(gap + 1)) / 2f
            val sideX = if (bulgeRight) vineX + bulge / 2f else vineX - bulge / 2f

            drawLeaf(
                base = Offset(x = sideX, y = apexY - 14f),
                rotateDegrees = if (bulgeRight) 40f else -40f,
                fill = vineGreen,
                stroke = vineGreenDeep,
                midrib = midribColor,
            )

            drawBerryTrio(
                center = Offset(x = sideX, y = apexY + 30f),
                color = if (bulgeRight) coralBerry else brownBerry,
                mirrored = !bulgeRight,
            )
        }
    }
}

private fun DrawScope.drawLeaf(
    base: Offset,
    rotateDegrees: Float,
    fill: Color,
    stroke: Color,
    midrib: Color,
) {
    // Leaf path drawn at origin, base at (0,0), tip at (0,-48). Fill +
    // stroke + midrib. Wrapped in translate(...) + rotate(...) so the
    // path units stay in the same coordinate space across calls.
    translate(left = base.x, top = base.y) {
        rotate(degrees = rotateDegrees, pivot = Offset.Zero) {
            val leaf = Path().apply {
                moveTo(0f, 0f)
                quadraticTo(-13f, -26f, 0f, -48f)
                quadraticTo(13f, -26f, 0f, 0f)
                close()
            }
            drawPath(path = leaf, color = fill)
            drawPath(path = leaf, color = stroke, style = Stroke(width = 1f))

            val midribPath = Path().apply {
                moveTo(0f, -6f)
                lineTo(0f, -42f)
            }
            drawPath(path = midribPath, color = midrib, style = Stroke(width = 0.8f))
        }
    }
}

private fun DrawScope.drawBerryTrio(center: Offset, color: Color, mirrored: Boolean) {
    val signX = if (mirrored) -1f else 1f
    val offsets = listOf(
        Triple(0f,            0f,    7.2f),
        Triple(10.8f * signX, 6.8f,  6.8f),
        Triple(-5.2f * signX, 10.0f, 6.2f),
    )
    for ((dx, dy, radius) in offsets) {
        drawCircle(
            color = color,
            radius = radius,
            center = Offset(center.x + dx, center.y + dy),
        )
    }
}

// endregion

// region Flower marker

@Composable
private fun FlowerMarker(
    theme: AccentPaletteId,
    prominence: ActivityProminence,
    modifier: Modifier = Modifier,
) {
    val palette = AccentPalettes.forId(theme)
    val isFeatured = prominence == ActivityProminence.Featured
    val petalRx = if (isFeatured) 10.8f else 9.6f
    val petalRy = if (isFeatured) 15.6f else 14.0f
    val centerR = if (isFeatured) 6.8f else 5.6f
    val petalCenterY = if (isFeatured) -17.0f else -15.4f
    val centerColor = if (theme == AccentPaletteId.Yellow) AppColors.Coral700 else AppColors.ThemeYellowPrimary

    Canvas(modifier = modifier.size(MarkerSizeDp)) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        translate(left = cx, top = cy) {
            for (i in 0 until 5) {
                rotate(degrees = i * 72f, pivot = Offset.Zero) {
                    drawOval(
                        color = palette.primary,
                        topLeft = Offset(-petalRx, petalCenterY - petalRy),
                        size = androidx.compose.ui.geometry.Size(petalRx * 2f, petalRy * 2f),
                    )
                    drawOval(
                        color = palette.deep,
                        topLeft = Offset(-petalRx, petalCenterY - petalRy),
                        size = androidx.compose.ui.geometry.Size(petalRx * 2f, petalRy * 2f),
                        style = Stroke(width = 1f),
                    )
                }
            }
            drawCircle(color = centerColor, radius = centerR, center = Offset.Zero)
        }
    }
}

// endregion
