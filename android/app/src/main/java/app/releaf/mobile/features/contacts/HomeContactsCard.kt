/*
 * HomeContactsCard.kt
 *
 * Contacts card on the signed-in Home dashboard. Same 56dp-tile +
 * stats-row shape as the Tasks and Reminders cards so the three
 * surfaces share a visual rhythm.
 *
 * The hero tile is a small trio of overlapping avatar circles that
 * fill in as the user captures more contacts — matches the Tasks
 * plant / Reminders constellation pattern of "illustration maps to
 * state". Stat row on the right reads phone / email / recent
 * buckets; the trailing line shows mini avatars for the three
 * most-recently-captured entries.
 */

package app.releaf.mobile.features.contacts

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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.releaf.mobile.ReleafApp
import app.releaf.mobile.auth.AuthState
import app.releaf.mobile.data.contact.DirectoryContact
import app.releaf.mobile.ui.theme.AppAccent
import app.releaf.mobile.ui.theme.AppColors
import app.releaf.mobile.ui.theme.AppRadius
import app.releaf.mobile.ui.theme.AppSpacing
import app.releaf.mobile.ui.theme.AppTypography
import app.releaf.mobile.ui.theme.LocalFontWeight
import kotlinx.coroutines.flow.flowOf
import java.time.Instant
import java.time.temporal.ChronoUnit

@Composable
fun HomeContactsCard(onOpenContacts: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as ReleafApp

    val contactsFlow = remember(app) {
        val userId = (app.authStore.state.value as? AuthState.SignedIn)?.session?.userId
        if (userId.isNullOrBlank()) flowOf(emptyList())
        else app.contactDirectoryRepository.observeAll(userId)
    }
    val contacts: List<DirectoryContact> by contactsFlow.collectAsState(initial = emptyList())

    val total     = contacts.size
    val withPhone = contacts.count { it.phones.isNotEmpty() }
    val withEmail = contacts.count { it.email != null }
    val sevenDaysAgo = remember { Instant.now().minus(7, ChronoUnit.DAYS) }
    val recentCount  = contacts.count {
        val t = it.updatedAt ?: return@count false
        t.isAfter(sevenDaysAgo)
    }
    val recent = contacts
        .sortedByDescending { it.updatedAt ?: Instant.EPOCH }
        .take(3)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.md))
            .background(AppColors.CardSolid)
            .border(1.dp, AppColors.BorderDefault, RoundedCornerShape(AppRadius.md))
            .clickable { onOpenContacts() }
            .padding(AppSpacing.s4),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.s3),
    ) {
        // Warm-cream hero tile. Keeps the three home cards' hero
        // sizes identical; the icon inside animates with the count.
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(AppRadius.md))
                .background(Color(0xFFF2E7DB)),
            contentAlignment = Alignment.Center,
        ) {
            AvatarTrio(
                total    = total,
                modifier = Modifier.fillMaxSize().padding(6.dp),
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text  = "CONTACTS",
                    style = AppTypography.Eyebrow,
                    color = AppAccent.primary,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text  = "$total",
                    style = AppTypography.Tag.copy(fontWeight = LocalFontWeight.current),
                    color = AppColors.TextSecondary,
                )
            }
            Text(
                text  = headline(total = total, recent = recentCount),
                style = AppTypography.SectionTitle.copy(fontWeight = LocalFontWeight.current),
                color = AppColors.TextPrimary,
            )
            Spacer(Modifier.height(4.dp))
            StatRow(
                withPhone = withPhone,
                withEmail = withEmail,
                recent    = recentCount,
            )
            Spacer(Modifier.height(6.dp))
            PreviewLine(recent = recent, total = total)
        }
    }
}

// ================================================================= Stats

@Composable
private fun StatRow(withPhone: Int, withEmail: Int, recent: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatInline(withPhone, "phone", AppAccent.primary)
        Dot()
        StatInline(withEmail, "email", AppColors.Info)
        Dot()
        StatInline(recent,    "new",   AppColors.Success)
    }
}

