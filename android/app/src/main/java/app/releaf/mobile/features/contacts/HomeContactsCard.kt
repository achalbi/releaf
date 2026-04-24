/*
 * HomeContactsCard.kt
 *
 * Compact Contacts card for the signed-in Home dashboard. Shows
 * the total contact count + a small preview of the most recent
 * few, and links out to the full contacts screen.
 */

package app.releaf.mobile.features.contacts

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import kotlinx.coroutines.flow.flowOf

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

    val recent = contacts.take(3)

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
        // Hero tile — same size as the Tasks / Reminders cards so
        // the three cards stack with the same visual rhythm.
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(AppRadius.md))
                .background(Color(0xFFF2E7DB)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Person,
                contentDescription = null,
                tint = AppAccent.primary,
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
                    text  = "${contacts.size}",
                    style = AppTypography.Tag,
                    color = AppColors.TextSecondary,
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text  = headline(count = contacts.size),
                style = AppTypography.SectionTitle,
                color = AppColors.TextPrimary,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text  = previewLine(recent, total = contacts.size),
                style = AppTypography.Meta,
                color = AppColors.TextSecondary,
            )

            if (recent.isNotEmpty()) {
                Spacer(Modifier.height(AppSpacing.s3))
                Row(horizontalArrangement = Arrangement.spacedBy(-8.dp)) {
                    recent.forEach { contact ->
                        MiniAvatar(initial = contact.name.firstOrNull()?.uppercase() ?: "?")
                    }
                    if (contacts.size > recent.size) {
                        Spacer(Modifier.width(AppSpacing.s2))
                        Text(
                            "+ ${contacts.size - recent.size} more",
                            style = AppTypography.Meta,
                            color = AppColors.TextTertiary,
                            modifier = Modifier.align(Alignment.CenterVertically),
                        )
                    }
                }
            }
        }
    }
}

private fun headline(count: Int): String = when (count) {
    0    -> "Nothing captured yet"
    1    -> "1 contact"
    else -> "$count contacts"
}

private fun previewLine(recent: List<DirectoryContact>, total: Int): String {
    if (recent.isEmpty()) return "Add a contact to a page or notepad entry — it'll land here."
    val names = recent.joinToString(", ") { it.name }
    return if (total <= recent.size) names else "$names…"
}

@Composable
private fun MiniAvatar(initial: String) {
    Box(
        modifier = Modifier
            .size(26.dp)
            .clip(CircleShape)
            .background(AppAccent.soft)
            .border(1.5.dp, AppColors.CardSolid, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(initial, style = AppTypography.Tag, color = AppAccent.primary)
    }
}
