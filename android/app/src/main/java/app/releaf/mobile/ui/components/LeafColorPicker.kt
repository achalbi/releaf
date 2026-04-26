/*
 * LeafColorPicker.kt
 *
 * Small, reusable color picker keyed off the four leaf-theme
 * tokens (coral / green / yellow / dry). Used in the create-shelf
 * and create-notebook flows so users pick a color *symbolically*
 * by leaf-theme name rather than via a generic hex picker.
 *
 * Each chip is a 32dp circle filled with the theme's primary
 * color, with a thin ring around the active selection. Tap to
 * pick. Mirrors `LeafColorPicker.swift` byte-for-byte on the
 * mapping rule.
 *
 * The selected value is the brand's `colorToken` string ("coral",
 * "green", "yellow", "dry") — same shape as
 * `Notebook.colorToken` and the Shelf entity.
 */

package app.releaf.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.releaf.mobile.ui.theme.AppColors
import app.releaf.mobile.ui.theme.AppSpacing
import app.releaf.mobile.ui.theme.AppTypography

private data class LeafColorOption(
    val token: String,
    val label: String,
    val color: Color,
)

@Composable
private fun leafOptions(): List<LeafColorOption> = listOf(
    LeafColorOption("coral",  "Coral",  AppColors.ThemeCoralPrimary),
    LeafColorOption("green",  "Green",  AppColors.ThemeGreenPrimary),
    LeafColorOption("yellow", "Yellow", AppColors.ThemeYellowPrimary),
    LeafColorOption("dry",    "Dry",    AppColors.ThemeDryPrimary),
)

@Composable
fun LeafColorPicker(
    selection: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** When true, a small `releaf · {token}` eyebrow preview
     *  renders below the chip row in the selected color so the
     *  user can see how their pick will read in the rest of the
     *  chrome before committing. Off by default. */
    showPreview: Boolean = false,
) {
    Column(
        modifier              = modifier,
        verticalArrangement   = Arrangement.spacedBy(AppSpacing.s2),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.s3),
            verticalAlignment     = Alignment.CenterVertically,
        ) {
            leafOptions().forEach { option ->
                ColorChip(
                    option   = option,
                    isActive = option.token == selection,
                    onClick  = { onSelect(option.token) },
                )
            }
        }
        if (showPreview) {
            val palette = ShelfTheme.palette(selection)
            Row(
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.s1),
                verticalAlignment     = Alignment.CenterVertically,
            ) {
                LeafDropletGlyph(tint = palette.background, size = 11.dp)
                Text(
                    text  = "releaf · $selection".uppercase(),
                    style = AppTypography.Eyebrow,
                    color = palette.background,
                )
            }
        }
    }
}

@Composable
private fun ColorChip(
    option: LeafColorOption,
    isActive: Boolean,
    onClick: () -> Unit,
) {
    // `Modifier.selectable` provides correct accessibility (RadioButton role
    // + selected state) and the click handling in one call.
    Box(
        modifier = Modifier
            .size(40.dp)
            .selectable(
                selected = isActive,
                role     = Role.RadioButton,
                onClick  = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (isActive) {
            // Outer ring in the chip's own colour.
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .border(2.dp, option.color, CircleShape),
            )
            // Cream insert separates the outer ring from the chip
            // so the active state reads as a halo, not a thicker
            // chip.
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .border(2.dp, AppColors.CardSolid, CircleShape),
            )
        }
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(option.color),
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF5EEDF, widthDp = 320)
@Composable
private fun LeafColorPickerPreview() {
    val state = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("green") }
    Box(
        modifier = Modifier
            .background(AppColors.Canvas)
            .padding(AppSpacing.s4),
    ) {
        LeafColorPicker(
            selection = state.value,
            onSelect  = { state.value = it },
        )
    }
}
