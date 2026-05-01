/*
 * CaptureScreen.kt
 *
 * The Capture page — promoted from the v6 ModalBottomSheet to a real
 * top-level destination per docs/CAPTURE_TAB_PLAN.md. Rendered as a
 * normal screen on the cream canvas with the BottomNav floating below
 * it; no drag handle, no scrim, no top-rounded corners.
 *
 * Layout (top to bottom):
 *   - Page header     :  "RELEAF" eyebrow + "Capture" serif title +
 *                        Search + Calendar icon buttons.
 *   - Day | Recents   :  Segmented control. Day = capture surface,
 *                        Recents = list of recent captures (Phase 4).
 *   - Scan hero       :  Mustard "Scan a page" card with Scan/Import.
 *   - Pre-tag chips   :  Horizontal scroll row of the user's top-N
 *                        scan-tagged categories.
 *   - "Or capture differently" divider.
 *   - 6-tile grid     :  Notes · Photo · Voice · Todo · Contact · Pin.
 *   - Footer hint     :  "Hold the green Leaf button to record voice…"
 *
 * Status:
 *   - Phase 1: ✓ scaffold (this file's first commit).
 *   - Phase 2: ✓ wired into NavHost; FAB navigates here.
 *   - Phase 4: ✓ Scan hero decorations (paper + crop-corner brackets);
 *               ✓ Notes added to CaptureMode and the tile→mode map.
 *               △ Pre-tag chip aggregate query needs a tagRepository
 *                  that doesn't exist yet — chips render from
 *                  [stubPretagChips] until the schema lands.
 *               △ Recents tab content is also blocked on
 *                  captureRepository; the toggle works as state but
 *                  the Recents view is a no-op for now.
 */

package app.releaf.mobile.features.capture

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.EventNote
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.LocalHospital
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Receipt
import androidx.compose.material.icons.outlined.RestaurantMenu
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.releaf.mobile.ui.components.CaptureMode
import app.releaf.mobile.ui.theme.AppAccent
import app.releaf.mobile.ui.theme.AppColors
import app.releaf.mobile.ui.theme.AppRadius
import app.releaf.mobile.ui.theme.AppSpacing
import app.releaf.mobile.ui.theme.AppTypography
import app.releaf.mobile.ui.theme.LocalFontWeight
import java.time.LocalDate
import java.time.format.DateTimeFormatter

// ──────────────────────────────────────────────────────────────────
// Public model
// ──────────────────────────────────────────────────────────────────

/**
 * The six capture tiles the page surfaces. Distinct from the existing
 * [app.releaf.mobile.ui.components.CaptureMode] enum because that's
 * the page-detail tab bar's vocabulary (which still has Overview /
 * Scans). The Capture page surfaces a curated subset + Notes (text).
 *
 * Mapping to [CaptureMode] (resolved by [toCaptureMode] below):
 *   Notes   -> CaptureMode.Notes
 *   Photo   -> CaptureMode.Photos
 *   Voice   -> CaptureMode.Voice
 *   Todo    -> CaptureMode.Todo
 *   Contact -> CaptureMode.Contacts
 *   Pin     -> CaptureMode.Location
 */
enum class CaptureTile(
    val title: String,
    val hint: String,
) {
    Notes  ("Notes",   "keyboard up"),
    Photo  ("Photo",   "camera"),
    Voice  ("Voice",   "tap or hold FAB"),
    Todo   ("Todo",    "checklist"),
    Contact("Contact", "phone · email"),
    Pin    ("Pin",     "tag this place"),
}

/**
 * Translate a [CaptureTile] to the existing page-detail
 * [CaptureMode] used by `Routes.pageLocal(...)`. All six tiles map
 * to a real [CaptureMode] entry as of Phase 4; the nullable return
 * type stays so future tiles (e.g. a clipboard-link tile) can opt
 * out of mode-preselection cleanly.
 */
fun CaptureTile.toCaptureMode(): CaptureMode? = when (this) {
    CaptureTile.Notes   -> CaptureMode.Notes
    CaptureTile.Photo   -> CaptureMode.Photos
    CaptureTile.Voice   -> CaptureMode.Voice
    CaptureTile.Todo    -> CaptureMode.Todo
    CaptureTile.Contact -> CaptureMode.Contacts
    CaptureTile.Pin     -> CaptureMode.Location
}

