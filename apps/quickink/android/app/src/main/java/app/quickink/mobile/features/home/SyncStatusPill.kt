/*
 * SyncStatusPill.kt
 *
 * Reusable status pill for QuickInk's sync state. Mirror of iOS
 * `SyncStatusPill.swift`.
 *
 * State branches:
 *   - Synced       success green dot · "Synced — moments ago"
 *   - Pending      warning amber dot · "N pending"
 *   - Syncing      accent dot       · "Syncing now…"
 *   - Offline      muted gray dot   · "Offline — changes saved locally"
 *   - Failed       danger red dot   · "Sync failed · tap to retry"
 *
 * Today the Home screen renders only the synced/pending paths from
 * `SyncStateDao` reads. The offline + failed branches activate once
 * the shared sync layer publishes a `lastError: String?` field and
 * a network monitor surfaces online/offline transitions.
 */

package app.quickink.mobile.features.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.quickink.mobile.ui.theme.LocalQuickInkColors
import app.quickink.mobile.ui.theme.LocalQuickInkTypography
import app.quickink.mobile.ui.theme.QuickInkRadius
import app.quickink.mobile.ui.theme.QuickInkSpacing

sealed class SyncPillState {
    data class Synced(val lastSyncAt: String?) : SyncPillState()
    data class Pending(val count: Int) : SyncPillState()
    object Syncing : SyncPillState()
    object Offline : SyncPillState()
    data class Failed(val message: String?) : SyncPillState()
}

@Composable
fun SyncStatusPill(
    state: SyncPillState,
    onRetry: (() -> Unit)? = null,
) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current

    val dotColor: Color = when (state) {
        is SyncPillState.Synced  -> colors.success
        is SyncPillState.Pending -> colors.warning
        SyncPillState.Syncing    -> colors.accent
        SyncPillState.Offline    -> colors.muted
        is SyncPillState.Failed  -> colors.danger
    }
    val message: String = when (state) {
        is SyncPillState.Synced  -> state.lastSyncAt?.let { "Synced — $it" } ?: "Not yet synced"
        is SyncPillState.Pending -> "${state.count} pending"
        SyncPillState.Syncing    -> "Syncing now…"
        SyncPillState.Offline    -> "Offline — changes saved locally"
        is SyncPillState.Failed  -> state.message ?: "Sync failed"
    }

    val isFailed = state is SyncPillState.Failed && onRetry != null

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(QuickInkRadius.pill))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(QuickInkRadius.pill))
            .let { if (isFailed) it.clickable(onClick = onRetry!!) else it }
            .padding(horizontal = QuickInkSpacing.s3, vertical = QuickInkSpacing.s2),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2),
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Text(text = message, style = type.meta, color = colors.inkSoft)
        if (isFailed) {
            Spacer(Modifier.size(QuickInkSpacing.s1))
            Text(text = "Retry", style = type.caption, color = colors.accent)
        }
    }
}
