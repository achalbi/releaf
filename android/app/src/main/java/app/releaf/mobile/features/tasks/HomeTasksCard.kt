/*
 * HomeTasksCard.kt
 *
 * Home-screen entry for the Tasks surface. The card reads the full
 * task list for the signed-in user and derives three numbers the
 * hero row cares about:
 *
 *   • open     — not-completed tasks
 *   • done     — completed tasks
 *   • overdue  — open tasks whose due date is strictly before today
 *
 * Visually it's a combined garden + dashboard: a canvas-drawn
 * potted plant on the left whose stem / leaves / bloom scale with
 * completion ratio (done / (open + done)), a numeric stat row
 * underneath the title, and a thin progress bar that mirrors the
 * plant's growth for users who prefer a precise percentage to a
 * pictogram.
 */

package app.releaf.mobile.features.tasks

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.releaf.mobile.ReleafApp
import app.releaf.mobile.auth.AuthState
import app.releaf.mobile.data.task.TaskEntity
import app.releaf.mobile.ui.theme.AppAccent
import app.releaf.mobile.ui.theme.AppColors
import app.releaf.mobile.ui.theme.AppRadius
import app.releaf.mobile.ui.theme.AppSpacing
import app.releaf.mobile.ui.theme.AppTypography
import kotlinx.coroutines.flow.flowOf
import java.time.LocalDate

@Composable
fun HomeTasksCard(onOpenTasks: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as ReleafApp

    // Full task list — we need the whole thing to derive overdue +
    // done as well as the open count. The card only renders a
    // handful of numbers so the cost of the stream is negligible
    // compared to observing three separate `count` queries.
    val tasksFlow = remember(app) {
        val userId = (app.authStore.state.value as? AuthState.SignedIn)?.session?.userId
        if (userId == null) flowOf(emptyList())
        else app.taskRepository.observeActive(userId)
    }
    val tasks: List<TaskEntity> by tasksFlow.collectAsState(initial = emptyList())

    val today = remember { LocalDate.now().toString() }
    val open    = tasks.count { !it.completed }
    val done    = tasks.count { it.completed }
    val overdue = tasks.count { !it.completed && (it.dueDate?.let { d -> d < today } == true) }

    // done / (open + done) keeps the denominator honest — an empty
    // list maps to 0f so the plant sits at its seedling stage
    // rather than showing 100% of nothing.
    val rawProgress = if (open + done > 0) done.toFloat() / (open + done) else 0f
    val progress by animateFloatAsState(
        targetValue   = rawProgress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 600),
        label         = "taskGrowth",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.md))
            .background(AppColors.CardSolid)
            .border(1.dp, AppColors.BorderDefault, RoundedCornerShape(AppRadius.md))
            .clickable { onOpenTasks() }
            .padding(AppSpacing.s4),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.s3),
    ) {
        // Soft-green tile frames the plant and gives the card a
        // visual anchor that matches the existing Reminders card
        // shape (same 56dp hero tile).
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(AppRadius.md))
                .background(Color(0xFFEAF3DE)),
            contentAlignment = Alignment.Center,
        ) {
            PlantIcon(
                progress = progress,
                modifier = Modifier.fillMaxSize().padding(4.dp),
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text  = "TASKS",
                    style = AppTypography.Eyebrow,
                    color = AppAccent.primary,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text  = "${(progress * 100).toInt()}%",
                    style = AppTypography.Tag.copy(fontWeight = FontWeight.Normal),
                    color = AppColors.TextSecondary,
                )
            }
            Text(
                text  = headline(open = open, done = done),
                style = AppTypography.SectionTitle.copy(fontWeight = FontWeight.Medium),
                color = AppColors.TextPrimary,
            )
            Spacer(Modifier.height(4.dp))
            StatRow(open = open, overdue = overdue, done = done)
            Spacer(Modifier.height(6.dp))
            ProgressBar(progress = progress)
        }
    }
}

// ================================================================= Stat row

@Composable
private fun StatRow(open: Int, overdue: Int, done: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatInline(value = open,    label = "open",    color = AppAccent.primary)
        if (overdue > 0) {
            Dot()
            StatInline(value = overdue, label = "overdue", color = AppColors.Danger)
        }
        Dot()
        StatInline(value = done,    label = "done",    color = AppColors.Success)
    }
}

@Composable
private fun StatInline(value: Int, label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text  = value.toString(),
            style = AppTypography.Meta.copy(fontWeight = FontWeight.Medium),
            color = color,
        )
        Spacer(Modifier.width(3.dp))
        Text(
            text  = label,
            style = AppTypography.Tag.copy(fontWeight = FontWeight.Normal),
            color = color,
        )
    }
}

@Composable
private fun Dot() {
    Text(
        text  = "  ·  ",
        style = AppTypography.Meta,
        color = AppColors.TextTertiary,
    )
}

// ================================================================= Progress