/** A pre-tag category chip in the horizontal-scroll row. */
data class PretagChip(
    val id: String,
    val name: String,
    val countLabel: String,    // "3 this week" / "12 total" / "8"
    val icon: ImageVector,
    val isActive: Boolean = false,
)

enum class CaptureScope { Day, Recents }

// ──────────────────────────────────────────────────────────────────
// Screen
// ──────────────────────────────────────────────────────────────────

/**
 * Capture page. Phase 1 ships with stub data and no real handlers — the
 * @Preview at the bottom validates the layout. Phase 2 wires this to
 * the NavHost; Phase 4 replaces [stubPretagChips] with a live query.
 */
@Composable
fun CaptureScreen(
    onSelectTile: (CaptureTile) -> Unit = {},
    onSelectPretag: (PretagChip) -> Unit = {},
    onAddPretag: () -> Unit = {},
    onScanNow: () -> Unit = {},
    onOpenSearch: () -> Unit = {},
    onOpenCalendar: () -> Unit = {},
    pretagChips: List<PretagChip> = stubPretagChips,
    today: LocalDate = LocalDate.now(),
    modifier: Modifier = Modifier,
) {
    // Use `remember` (not `rememberSaveable`) for the scope to mirror
    // the existing `showCapture` state in MainActivity. Phase 4 may
    // promote this to a saveable Saver-backed state once the Recents
    // tab has real content worth restoring across process death.
    var scope by remember { mutableStateOf(CaptureScope.Day) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(
                start = AppSpacing.s4,
                end = AppSpacing.s4,
                bottom = AppSpacing.s4,
            ),
    ) {
        Spacer(Modifier.size(AppSpacing.s2))

        // 1. Page header — eyebrow + title (left), search + calendar (right).
        PageHeader(
            onOpenSearch = onOpenSearch,
            onOpenCalendar = onOpenCalendar,
        )

        Spacer(Modifier.size(AppSpacing.s3))

        // 2. Day / Recents scope toggle (left) + date meta (right).
        ScopeRow(
            scope = scope,
            onScopeChange = { scope = it },
            today = today,
        )

        Spacer(Modifier.size(AppSpacing.s4))

        // 3. The Scan hero.
        ScanHeroCard(
            onScanNow = onScanNow,
        )

        Spacer(Modifier.size(AppSpacing.s4))

        // 4. Pre-tag header + horizontal chip row.
        PretagSection(
            chips = pretagChips,
            onSelect = onSelectPretag,
            onAddNew = onAddPretag,
        )

        Spacer(Modifier.size(AppSpacing.s4))

        // 5. Divider.
        OrCaptureDifferentlyDivider()

        Spacer(Modifier.size(AppSpacing.s3))

        // 6. The 6-tile grid.
        CaptureTileGrid(onSelect = onSelectTile)

        Spacer(Modifier.size(AppSpacing.s4))

        // 7. Footer hint.
        FooterHint()

        Spacer(Modifier.size(AppSpacing.s10))
    }
}

// ──────────────────────────────────────────────────────────────────
// Page header
// ──────────────────────────────────────────────────────────────────

@Composable
private fun PageHeader(
    onOpenSearch: () -> Unit,
    onOpenCalendar: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.s3),
    ) {
        Column(
            modifier = Modifier
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = "RELEAF",
                style = AppTypography.Eyebrow,
                color = AppAccent.primary,
            )
            Text(
                text = "Capture",
                style = TextStyle(
                    fontFamily = FontFamily.Serif,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.W600,
                    letterSpacing = (-0.5).sp,
                ),
                color = AppColors.TextPrimary,
            )
        }
        HeadIconButton(icon = Icons.Outlined.Search, label = "Search", onClick = onOpenSearch)
        HeadIconButton(icon = Icons.Outlined.CalendarMonth, label = "Calendar", onClick = onOpenCalendar)
    }
}