@Composable
private fun StatInline(value: Int, label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text  = value.toString(),
            style = AppTypography.Meta.copy(fontWeight = LocalFontWeight.current),
            color = color,
        )
        Spacer(Modifier.width(3.dp))
        Text(
            text  = label,
            style = AppTypography.Tag.copy(fontWeight = LocalFontWeight.current),
            color = color,
        )
    }
}

@Composable
private fun Dot() {
    Text(
        text  = "  \u00B7  ",
        style = AppTypography.Meta,
        color = AppColors.TextTertiary,
    )
}

// ================================================================= Preview line

/**
 * Trailing line mirrors the Reminders card's "Next" line: mini
 * avatars for the three most-recent contacts, followed by
 * "+N more" when the directory has more. Empty state nudges the
 * user toward capturing their first contact.
 */
@Composable
private fun PreviewLine(recent: List<DirectoryContact>, total: Int) {
    if (recent.isEmpty()) {
        Text(
            text  = "Add a contact to a note \u00B7 tap to open",
            style = AppTypography.Meta,
            color = AppColors.TextSecondary,
            maxLines = 1,
        )
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Row(horizontalArrangement = Arrangement.spacedBy(-8.dp)) {
            recent.forEach { contact ->
                MiniAvatar(initial = contact.name.firstOrNull()?.uppercase() ?: "?")
            }
        }
        if (total > recent.size) {
            Spacer(Modifier.width(AppSpacing.s2))
            Text(
                text  = "+${total - recent.size} more",
                style = AppTypography.Tag.copy(fontWeight = LocalFontWeight.current),
                color = AppColors.TextTertiary,
            )
        }
    }
}

@Composable
private fun MiniAvatar(initial: String) {
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(AppAccent.soft)
            .border(1.5.dp, AppColors.CardSolid, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(initial, style = AppTypography.Tag, color = AppAccent.primary)
    }
}

// ================================================================= Hero illustration

/**
 * Three overlapping avatar bubbles inside the hero tile. All three
 * stay painted at every count — a lone contact still reads as
 * "network in progress" rather than an empty tile. Fill strength
 * scales gently with count: a fresh install paints them at 55%
 * opacity, filling in as contacts accumulate.
 */
@Composable
private fun AvatarTrio(total: Int, modifier: Modifier = Modifier) {
    val primary   = AppAccent.primary
    val accentSoft = AppAccent.soft
    val accentDeep = AppAccent.deep
    val fillStrength = when {
        total == 0       -> 0.55f
        total in 1..4    -> 0.75f
        total in 5..19   -> 0.90f
        else             -> 1.0f
    }

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val r = w * 0.28f

        // Back-left bubble (soft tone)
        drawCircle(
            color  = accentSoft.copy(alpha = 0.9f * fillStrength),
            radius = r,
            center = Offset(w * 0.28f, h * 0.62f),
        )
        // Back-right bubble (deep tone at lower alpha so it reads
        // as a different person without competing with the front).
        drawCircle(
            color  = accentDeep.copy(alpha = 0.45f * fillStrength),
            radius = r,
            center = Offset(w * 0.72f, h * 0.62f),
        )
        // Front/top bubble (primary accent, fully opaque).
        drawCircle(
            color  = primary.copy(alpha = fillStrength),
            radius = r * 1.05f,
            center = Offset(w * 0.50f, h * 0.38f),
        )
    }
}

// ================================================================= Copy

/**
 * Headline sits above the stat row. Plays off the "network is
 * alive" metaphor of the avatar trio — recent captures bump the
 * user into the upbeat phrasing so the card feels responsive.
 */
private fun headline(total: Int, recent: Int): String = when {
    total == 0  -> "Nothing captured yet"
    recent > 0  -> "Your circle grew"
    total < 5   -> "Building your circle"
    else        -> "Your rolodex"
}
