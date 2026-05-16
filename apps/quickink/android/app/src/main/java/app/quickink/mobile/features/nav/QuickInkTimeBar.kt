/*
 * QuickInkTimeBar.kt
 *
 * Slim top bar that shows just the current wall-clock time aligned to
 * the right edge. Sits above the NavHost in `QuickInkRoot` on every
 * non-Home, non-ScanDetail surface, and is reused inside
 * `ScanDetailScreen` for its auto-hide-on-scroll variant.
 *
 * Replaces the editorial `DaylightStatusBar` that previously occupied
 * this slot — the time chip reads as quiet ambient context without
 * crowding the screen below it.
 *
 * Mirror of iOS `QuickInkTimeBar.swift`. Keep the layout (right-
 * aligned, 12sp medium, muted color) in sync between the two.
 */

package app.quickink.mobile.features.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.quickink.mobile.ui.theme.LocalQuickInkColors
import app.quickink.mobile.ui.theme.QuickInkSpacing
import kotlinx.coroutines.delay
import java.time.LocalTime
import java.time.format.DateTimeFormatter

@Composable
fun QuickInkTimeBar(modifier: Modifier = Modifier) {
    val colors = LocalQuickInkColors.current
    var now by remember { mutableStateOf(LocalTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalTime.now()
            delay(60_000L)
        }
    }
    val formatted = remember(now) { now.format(TimeFormatter) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.bg)
            .padding(
                start  = QuickInkSpacing.s4,
                end    = QuickInkSpacing.s4,
                top    = QuickInkSpacing.s2,
                bottom = QuickInkSpacing.s1,
            ),
        horizontalArrangement = Arrangement.End,
        verticalAlignment     = Alignment.CenterVertically,
    ) {
        Text(
            text       = formatted,
            fontSize   = 12.sp,
            fontWeight = FontWeight.Medium,
            color      = colors.muted,
        )
    }
}

private val TimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a")