@Composable
private fun HeadIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(AppColors.CardSolid)
            .border(1.dp, AppColors.BorderDefault, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = AppColors.TextPrimary,
            modifier = Modifier.size(16.dp),
        )
    }
}

// ──────────────────────────────────────────────────────────────────
// Scope toggle (Day | Recents)
// ──────────────────────────────────────────────────────────────────

private val DateFormatter = DateTimeFormatter.ofPattern("EEEE · MMM d")

@Composable
private fun ScopeRow(
    scope: CaptureScope,
    onScopeChange: (CaptureScope) -> Unit,
    today: LocalDate,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(AppRadius.pill))
                .background(AppColors.Subtle)
                .padding(3.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                ScopeSegment(label = "Day",     selected = scope == CaptureScope.Day,     onClick = { onScopeChange(CaptureScope.Day) })
                ScopeSegment(label = "Recents", selected = scope == CaptureScope.Recents, onClick = { onScopeChange(CaptureScope.Recents) })
            }
        }
        Text(
            text = today.format(DateFormatter),
            style = AppTypography.Meta.copy(fontWeight = LocalFontWeight.current),
            color = AppColors.TextTertiary,
        )
    }
}

@Composable
private fun ScopeSegment(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(AppRadius.pill))
            .background(if (selected) ScopeActiveBg else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            style = AppTypography.Tag.copy(
                fontWeight = LocalFontWeight.current,
                fontSize = 12.sp,
            ),
            color = if (selected) AppColors.Canvas else AppColors.TextSecondary,
        )
    }
}

// Forest green for the active segment; sympathy with the leaf brand
// without competing with the coral FAB. Falls back to TextPrimary on
// dark mode (Phase 4 will route this through a token).
private val ScopeActiveBg = Color(0xFF1E5943)

// ──────────────────────────────────────────────────────────────────
// Scan hero
// ──────────────────────────────────────────────────────────────────

private val MustardLight = Color(0xFFC68628)
private val Mustard      = Color(0xFFB27A2A)
private val MustardDeep  = Color(0xFF8E5F1F)

@Composable
private fun ScanHeroCard(
    onScanNow: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(8.dp, RoundedCornerShape(AppRadius.lg + 4.dp))
            .clip(RoundedCornerShape(AppRadius.lg + 4.dp))
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(MustardLight, Mustard, MustardDeep),
                ),
            )
            // Whole card is the affordance — taps anywhere inside the
            // hero kick off the scanner directly. The inner "Scan now"
            // pill is a visual anchor that shares the same handler so
            // both code paths behave identically.
            .clickable(onClick = onScanNow)
            .padding(start = AppSpacing.s5, top = AppSpacing.s5, end = AppSpacing.s5, bottom = AppSpacing.s4),
    ) {
        // Right-side decorations sit underneath the text content's
        // z-order — Box stacks children in declaration order. Drawn
        // first so the buttons sit on top of any overlapping paper.
        ScanHeroDecorations(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(width = 110.dp, height = 130.dp),
        )

        Column(
            modifier = Modifier.fillMaxWidth(0.62f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "TODAY'S FIRST CAPTURE",
                style = AppTypography.Eyebrow.copy(fontSize = 10.5.sp),
                color = AppColors.Canvas.copy(alpha = 0.78f),
            )
            Text(
                text = "Scan a page",
                style = TextStyle(
                    fontFamily = FontFamily.Serif,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.W700,
                    letterSpacing = (-0.4).sp,
                ),
                color = AppColors.TextPrimary,
            )
            Text(
                text = "Auto-crops, OCRs, and files under today.",
                style = AppTypography.Body.copy(fontSize = 13.5.sp),
                color = AppColors.Canvas,
            )
            Spacer(Modifier.size(AppSpacing.s2))
            // Single primary action — Import was dropped to keep the
            // hero focused on the live scanner. Re-add when an
            // import-from-Photos flow is built.
            ScanButtonPrimary(onClick = onScanNow)
        }
    }
}

/**
 * The paper + crop-corner brackets that sit on the top-right of the
 * Scan hero. Brackets read as "framing the document"; the rotated
 * paper card slots inside them. Cream + 85% alpha against the mustard
 * gradient gives a warm, slightly translucent look.
 */
