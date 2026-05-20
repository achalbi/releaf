/*
 * WorkspaceTaxonomy.kt
 *
 * Phase 1 primitives for the Workspace tab refresh
 * (`design/WORKSPACE_TAB_HANDOFF.md`):
 *
 *   - `TagBucket`        — value type describing a tag-vocabulary
 *                          bucket (id, name, question, hue, prefix,
 *                          controlled / exclusive / autoApplied
 *                          flags). Seeded in Phase 2 via
 *                          `workspace_seed.json`; this file ships
 *                          the in-code defaults so the primitives
 *                          render in previews before the data
 *                          layer lands.
 *   - `FolderTier`       — presentation-only grouping (1 Workflow,
 *                          2 Life Domains, 3 Creative & Output,
 *                          + Custom for user-created folders).
 *   - `TierHeader`       — section header for a tier block.
 *   - `FolderRow`        — single row in a tier block.
 *   - `TagBucketBlock`   — bar + name + question + count pill +
 *                          pill row, one per bucket.
 *   - `TagPill`          — pill rendering inside a bucket block.
 *
 * Phase 1 ships the components and the static bucket seed only —
 * no screen is wired up yet. Phase 3 plumbs `FolderRow` into the
 * Workspace tab; Phase 4 plumbs `TagBucketBlock`.
 *
 * Mirror of iOS `WorkspaceTaxonomy.swift`.
 */

@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.quickink.mobile.features.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.quickink.mobile.ui.theme.QuickInkColors
import app.quickink.mobile.ui.theme.QuickInkFonts
import app.quickink.mobile.ui.theme.QuickInkSpacing

// ─── Bucket model ───────────────────────────────────────────────

/**
 * A tag-vocabulary bucket. Phase 1 keeps this in code; Phase 2
 * reads the same shape from `workspace_seed.json` and persists the
 * bucket id on each tag row.
 */
data class TagBucket(
    val id: String,
    val name: String,
    val question: String,
    val hue: Color,
    /**
     * Zero, one, or many prefix strings (e.g. `listOf("org/", "place/")`).
     * `null` → bucket is unprefixed (Status, Energy, …). User-added
     * tags in a prefixed bucket auto-complete the prefix.
     */
    val prefixes: List<String>? = null,
    val controlled: Boolean = false,
    val exclusive: Boolean = false,
    val autoApplied: Boolean = false,
)

/** The seven canonical buckets in spec order (§4.2). */
val workspaceTagBuckets: List<TagBucket> = listOf(
    TagBucket("status",   "Status",           "what state is it in?",            QuickInkColors.BucketStatus,   controlled  = true),
    TagBucket("people",   "People",           "who is this about?",              QuickInkColors.BucketPeople,   prefixes    = listOf("p/")),
    TagBucket("orgplace", "Org & Place",      "what organization or location?", QuickInkColors.BucketOrgPlace, prefixes    = listOf("org/", "place/")),
    TagBucket("energy",   "Energy",           "what state of mind does it need?", QuickInkColors.BucketEnergy, controlled  = true),
    TagBucket("time",     "Time-sensitivity", "which horizon?",                  QuickInkColors.BucketTime,     controlled  = true, exclusive = true),
    TagBucket("kind",     "Kind",             "what kind of content?",           QuickInkColors.BucketKind),
    TagBucket("source",   "Source",           "where did it come from?",         QuickInkColors.BucketSource,   controlled  = true, autoApplied = true),
)

// ─── Folder tier ────────────────────────────────────────────────

/**
 * Presentation-only tier grouping for the folders section. The
 * underlying `folders` table stores `tier` as an int (1, 2, 3) on
 * each row; Phase 2 adds that column. [FolderTier.Custom] (= 0)
 * is the visual bucket for user-created folders that coexist
 * alongside the 12 seeded ones.
 */
