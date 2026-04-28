package app.releaf.mobile.features.notepad.recents.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.BasicText
import app.releaf.mobile.features.notepad.recents.model.RecentsTotals
import app.releaf.mobile.features.notepad.recents.theme.BgSurfaceMuted
import app.releaf.mobile.features.notepad.recents.theme.BorderDivider
import app.releaf.mobile.features.notepad.recents.theme.BorderFaint
import app.releaf.mobile.features.notepad.recents.theme.TextGreenMuted
import app.releaf.mobile.features.notepad.recents.theme.TextPrimary
import app.releaf.mobile.features.notepad.recents.theme.Type

/**
 * Three-cell stats card sitting under the Day/Recents toggle.
 *
 *  | Day streak | Bloomed in Apr | Top theme |
 */
@Composable
fun StatsStrip(
    totals: RecentsTotals,
    monthLabel: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(BgSurfaceMuted)
            .border(1.dp, BorderFaint, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Asymmetric weights — the middle cell ("Bloomed in <month>")
        // gets the longest label, so we trade ~10% off each side cell
        // to let the middle cell breathe (no awkward wrapping on narrow
        // screens, and the "X / 30" suffix sits comfortably).
        StatCell(
            number = AnnotatedString("${totals.dayStreak}"),
            label = "Day streak",
            modifier = Modifier.weight(0.9f),
        )
        Divider()
        StatCell(
            number = buildAnnotatedString {
                // Number + suffix share the serif family used by
                // `Type.StatNumber` so "<n>/<total>" reads as one
                // typographic cluster — large serif numeral with a
                // smaller, lighter serif denominator beside it.
                withStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.Medium,
                        fontSize = 18.sp,
                        color = TextPrimary,
                    )
                ) {
                    append("${totals.bloomedThisMonth}")
                }
                withStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.Normal,
                        fontSize = 11.sp,
                        color = TextGreenMuted,
                    )
                ) {
                    append("/${totals.daysInMonth}")
                }
            },
            label = "Bloomed in $monthLabel",
            modifier = Modifier.weight(1.2f),
        )
        Divider()
        StatCell(
            number = AnnotatedString(totals.topTheme?.display ?: "—"),
            label = "Top theme",
            isWord = true,
            modifier = Modifier.weight(0.9f),
        )
    }
}

@Composable
private fun StatCell(
    number: AnnotatedString,
    label: String,
    modifier: Modifier = Modifier,
    isWord: Boolean = false,
) {
    Column(
        modifier = modifier.padding(horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (isWord) {
            BasicText(
                text = number.text,
                style = Type.StatNumber.copy(color = TextPrimary, fontSize = 16.sp),
            )
        } else {
            BasicText(
                text = number,
                style = Type.StatNumber.copy(color = TextPrimary),
            )
        }
        Spacer(Modifier.height(4.dp))
        BasicText(
            text = label.uppercase(),
            style = Type.MicroLabel.copy(color = TextGreenMuted),
        )
    }
}

@Composable
private fun Divider() {
    Box(
        modifier = Modifier
            .padding(horizontal = 4.dp)
            .width(1.dp)
            .height(28.dp)
            .background(BorderDivider),
    )
}