@Composable
private fun ScanHeroDecorations(modifier: Modifier = Modifier) {
    Box(modifier = modifier) {
        // The four L-shapes — each corner gets two perpendicular line
        // segments. drawLine in Canvas keeps stroke widths consistent
        // across screen densities better than 4 Box composables would.
        Canvas(modifier = Modifier.fillMaxSize()) {
            val brush      = Color.White.copy(alpha = 0.82f)
            val stroke     = 2.5.dp.toPx()
            val cornerLen  = 18.dp.toPx()
            val w          = size.width
            val h          = size.height

            // top-left
            drawLine(brush, Offset(0f, 0f),       Offset(cornerLen, 0f), stroke, StrokeCap.Round)
            drawLine(brush, Offset(0f, 0f),       Offset(0f, cornerLen), stroke, StrokeCap.Round)
            // top-right
            drawLine(brush, Offset(w, 0f),        Offset(w - cornerLen, 0f), stroke, StrokeCap.Round)
            drawLine(brush, Offset(w, 0f),        Offset(w, cornerLen),      stroke, StrokeCap.Round)
            // bottom-left
            drawLine(brush, Offset(0f, h),        Offset(cornerLen, h),      stroke, StrokeCap.Round)
            drawLine(brush, Offset(0f, h),        Offset(0f, h - cornerLen), stroke, StrokeCap.Round)
            // bottom-right
            drawLine(brush, Offset(w, h),         Offset(w - cornerLen, h),  stroke, StrokeCap.Round)
            drawLine(brush, Offset(w, h),         Offset(w, h - cornerLen),  stroke, StrokeCap.Round)
        }

        // The rotated paper card. Slightly narrower than the bracket
        // frame so a small ring of mustard shows around it — that's
        // what makes the brackets read as "framing the page" rather
        // than "outlining the page."
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(width = 78.dp, height = 96.dp)
                .graphicsLayer { rotationZ = 8f }
                .shadow(6.dp, RoundedCornerShape(6.dp))
                .clip(RoundedCornerShape(6.dp))
                .background(AppColors.Canvas),
        ) {
            // Lines representing text on the page. The colour has to
            // be resolved out here (composable scope) because
            // `AppColors.TextTertiary` is a @Composable getter — the
            // DrawScope inside `Canvas { … }` isn't a composable
            // context and reading it there fails to compile.
            val lineColor = AppColors.TextTertiary.copy(alpha = 0.4f)
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 12.dp, end = 14.dp, top = 16.dp),
            ) {
                val stroke = 2.dp.toPx()
                val gap    = 8.dp.toPx()
                // 6 lines, alternating full and partial widths so it
                // reads as paragraph text rather than a barcode.
                val widths = floatArrayOf(1f, 0.78f, 1f, 0.86f, 1f, 0.66f)
                widths.forEachIndexed { i, frac ->
                    val y = i * gap + 4.dp.toPx()
                    drawLine(
                        color = lineColor,
                        start = Offset(0f, y),
                        end = Offset(size.width * frac, y),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round,
                    )
                }
            }
        }
    }
}

@Composable
private fun ScanButtonPrimary(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(AppRadius.pill))
            .background(AppColors.Canvas)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.CameraAlt,
            contentDescription = null,
            tint = MustardDeep,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = "Scan now",
            style = AppTypography.Button.copy(fontWeight = LocalFontWeight.current, fontSize = 13.sp),
            color = MustardDeep,
        )
    }
}

// ──────────────────────────────────────────────────────────────────
// Pre-tag chips
// ──────────────────────────────────────────────────────────────────

@Composable
private fun PretagSection(
    chips: List<PretagChip>,
    onSelect: (PretagChip) -> Unit,
    onAddNew: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.s2)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "PRE-TAG & SCAN",
                style = AppTypography.Eyebrow.copy(fontSize = 10.5.sp, letterSpacing = 1.0.sp),
                color = AppColors.TextSecondary,
            )
            Text(
                text = "scroll for more →",
                style = AppTypography.Tag.copy(fontWeight = LocalFontWeight.current),
                color = AppColors.TextTertiary,
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.s2),
        ) {
            chips.forEach { chip ->
                PretagChipView(chip = chip, onClick = { onSelect(chip) })
            }
            AddPretagChip(onClick = onAddNew)
        }
    }
}