enum class FolderTier(
    val raw: Int,
    val label: String,
    val sub: String?,
    val numeral: String,
) {
    Workflow(1, "Workflow",          null,                   "1"),
    Life    (2, "Life domains",      "where it belongs",     "2"),
    Creative(3, "Creative & output", "output, study, sparks", "3"),
    Custom  (0, "Custom",            "your own folders",     "+");

    fun stripeColor(): Color = when (this) {
        Workflow -> QuickInkColors.Tier1
        Life     -> QuickInkColors.Ink
        Creative -> QuickInkColors.Tier3
        Custom   -> QuickInkColors.Muted
    }

    companion object {
        /** Map a stored `tier` int back onto the enum. Unknown values fall back to Custom. */
        fun fromRaw(raw: Int?): FolderTier =
            values().firstOrNull { it.raw == raw } ?: Custom
    }
}

// ─── TierHeader ─────────────────────────────────────────────────

/**
 * Section header above a tier's stack of [FolderRow]s. Numeral +
 * label + optional italic sub + 1 dp divider beneath.
 */
@Composable
fun TierHeader(
    tier: FolderTier,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(bottom = 4.dp),
        ) {
            Text(
                text     = tier.numeral,
                color    = QuickInkColors.Accent,
                fontSize = 14.sp,
                fontFamily = QuickInkFonts.serif,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.84.sp,
            )
            Text(
                text     = tier.label.uppercase(),
                color    = QuickInkColors.Ink,
                fontSize = 11.sp,
                fontFamily = QuickInkFonts.ui,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.4.sp,
            )
            tier.sub?.let { sub ->
                Text(
                    text       = sub,
                    color      = QuickInkColors.Muted,
                    fontSize   = 11.sp,
                    fontFamily = QuickInkFonts.ui,
                    fontStyle  = FontStyle.Italic,
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .height(1.dp)
                .background(QuickInkColors.Border)
        )
    }
}

// ─── FolderRow ──────────────────────────────────────────────────

/**
 * One folder in a tier block. `description` is optional — when
 * null the row collapses to one text line. `isSystemManaged`
 * renders the Inbox lock glyph next to the name.
 */
@Composable
fun FolderRow(
    name: String,
    count: Int,
    tier: FolderTier,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    isSystemManaged: Boolean = false,
    showBottomBorder: Boolean = true,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = "$name, $count ${if (count == 1) "item" else "items"}"
            }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.padding(vertical = 13.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(width = 3.dp, height = 26.dp)
                    .clip(RoundedCornerShape(1.5.dp))
                    .background(tier.stripeColor())
            )

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text       = name,
                        color      = QuickInkColors.Ink,
                        fontSize   = 15.sp,
                        fontFamily = QuickInkFonts.ui,
                        fontWeight = FontWeight.Medium,
                        maxLines   = 1,
                        overflow   = TextOverflow.Ellipsis,
                    )
                    if (isSystemManaged) {
                        Icon(
                            imageVector       = Icons.Filled.Lock,
                            contentDescription = "system-managed",
                            tint              = QuickInkColors.Muted,
                            modifier          = Modifier
                                .padding(start = 6.dp)
                                .size(10.dp),
                        )
                    }
                }
                description?.let { desc ->
                    Text(
                        text       = desc,
                        color      = QuickInkColors.Muted,
                        fontSize   = 12.sp,
                        fontFamily = QuickInkFonts.ui,
                        fontWeight = FontWeight.Normal,
                        maxLines   = 1,
                        overflow   = TextOverflow.Ellipsis,
                        modifier   = Modifier.padding(top = 2.dp),
                    )
                }
            }

            CountPill(count = count)

            Icon(
                imageVector       = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint              = QuickInkColors.Muted,
                modifier          = Modifier.size(18.dp),
            )
        }
        if (showBottomBorder) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(QuickInkColors.Border)
            )
        }
    }
}

// ─── Count pill ─────────────────────────────────────────────────

/**
 * The 999-radius soft-bg badge used in folder rows and bucket
 * blocks. Shared between [FolderRow] and [TagBucketBlock].
 */
@Composable
fun CountPill(count: Int, modifier: Modifier = Modifier) {
    val text = if (count > 999) "999+" else count.toString()
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(QuickInkColors.AccentSoft)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text       = text,
            color      = QuickInkColors.InkSoft,
            fontSize   = 12.sp,
            fontFamily = QuickInkFonts.ui,
            fontWeight = FontWeight.Medium,
        )
    }
}

