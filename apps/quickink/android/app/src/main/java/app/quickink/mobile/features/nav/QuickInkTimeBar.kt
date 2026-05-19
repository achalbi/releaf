/*
 * QuickInkTimeBar.kt
 *
 * Slim top bar that shows the current time and date in a single
 * line with a center-dot separator ("9:35 AM · Tue 19 Jan"). Sits
 * above the NavHost in `QuickInkRoot` on every screen — functions
 * as the app's status strip now that the system bar is hidden
 * app-wide. Refreshes every 60s; only the minute moves below the
 * hour scale.
 *
 * Mirror of iOS `QuickInkTimeBar.swift`. Keep the layout (top-
 * left, 12sp medium, muted color, 10dp top + s5 start padding)
 * in sync between the two. The start inset matches the Home
 * screen's content margin so the bar text lines up with the
 * "Good evening" greeting below it.
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.quickink.mobile.ui.theme.LocalQuickInkColors
import app.quickink.mobile.ui.theme.QuickInkSpacing
import kotlinx.coroutines.delay
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun QuickInkTimeBar(modifier: Modifier = Modifier) {
    val colors = LocalQuickInkColors.current
    var now by remember { mutableStateOf(ZonedDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = ZonedDateTime.now()
            delay(60_000L)
        }
    }
    val formatted = remember(now) { now.format(TimeBarFormatter) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.bg)
            .padding(top = 10.dp, start = QuickInkSpacing.s5),
        horizontalArrangement = Arrangement.Start,
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

private val TimeBarFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("h:mm a · EEE d MMM", Locale.US)