private val LetterActiveBg = Color(0xFFF0DDA8)
private val LetterActiveDeep = Color(0xFF8E5F1F)
private val ForestLeaf = Color(0xFF1E5943)

@Composable
private fun PretagChipView(chip: PretagChip, onClick: () -> Unit) {
    val bg = if (chip.isActive) LetterActiveBg else AppColors.CardSolid
    val border = if (chip.isActive) LetterActiveDeep.copy(alpha = 0.25f) else AppColors.BorderDefault
    val iconTint = if (chip.isActive) LetterActiveDeep else ForestLeaf
    val countTint = if (chip.isActive) LetterActiveDeep else AppColors.TextTertiary

    Row(
        modifier = Modifier
            .shadow(2.dp, RoundedCornerShape(AppRadius.md))
            .clip(RoundedCornerShape(AppRadius.md))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(AppRadius.md))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.s2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = chip.icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(20.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                text = chip.name,
                style = AppTypography.Button.copy(fontWeight = LocalFontWeight.current, fontSize = 12.5.sp),
                color = AppColors.TextPrimary,
            )
            Text(
                text = chip.countLabel,
                style = AppTypography.Tag.copy(
                    fontWeight = if (chip.isActive) LocalFontWeight.current else LocalFontWeight.current,
                    fontSize = 10.5.sp,
                ),
                color = countTint,
            )
        }
    }
}

@Composable
private fun AddPretagChip(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(AppRadius.md))
            .border(1.dp, AppColors.BorderStrong, RoundedCornerShape(AppRadius.md))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.s2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Add,
            contentDescription = null,
            tint = AppColors.TextSecondary,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = "New tag",
            style = AppTypography.Button.copy(fontWeight = LocalFontWeight.current, fontSize = 12.5.sp),
            color = AppColors.TextSecondary,
        )
    }
}

// ──────────────────────────────────────────────────────────────────
// "Or capture differently" divider
// ──────────────────────────────────────────────────────────────────

@Composable
private fun OrCaptureDifferentlyDivider() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.s3),
    ) {
        Box(
            Modifier
                .weight(1f)
                .height(1.dp)
                .background(AppColors.BorderDefault),
        )
        Text(
            text = "OR CAPTURE DIFFERENTLY",
            style = AppTypography.Eyebrow.copy(fontSize = 10.sp),
            color = AppColors.TextTertiary,
        )
        Box(
            Modifier
                .weight(1f)
                .height(1.dp)
                .background(AppColors.BorderDefault),
        )
    }
}

// ──────────────────────────────────────────────────────────────────
// Capture tile grid (Notes / Photo / Voice / Todo / Contact / Pin)
// ──────────────────────────────────────────────────────────────────

@Composable
private fun CaptureTileGrid(onSelect: (CaptureTile) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(0.dp),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.s2),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.s2),
        // The grid lives inside a scrollable column; we cap it at the
        // intrinsic 2-row height so it doesn't fight the parent
        // scroller. 220dp = 2 × 96dp tile + 8dp gap + small padding.
        modifier = Modifier
            .fillMaxWidth()
            .height(232.dp),
        userScrollEnabled = false,
    ) {
        items(CaptureTile.values().toList()) { tile ->
            CaptureTileView(tile = tile, onClick = { onSelect(tile) })
        }
    }
}