@Composable
private fun ProgressBar(progress: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(AppColors.InputBg),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(4.dp)
                .background(AppAccent.primary),
        )
    }
}

// ================================================================= Plant

/**
 * Plant illustration where each growth stage is unlocked by a
 * threshold on [progress]:
 *
 *   0 .. 0.10   seedling (short stem, no leaves)
 *   0.10 .. 0.35   stem + left leaf
 *   0.35 .. 0.60   stem + both leaves
 *   0.60 ..        stem + both leaves + blooming flower
 *
 * Stem height itself also scales linearly with progress so the
 * transitions blend — the plant doesn't jump size between stages.
 */
@Composable
private fun PlantIcon(
    progress: Float,
    modifier: Modifier = Modifier,
) {
    val stemColor    = Color(0xFF3B6D11)
    val leafColor    = Color(0xFF639922)
    val leafLight    = Color(0xFF97C459)
    val bloomColor   = Color(0xFFEF9F27)
    val bloomCenter  = Color(0xFFFEF4E0)
    val potColor     = Color(0xFFC65A3E)
    val potRim       = Color(0xFFE07856)

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        // Pot sits at the bottom ~25% of the canvas. Using a
        // trapezoid shape (wider rim, narrower base) so it reads
        // as a plant pot rather than a square planter.
        val potH     = h * 0.26f
        val potTop   = h - potH
        val rimH     = potH * 0.22f
        val potL     = w * 0.24f
        val potR     = w * 0.76f
        val baseL    = w * 0.30f
        val baseR    = w * 0.70f
        val potPath  = androidx.compose.ui.graphics.Path().apply {
            moveTo(potL, potTop + rimH)
            lineTo(potR, potTop + rimH)
            lineTo(baseR, h)
            lineTo(baseL, h)
            close()
        }
        drawPath(path = potPath, color = potColor)
        // Rim (lighter orange across the top of the pot).
        drawRect(
            color   = potRim,
            topLeft = Offset(potL, potTop),
            size    = Size(potR - potL, rimH),
        )

        // Stem — grows from pot rim upward. Minimum height keeps
        // the seedling visible even at progress=0.
        val stemMin = h * 0.12f
        val stemMax = h * 0.60f
        val stemTop = (potTop) - stemMin - (stemMax - stemMin) * progress
        drawLine(
            color       = stemColor,
            start       = Offset(w * 0.5f, potTop),
            end         = Offset(w * 0.5f, stemTop),
            strokeWidth = 2.dp.toPx(),
            cap         = StrokeCap.Round,
        )

        // Left leaf at ~10%+ of progress — appears early so the
        // plant doesn't look barren unless completion is literally 0.
        if (progress >= 0.10f) {
            val leafOpacity = ((progress - 0.10f) / 0.15f).coerceIn(0f, 1f)
            drawOval(
                color   = leafLight.copy(alpha = leafOpacity),
                topLeft = Offset(w * 0.22f, h * 0.42f),
                size    = Size(w * 0.30f, w * 0.14f),
            )
            drawOval(
                color   = leafColor.copy(alpha = leafOpacity),
                topLeft = Offset(w * 0.24f, h * 0.44f),
                size    = Size(w * 0.24f, w * 0.10f),
            )
        }

        // Right leaf at ~35%+.
        if (progress >= 0.35f) {
            val leafOpacity = ((progress - 0.35f) / 0.15f).coerceIn(0f, 1f)
            drawOval(
                color   = leafLight.copy(alpha = leafOpacity),
                topLeft = Offset(w * 0.50f, h * 0.30f),
                size    = Size(w * 0.30f, w * 0.14f),
            )
            drawOval(
                color   = leafColor.copy(alpha = leafOpacity),
                topLeft = Offset(w * 0.52f, h * 0.32f),
                size    = Size(w * 0.24f, w * 0.10f),
            )
        }

        // Flower blooms above 60%, scaling up from zero to full
        // size as the user finishes the last 40% of their tasks.
        val bloomScale = ((progress - 0.60f) / 0.40f).coerceIn(0f, 1f)
        if (bloomScale > 0.05f) {
            val r = (w * 0.14f) * bloomScale
            drawCircle(
                color  = bloomColor,
                radius = r,
                center = Offset(w * 0.5f, stemTop),
            )
            drawCircle(
                color  = bloomCenter,
                radius = r * 0.45f,
                center = Offset(w * 0.5f, stemTop),
            )
        }
    }
}

// ================================================================= Copy

/**
 * Headline above the stat row. Mirrors the "watered" language of
 * the Garden Growth prototype for non-empty lists, with explicit
 * fallbacks for "empty" and "all done" so the card doesn't read
 * strange on a fresh install.
 */
private fun headline(open: Int, done: Int): String = when {
    open == 0 && done == 0 -> "A fresh patch"
    open == 0              -> "All watered"
    done == 0              -> "Your tasks"
    else                   -> "Growing well"
}
