/*
 * DailyPlantInfoSheet.kt
 *
 * Reusable bottom sheet that surfaces a page's "Plant of the Page".
 * Same pattern across notepad entries and notebook pages — both seed
 * a [DailyPlant] from the row's id and let the user open this sheet
 * via the Spa icon next to the title.
 */

package app.releaf.mobile.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.sp
import app.releaf.mobile.ui.theme.AppColors
import app.releaf.mobile.ui.theme.AppSpacing
import app.releaf.mobile.ui.theme.AppTypography
import app.releaf.mobile.ui.theme.DailyPlant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DailyPlantInfoSheet(
    plant: DailyPlant,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = sheetState,
        containerColor   = AppColors.CardSolid,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start  = AppSpacing.s5,
                    end    = AppSpacing.s5,
                    top    = AppSpacing.s2,
                    bottom = AppSpacing.s6,
                ),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.s4),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.s2)) {
                Text(
                    text  = "PLANT OF THE PAGE",
                    style = AppTypography.Eyebrow,
                    color = AppColors.ThemeGreenDeep,
                )
                Text(
                    text  = plant.name,
                    style = TextStyle(fontFamily = FontFamily.Serif, fontSize = 36.sp),
                    color = AppColors.TextPrimary,
                )
                if (plant.commonName.isNotEmpty()) {
                    Text(
                        text  = plant.commonName,
                        style = TextStyle(
                            fontFamily = FontFamily.Serif,
                            fontSize   = 18.sp,
                            fontStyle  = FontStyle.Italic,
                        ),
                        color = AppColors.TextSecondary,
                    )
                }
            }

            HairlineDivider()

            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.s4)) {
                DailyPlantInfoBlock(title = "EPITHET",          body = plant.epithet)
                DailyPlantInfoBlock(title = "TRADITIONAL USES", body = plant.usedFor)
            }

            TextButton(
                onClick  = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Close", color = AppColors.TextSecondary)
            }
        }
    }
}

@Composable
private fun DailyPlantInfoBlock(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.s1)) {
        Text(text = title, style = AppTypography.Eyebrow, color = AppColors.TextSecondary)
        Text(text = body,  style = AppTypography.Body,    color = AppColors.TextPrimary)
    }
}