@Composable
private fun CaptureTileView(tile: CaptureTile, onClick: () -> Unit) {
    val palette = tile.palette()
    Column(
        modifier = Modifier
            .aspectRatio(1.05f)
            .shadow(2.dp, RoundedCornerShape(AppRadius.lg))
            .clip(RoundedCornerShape(AppRadius.lg))
            .background(AppColors.CardSolid)
            .border(1.dp, AppColors.BorderDefault, RoundedCornerShape(AppRadius.lg))
            .clickable(onClick = onClick)
            .padding(AppSpacing.s2),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(palette.bg),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = tile.icon(),
                contentDescription = null,
                tint = palette.fg,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.size(6.dp))
        Text(
            text = tile.title.uppercase(),
            style = AppTypography.Button.copy(
                fontWeight = LocalFontWeight.current,
                fontSize = 12.sp,
                letterSpacing = 0.4.sp,
            ),
            color = AppColors.TextPrimary,
        )
        Text(
            text = tile.hint,
            style = AppTypography.Tag.copy(
                fontWeight = LocalFontWeight.current,
                fontSize = 10.sp,
            ),
            color = AppColors.TextTertiary,
        )
    }
}

private data class TilePalette(val bg: Color, val fg: Color)

@Composable
private fun CaptureTile.palette(): TilePalette = when (this) {
    CaptureTile.Notes   -> TilePalette(AppColors.Subtle,                   AppColors.TextSecondary)
    CaptureTile.Photo   -> TilePalette(AppAccent.soft,                     AppAccent.primary)
    CaptureTile.Voice   -> TilePalette(InfoSoft,                           Info)
    CaptureTile.Todo    -> TilePalette(ForestSoft,                         ForestLeaf)
    CaptureTile.Contact -> TilePalette(WarningSoft,                        Warning)
    CaptureTile.Pin     -> TilePalette(DangerSoft,                         Danger)
}

private fun CaptureTile.icon(): ImageVector = when (this) {
    CaptureTile.Notes   -> Icons.AutoMirrored.Outlined.EventNote
    CaptureTile.Photo   -> Icons.Outlined.CameraAlt
    CaptureTile.Voice   -> Icons.Outlined.Mic
    CaptureTile.Todo    -> Icons.Outlined.CheckBox
    CaptureTile.Contact -> Icons.Outlined.Person
    CaptureTile.Pin     -> Icons.Outlined.LocationOn
}

// Tile palette colors live here for Phase-1 self-contained scaffolding.
// Phase 4 will route these through `design-tokens.json` semantic.* roles.
private val Info        = Color(0xFF2E6FB5)
private val InfoSoft    = Color(0xFFE1ECF8)
private val ForestSoft  = Color(0xFFE8F0E2)
private val Warning     = Color(0xFFA87418)
private val WarningSoft = Color(0xFFFBEECD)
private val Danger      = Color(0xFFC8432E)
private val DangerSoft  = Color(0xFFFDEEE9)

// ──────────────────────────────────────────────────────────────────
// Footer hint
// ──────────────────────────────────────────────────────────────────

@Composable
private fun FooterHint() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Hold the green Leaf button on any tab to record voice without leaving.",
            style = AppTypography.Tag.copy(
                fontWeight = LocalFontWeight.current,
                fontSize = 11.sp,
            ),
            color = AppColors.TextTertiary,
        )
    }
}

// ──────────────────────────────────────────────────────────────────
// Stub data — Phase 4 replaces with a live ViewModel query
// ──────────────────────────────────────────────────────────────────

private val stubPretagChips: List<PretagChip> = listOf(
    PretagChip(id = "t-letter",  name = "Letter",  countLabel = "3 this week", icon = Icons.Outlined.Description,    isActive = true),
    PretagChip(id = "t-receipt", name = "Receipt", countLabel = "12 total",    icon = Icons.Outlined.Receipt),
    PretagChip(id = "t-medical", name = "Medical", countLabel = "5 total",     icon = Icons.Outlined.LocalHospital),
    PretagChip(id = "t-recipe",  name = "Recipe",  countLabel = "8",           icon = Icons.Outlined.RestaurantMenu),
    PretagChip(id = "t-school",  name = "School",  countLabel = "4",           icon = Icons.Outlined.School),
)

// ──────────────────────────────────────────────────────────────────
// Preview
// ──────────────────────────────────────────────────────────────────

@Preview(showBackground = true, backgroundColor = 0xFFF5EEE3, widthDp = 390, heightDp = 844)
@Composable
private fun CaptureScreenPreview() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColors.Canvas),
    ) {
        CaptureScreen()
    }
}
