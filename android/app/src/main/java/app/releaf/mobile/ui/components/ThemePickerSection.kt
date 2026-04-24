/*
 * ThemePickerSection.kt
 *
 * Home-screen card that lets the user flip between light / dark / system
 * and pick one of four primary palettes. Changes hot-swap the whole
 * app via `UiPreferences` — theme prefs live in SharedPreferences, the
 * root `ReleafTheme` reads them and pushes them down through
 * `LocalConfiguration` (dark mode) + `LocalAccent` (palette).
 */

package app.releaf.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.releaf.mobile.ui.theme.AccentPaletteId
import app.releaf.mobile.ui.theme.AccentPalettes
import app.releaf.mobile.ui.theme.AppAccent
import app.releaf.mobile.ui.theme.AppColors
import app.releaf.mobile.ui.theme.AppRadius
import app.releaf.mobile.ui.theme.AppSpacing
import app.releaf.mobile.ui.theme.AppTypography
import app.releaf.mobile.ui.theme.ThemeMode
import app.releaf.mobile.ui.theme.UiPreferences

@Composable
fun ThemePickerSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val prefs = remember { UiPreferences.get(context) }
    val state by prefs.state.collectAsState()

    // Compact layout: tighter card padding, shorter segmented-control
    // chips, smaller swatches, no "Primary color" sub-label. Two rows
    // still (one-row layout overflows on <400dp-wide phones) but each
    // row is denser than before.
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.md))
            .background(AppColors.CardSolid)
            .border(1.dp, AppColors.BorderDefault, RoundedCornerShape(AppRadius.md))
            .padding(horizontal = AppSpacing.s3, vertical = AppSpacing.s3),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.s2),
    ) {
        Text(
            text  = "APPEARANCE",
            style = AppTypography.Eyebrow,
            color = AppColors.TextSecondary,
        )

        // --- Theme-mode segmented control (compact) ---------------------
        Row(
            modifier              = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(AppRadius.pill))
                .background(AppColors.Subtle)
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            ThemeMode.entries.forEach { mode ->
                ThemeModeChip(
                    label   = mode.displayLabel(),
                    active  = state.themeMode == mode,
                    onClick = { prefs.setThemeMode(mode) },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // --- Palette swatches (compact, label dropped) -----------------
        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.s2),
        ) {
            AccentPaletteId.entries.forEach { id ->
                ColorSwatch(
                    color    = AccentPalettes.forId(id).primary,
                    selected = state.paletteId == id,
                    onClick  = { prefs.setPalette(id) },
                )
            }
        }
    }
}

@Composable
private fun ThemeModeChip(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(AppRadius.pill))
            .background(if (active) AppAccent.primary else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = AppSpacing.s1),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text  = label,
            style = AppTypography.Button,
            color = if (active) AppColors.OnAccent else AppColors.TextSecondary,
        )
    }
}

@Composable
private fun ColorSwatch(
    color: Color,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val ringColor = if (selected) AppColors.TextPrimary else AppColors.BorderDefault
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(color)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = ringColor,
                shape = CircleShape,
            )
            .clickable(onClick = onClick),
    ) {
        Spacer(Modifier)
    }
}

private fun ThemeMode.displayLabel(): String = when (this) {
    ThemeMode.System -> "System"
    ThemeMode.Light  -> "Light"
    ThemeMode.Dark   -> "Dark"
}