// ─── TagPill ────────────────────────────────────────────────────

/**
 * 26-dp-tall capsule used inside a [TagBucketBlock] pill row.
 * Background is the bucket hue at 12 % opacity over canvas;
 * border + text are the bucket hue at 100 %. The `+ add` variant
 * renders a dashed-border placeholder for prefixed / uncontrolled
 * buckets.
 */
@Composable
fun TagPill(
    label: String,
    hue: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(26.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(hue.copy(alpha = 0.12f))
            .border(width = 1.dp, color = hue, shape = RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text       = label,
            color      = hue,
            fontSize   = 12.sp,
            fontFamily = QuickInkFonts.ui,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
fun TagPillAdd(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String = "+ add",
) {
    // Dashed-border affordance for non-controlled buckets. Compose
    // doesn't have a first-class dashed `border` modifier on `Box`,
    // so the look is approximated with a soft-bg + muted text +
    // 1 dp border — the dash is dropped in the Phase 5 polish pass
    // if visual diff demands it.
    Box(
        modifier = modifier
            .height(26.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(QuickInkColors.Canvas)
            .border(
                width = 1.dp,
                color = QuickInkColors.Border,
                shape = RoundedCornerShape(999.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text       = label,
            color      = QuickInkColors.Muted,
            fontSize   = 12.sp,
            fontFamily = QuickInkFonts.ui,
            fontWeight = FontWeight.Medium,
        )
    }
}

// ─── TagBucketBlock ─────────────────────────────────────────────

data class TagPillSpec(val id: String, val label: String)

/**
 * One block of the tag vocabulary section — bar + name + question
 * + count pill + pill row. Tap on a pill routes via [onTapTag];
 * tap on the `+ add` pill (rendered only for non-`controlled`
 * buckets) routes via [onAddTag].
 */
@Composable
fun TagBucketBlock(
    bucket: TagBucket,
    pills: List<TagPillSpec>,
    onTapTag: (TagPillSpec) -> Unit,
    onAddTag: () -> Unit,
    modifier: Modifier = Modifier,
    showBottomBorder: Boolean = true,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(width = 3.dp, height = 26.dp)
                    .clip(RoundedCornerShape(1.5.dp))
                    .background(bucket.hue)
            )

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text       = bucket.name.uppercase(),
                        color      = bucket.hue,
                        fontSize   = 12.5.sp,
                        fontFamily = QuickInkFonts.ui,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.5.sp,
                    )
                    bucket.prefixes?.takeIf { it.isNotEmpty() }?.let { prefixes ->
                        Text(
                            text       = "(#" + prefixes.joinToString(", #") + ")",
                            color      = QuickInkColors.Muted,
                            fontSize   = 12.5.sp,
                            fontFamily = QuickInkFonts.ui,
                            fontWeight = FontWeight.Normal,
                        )
                    }
                    if (bucket.autoApplied) {
                        Text(
                            text       = "· auto-applied",
                            color      = QuickInkColors.Muted,
                            fontSize   = 12.5.sp,
                            fontFamily = QuickInkFonts.ui,
                            fontWeight = FontWeight.Normal,
                        )
                    }
                }
                Text(
                    text       = bucket.question,
                    color      = QuickInkColors.Muted,
                    fontSize   = 11.sp,
                    fontFamily = QuickInkFonts.ui,
                    fontWeight = FontWeight.Normal,
                    fontStyle  = FontStyle.Italic,
                    modifier   = Modifier.padding(top = 1.dp),
                )
            }

            CountPill(count = pills.size)
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement   = Arrangement.spacedBy(6.dp),
            modifier              = Modifier
                .padding(start = 15.dp, top = 8.dp, bottom = 4.dp)
                .fillMaxWidth(),
        ) {
            pills.forEach { pill ->
                TagPill(
                    label   = pill.label,
                    hue     = bucket.hue,
                    onClick = { onTapTag(pill) },
                )
            }
            if (!bucket.controlled) {
                TagPillAdd(onClick = onAddTag)
            }
        }

        if (showBottomBorder) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(QuickInkColors.Border)
            )
        }
    }
}
